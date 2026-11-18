package io.github.bmp02050.tare

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Body-triggered check-ins. Health Services delivers heart-rate and step batches in the
 * background (every few minutes, more often if Samsung Health measures continuously). When the
 * heart rate is well above your own resting level while you've been still, the watch asks the
 * phone app to send its usual neutral check-in. The phone does the asking and logging; the watch
 * never tells you what you feel and shows no numbers.
 */
object Body {
    const val TAG = "TareBody"
    const val PATH = "/tare/body"

    // Rule parameters (deliberately conservative; tune after a week of data).
    const val ABOVE_REST_BPM = 20          // trigger when >= resting + this
    const val STILL_MIN = 10L              // no steps for this long counts as still
    const val COOLDOWN_MIN = 90L
    const val MAX_PER_DAY = 4
    const val START_HOUR = 9               // 09:00-21:00, same window as random check-ins
    const val END_HOUR = 21
    const val MIN_BASELINE_SAMPLES = 60    // roughly a day of still readings before any prompt
    const val BASELINE_DAYS = 7L
    const val REST_PERCENTILE = 0.20       // "resting" = 20th percentile of still readings

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("body", Context.MODE_PRIVATE)
    private fun still(ctx: Context) = File(ctx.filesDir, "still_hr.csv")
    private fun events(ctx: Context) = File(ctx.filesDir, "body_events.csv")

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", true)

    fun register(ctx: Context) {
        val config = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.HEART_RATE_BPM, DataType.STEPS))
            .setShouldUserActivityInfoBeRequested(true)
            .build()
        val f = HealthServices.getClient(ctx).passiveMonitoringClient
            .setPassiveListenerServiceAsync(BodyService::class.java, config)
        f.addListener({
            try { f.get(); Log.i(TAG, "passive listener registered") }
            catch (e: Exception) { Log.w(TAG, "register failed", e) }
        }, ctx.mainExecutor)
    }

    fun unregister(ctx: Context) {
        HealthServices.getClient(ctx).passiveMonitoringClient.clearPassiveListenerServiceAsync()
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("on", on).apply()
        if (on) register(ctx) else unregister(ctx)
    }

    /** How many still readings the resting baseline has so far (the UI shows only learning / ready). */
    fun baselineSize(ctx: Context): Int = loadStill(ctx).size

    private fun loadStill(ctx: Context): List<Pair<Long, Double>> {
        val f = still(ctx)
        if (!f.exists()) return emptyList()
        val cutoff = System.currentTimeMillis() - BASELINE_DAYS * 86_400_000L
        return f.readLines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size == 2) p[0].toLongOrNull()?.let { t -> p[1].toDoubleOrNull()?.let { t to it } } else null
        }.filter { it.first >= cutoff }
    }

    private fun saveStill(ctx: Context, rows: List<Pair<Long, Double>>) {
        still(ctx).writeText(rows.takeLast(5000).joinToString("") { "${it.first},${it.second}\n" })
    }

    fun note(ctx: Context, what: String) {
        events(ctx).appendText("${Instant.now()},$what\n")
        Log.i(TAG, what)
    }

    fun activity(ctx: Context, info: UserActivityInfo) {
        prefs(ctx).edit().putInt("state", info.userActivityState.id).apply()
    }

    fun process(ctx: Context, data: DataPointContainer) {
        val p = prefs(ctx)
        val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())

        var lastStep = p.getLong("last_step_ms", 0L)
        for (s in data.getData(DataType.STEPS)) {
            if (s.value > 0) lastStep = maxOf(lastStep, s.getEndInstant(boot).toEpochMilli())
        }
        p.edit().putLong("last_step_ms", lastStep).apply()

        val hr = data.getData(DataType.HEART_RATE_BPM)
            .map { it.getTimeInstant(boot).toEpochMilli() to it.value }
            .filter { it.second in 30.0..220.0 }
            .sortedBy { it.first }
        if (hr.isEmpty()) return

        // Exercise and sleep are not "still": skip them entirely.
        val state = p.getInt("state", UserActivityState.USER_ACTIVITY_PASSIVE.id)
        if (state != UserActivityState.USER_ACTIVITY_PASSIVE.id) return

        val stillRows = loadStill(ctx).toMutableList()
        var best: Pair<Long, Double>? = null
        for ((t, bpm) in hr) {
            if (t - lastStep < STILL_MIN * 60_000L) continue
            stillRows.add(t to bpm)
            if (best == null || bpm > best.second) best = t to bpm
        }
        saveStill(ctx, stillRows)
        val (t, bpm) = best ?: return

        if (stillRows.size < MIN_BASELINE_SAMPLES) return
        val sorted = stillRows.map { it.second }.sorted()
        val rest = sorted[(sorted.size * REST_PERCENTILE).toInt().coerceIn(0, sorted.size - 1)]
        if (bpm < rest + ABOVE_REST_BPM) return

        val now = System.currentTimeMillis()
        val local = LocalDateTime.now()
        val today = local.toLocalDate().toString()
        val count = if (p.getString("day", "") == today) p.getInt("count", 0) else 0
        val why = when {
            !enabled(ctx) -> "off"
            local.hour < START_HOUR || local.hour >= END_HOUR -> "outside hours"
            now - t > 15 * 60_000L -> "stale reading"
            now - p.getLong("last_prompt_ms", 0L) < COOLDOWN_MIN * 60_000L -> "cooldown"
            count >= MAX_PER_DAY -> "daily cap"
            else -> null
        }
        if (why != null) {
            note(ctx, "elevated,${bpm.toInt()},${rest.toInt()},skipped:$why")
            return
        }
        p.edit().putLong("last_prompt_ms", now).putString("day", today).putInt("count", count + 1).apply()
        val sampleAt = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toOffsetDateTime()
        send(ctx, "bpm=${bpm.toInt()};rest=${rest.toInt()};at=$sampleAt")
    }

    /** Ask the phone app to post its check-in. Payload is for the research log only. */
    fun send(ctx: Context, payload: String) {
        Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) note(ctx, "no phone connected,$payload")
            for (n in nodes) {
                Wearable.getMessageClient(ctx).sendMessage(n.id, PATH, payload.toByteArray())
                    .addOnSuccessListener { note(ctx, "sent,$payload") }
                    .addOnFailureListener { note(ctx, "send failed,$payload,${it.message}") }
            }
        }.addOnFailureListener { note(ctx, "node lookup failed,${it.message}") }
    }
}

class BodyService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        try { Body.process(this, dataPoints) } catch (e: Exception) { Log.w(Body.TAG, "process failed", e) }
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) = Body.activity(this, info)

    override fun onPermissionLost() {
        Body.note(this, "permission lost")
    }
}
