package io.github.bmp02050.tare

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One screen: permission, on/off, test. Shows no numbers, on purpose. */
class WatchActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: TextView

    private val foreground get() = if (Build.VERSION.SDK_INT >= 36)
        arrayOf("android.permission.health.READ_HEART_RATE", "android.permission.ACTIVITY_RECOGNITION")
    else arrayOf("android.permission.BODY_SENSORS", "android.permission.ACTIVITY_RECOGNITION")

    private val background get() = if (Build.VERSION.SDK_INT >= 36)
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" else "android.permission.BODY_SENSORS_BACKGROUND"

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private fun button(s: String, fill: Int, onClick: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        minHeight = 44.dp()
        background = GradientDrawable().apply { setColor(fill); cornerRadius = 22.dp().toFloat() }
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28.dp(), 30.dp(), 28.dp(), 40.dp())  // round screen: keep content off the edges
        }
        col.addView(TextView(this).apply {
            text = "Tare"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            textSize = 13f; setTextColor(0xFFB8BEC6.toInt()); gravity = Gravity.CENTER
            setPadding(0, 6.dp(), 0, 10.dp())
        }
        col.addView(status)
        toggle = button("", 0xFF1F6F9E.toInt()) {
            Body.setEnabled(this, !Body.enabled(this))
            refresh()
        }
        col.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(android.view.View(this), LinearLayout.LayoutParams(1, 8.dp()))
        col.addView(button("Send test", 0xFF30343A.toInt()) {
            Body.send(this, "test")
            status.text = "Test sent: check your phone"
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
    }

    override fun onResume() {
        super.onResume()
        when {
            !foreground.all(::granted) -> requestPermissions(foreground, 1)
            !granted(background) -> requestPermissions(arrayOf(background), 2)
            Body.enabled(this) -> Body.register(this)
        }
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && foreground.all(::granted) && !granted(background)) {
            requestPermissions(arrayOf(background), 2)
        } else if (foreground.all(::granted) && granted(background) && Body.enabled(this)) {
            Body.register(this)
        }
        refresh()
    }

    private fun refresh() {
        val on = Body.enabled(this)
        toggle.text = if (on) "Body check-ins: on" else "Body check-ins: off"
        status.text = when {
            !foreground.all(::granted) || !granted(background) ->
                "Needs heart-rate access (all the time) to notice when your body changes"
            !on -> "Off. Random check-ins still come from your phone."
            Body.baselineSize(this) < Body.MIN_BASELINE_SAMPLES ->
                "Learning your resting rhythm. Prompts start in a day or two."
            else -> "On. If your body shifts while you're still, your phone will ask you to check in."
        }
    }
}
