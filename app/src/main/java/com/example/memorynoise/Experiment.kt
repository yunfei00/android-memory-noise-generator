package com.example.memorynoise

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

enum class Mode { CONTINUOUS, BURST, TOGGLE, SWEEP }

data class Config(
    val memoryMb: Int, val pattern: String, val hex: String = "AA55",
    val mode: Mode = Mode.CONTINUOUS, val durationMs: Long = 0,
    val writeMs: Long = 1000, val idleMs: Long = 1000,
    val sweepPatternMs: Long = 20_000, val sweepGapMs: Long = 2_000
) {
    fun bytes(): ByteArray = when (pattern) {
        "IDLE", "ZERO" -> byteArrayOf(0)
        "ONE" -> byteArrayOf(-1)
        "AA" -> byteArrayOf(0xAA.toByte())
        "55" -> byteArrayOf(0x55)
        "AA55" -> byteArrayOf(0xAA.toByte(), 0x55)
        "CUSTOM" -> parseHex(hex)
        else -> error("未知 Pattern")
    }
    fun validate() {
        require(memoryMb in 1..2047) { "内存必须为 1–2047 MB" }
        require(durationMs in 0..86_400_000) { "时长必须为 0–86400 秒" }
        require(writeMs in 1..3_600_000 && idleMs in 1..3_600_000) { "Write / Idle 必须为 1–3600000 ms" }
        require(sweepPatternMs in 1_000..3_600_000) { "Sweep 单组时间必须为 1–3600 秒" }
        require(sweepGapMs in 0..60_000) { "Sweep 间隔必须为 0–60 秒" }
        if (mode != Mode.SWEEP) bytes()
    }
}

