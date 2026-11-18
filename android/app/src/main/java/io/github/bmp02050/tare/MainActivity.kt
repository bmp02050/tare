package io.github.bmp02050.tare

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private lateinit var log: NoticeLog
    private lateinit var labels: List<Label>
    private lateinit var feelings: Feelings
    private val byId = HashMap<String, Label>()
    private val ui = Handler(Looper.getMainLooper())

    private var dark = false
    private val cBg get() = if (dark) 0xFF14161A.toInt() else 0xFFF6F5F2.toInt()
    private val cSurface get() = if (dark) 0xFF1E2126.toInt() else 0xFFFFFFFF.toInt()
    private val cText get() = if (dark) 0xFFECEAE6.toInt() else 0xFF1B1D21.toInt()
    private val cMuted get() = if (dark) 0xFFA3A8AF.toInt() else 0xFF5D636B.toInt()
    private val cLine get() = if (dark) 0xFF30343A.toInt() else 0xFFDEDBD4.toInt()
    private val cAccent get() = if (dark) 0xFF6FB3DC.toInt() else 0xFF1F6F9E.toInt()
    private val cWarn get() = if (dark) 0xFFE0A24A.toInt() else 0xFF9A5A00.toInt()
    // Text on accent fills: the dark-mode accent is light blue, so white text was ~2:1 contrast.
    private val cOnAccent get() = if (dark) 0xFF0B1F2B.toInt() else Color.WHITE

    // Screens share one ScrollView; only one is attached at a time.
    private lateinit var scroll: ScrollView
    private lateinit var mainScreen: LinearLayout
    private val wheelScreen by lazy { buildWheelScreen() }
    private val reflectScreen by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; pad() } }

    private lateinit var grid: GridLayout
    private lateinit var recentList: LinearLayout
    private lateinit var checkinToggle: TextView
    private lateinit var sheet: LinearLayout
    private lateinit var sheetTitle: TextView
    private lateinit var sheetSupport: TextView
    private lateinit var note: EditText
    private val perDayButtons = ArrayList<TextView>()

    private var current: String? = null          // row the sheet is amending
    private val hideSheet = Runnable { if (note.hasFocus()) armHide() else closeSheet() }

    // Wheel screen state
    private lateinit var wheel: WheelView
    private lateinit var search: EditText
    private lateinit var results: LinearLayout
    private lateinit var wheelHint: TextView
    private lateinit var wheelTitle: TextView
    private lateinit var wheelSub: TextView
    private lateinit var pickBox: LinearLayout
    private lateinit var pathText: TextView
    private lateinit var chips: GridLayout
    private lateinit var nextBtn: TextView
    private lateinit var checkinExtras: LinearLayout
    private lateinit var awareBox: LinearLayout
    private lateinit var awareQ: TextView
    private var wheelCheckin = false
    private var promptRef = ""                         // prompt row this check-in answers, if it came from one

    // Reflection screen state: answers are written when you leave the screen (Done, back, or app left)
    private var reflectRef: String? = null
    private val answerFields = LinkedHashMap<String, EditText>()
    private val saved = HashMap<String, String>()      // question id -> last text written
    private val mindPicked = LinkedHashSet<String>()
    private var micTarget: EditText? = null

    private var backCallback: Any? = null              // OnBackInvokedCallback on API 33+

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)  // our own header; the theme's bar overlapped it
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        log = NoticeLog(this)
        labels = NoticeLog.loadLabels(this)
        labels.forEach { byId[it.id] = it }
        byId["other"] = Label("other", "Other", "add a note", 0xFF5A6672.toInt())
        byId["none"] = Label("none", "Nothing notable", "", 0xFF5A6672.toInt())
        byId["unsure"] = Label("unsure", "Couldn't name it", "", 0xFF5A6672.toInt())
        byId["notnow"] = Label("notnow", "Not now", "", 0xFF5A6672.toInt())
        feelings = Feelings.load(this)
        if (!log.hasMeta(PROTOCOL_V3)) {
            log.append("meta", label = PROTOCOL_V3,
                note = "Not now button on check-in notifications (checkin label notnow); safety line. Protocol frozen 4 weeks from here")
        }
        if (!log.hasMeta(PROTOCOL_V2)) {
            // Marks the switch so baseline analysis can compare before/after.
            log.append("meta", label = PROTOCOL_V2,
                note = "check-ins and Name a feeling use the feelings wheel + open reflection questions; no strength rating in that flow")
        }
        if (!log.hasMeta(NO_STRENGTH)) {
            log.append("meta", label = NO_STRENGTH,
                note = "strength (1-5) removed everywhere; added good states (let it go, paused, in flow, energized, present, connected)")
        }

        // Window styling must come after setContentView: before it the decor view doesn't exist
        // and window.insetsController throws an NPE (seen on Android 13).
        setContentView(buildUi())
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        Checkins.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Checkins.scheduleNext(this)
        refreshRecent()
        refreshFooter()
    }

    override fun onStop() {
        super.onStop()
        saveReflection()  // keep answers if the app is left mid-reflection
        savePlan()
    }

    private fun handleIntent(i: Intent?) {
        val isCheckin = i?.getBooleanExtra(Checkins.EXTRA_CHECKIN, false) == true
        val prompt = i?.getStringExtra(Checkins.EXTRA_PROMPT).orEmpty()
        i?.removeExtra(Checkins.EXTRA_CHECKIN)
        i?.removeExtra(Checkins.EXTRA_PROMPT)
        if (isCheckin) {
            saveReflection()
            openWheel(checkin = true)
            promptRef = prompt
        }
    }

    // ------------------------------------------------------------------ navigation
    private fun show(screen: View) {
        hideKeyboard()
        scroll.removeAllViews()
        scroll.addView(screen)
        scroll.scrollTo(0, 0)
        setBackIntercept(screen !== mainScreen)
    }

    private fun goHome() {
        show(mainScreen)
        refreshRecent()
        refreshFooter()
    }

    /** Back returns to the main screen from the wheel/reflection screens instead of leaving the app. */
    private fun onBack(): Boolean {
        val shown = scroll.getChildAt(0)
        return when {
            shown === reflectScreen -> { saveReflection(); reflectRef = null; goHome(); true }
            shown === supportScreen -> { savePlan(); goHome(); true }
            shown === wheelScreen && awareBox.visibility == View.VISIBLE -> { showAware(false); true }
            shown === wheelScreen -> { goHome(); true }
            else -> false
        }
    }

    private fun setBackIntercept(on: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val d = onBackInvokedDispatcher
        if (on && backCallback == null) {
            val cb = OnBackInvokedCallback { onBack() }
            d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (!on && backCallback != null) {
            d.unregisterOnBackInvokedCallback(backCallback as OnBackInvokedCallback)
            backCallback = null
        }
    }

    @Deprecated("Used below API 33; newer versions use OnBackInvokedCallback")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (!onBack()) super.onBackPressed()
    }

    // ------------------------------------------------------------------ UI helpers
    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp.dp().toFloat()
        if (stroke != null) setStroke(1.dp(), stroke)
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun pill(s: String, primary: Boolean = false, onClick: () -> Unit) =
        text(s, 15f, if (primary) cOnAccent else cText).apply {
            background = if (primary) rounded(cAccent, 12) else rounded(cSurface, 12, cLine)
            gravity = Gravity.CENTER
            minHeight = 48.dp()
            setPadding(16.dp(), 0, 16.dp(), 0)
            setOnClickListener { onClick() }
        }

    private fun link(s: String, onClick: () -> Unit) = text(s, 14f, cAccent).apply {
        setPadding(0, 12.dp(), 0, 12.dp())
        setOnClickListener { onClick() }
    }

    private fun LinearLayout.pad() = setPadding(16.dp(), 16.dp(), 16.dp(), 32.dp())

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }

    private fun spacer(wDp: Int = 0, weight: Float = 0f) =
        View(this).apply { layoutParams = LinearLayout.LayoutParams(if (weight > 0) 0 else wDp.dp(), 1, weight) }

    private fun gridParams() = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f),
    ).apply {
        width = 0
        setMargins(4.dp(), 4.dp(), 4.dp(), 4.dp())
    }

    // ------------------------------------------------------------------ main screen
    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(cBg) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; pad() }
        mainScreen = content

        content.addView(text("Notice", 24f, cText, bold = true))
        content.addView(text("Tap what you notice. One tap is enough.", 14f, cMuted).apply { setPadding(0, 2.dp(), 0, 14.dp()) })

        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            background = rounded(cSurface, 16, cAccent)
            addView(text("Name a feeling", 17f, cText, bold = true))
            addView(text("Find it on the wheel, then put it into words", 13f, cMuted))
            isClickable = true
            setOnClickListener { buzz(this); openWheel(checkin = false) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(5.dp(), 0, 5.dp(), 8.dp())
        })

        grid = GridLayout(this).apply { columnCount = 2 }
        content.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rebuildGrid()

        content.addView(text("Recent", 14f, cMuted, bold = true).apply { setPadding(0, 22.dp(), 0, 6.dp()) })
        recentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(recentList)

        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 14.dp(), 0, 0)
            addView(link("Do a check-in") { openWheel(checkin = true) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(link("Export CSV") { exportCsv() })
        })
        content.addView(text("Random check-ins per day", 14f, cMuted, bold = true).apply { setPadding(0, 18.dp(), 0, 8.dp()) })
        val choiceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in Checkins.CHOICES) {
            val b = text(if (n == 0) "Off" else "$n", 15f, cText, bold = true).apply {
                gravity = Gravity.CENTER
                tag = n
                setOnClickListener {
                    Checkins.setPerDay(this@MainActivity, n)
                    buzz(this)
                    refreshFooter()
                }
            }
            perDayButtons.add(b)
            choiceRow.addView(b, LinearLayout.LayoutParams(0, 44.dp(), 1f).apply { marginEnd = 8.dp() })
        }
        content.addView(choiceRow)
        checkinToggle = text("", 14f, cMuted).apply { setPadding(0, 10.dp(), 0, 4.dp()) }
        content.addView(checkinToggle)
        content.addView(link("Send test check-in") {
            if (!Checkins.notificationsAllowed(this)) {
                Toast.makeText(this, "Notifications are off for Tare Notice: enable them in Settings → Apps", Toast.LENGTH_LONG).show()
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            } else {
                Checkins.notify(this, null)
                Toast.makeText(this, "Sent: check your notification shade", Toast.LENGTH_SHORT).show()
            }
        }.apply { setTextColor(cMuted) })
        content.addView(link("If you're not safe right now") { openSupport() })

        scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(buildSheet(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        root.setOnApplyWindowInsetsListener { v, insets ->
            val s = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            WindowInsets.CONSUMED
        }
        return root
    }

    private fun buildSheet(): View {
        sheetTitle = text("", 17f, cText, bold = true)
        note = EditText(this).apply {
            hint = "Note (optional)"
            setHintTextColor(cMuted)
            setTextColor(cText)
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = rounded(cBg, 12, cLine)
            setPadding(12.dp(), 0, 12.dp(), 0)
            minHeight = 48.dp()
            // Keyboard "Done" sends IME_ACTION_DONE; hardware/injected Enter arrives as a key event instead.
            setOnEditorActionListener { _, id, ev ->
                val enter = ev?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && ev.action == android.view.KeyEvent.ACTION_DOWN
                if (id == EditorInfo.IME_ACTION_DONE || enter) { saveNote(); true } else false
            }
            setOnFocusChangeListener { _, _ -> armHide() }
        }
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = 16f.dp()
            background = GradientDrawable().apply {
                setColor(cSurface)
                val r = 20.dp().toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
            isClickable = true  // don't let taps fall through to the buttons underneath
            addView(sheetTitle)
            sheetSupport = link("Not safe? Call or text 988, or open your plan") { closeSheet(); openSupport() }
            addView(sheetSupport)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12.dp(), 0, 12.dp())
                addView(note, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(View(context), LinearLayout.LayoutParams(8.dp(), 1))
                addView(pill("Add") { saveNote() })
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pill("Undo") { current?.let { log.append("void", ref = it) }; buzz(this); closeSheet() })
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(pill("Done", primary = true) { closeSheet() })
            })
        }
        return sheet
    }

    private fun Float.dp() = this * resources.displayMetrics.density

    private fun labelButton(l: Label, plain: Boolean, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.START
            minimumHeight = 84.dp()
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            background = if (plain) rounded(cSurface, 16, cLine) else rounded(l.color, 16)
            val fg = if (plain) cText else Color.WHITE
            addView(text(l.text, 17f, fg, bold = true))
            if (l.hint.isNotEmpty()) addView(text(l.hint, 12.5f, fg).apply { alpha = 0.88f })
            isClickable = true
            setOnClickListener { buzz(this); onClick() }
        }

    private fun rebuildGrid() {
        grid.removeAllViews()
        fun header(s: String) = grid.addView(text(s, 14f, cMuted, bold = true).apply { setPadding(5.dp(), 14.dp(), 0, 2.dp()) },
            GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 2)))
        fun tile(l: Label, plain: Boolean = false) =
            grid.addView(labelButton(l, plain) { tap(l.id) }, gridParams().apply { setMargins(5.dp(), 5.dp(), 5.dp(), 5.dp()) })
        header("Hard")
        labels.filter { !it.good }.forEach { tile(it) }
        header("Good")
        labels.filter { it.good }.forEach { tile(it) }
        tile(byId.getValue("other"), plain = true)
    }

    private fun refreshFooter() {
        val n = Checkins.perDay(this)
        for (b in perDayButtons) {
            val on = b.tag == n
            b.background = if (on) rounded(cAccent, 12) else rounded(cSurface, 12, cLine)
            b.setTextColor(if (on) cOnAccent else cText)
        }
        val next = Checkins.next(this)
        checkinToggle.text = if (next == null) "Off: no random check-ins." else {
            val day = if (next.toLocalDate() == LocalDate.now()) "" else "tomorrow "
            "Between 09:00 and 21:00 · next ${day}${next.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        }
    }

    private fun refreshRecent() {
        recentList.removeAllViews()
        val items = log.recent(10)
        if (items.isEmpty()) {
            recentList.addView(text("Nothing yet.", 14f, cMuted).apply { setPadding(0, 8.dp(), 0, 8.dp()) })
            return
        }
        for (e in items) recentList.addView(recentRow(e))
    }

    private fun recentRow(e: Entry): View {
        val f = feelings.find(e.label)
        val l = if (f != null) Label(e.label, f.text, "", f.color) else byId[e.label] ?: Label(e.label, e.label, "", Color.GRAY)
        val meta = buildList {
            if (e.reflected) add("✎")
            if (e.kind == "checkin") add(when (e.aware) { "yes" -> "check-in ✓"; "no" -> "check-in ✗"; else -> "check-in" })
        }.joinToString(" · ")
        val undo = text("undo", 13f, cMuted).apply { setPadding(10.dp(), 8.dp(), 0, 8.dp()) }
        var armed = false
        undo.setOnClickListener {
            if (armed) {  // second tap within 3 s
                log.append("void", ref = e.id)
                buzz(undo)
                refreshRecent()
            } else {
                armed = true
                undo.text = "tap to undo"
                undo.setTextColor(cWarn)
                ui.postDelayed({ armed = false; undo.text = "undo"; undo.setTextColor(cMuted) }, 3000)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6.dp(), 0, 6.dp())
            addView(text(e.tClient.drop(11).take(5), 15f, cMuted), LinearLayout.LayoutParams(50.dp(), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(context).apply { background = rounded(l.color, 5) }, LinearLayout.LayoutParams(10.dp(), 10.dp()).apply { marginEnd = 10.dp() })
            addView(text(l.text + if (e.note.isNotEmpty()) " · ${e.note}" else "", 15f, cText).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (meta.isNotEmpty()) addView(text(meta, 13f, cMuted).apply { setPadding(8.dp(), 0, 0, 0) })
            addView(undo)
            if (f != null || e.label == "unsure") {  // tap to reread or add to what you wrote
                isClickable = true
                setOnClickListener { openReflection(e.id, f, log.answers(e.id)) }
            }
        }
    }

    // ------------------------------------------------------------------ wheel screen
    private fun buildWheelScreen(): LinearLayout {
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; pad() }
        wheelTitle = text("", 24f, cText, bold = true)
        wheelSub = text("", 14f, cMuted).apply { setPadding(0, 2.dp(), 0, 12.dp()) }
        s.addView(wheelTitle)
        s.addView(wheelSub)

        pickBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        search = EditText(this).apply {
            hint = "Search, e.g. lonely, rushed, proud"
            setHintTextColor(cMuted)
            setTextColor(cText)
            textSize = 16f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = rounded(cSurface, 12, cLine)
            setPadding(14.dp(), 0, 14.dp(), 0)
            minHeight = 48.dp()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = showResults(s?.toString().orEmpty())
            })
            setOnEditorActionListener { _, _, _ -> feelings.search(text.toString()).firstOrNull()?.let { choose(it) }; true }
        }
        pickBox.addView(search)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 4.dp(), 0, 4.dp()) }
        pickBox.addView(results)
        wheel = WheelView(this, feelings.cores, cBg).apply {
            onPick = { buzz(this); pick(it) }
            onZoomOut = { buzz(this); updateHint() }
        }
        pickBox.addView(wheel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 8.dp()
        })
        pathText = text("", 20f, cText, bold = true).apply { setPadding(0, 14.dp(), 0, 2.dp()) }
        pickBox.addView(pathText)
        wheelHint = text("", 13f, cMuted).apply { setPadding(0, 0, 0, 8.dp()) }
        pickBox.addView(wheelHint)
        chips = GridLayout(this).apply { columnCount = 3 }
        pickBox.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        nextBtn = pill("Next", primary = true) { confirmPick() }
        pickBox.addView(row(pill("Back") { goHome() }, spacer(weight = 1f), nextBtn).apply { setPadding(0, 14.dp(), 0, 0) })
        checkinExtras = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6.dp(), 0, 0)
            addView(link("Can't name it") { unsure() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(link("Nothing notable") {
                log.append("checkin", label = "none", ref = promptRef)
                Toast.makeText(this@MainActivity, "Logged. Thanks.", Toast.LENGTH_SHORT).show()
                goHome()
            })
        }
        pickBox.addView(checkinExtras)
        s.addView(pickBox)

        awareQ = text("", 18f, cText).apply { setPadding(0, 8.dp(), 0, 14.dp()) }
        awareBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(awareQ)
            addView(pill("Yes, I'd already noticed", primary = true) { answerAware("yes") })
            addView(spacer(), LinearLayout.LayoutParams(1, 10.dp()))
            addView(pill("No, only now") { answerAware("no") })
            addView(link("Back to the wheel") { showAware(false) })
        }
        s.addView(awareBox)
        return s
    }

    private fun openWheel(checkin: Boolean) {
        closeSheet()
        val s = wheelScreen
        wheelCheckin = checkin
        promptRef = ""
        wheelTitle.text = if (checkin) "Check-in" else "Name a feeling"
        wheelSub.text = if (checkin) "How are you right now? Find the closest spot." else "What are you feeling? Find the closest spot."
        checkinExtras.visibility = if (checkin) View.VISIBLE else View.GONE
        search.setText("")
        wheel.selected = null
        wheel.focus = null
        showAware(false)
        pick(null)
        show(s)
    }

    private fun updateHint() {
        wheelHint.text = if (wheel.focus == null) "Tap a color to zoom in, or search above."
            else "Tap the word that fits. Tap the center to see all feelings."
    }

    /** Search results: the word plus where it sits, since some words appear in two families. */
    private fun showResults(q: String) {
        results.removeAllViews()
        if (q.isBlank()) return
        val found = feelings.search(q)
        if (found.isEmpty()) {
            val tip = if (wheelCheckin) "Not on the wheel. Pick the closest, or use Can't name it and describe it in your own words."
                else "Not on the wheel. Pick the closest word, then describe it in your own words."
            results.addView(text(tip, 14f, cMuted)
                .apply { setPadding(4.dp(), 8.dp(), 4.dp(), 8.dp()) })
            return
        }
        for (f in found) {
            results.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4.dp(), 10.dp(), 4.dp(), 10.dp())
                addView(View(context).apply { background = rounded(f.color, 6) },
                    LinearLayout.LayoutParams(12.dp(), 12.dp()).apply { marginEnd = 12.dp() })
                addView(text(f.text, 17f, cText, bold = true))
                if (f.depth > 0) addView(text("   " + f.path.dropLast(1).joinToString(" › ") { it.text }, 14f, cMuted))
                isClickable = true
                setOnClickListener { buzz(this); choose(f) }
            })
        }
    }

    private fun choose(f: Feeling) {
        search.setText("")
        hideKeyboard()
        pick(f)
    }

    /** Selection changed (wheel tap or chip): show the path and the nearby words to refine with. */
    private fun pick(f: Feeling?) {
        wheel.selected = f
        if (f != null) wheel.focus = f.core
        updateHint()
        nextBtn.isEnabled = f != null
        nextBtn.alpha = if (f != null) 1f else 0.4f
        pathText.text = f?.path?.joinToString("  ›  ") { it.text } ?: "Nothing picked yet"
        pathText.setTextColor(if (f != null) cText else cMuted)
        chips.removeAllViews()
        if (f == null) return
        // Core: its secondary feelings. Secondary or specific: that secondary and its specifics, plus a way back out.
        val options = when (f.depth) {
            0 -> f.children
            1 -> listOf(f.parent!!, f) + f.children
            else -> listOf(f.parent!!.parent!!, f.parent!!) + f.parent!!.children
        }
        for (o in options) {
            val on = o == f
            val chip = text(if (o.depth < f.depth && o.depth == 0 && f.depth > 0) "‹ ${o.text}" else o.text, 15f,
                if (on) 0xFF1B1D21.toInt() else cText, bold = on).apply {
                gravity = Gravity.CENTER
                minHeight = 46.dp()
                setPadding(6.dp(), 0, 6.dp(), 0)
                background = if (on) rounded(o.color, 12) else rounded(cSurface, 12, cLine)
                setOnClickListener { buzz(this); pick(o) }
            }
            chips.addView(chip, gridParams())
        }
    }

    private fun confirmPick() {
        val f = wheel.selected ?: return
        if (wheelCheckin) {
            awareQ.text = "${f.text}: had you noticed it yourself before this check-in?"
            showAware(true)
        } else {
            val id = log.append("event", label = f.id)
            openReflection(id, f, emptyMap())
        }
    }

    private fun showAware(on: Boolean) {
        awareBox.visibility = if (on) View.VISIBLE else View.GONE
        pickBox.visibility = if (on) View.GONE else View.VISIBLE
        scroll.scrollTo(0, 0)
    }

    private fun answerAware(answer: String) {
        val f = wheel.selected ?: return
        val id = log.append("checkin", label = f.id, aware = answer, ref = promptRef)
        openReflection(id, f, emptyMap())
    }

    private fun unsure() {
        val id = log.append("checkin", label = "unsure", ref = promptRef)
        openReflection(id, null, emptyMap())
    }

    // ------------------------------------------------------------------ reflection screen
    /**
     * Open questions in your own words: no scores, nothing to get right. Answers are optional;
     * "What happened right before this?" always shows, plus two others, with the rest one tap away.
     */
    private fun openReflection(ref: String, f: Feeling?, answers: Map<String, String>) {
        saveReflection()
        reflectRef = ref
        answerFields.clear()
        saved.clear()
        saved.putAll(answers)
        mindPicked.clear()
        answers["mind"]?.split(';')?.filter { it.isNotEmpty() }?.let { mindPicked.addAll(it) }

        val s = reflectScreen
        s.removeAllViews()
        s.addView(row(
            View(this).apply { background = rounded(f?.color ?: cMuted, 8) }.also {
                it.layoutParams = LinearLayout.LayoutParams(16.dp(), 16.dp()).apply { marginEnd = 10.dp() }
            },
            text(f?.text ?: "Couldn't name it", 24f, cText, bold = true),
        ))
        if (f != null && f.depth > 0) s.addView(text(f.path.joinToString("  ›  ") { it.text }, 14f, cMuted).apply { setPadding(26.dp(), 0, 0, 0) })
        s.addView(text("Naming it is the main thing. Anything below is optional; say it or type it.", 14f, cMuted).apply {
            setPadding(0, 10.dp(), 0, 6.dp())
        })
        if (f != null && f.path.any { it.text in HEAVY }) s.addView(supportCard())

        val core = f?.core?.text ?: ""
        val applicable = feelings.questions.filter { it.appliesTo(core) }
        val first = applicable.filter { it.always || answers.containsKey(it.id) }
        val rest = applicable.filter { it !in first }.shuffled()
        val shown = first + rest.take((3 - first.size).coerceAtLeast(0))
        val hidden = applicable.filter { it !in shown }
        val qBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        shown.forEach { qBox.addView(questionView(it, answers[it.id].orEmpty())) }
        s.addView(qBox)
        if (hidden.isNotEmpty()) {
            lateinit var more: TextView
            more = link("More questions (${hidden.size})") {
                hidden.forEach { qBox.addView(questionView(it, "")) }
                more.visibility = View.GONE
            }
            s.addView(more)
        }

        // The tare states, if one of them is also going on (multi-select).
        s.addView(text("Anything else going on?", 15f, cText, bold = true).apply { setPadding(0, 16.dp(), 0, 6.dp()) })
        val mind = GridLayout(this).apply { columnCount = 2 }
        for (l in labels) {
            val chip = text(l.text, 14f, cText).apply {
                gravity = Gravity.CENTER
                minHeight = 44.dp()
            }
            fun paint() {
                val on = l.id in mindPicked
                chip.background = if (on) rounded(l.color, 12) else rounded(cSurface, 12, cLine)
                chip.setTextColor(if (on) Color.WHITE else cText)
            }
            paint()
            chip.setOnClickListener {
                if (!mindPicked.add(l.id)) mindPicked.remove(l.id)
                buzz(chip)
                paint()
            }
            mind.addView(chip, gridParams())
        }
        s.addView(mind, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        s.addView(row(spacer(weight = 1f), pill("Done", primary = true) {
            saveReflection()
            reflectRef = null
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            goHome()
        }).apply { setPadding(0, 18.dp(), 0, 0) })
        show(s)
    }

    private fun questionView(q: Question, existing: String): View {
        val field = EditText(this).apply {
            setText(existing)
            hint = q.hint.ifEmpty { "Your words" }
            setHintTextColor(cMuted)
            setTextColor(cText)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            background = rounded(cSurface, 12, cLine)
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
        }
        answerFields[q.id] = field
        val mic = text("🎙", 22f, cText).apply {
            gravity = Gravity.CENTER
            contentDescription = "Speak your answer"
            background = rounded(cSurface, 12, cLine)
            setOnClickListener { listen(field, q.text) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 14.dp(), 0, 0)
            addView(text(q.text, 16f, cText, bold = true).apply { setPadding(0, 0, 0, 6.dp()) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false  // baseline alignment pushed the field down and clipped its bottom edge
                addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(mic, LinearLayout.LayoutParams(52.dp(), 52.dp()).apply { marginStart = 8.dp() })
            })
        }
    }

    /** Speech-to-text via the system recognizer (no permission needed: it owns the mic). */
    private fun listen(target: EditText, prompt: String) {
        micTarget = target
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, SPEECH_REQUEST)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No speech recognizer here: use the mic on your keyboard", Toast.LENGTH_LONG).show()
        }
    }

    /** Write answers that changed since the last save (the latest row per question wins). */
    private fun saveReflection() {
        val ref = reflectRef ?: return
        val current = answerFields.mapValues { it.value.text.toString().trim() } +
            ("mind" to mindPicked.joinToString(";"))
        for ((qid, v) in current) {
            if (v != saved[qid].orEmpty()) {
                log.append("reflect", label = qid, note = v, ref = ref)
                saved[qid] = v
            }
        }
    }

    // ------------------------------------------------------------------ quick-tap actions
    private fun buzz(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    private fun tap(labelId: String) {
        val id = log.append("event", label = labelId)
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        openSheet(id, "Logged: ${byId.getValue(labelId).text} · $time", focusNote = labelId == "other")
        sheetSupport.visibility = if (labelId == "checkout") View.VISIBLE else View.GONE
        refreshRecent()
    }

    // ------------------------------------------------------------------ support
    private val planPrefs get() = getSharedPreferences("support", MODE_PRIVATE)

    /** Short, calm, and never in the log. Dial/SMS screens open; nothing is sent automatically. */
    private fun supportCard(full: Boolean = true): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
        background = rounded(cSurface, 14, cWarn)
        addView(text("If you're not safe right now, you don't have to handle it alone.", 15f, cText, bold = true))
        addView(text("988 Suicide & Crisis Lifeline (US): call or text, any time.", 14f, cMuted).apply { setPadding(0, 4.dp(), 0, 8.dp()) })
        addView(row(
            pill("Call 988") { startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:988"))) },
            spacer(8),
            pill("Text 988") { startActivity(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:988"))) },
        ).apply { if (full) { addView(spacer(8)); addView(pill("My plan") { openSupport() }) } })
        val plan = planPrefs.getString("plan", "").orEmpty()
        if (full && plan.isNotBlank()) addView(text(plan, 14f, cText).apply { setPadding(0, 10.dp(), 0, 0) })
    }

    private lateinit var planField: EditText
    private val supportScreen by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            pad()
            addView(text("Support", 24f, cText, bold = true))
            addView(supportCard(full = false),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dp() })
            addView(text("My plan", 17f, cText, bold = true).apply { setPadding(0, 20.dp(), 0, 4.dp()) })
            addView(text("Write it with your therapist: early signs, what helps, who to reach (names and numbers), " +
                "and how to make where you are safer. It stays on this phone and isn't part of the log.", 14f, cMuted)
                .apply { setPadding(0, 0, 0, 8.dp()) })
            planField = EditText(context).apply {
                setText(planPrefs.getString("plan", ""))
                hint = "My plan"
                setHintTextColor(cMuted)
                setTextColor(cText)
                textSize = 16f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 6
                gravity = Gravity.TOP or Gravity.START
                background = rounded(cSurface, 12, cLine)
                setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            }
            addView(planField)
            addView(row(spacer(weight = 1f), pill("Done", primary = true) { savePlan(); goHome() }).apply { setPadding(0, 14.dp(), 0, 0) })
        }
    }

    private fun openSupport() {
        saveReflection()
        reflectRef = null
        val s = supportScreen
        planField.setText(planPrefs.getString("plan", ""))
        show(s)
    }

    private fun savePlan() {
        if (::planField.isInitialized) planPrefs.edit().putString("plan", planField.text.toString().trim()).apply()
    }

    private fun openSheet(id: String, heading: String, focusNote: Boolean) {
        current = id
        sheetTitle.text = heading
        note.setText("")
        sheet.visibility = View.VISIBLE
        if (focusNote) {
            note.requestFocus()
            ui.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(note, 0) }, 150)
        }
        armHide()
    }

    private fun armHide() {
        ui.removeCallbacks(hideSheet)
        ui.postDelayed(hideSheet, 20_000)
    }

    private fun closeSheet() {
        ui.removeCallbacks(hideSheet)
        hideKeyboard()
        sheet.visibility = View.GONE
        current = null
        refreshRecent()
    }

    private fun saveNote() {
        val ref = current ?: return
        val v = note.text.toString().trim()
        if (v.isNotEmpty()) {
            log.append("amend", note = v, ref = ref)
            note.setText("")
            buzz(note)
            Toast.makeText(this, "Note added", Toast.LENGTH_SHORT).show()
        }
        hideKeyboard()
        armHide()
    }

    private fun hideKeyboard() {
        val focused = currentFocus ?: note
        focused.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(focused.windowToken, 0)
    }

    // ------------------------------------------------------------------ export
    private fun exportCsv() {
        if (!log.file.exists()) {
            Toast.makeText(this, "Nothing logged yet", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, "notices_${LocalDate.now()}.csv")
        @Suppress("DEPRECATION")
        startActivityForResult(i, EXPORT_REQUEST)
    }

    @Deprecated("Platform Activity result API; no AndroidX in this app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SPEECH_REQUEST) {
            val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            val target = micTarget
            if (resultCode == RESULT_OK && !heard.isNullOrBlank() && target != null) {
                val before = target.text.toString().trimEnd()
                target.setText(if (before.isEmpty()) heard.replaceFirstChar { it.uppercase() } else "$before $heard")
                target.setSelection(target.text.length)
            }
            return
        }
        if (requestCode != EXPORT_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { out -> log.file.inputStream().use { it.copyTo(out) } }
            Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val EXPORT_REQUEST = 42
        private const val SPEECH_REQUEST = 43
        private const val PROTOCOL_V2 = "protocol_v2"
        private const val NO_STRENGTH = "no_strength"
        private const val PROTOCOL_V3 = "protocol_v3"
        /** Words after which the support line is shown up front (it's always one tap away on the home screen). */
        private val HEAVY = setOf("Helpless", "Worthless", "Despair", "Powerless", "Empty", "Depressed",
            "Ashamed", "Abandoned", "Isolated", "Grief")
    }
}
