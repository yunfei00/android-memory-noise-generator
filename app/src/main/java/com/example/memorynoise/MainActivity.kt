package com.example.memorynoise

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val engine get() = Experiments.engine
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var memory: Spinner
    private lateinit var pattern: Spinner
    private lateinit var mode: Spinner
    private lateinit var duration: Spinner
    private lateinit var write: Spinner
    private lateinit var idle: Spinner
    private lateinit var customMemory: EditText
    private lateinit var customHex: EditText
    private lateinit var customDuration: EditText
    private lateinit var customWrite: EditText
    private lateinit var customIdle: EditText
    private lateinit var state: TextView
    private lateinit var stats: TextView
    private lateinit var result: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private val controls = mutableListOf<View>()
    private var stopping = false
    private val ticker = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 100) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val scroll = ScrollView(this).apply { addView(root); isFillViewport = true }
        setContentView(scroll)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        } else {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text = text; textSize = size; root.addView(this) }
        label("Memory Noise Generator", 21f)
        label("Experiment Configuration · MB = MiB", 13f)
        fun row(title: String, choices: List<String>, value: String): Pair<Spinner, EditText> {
            val line = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            line.addView(TextView(this).apply { text = title; textSize = 13f }, LinearLayout.LayoutParams(dp(80), dp(46)))
            val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, choices) }
            line.addView(spinner, LinearLayout.LayoutParams(0, dp(46), 1f))
            val edit = EditText(this).apply { setText(value); textSize = 14f; inputType = android.text.InputType.TYPE_CLASS_NUMBER; isSingleLine = true; visibility = View.GONE }
            line.addView(edit, LinearLayout.LayoutParams(dp(90), dp(46)))
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { edit.visibility = if (choices[position] == "Custom") View.VISIBLE else View.GONE }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            controls.add(spinner); controls.add(edit); root.addView(line)
            return spinner to edit
        }
        row("Memory", listOf("16 MB", "32 MB", "64 MB", "128 MB", "256 MB", "512 MB", "Custom"), "64").also { memory = it.first; customMemory = it.second; memory.setSelection(2) }
        row("Pattern", listOf("IDLE", "ZERO", "ONE", "AA", "55", "AA55", "CUSTOM"), "").also { pattern = it.first; it.second.visibility = View.GONE; pattern.setSelection(3) }
        customHex = EditText(this).apply { hint = "CUSTOM HEX，例如 DEADBEEF"; setText("AA55"); isSingleLine = true; textSize = 14f; visibility = View.GONE }
        root.addView(customHex); controls.add(customHex)
        pattern.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { customHex.visibility = if (position == 6) View.VISIBLE else View.GONE }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        row("Mode", listOf("Continuous", "Burst", "AA ↔ 55 Toggle"), "").also { mode = it.first }
        row("Duration", listOf("Unlimited", "10 s", "30 s", "60 s", "120 s", "300 s", "Custom"), "10").also { duration = it.first; customDuration = it.second; customDuration.hint = "秒" }
        val timingStart = root.childCount
        row("Write ms", listOf("100", "500", "1000", "2000", "5000", "Custom"), "1000").also { write = it.first; customWrite = it.second; write.setSelection(2) }
        row("Idle ms", listOf("100", "500", "1000", "2000", "5000", "Custom"), "1000").also { idle = it.first; customIdle = it.second; idle.setSelection(2) }
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                for (i in timingStart..timingStart + 1) root.getChildAt(i).visibility = if (position == 1) View.VISIBLE else View.GONE
                pattern.isEnabled = !engine.isRunning && position != 2
                customHex.isEnabled = pattern.isEnabled
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val buttons = LinearLayout(this)
        start = Button(this).apply { text = "START"; textSize = 20f; setOnClickListener { begin() } }
        stop = Button(this).apply { text = "STOP"; textSize = 20f; setOnClickListener { stopping = true; engine.stop(); render() } }
        buttons.addView(start, LinearLayout.LayoutParams(0, dp(58), 1f)); buttons.addView(stop, LinearLayout.LayoutParams(0, dp(58), 1f)); root.addView(buttons)
        state = label("STOPPED", 25f).apply { gravity = android.view.Gravity.CENTER; setPadding(0, dp(9), 0, dp(9)); setTextColor(Color.WHITE) }
        stats = label("", 14f).apply { setPadding(0, dp(6), 0, dp(6)); typeface = android.graphics.Typeface.MONOSPACE }
        result = label("最近实验：暂无", 12f).apply { setTextIsSelectable(true) }
        savedInstanceState?.let { b ->
            listOf(memory, pattern, mode, duration, write, idle).forEachIndexed { i, s -> s.setSelection(b.getInt("select$i", s.selectedItemPosition)) }
            listOf(customMemory, customHex, customDuration, customWrite, customIdle).forEachIndexed { i, e -> e.setText(b.getString("input$i", e.text.toString())) }
        }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun number(s: Spinner, e: EditText): Long = (if (s.selectedItem.toString() == "Custom") e.text.toString() else s.selectedItem.toString().substringBefore(' ')).toLong()
    private fun begin() {
        try {
            val mb = number(memory, customMemory)
            require(mb in 1..2047) { "内存必须为 1–2047 MB" }
            val seconds = if (duration.selectedItemPosition == 0) 0 else number(duration, customDuration)
            require(seconds in 0..86400 && (duration.selectedItemPosition == 0 || seconds > 0)) { "实验时长必须为 1–86400 秒" }
            val selectedMode = Mode.entries[mode.selectedItemPosition]
            val c = Config(mb.toInt(), if (selectedMode == Mode.TOGGLE) "AA" else pattern.selectedItem.toString(), customHex.text.toString(),
                selectedMode, seconds * 1000, if (selectedMode == Mode.BURST) number(write, customWrite) else 1000,
                if (selectedMode == Mode.BURST) number(idle, customIdle) else 1000)
            if (engine.start(c)) stopping = false
            render()
        } catch (e: IllegalArgumentException) { Toast.makeText(this, e.message ?: "请检查输入", Toast.LENGTH_LONG).show() }
    }
    private fun render() {
        val s = engine.snapshot
        val running = engine.isRunning
        start.isEnabled = !running; stop.isEnabled = running && !stopping
        controls.forEach { it.isEnabled = !running }
        pattern.isEnabled = !running && mode.selectedItemPosition != 2; customHex.isEnabled = pattern.isEnabled
        if (running && !stopping) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        state.text = if (running && stopping) "STOPPING…" else s.state
        state.setBackgroundColor(when { s.error != null -> Color.rgb(160, 35, 35); running && s.state.endsWith("WRITE") -> Color.rgb(0, 112, 75); running -> Color.rgb(156, 96, 0); else -> Color.rgb(65, 75, 85) })
        val p = s.config?.let { if (it.mode == Mode.TOGGLE) "AA ↔ 55" else if (it.pattern == "CUSTOM") "CUSTOM ${it.hex.take(24)}" else it.pattern } ?: "—"
        stats.text = "Pattern: $p   ${s.config?.mode ?: ""}\nAllocated: ${s.allocated / 1048576} MiB   Time: ${f(s.elapsedNs / 1e9)} s\nTotal: ${f(s.totalBytes / 1048576.0)} MiB\nAvg: ${f(s.averageBps / 1048576)} MiB/s   Now: ${f(s.currentBps / 1048576)} MiB/s\nLoops / Writes: ${s.loops}   Cycles: ${s.cycles}\nToggles: ${s.toggles}"
        val r = engine.lastResult
        result.text = if (r == null) "最近实验：暂无\n运行期间屏幕常亮；离开页面 / 旋转会停止实验。" else {
            val c = r.config!!
            "最近实验：${r.id}\n${date(r.startTime)} → ${date(r.stopTime)}\n${if (c.mode == Mode.TOGGLE) "AA ↔ 55" else c.pattern}${if (c.pattern == "CUSTOM") " (${c.hex})" else ""} · ${c.memoryMb} MiB · ${c.mode} · Write/Idle ${c.writeMs}/${c.idleMs} ms\nTotal ${r.totalBytes} bytes · Avg ${f(r.averageBps / 1048576)} MiB/s\n${r.error ?: r.reason} · Buffer 已释放"
        }
    }
    private fun f(n: Double) = String.format(Locale.US, "%.2f", n)
    private fun date(t: Long) = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(t))
    override fun onStart() { super.onStart(); handler.post(ticker) }
    override fun onStop() {
        handler.removeCallbacks(ticker)
        engine.stop("页面离开 / Activity 重建")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        listOf(memory, pattern, mode, duration, write, idle).forEachIndexed { i, s -> outState.putInt("select$i", s.selectedItemPosition) }
        listOf(customMemory, customHex, customDuration, customWrite, customIdle).forEachIndexed { i, e -> outState.putString("input$i", e.text.toString()) }
        super.onSaveInstanceState(outState)
    }
}
