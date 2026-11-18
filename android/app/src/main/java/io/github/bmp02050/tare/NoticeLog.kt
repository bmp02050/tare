package io.github.bmp02050.tare

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** One button. Loaded from assets/labels.json (a copy of sense/selfreport/labels.json). */
data class Label(val id: String, val text: String, val hint: String, val color: Int, val good: Boolean = false)

/** A resolved row for display: event or check-in with amends applied. */
data class Entry(
    val id: String, val kind: String, val tClient: String, val label: String,
    val intensity: String, val aware: String, val note: String, val reflected: Boolean = false,
)

/**
 * Append-only CSV, same schema as sense/selfreport/server.py so summary.py reads it unchanged:
 *   event | checkin | amend (ref + intensity/note) | void (ref) | prompt (check-in notification sent)
 *   reflect (ref = the event/check-in, label = question id, note = answer; the latest per question wins)
 *   meta (label = what changed, note = details; marks protocol changes so analysis can split on them)
 */
class NoticeLog(ctx: Context) {
    companion object {
        val FIELDS = listOf("id", "kind", "t_client", "t_server", "label", "intensity", "aware_before", "note", "ref")
        private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
        private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val rng = SecureRandom()
        private val lock = Any()

        fun nowIso(): String = OffsetDateTime.now().format(TS)

        fun newId(): String = (1..16).map { ID_CHARS[rng.nextInt(ID_CHARS.length)] }.joinToString("")

        fun loadLabels(ctx: Context): List<Label> {
            val json = JSONObject(ctx.assets.open("labels.json").bufferedReader().use { it.readText() })
            val arr = json.getJSONArray("labels")
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Label(o.getString("id"), o.getString("text"), o.optString("hint"),
                    android.graphics.Color.parseColor(o.getString("color")), o.optString("group") == "good")
            }
        }

        private fun esc(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

        /** Minimal RFC 4180 line parser (we never write embedded newlines). */
        fun parseLine(line: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var i = 0
            var quoted = false
            while (i < line.length) {
                val c = line[i]
                if (quoted) {
                    if (c == '"' && i + 1 < line.length && line[i + 1] == '"') { cur.append('"'); i++ }
                    else if (c == '"') quoted = false
                    else cur.append(c)
                } else when (c) {
                    '"' -> quoted = true
                    ',' -> { out.add(cur.toString()); cur.setLength(0) }
                    else -> cur.append(c)
                }
                i++
            }
            out.add(cur.toString())
            return out
        }
    }

    val file = File(ctx.filesDir, "notices.csv")

    fun append(
        kind: String, label: String = "", intensity: String = "", aware: String = "",
        note: String = "", ref: String = "", id: String = newId(),
    ): String = synchronized(lock) {
        if (!file.exists()) file.writeText(FIELDS.joinToString(",") + "\n")
        val now = nowIso()
        val clean = note.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(if (kind == "reflect") 2000 else 500)
        val row = listOf(id, kind, now, now, label, intensity, aware, clean, ref).joinToString(",") { esc(it) }
        FileOutputStream(file, true).use { out ->
            out.write((row + "\n").toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        id
    }

    /** Latest reflection answer per question for one entry ("mind" holds the extra states, ';'-separated). */
    fun answers(ref: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (r in rows()) if (r["kind"] == "reflect" && r["ref"] == ref) {
            val q = r["label"].orEmpty()
            val v = r["note"].orEmpty()
            if (v.isEmpty()) out.remove(q) else out[q] = v
        }
        return out
    }

    /** True once a meta row with this label exists. */
    fun hasMeta(label: String) = rows().any { it["kind"] == "meta" && it["label"] == label }

    fun rows(): List<Map<String, String>> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val header = parseLine(lines[0])
        lines.drop(1).map { line -> header.zip(parseLine(line)).toMap() }
    }

    /** Last n events/check-ins, newest first, amends applied, voided rows removed. */
    fun recent(n: Int): List<Entry> {
        val rows = rows()
        val voided = rows.filter { it["kind"] == "void" }.mapNotNull { it["ref"] }.toSet()
        val intensity = HashMap<String, String>()
        val notes = HashMap<String, String>()
        val reflected = rows.filter { it["kind"] == "reflect" && !it["note"].isNullOrEmpty() }.mapNotNull { it["ref"] }.toSet()
        for (r in rows) if (r["kind"] == "amend") {
            val ref = r["ref"] ?: continue
            r["intensity"]?.takeIf { it.isNotEmpty() }?.let { intensity[ref] = it }
            r["note"]?.takeIf { it.isNotEmpty() }?.let { notes[ref] = it }
        }
        return rows.asReversed().asSequence()
            .filter { (it["kind"] == "event" || it["kind"] == "checkin") && it["id"] !in voided }
            .take(n)
            .map {
                val id = it["id"].orEmpty()
                Entry(id, it["kind"].orEmpty(), it["t_client"].orEmpty(), it["label"].orEmpty(),
                    intensity[id] ?: it["intensity"].orEmpty(), it["aware_before"].orEmpty(),
                    notes[id] ?: it["note"].orEmpty(), id in reflected)
            }.toList()
    }
}
