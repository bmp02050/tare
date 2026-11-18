package io.github.bmp02050.tare

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** A node of the feelings wheel: core (depth 0) > secondary (1) > specific (2). */
class Feeling(val text: String, val depth: Int, val color: Int, val parent: Feeling?) {
    val children = ArrayList<Feeling>()
    val core: Feeling get() = parent?.core ?: this
    val path: List<Feeling> get() = (parent?.path ?: emptyList()) + this
    /** Logged as the label, e.g. "feel:angry/frustrated/annoyed" (words repeat across branches, so the path is the id). */
    val id: String get() = PREFIX + path.joinToString("/") { it.text.lowercase().replace(' ', '_') }
    val leaves: Int get() = if (children.isEmpty()) 1 else children.sumOf { it.leaves }

    companion object { const val PREFIX = "feel:" }
}

data class Question(val id: String, val text: String, val hint: String, val always: Boolean,
                    val only: Set<String>, val not: Set<String>) {
    fun appliesTo(core: String) = (only.isEmpty() || core in only) && core !in not
}

/** Loaded from assets/feelings.json (a copy of sense/selfreport/feelings.json). */
class Feelings(val cores: List<Feeling>, val questions: List<Question>) {
    private val byId = LinkedHashMap<String, Feeling>()

    init {
        fun walk(f: Feeling) { byId[f.id] = f; f.children.forEach(::walk) }
        cores.forEach(::walk)
    }

    fun find(label: String): Feeling? = byId[label]

    /** Words containing the query: exact matches first, then prefixes, then the rest; specific words before general. */
    fun search(query: String, limit: Int = 8): List<Feeling> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return byId.values.filter { it.text.lowercase().contains(q) }
            .sortedWith(compareBy<Feeling>(
                { val t = it.text.lowercase(); if (t == q) 0 else if (t.startsWith(q)) 1 else 2 },
                { -it.depth }, { it.text }))
            .take(limit)
    }

    companion object {
        fun load(ctx: Context): Feelings {
            val json = JSONObject(ctx.assets.open("feelings.json").bufferedReader().use { it.readText() })
            val wheel = json.getJSONArray("wheel")
            val cores = (0 until wheel.length()).map { i ->
                val c = wheel.getJSONObject(i)
                val color = Color.parseColor(c.getString("color"))
                val core = Feeling(c.getString("text"), 0, color, null)
                val mids = c.getJSONArray("children")
                for (j in 0 until mids.length()) {
                    val m = mids.getJSONObject(j)
                    val mid = Feeling(m.getString("text"), 1, color, core)
                    val leaves = m.getJSONArray("children")
                    for (k in 0 until leaves.length()) mid.children.add(Feeling(leaves.getString(k), 2, color, mid))
                    core.children.add(mid)
                }
                core
            }
            val qs = json.getJSONArray("questions")
            fun set(o: JSONObject, key: String) =
                o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            val questions = (0 until qs.length()).map { i ->
                val o = qs.getJSONObject(i)
                Question(o.getString("id"), o.getString("text"), o.optString("hint"),
                    o.optBoolean("always"), set(o, "only"), set(o, "not"))
            }
            return Feelings(cores, questions)
        }
    }
}

/**
 * The wheel, drawn rather than shipped as an image: crisp at any size, works in dark mode,
 * and a tap maps straight to a node. Each ring's segments are sized by how many specific
 * feelings they contain, like the printed wheel.
 *
 * Zoom: a tap on the full wheel zooms into that family (its secondary and specific feelings
 * spread over the whole circle, so the words get several times bigger). Tapping the center
 * zooms back out.
 */
class WheelView(ctx: Context, private val cores: List<Feeling>, private val bg: Int) : View(ctx) {
    var selected: Feeling? = null
        set(v) { field = v; invalidate() }

    /** The family shown full-circle, or null for the whole wheel. */
    var focus: Feeling? = null
        set(v) {
            if (field == v) return
            field = v
            layoutSpans()
            invalidate()
            scaleX = 0.92f; scaleY = 0.92f; alpha = 0.5f
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start()
            contentDescription = if (v == null) "Feelings wheel. Tap a color to zoom in."
                else "${v.text} feelings. Tap the center to see all feelings."
        }
    var onPick: ((Feeling) -> Unit)? = null
    var onZoomOut: (() -> Unit)? = null