fun parseHex(input: String): ByteArray {
    val s = input.trim()
    require(s.isNotEmpty() && s.length % 2 == 0 && s.length <= 8192 && s.all { it in "0123456789abcdefABCDEF" }) {
        "HEX 必须为偶数个十六进制字符（1–4096 字节，无空格）"
    }
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun complementPair(index: Int): Pair<Byte, Byte> {
    require(index in 0..127)
    return index.toByte() to (index xor 0xFF).toByte()
}

fun pairLabel(index: Int): String {
    val (a, b) = complementPair(index)
    return "%02X ↔ %02X".format(Locale.US, a.toInt() and 0xFF, b.toInt() and 0xFF)
}

data class Snapshot(
    val config: Config? = null, val id: String = "—", val startTime: Long = 0,
    val stopTime: Long = 0, val state: String = "STOPPED", val allocated: Long = 0,
    val elapsedNs: Long = 0, val totalBytes: Long = 0, val loops: Long = 0,
    val cycles: Long = 0, val toggles: Long = 0, val currentBps: Double = 0.0,
    val error: String? = null, val reason: String = "",
    val sweepIndex: Int = -1, val sweepTotal: Int = 128,
    val sweepCurrent: String = "—", val sweepNext: String = "—", val sweepRemainingMs: Long = 0,
    val sweepInGap: Boolean = false
) {
    val averageBps: Double get() = if (elapsedNs > 0) totalBytes * 1e9 / elapsedNs else 0.0
}

class ExperimentEngine(private val allocate: (Int) -> ByteArray = { ByteArray(it) }) {
    @Volatile var snapshot = Snapshot(); private set
    @Volatile var lastResult: Snapshot? = null; private set
    @Volatile private var worker: Thread? = null
    @Volatile private var buffer: ByteArray? = null
    private val cancelled = AtomicBoolean(false)
    private val pauseLock = Object()
    @Volatile var isPauseRequested = false; private set
    @Volatile private var stopReason = "用户停止"
    val isRunning: Boolean get() = worker?.isAlive == true
    val hasBuffer: Boolean get() = buffer != null

    @Synchronized fun start(config: Config): Boolean {
        if (isRunning) return false
        config.validate()
        cancelled.set(false); isPauseRequested = false; stopReason = "用户停止"
        val now = System.currentTimeMillis()
        val label = when (config.mode) { Mode.TOGGLE -> "AA-55"; Mode.SWEEP -> "SWEEP"; else -> config.pattern }
        val id = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now)) + "_${label}_${config.memoryMb}MB"
        snapshot = Snapshot(config = config, id = id, startTime = now, state = "ALLOCATING")
        worker = Thread({ run(config) }, "MemoryNoiseWorker").also { it.start() }
        return true
    }

    @Synchronized fun stop(reason: String = "用户停止") {
        if (!isRunning) return
        stopReason = reason; cancelled.set(true); worker?.interrupt()
    }

    /** Asynchronous request. PAUSED is published only after the worker stops writing. */
    @Synchronized fun pause(): Boolean {
        if (!isRunning || cancelled.get() || snapshot.config?.mode != Mode.SWEEP) return false
        synchronized(pauseLock) { isPauseRequested = true }
        return true
    }

    @Synchronized fun resume(): Boolean {
        if (!isRunning || cancelled.get() || !isPauseRequested) return false
        synchronized(pauseLock) { isPauseRequested = false; pauseLock.notifyAll() }
        return true
    }

    fun awaitStopped(timeoutMs: Long): Boolean { worker?.join(timeoutMs); return !isRunning }

    private fun run(c: Config) {
        var start = 0L; var total = 0L; var loops = 0L; var cycles = 0L; var toggles = 0L
        var error: String? = null; var reason = "计时结束"
        try {
            buffer = allocate(c.memoryMb * 1024 * 1024)
            val writer = if (c.mode == Mode.SWEEP) null else PatternWriter(c.bytes())
            start = System.nanoTime()
            val deadline = if (c.mode == Mode.SWEEP || c.durationMs == 0L) Long.MAX_VALUE else start + c.durationMs * 1_000_000
            val sweep = if (c.mode == Mode.SWEEP) SweepTimeline(c.sweepPatternMs, c.sweepGapMs) else null
            val complementWriter = ComplementWriter()
            val period = (c.writeMs + c.idleMs) * 1_000_000
            var offset = 0; var nextPublish = start; var sampleTime = start; var sampleBytes = 0L; var bandwidth = 0.0; var previousState = ""
            var lastSweepIndex = -1; var sweepPass = 0L
            var lastTick = start
            while (!cancelled.get()) {
                val now = System.nanoTime(); if (now >= deadline) break
                val elapsed = now - start
                sweep?.advance(now - lastTick)
                lastTick = now
                if (sweep?.finished == true) break
                val sweepIndex = sweep?.index ?: -1
                if (sweep != null && sweepIndex != lastSweepIndex) {
                    lastSweepIndex = sweepIndex; offset = 0; sweepPass = 0
                    complementWriter.select(sweepIndex)
                }
                cycles = if (c.mode == Mode.BURST) elapsed / period else 0
                val idle = when (c.mode) {
                    Mode.SWEEP -> sweep!!.inGap
                    Mode.TOGGLE -> false
                    else -> c.pattern == "IDLE" || (c.mode == Mode.BURST && elapsed % period >= c.writeMs * 1_000_000)
                }
                val state = if (sweep != null) {
                    if (isPauseRequested) "PAUSED" else if (idle) "SWEEP / IDLE GAP" else "SWEEP / WRITE"
                } else if (idle) "RUNNING / IDLE" else "RUNNING / WRITE"
                if (now >= nextPublish || state != previousState || sweepIndex != snapshot.sweepIndex) {
                    if (now - sampleTime >= 200_000_000) {
                        bandwidth = (total - sampleBytes) * 1e9 / (now - sampleTime); sampleTime = now; sampleBytes = total
                    }
                    snapshot = snapshot.copy(state = state, allocated = buffer!!.size.toLong(), elapsedNs = elapsed, totalBytes = total,
                        loops = loops, cycles = cycles, toggles = toggles, currentBps = if (idle || state == "PAUSED") 0.0 else bandwidth,
                        sweepIndex = sweepIndex, sweepCurrent = sweep?.currentLabel ?: "—",
                        sweepNext = sweep?.nextLabel ?: "—", sweepRemainingMs = (sweep?.remainingNs ?: 0) / 1_000_000,
                        sweepInGap = sweep?.inGap ?: false)
                    previousState = state; nextPublish = now + 100_000_000
                }
                if (state == "PAUSED") {
                    synchronized(pauseLock) {
                        while (isPauseRequested && !cancelled.get()) {
                            pauseLock.wait(100)
                            snapshot = snapshot.copy(elapsedNs = System.nanoTime() - start)
                        }
                    }
                    // Keep offset, pass parity, pair and phase remaining time. Exclude pause time.
                    lastTick = System.nanoTime(); sampleTime = lastTick; sampleBytes = total; bandwidth = 0.0
                    nextPublish = 0
                    continue
                }
                // A request arriving during publication must be acknowledged before the next block.
                if (cancelled.get() || isPauseRequested) continue
                if (idle) {
                    val phaseEnd = if (sweep != null) now + sweep.remainingNs
                        else if (c.mode == Mode.BURST && c.pattern != "IDLE") start + (cycles + 1) * period else deadline
                    val wait = min(min(deadline, phaseEnd) - System.nanoTime(), 50_000_000L)
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                } else {
                    val size = buffer!!.size; val end = min(offset + 64 * 1024, size)
                    when (c.mode) {
                        Mode.TOGGLE -> buffer!!.fill(if (loops % 2 == 0L) 0xAA.toByte() else 0x55, offset, end)
                        Mode.SWEEP -> {
                            complementWriter.write(buffer!!, offset, end, sweepPass)
                        }
                        else -> writer!!.write(buffer!!, offset, end)
                    }
                    total += end - offset; offset = end
                    if (offset == size) {
                        loops++; offset = 0
                        if (c.mode == Mode.TOGGLE) toggles = (loops - 1).coerceAtLeast(0)
                        if (c.mode == Mode.SWEEP) { sweepPass++; if (sweepPass > 1) toggles++ }
                    }
                }
            }
            if (cancelled.get()) reason = stopReason else if (c.mode == Mode.SWEEP) reason = "Sweep 完成"
        } catch (_: InterruptedException) { reason = stopReason
        } catch (_: OutOfMemoryError) { buffer = null; error = "内存分配或运行失败（OutOfMemoryError），请减小 Memory Size。"; reason = "内存不足"
        } catch (e: Exception) { error = "${e.javaClass.simpleName}: ${e.message}"; reason = "运行异常"
        } finally {
            buffer = null
            isPauseRequested = false
            val elapsed = if (start == 0L) 0L else System.nanoTime() - start
            cycles = if (start != 0L && c.mode == Mode.BURST) elapsed / ((c.writeMs + c.idleMs) * 1_000_000) else cycles
            snapshot = snapshot.copy(state = if (error == null) "STOPPED" else "ERROR / STOPPED", stopTime = System.currentTimeMillis(),
                allocated = 0, elapsedNs = elapsed, totalBytes = total, loops = loops, cycles = cycles, toggles = toggles,
                currentBps = 0.0, error = error, reason = reason, sweepRemainingMs = 0)
            lastResult = snapshot
        }
    }
}

