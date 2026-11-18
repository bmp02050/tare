package io.github.bmp02050.tare

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Random check-ins: perDay times between START and END, spaced at least
 * span / (2 * perDay) apart (6/day -> 60 min, 10/day -> 36 min).
 * A day's times are derived from (date, count, per-install salt), so they're stable across
 * reboots and app restarts; only the next one is ever scheduled. Inexact alarms
 * (no special permission); Android may shift them by a few minutes, which is fine
 * for times that are random anyway.
 */
object Checkins {
    val CHOICES = listOf(0, 3, 6, 8, 10)  // 0 = off
    const val DEFAULT_PER_DAY = 6
    const val START_MIN = 9 * 60
    const val END_MIN = 21 * 60
    const val CHANNEL = "checkins"
    const val EXTRA_CHECKIN = "checkin"
    const val EXTRA_PROMPT = "prompt"      // id of the logged prompt row this check-in answers
    private const val ACTION_FIRE = "io.github.bmp02050.tare.CHECKIN"
    const val ACTION_NOT_NOW = "io.github.bmp02050.tare.NOT_NOW"
    const val NOTIFICATION_ID = 1
    private const val PREFS = "checkins"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun perDay(ctx: Context) = prefs(ctx).getInt("per_day", DEFAULT_PER_DAY)

    fun enabled(ctx: Context) = perDay(ctx) > 0

    fun setPerDay(ctx: Context, n: Int) {
        prefs(ctx).edit().putInt("per_day", n).apply()
        scheduleNext(ctx)
    }

    private fun salt(ctx: Context): Long {
        val p = prefs(ctx)
        if (!p.contains("salt")) p.edit().putLong("salt", java.security.SecureRandom().nextLong()).apply()
        return p.getLong("salt", 0L)
    }

    fun plan(date: LocalDate, salt: Long, n: Int): List<LocalDateTime> {
        val rng = Random(date.toEpochDay() * 1_000_003L xor salt xor n.toLong())
        val span = END_MIN - START_MIN
        val gap = span / (2 * n)
        repeat(5000) {
            val offs = List(n) { rng.nextInt(span) }.sorted()
            if (offs.zipWithNext().all { (a, b) -> b - a >= gap }) {
                return offs.map { date.atStartOfDay().plusMinutes((START_MIN + it).toLong()) }
            }
        }
        return List(n) { i -> date.atStartOfDay().plusMinutes((START_MIN + span * (2 * i + 1) / (2 * n)).toLong()) }
    }

    /** Next planned check-in after now (today or tomorrow), or null when off. */
    fun next(ctx: Context): LocalDateTime? {
        val n = perDay(ctx)
        if (n <= 0) return null
        val now = LocalDateTime.now()
        val s = salt(ctx)
        return plan(now.toLocalDate(), s, n).firstOrNull { it.isAfter(now) }
            ?: plan(now.toLocalDate().plusDays(1), s, n).first()
    }

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, CheckinReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx)
        val at = next(ctx)
        if (at == null) {
            am.cancel(pi)
            return
        }
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        prefs(ctx).edit().putLong("next_ms", millis).apply()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Check-ins", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Random prompts to notice how you are right now" }
            )
        }
    }

    internal fun fire(ctx: Context) {
        // Inexact alarms can be delivered late but not meaningfully early; guard against a stale duplicate.
        val due = prefs(ctx).getLong("next_ms", 0L)
        if (enabled(ctx) && System.currentTimeMillis() >= due - 5 * 60_000L) {
            notify(ctx, NoticeLog(ctx).append(kind = "prompt"))
        }
        scheduleNext(ctx)
    }

    /**
     * Post the check-in notification. promptId links the answer to its prompt row (random or body);
     * null for "Send test check-in", which isn't logged.
     */
    fun notify(ctx: Context, promptId: String?) {
        ensureChannel(ctx)
        run {
            val open = PendingIntent.getActivity(
                ctx, 2,
                Intent(ctx, MainActivity::class.java).putExtra(EXTRA_CHECKIN, true).putExtra(EXTRA_PROMPT, promptId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Tare check-in")
                .setContentText("How are you right now? Tap to answer.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
            if (promptId != null) {
                // "Saw it, not now" is data too: skipped prompts cluster in hard moments, so they
                // shouldn't look the same as unseen ones.
                val later = PendingIntent.getBroadcast(
                    ctx, 3,
                    Intent(ctx, CheckinReceiver::class.java).setAction(ACTION_NOT_NOW).putExtra(EXTRA_PROMPT, promptId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                b.addAction(Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_notify), "Not now", later).build())
            }
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, b.build())
        }
    }

    fun notificationsAllowed(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
}

class CheckinReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Checkins.scheduleNext(ctx)
            Checkins.ACTION_NOT_NOW -> {
                NoticeLog(ctx).append("checkin", label = "notnow", ref = intent.getStringExtra(Checkins.EXTRA_PROMPT).orEmpty())
                ctx.getSystemService(android.app.NotificationManager::class.java).cancel(Checkins.NOTIFICATION_ID)
            }
            else -> Checkins.fire(ctx)
        }
    }
}