    private val radii = floatArrayOf(0f, 0.29f, 0.60f, 1f)   // ring boundaries as a fraction of the radius
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x55000000 }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF1B1D21.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1D21.toInt() }
    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val plain = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val oval = RectF()
    private val cell = Path()

    /** Start angle (degrees clockwise from 12 o'clock) and sweep of every node shown. */
    private val spans = HashMap<Feeling, Pair<Float, Float>>()
    private val roots get() = focus?.let { listOf(it) } ?: cores

    init {
        layoutSpans()
        contentDescription = "Feelings wheel. Tap a color to zoom in."
    }

    private fun layoutSpans() {
        spans.clear()
        var start = 0f
        val per = 360f / roots.sumOf { it.leaves }
        fun lay(f: Feeling) {
            spans[f] = start to f.leaves * per
            if (f.children.isEmpty()) start += per else f.children.forEach { lay(it) }
        }
        roots.forEach { lay(it) }
    }

    private fun sp(v: Float) =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    private fun shade(c: Int, depth: Int): Int {
        val t = when (depth) { 0 -> 0f; 1 -> 0.55f; else -> 0.30f }   // middle ring lightest, like the print
        fun mix(a: Int) = (a + (255 - a) * t).toInt()
        return Color.rgb(mix(Color.red(c)), mix(Color.green(c)), mix(Color.blue(c)))
    }

    private fun dim(c: Int): Int {
        val t = 0.6f
        fun mix(a: Int, b: Int) = (a + (b - a) * t).toInt()
        return Color.rgb(mix(Color.red(c), Color.red(bg)), mix(Color.green(c), Color.green(bg)), mix(Color.blue(c), Color.blue(bg)))
    }

    private fun cellPath(cx: Float, cy: Float, r: Float, f: Feeling): Path {
        val (start, sweep) = spans.getValue(f)
        val r0 = radii[f.depth] * r
        val r1 = radii[f.depth + 1] * r
        cell.reset()
        if (sweep >= 359.9f && r0 == 0f) {                // zoomed-in center: a full disk
            cell.addCircle(cx, cy, r1, Path.Direction.CW)
            return cell
        }
        val a0 = start - 90f
        oval.set(cx - r1, cy - r1, cx + r1, cy + r1)
        cell.arcTo(oval, a0, sweep, true)
        if (r0 > 0f) {
            oval.set(cx - r0, cy - r0, cx + r0, cy + r0)
            cell.arcTo(oval, a0 + sweep, -sweep)
        } else {
            cell.lineTo(cx, cy)
        }
        cell.close()
        return cell
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 4f
        val sel = selected
        val density = resources.displayMetrics.density
        line.strokeWidth = 0.75f * density

        fun draw(f: Feeling) {
            val inFamily = sel == null || focus != null || f.core == sel.core
            var c = shade(f.color, f.depth)
            if (!inFamily) c = dim(c)
            fill.color = c
            val p = cellPath(cx, cy, r, f)
            canvas.drawPath(p, fill)
            canvas.drawPath(p, line)
            drawLabel(canvas, cx, cy, r, f, inFamily)
            f.children.forEach { draw(it) }
        }
        roots.forEach { draw(it) }

        // Outline the picked cell boldly and its ancestors lightly (not the zoomed-in center).
        sel?.path?.forEach { f ->
            if (f !in spans || (focus != null && f.depth == 0)) return@forEach
            outline.strokeWidth = (if (f == sel) 3f else 1.5f) * density
            canvas.drawPath(cellPath(cx, cy, r, f), outline)
        }
    }

    private fun drawLabel(canvas: Canvas, cx: Float, cy: Float, r: Float, f: Feeling, inFamily: Boolean) {
        val (start, sweep) = spans.getValue(f)
        val r0 = radii[f.depth] * r
        val r1 = radii[f.depth + 1] * r
        label.typeface = if (f.depth == 0 || f == selected) bold else plain
        label.alpha = if (inFamily) 255 else 110

        if (focus != null && f.depth == 0) {             // zoomed-in center: horizontal title + the way out
            label.textAlign = Paint.Align.CENTER
            label.textSize = sp(24f)
            val w = label.measureText(f.text)
            if (w > r1 * 1.7f) label.textSize *= r1 * 1.7f / w
            canvas.drawText(f.text, cx, cy, label)
            label.typeface = plain
            label.textSize = sp(13f)
            canvas.drawText("‹ all feelings", cx, cy + label.textSize * 1.8f, label)
            return
        }

        // Text runs along the radius. Its height is limited by the arc width near the inner edge,
        // its length by the ring depth.
        val mid = start + sweep / 2f                     // degrees clockwise from 12 o'clock
        val inner = if (f.depth == 0) r1 * 0.30f else r0 + (r1 - r0) * 0.06f
        val arc = Math.toRadians(sweep.toDouble()).toFloat() * inner
        val avail = (r1 - inner) - (r1 - r0) * 0.06f
        var size = min(arc * 0.78f, sp(if (focus != null) 19f else 15f))
        label.textSize = size
        val w = label.measureText(f.text)
        if (w > avail) { size *= avail / w; label.textSize = size }

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(mid - 90f)                         // +x now points along the bisector, outward
        val flip = mid > 180f                            // left half: turn the text so it isn't upside down
        val baseline = label.textSize * 0.35f
        if (flip) {
            canvas.rotate(180f)
            label.textAlign = Paint.Align.RIGHT
            canvas.drawText(f.text, -inner - (avail - min(avail, label.measureText(f.text))) / 2f, baseline, label)
        } else {
            label.textAlign = Paint.Align.LEFT
            canvas.drawText(f.text, inner + (avail - min(avail, label.measureText(f.text))) / 2f, baseline, label)
        }
        canvas.restore()
    }

    private fun hit(x: Float, y: Float): Feeling? {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 4f
        val d = hypot(x - cx, y - cy) / r
        if (d > 1f) return null
        val depth = when { d < radii[1] -> 0; d < radii[2] -> 1; else -> 2 }
        var a = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat() + 90f
        if (a < 0f) a += 360f
        return spans.keys.firstOrNull { f ->
            val (s, w) = spans.getValue(f)
            f.depth == depth && (w >= 359.9f || (a >= s && a < s + w))
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> return hit(e.x, e.y) != null
            MotionEvent.ACTION_UP -> {
                val f = hit(e.x, e.y) ?: return true
                performClick()
                if (focus != null && f.depth == 0) {
                    focus = null
                    onZoomOut?.invoke()
                } else {
                    selected = f
                    focus = f.core
                    onPick?.invoke(f)
                }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
