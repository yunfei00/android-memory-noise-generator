package com.example.memorynoise

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val engine get() = Experiments.engine
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var memory: Spinner; private lateinit var pattern: Spinner; private lateinit var mode: Spinner; private lateinit var duration: Spinner
    private lateinit var write: Spinner; private lateinit var idle: Spinner; private lateinit var sweepTime: Spinner; private lateinit var sweepGap: Spinner
    private lateinit var customMemory: EditText; private lateinit var customHex: EditText; private lateinit var customDuration: EditText
    private lateinit var customWrite: EditText; private lateinit var customIdle: EditText; private lateinit var customSweepTime: EditText; private lateinit var customSweepGap: EditText
    private lateinit var state: TextView; private lateinit var sweepStatus: TextView; private lateinit var stats: TextView; private lateinit var result: TextView
    private lateinit var start: Button; private lateinit var stop: Button
    private lateinit var pause: Button; private lateinit var resume: Button
    private lateinit var configuration: LinearLayout
    private lateinit var pauseButtons: LinearLayout
    private val controls = mutableListOf<View>(); private var stopping = false
    private val ticker = object : Runnable { override fun run() { render(); handler.postDelayed(this, 100) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val scroll = ScrollView(this).apply { addView(root); isFillViewport = true }; setContentView(scroll)
        if (android.os.Build.VERSION.SDK_INT >= 30) scroll.setOnApplyWindowInsetsListener { view, insets -> val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets }
        else scroll.setOnApplyWindowInsetsListener { view, insets -> @Suppress("DEPRECATION") view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom); insets }
        fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text=text; textSize=size; root.addView(this) }
        label("Memory Noise Generator", 21f); label("Experiment Configuration · MB = MiB", 13f)
        configuration = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(configuration)
        fun row(title: String, choices: List<String>, value: String): Pair<Spinner,EditText> {
            val line=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL; tag="row:$title" }
            line.addView(TextView(this).apply { text=title; textSize=13f }, LinearLayout.LayoutParams(dp(88),dp(46)))
            val spinner=Spinner(this).apply { tag="select:$title"; adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,choices) }; line.addView(spinner,LinearLayout.LayoutParams(0,dp(46),1f))
            val edit=EditText(this).apply { tag="custom:$title"; setText(value); textSize=14f; inputType=android.text.InputType.TYPE_CLASS_NUMBER; isSingleLine=true; visibility=View.GONE }; line.addView(edit,LinearLayout.LayoutParams(dp(90),dp(46)))
            spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener { override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){ edit.visibility=if(choices[pos]=="Custom") View.VISIBLE else View.GONE }; override fun onNothingSelected(p:AdapterView<*>?)=Unit }
            controls.add(spinner); controls.add(edit); configuration.addView(line); return spinner to edit
        }
        row("Memory",listOf("16 MB","32 MB","64 MB","128 MB","256 MB","512 MB","Custom"),"64").also { memory=it.first; customMemory=it.second; memory.setSelection(2) }
        row("Pattern",listOf("IDLE","ZERO","ONE","AA","55","AA55","CUSTOM"),"").also { pattern=it.first; it.second.visibility=View.GONE; pattern.setSelection(3) }
        customHex=EditText(this).apply { hint="CUSTOM HEX，例如 DEADBEEF"; setText("AA55"); isSingleLine=true; textSize=14f; visibility=View.GONE }; configuration.addView(customHex); controls.add(customHex)
        pattern.onItemSelectedListener=object:AdapterView.OnItemSelectedListener { override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){ customHex.visibility=if(pos==6) View.VISIBLE else View.GONE }; override fun onNothingSelected(p:AdapterView<*>?)=Unit }
        row("Mode",listOf("Continuous","Burst","AA ↔ 55 Toggle","Pattern Sweep"),"").also { mode=it.first }
        val durationRow=configuration.childCount; row("Duration",listOf("Unlimited","10 s","30 s","60 s","120 s","300 s","Custom"),"10").also { duration=it.first; customDuration=it.second }
        val writeRow=configuration.childCount; row("Write ms",listOf("100","500","1000","2000","5000","Custom"),"1000").also { write=it.first; customWrite=it.second; write.setSelection(2) }
        val idleRow=configuration.childCount; row("Idle ms",listOf("100","500","1000","2000","5000","Custom"),"1000").also { idle=it.first; customIdle=it.second; idle.setSelection(2) }
        val sweepTimeRow=configuration.childCount; row("Sweep /组",listOf("10 s","20 s","30 s","60 s","Custom"),"20").also { sweepTime=it.first; customSweepTime=it.second; sweepTime.setSelection(1) }
        val sweepGapRow=configuration.childCount; row("Idle Gap",listOf("0 s","1 s","2 s","5 s","Custom"),"2").also { sweepGap=it.first; customSweepGap=it.second; sweepGap.setSelection(2) }
        fun refreshMode(pos:Int) {
            val burst=pos==1; val sweep=pos==3
            configuration.getChildAt(durationRow).visibility=if(sweep) View.GONE else View.VISIBLE
            configuration.getChildAt(writeRow).visibility=if(burst) View.VISIBLE else View.GONE; configuration.getChildAt(idleRow).visibility=if(burst) View.VISIBLE else View.GONE
            configuration.getChildAt(sweepTimeRow).visibility=if(sweep) View.VISIBLE else View.GONE; configuration.getChildAt(sweepGapRow).visibility=if(sweep) View.VISIBLE else View.GONE
            (pattern.parent as View).visibility=if(sweep) View.GONE else View.VISIBLE; customHex.visibility=if(!sweep && pattern.selectedItemPosition==6) View.VISIBLE else View.GONE
            pattern.isEnabled=!engine.isRunning && pos!=2 && !sweep
        }
        mode.onItemSelectedListener=object:AdapterView.OnItemSelectedListener { override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long)=refreshMode(pos); override fun onNothingSelected(p:AdapterView<*>?)=Unit }
        refreshMode(0)
        val buttons=LinearLayout(this); start=Button(this).apply { text="START"; textSize=20f; setOnClickListener{begin()} }; stop=Button(this).apply { text="STOP"; textSize=20f; setOnClickListener{stopping=true;engine.stop();render()} }
        buttons.addView(start,LinearLayout.LayoutParams(0,dp(58),1f)); buttons.addView(stop,LinearLayout.LayoutParams(0,dp(58),1f)); root.addView(buttons)
        pauseButtons = LinearLayout(this)
        pause = Button(this).apply { text="PAUSE"; textSize=20f; setOnClickListener { engine.pause(); render() } }
        resume = Button(this).apply { text="RESUME"; textSize=20f; setOnClickListener { engine.resume(); render() } }
        pauseButtons.addView(pause,LinearLayout.LayoutParams(0,dp(58),1f)); pauseButtons.addView(resume,LinearLayout.LayoutParams(0,dp(58),1f)); root.addView(pauseButtons)
        state=label("STOPPED",25f).apply { tag="state"; gravity=android.view.Gravity.CENTER; setPadding(0,dp(9),0,dp(9)); setTextColor(Color.WHITE) }
        sweepStatus=label("",29f).apply { tag="sweepStatus"; gravity=android.view.Gravity.CENTER; typeface=android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(20,35,50)); setPadding(dp(4),dp(10),dp(4),dp(10)); visibility=View.GONE }
        stats=label("",14f).apply { setPadding(0,dp(6),0,dp(6)); typeface=android.graphics.Typeface.MONOSPACE }; result=label("最近实验：暂无",12f).apply { setTextIsSelectable(true) }
        savedInstanceState?.let { b ->
            listOf(memory,pattern,mode,duration,write,idle,sweepTime,sweepGap).forEachIndexed { i,s -> s.setSelection(b.getInt("select$i",s.selectedItemPosition)) }
            listOf(customMemory,customHex,customDuration,customWrite,customIdle,customSweepTime,customSweepGap).forEachIndexed { i,e -> e.setText(b.getString("input$i",e.text.toString())) }
        }
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun number(s:Spinner,e:EditText):Long=(if(s.selectedItem.toString()=="Custom") e.text.toString() else s.selectedItem.toString().substringBefore(' ')).toLong()
    private fun begin(){
        try {
            val mb=number(memory,customMemory); require(mb in 1..2047){"内存必须为 1–2047 MB"}; val selectedMode=Mode.entries[mode.selectedItemPosition]
            val seconds=if(selectedMode==Mode.SWEEP) 0 else if(duration.selectedItemPosition==0) 0 else number(duration,customDuration)
            require(seconds in 0..86400 && (selectedMode==Mode.SWEEP || duration.selectedItemPosition==0 || seconds>0)){"实验时长必须为 1–86400 秒"}
            val sweepSeconds=if(selectedMode==Mode.SWEEP) number(sweepTime,customSweepTime) else 20; val gapSeconds=if(selectedMode==Mode.SWEEP) number(sweepGap,customSweepGap) else 2
            require(sweepSeconds in 1..3600){"Sweep 单组时间必须为 1–3600 秒"}; require(gapSeconds in 0..60){"Gap 必须为 0–60 秒"}
            val c=Config(mb.toInt(),if(selectedMode==Mode.TOGGLE) "AA" else pattern.selectedItem.toString(),customHex.text.toString(),selectedMode,seconds*1000,
                if(selectedMode==Mode.BURST) number(write,customWrite) else 1000,if(selectedMode==Mode.BURST) number(idle,customIdle) else 1000,sweepSeconds*1000,gapSeconds*1000)
            if(engine.start(c)) stopping=false; render()
        } catch(e:Exception){ Toast.makeText(this,e.message?:"请检查输入",Toast.LENGTH_LONG).show() }
    }
    private fun render(){
        val s=engine.snapshot; val running=engine.isRunning; start.isEnabled=!running; stop.isEnabled=running&&!stopping; controls.forEach{it.isEnabled=!running}
        val selectedMode=Mode.entries[mode.selectedItemPosition]; pattern.isEnabled=!running&&selectedMode!=Mode.TOGGLE&&selectedMode!=Mode.SWEEP; customHex.isEnabled=pattern.isEnabled
        val activeSweep=running && s.config?.mode==Mode.SWEEP
        configuration.visibility=if(activeSweep) View.GONE else View.VISIBLE
        pauseButtons.visibility=if(activeSweep || selectedMode==Mode.SWEEP) View.VISIBLE else View.GONE
        pause.isEnabled=activeSweep&&!stopping&&!engine.isPauseRequested&&s.state!="ALLOCATING"
        resume.isEnabled=activeSweep&&!stopping&&s.state=="PAUSED"&&engine.isPauseRequested
        if(running&&!stopping) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        state.text=if(running&&stopping) "STOPPING…" else if(engine.isPauseRequested&&s.state!="PAUSED") "PAUSING…" else s.state; state.setBackgroundColor(when{ s.error!=null->Color.rgb(160,35,35); s.state=="PAUSED"->Color.rgb(35,75,150); running&&s.state.endsWith("WRITE")->Color.rgb(0,112,75); running->Color.rgb(156,96,0); else->Color.rgb(65,75,85) })
        val isSweep=s.config?.mode==Mode.SWEEP; sweepStatus.visibility=if(isSweep&&running) View.VISIBLE else View.GONE
        if(isSweep){ val pos=(s.sweepIndex+1).coerceAtLeast(1); sweepStatus.text="Pattern Sweep\n# $pos / ${s.sweepTotal}\n${s.sweepCurrent}\n${if(s.sweepInGap) "Idle Gap · " else ""}Remaining: ${String.format(Locale.US,"%.1f",s.sweepRemainingMs/1000.0)} s\nNext: ${s.sweepNext}" }
        val p=s.config?.let{when(it.mode){Mode.TOGGLE->"AA ↔ 55";Mode.SWEEP->s.sweepCurrent;else->if(it.pattern=="CUSTOM") "CUSTOM ${it.hex.take(24)}" else it.pattern}}?:"—"
        stats.text="Pattern: $p   ${s.config?.mode?:""}\nAllocated: ${s.allocated/1048576} MiB   Time: ${f(s.elapsedNs/1e9)} s\nTotal: ${f(s.totalBytes/1048576.0)} MiB\nAvg: ${f(s.averageBps/1048576)} MiB/s   Now: ${f(s.currentBps/1048576)} MiB/s\nLoops / Writes: ${s.loops}   Cycles: ${s.cycles}\nToggles: ${s.toggles}"
        val r=engine.lastResult; result.text=if(r==null) "最近实验：暂无\n运行期间屏幕常亮；离开页面 / 旋转会停止实验。" else { val c=r.config!!; "最近实验：${r.id}\n${date(r.startTime)} → ${date(r.stopTime)}\n${if(c.mode==Mode.SWEEP) "Complement Sweep 00↔FF … 7F↔80" else if(c.mode==Mode.TOGGLE) "AA ↔ 55" else c.pattern} · ${c.memoryMb} MiB · ${c.mode}\nTotal ${r.totalBytes} bytes · Avg ${f(r.averageBps/1048576)} MiB/s\n${r.error?:r.reason} · Buffer 已释放" }
    }
    private fun f(n:Double)=String.format(Locale.US,"%.2f",n); private fun date(t:Long)=SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.US).format(Date(t))
    override fun onStart(){super.onStart();handler.post(ticker)}
    override fun onStop(){handler.removeCallbacks(ticker);engine.stop("页面离开 / Activity 重建");window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onStop()}
    override fun onSaveInstanceState(outState:Bundle){ listOf(memory,pattern,mode,duration,write,idle,sweepTime,sweepGap).forEachIndexed{i,s->outState.putInt("select$i",s.selectedItemPosition)}; listOf(customMemory,customHex,customDuration,customWrite,customIdle,customSweepTime,customSweepGap).forEachIndexed{i,e->outState.putString("input$i",e.text.toString())}; super.onSaveInstanceState(outState) }
}