/** One WRITE then one optional GAP per pair; late scheduling never skips a pair.
 * The caller advances only unpaused time. No trailing gap after the final pair. */
internal class SweepTimeline(private val writeMs: Long, private val gapMs: Long) {
    var index = 0; private set
    var inGap = false; private set
    var finished = false; private set
    var remainingNs = writeMs * 1_000_000; private set
    var currentLabel = pairLabel(0); private set
    var nextLabel = pairLabel(1); private set

    fun advance(deltaNs: Long) {
        if (finished) return
        remainingNs = (remainingNs - deltaNs.coerceAtLeast(0)).coerceAtLeast(0)
        if (remainingNs > 0) return
        if (!inGap && index == 127) { finished = true; return }
        if (!inGap && gapMs > 0) { inGap = true; remainingNs = gapMs * 1_000_000; return }
        index++; inGap = false; remainingNs = writeMs * 1_000_000
        currentLabel = pairLabel(index)
        nextLabel = if (index < 127) pairLabel(index + 1) else "完成"
    }
}

/** Each pass overwrites the same target array. No source-template reads or per-block allocations. */
internal class ComplementWriter {
    private var a: Byte = 0
    private var b: Byte = -1
    fun select(index: Int) {
        require(index in 0..127)
        a = index.toByte(); b = (index xor 0xFF).toByte()
    }
    fun write(target: ByteArray, from: Int, until: Int, pass: Long) {
        target.fill(if (pass % 2 == 0L) a else b, from, until)
    }
}

class PatternWriter(private val pattern: ByteArray) {
    private val template = ByteArray(64 * 1024 + pattern.size) { pattern[it % pattern.size] }
    fun write(target: ByteArray, from: Int, until: Int) {
        if (pattern.size == 1) target.fill(pattern[0], from, until)
        else System.arraycopy(template, from % pattern.size, target, from, until - from)
    }
}

object Experiments { val engine = ExperimentEngine() }
