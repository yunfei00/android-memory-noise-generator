package com.example.memorynoise

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

enum class Mode { CONTINUOUS, BURST, TOGGLE }

data class Config(
    val memoryMb: Int, val pattern: String, val hex: String = "AA55",
    val mode: Mode = Mode.CONTINUOUS, val durationMs: Long = 0,
    val writeMs: Long = 1000, val idleMs: Long = 1000
) {
    fun bytes(): ByteArray = when (pattern) {
        "IDLE" -> byteArrayOf(0)
        "ZERO" -> byteArrayOf(0)
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
        bytes()
    }
}

fun parseHex(input: String): ByteArray {
    val s = input.trim()
    require(s.isNotEmpty() && s.length % 2 == 0 && s.length <= 8192 && s.all { it in "0123456789abcdefABCDEF" }) {
        "HEX 必须为偶数个十六进制字符（1–4096 字节，无空格）"
    }
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

data class Snapshot(
    val config: Config? = null, val id: String = "—", val startTime: Long = 0,
    val stopTime: Long = 0, val state: String = "STOPPED", val allocated: Long = 0,
    val elapsedNs: Long = 0, val totalBytes: Long = 0, val loops: Long = 0,
    val cycles: Long = 0, val toggles: Long = 0, val currentBps: Double = 0.0,
    val error: String? = null, val reason: String = ""
) {
    val averageBps: Double get() = if (elapsedNs > 0) totalBytes * 1e9 / elapsedNs else 0.0
}

/** Pure JVM controller: no Activity, View or Android Context references. */
class ExperimentEngine(private val allocate: (Int) -> ByteArray = { ByteArray(it) }) {
    @Volatile var snapshot = Snapshot(); private set
    @Volatile var lastResult: Snapshot? = null; private set
    @Volatile private var worker: Thread? = null
    @Volatile private var buffer: ByteArray? = null
    private val cancelled = AtomicBoolean(false)
    @Volatile private var stopReason = "用户停止"
    val isRunning: Boolean get() = worker?.isAlive == true
    val hasBuffer: Boolean get() = buffer != null

    @Synchronized fun start(config: Config): Boolean {
        if (isRunning) return false
        config.validate()
        cancelled.set(false)
        stopReason = "用户停止"
        val now = System.currentTimeMillis()
        val label = if (config.mode == Mode.TOGGLE) "AA-55" else config.pattern
        val id = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now)) + "_${label}_${config.memoryMb}MB"
        snapshot = Snapshot(config = config, id = id, startTime = now, state = "ALLOCATING")
        worker = Thread({ run(config) }, "MemoryNoiseWorker").also { it.start() }
        return true
    }

    @Synchronized fun stop(reason: String = "用户停止") {
        if (!isRunning) return
        stopReason = reason
        cancelled.set(true)
        worker?.interrupt()
    }

    fun awaitStopped(timeoutMs: Long): Boolean { worker?.join(timeoutMs); return !isRunning }

    private fun run(c: Config) {
        var start = 0L
        var total = 0L
        var loops = 0L
        var cycles = 0L
        var toggles = 0L
        var error: String? = null
        var reason = "计时结束"
        try {
            // Allocation and initial VM zeroing are outside measured experiment time.
            buffer = allocate(c.memoryMb * 1024 * 1024)
            val pattern = c.bytes()
            val writer = PatternWriter(pattern)
            start = System.nanoTime()
            val deadline = if (c.durationMs == 0L) Long.MAX_VALUE else start + c.durationMs * 1_000_000
            val period = (c.writeMs + c.idleMs) * 1_000_000
            var offset = 0
            var nextPublish = start
            var sampleTime = start
            var sampleBytes = 0L
            var bandwidth = 0.0
            var previousState = ""
            while (!cancelled.get()) {
                val now = System.nanoTime()
                if (now >= deadline) break
                val elapsed = now - start
                cycles = if (c.mode == Mode.BURST) elapsed / period else 0
                val idle = c.mode != Mode.TOGGLE && (c.pattern == "IDLE" || (c.mode == Mode.BURST && elapsed % period >= c.writeMs * 1_000_000))
                val state = if (idle) "RUNNING / IDLE" else "RUNNING / WRITE"
                if (now >= nextPublish || state != previousState) {
                    if (now - sampleTime >= 200_000_000) {
                        bandwidth = (total - sampleBytes) * 1e9 / (now - sampleTime)
                        sampleTime = now; sampleBytes = total
                    }
                    snapshot = snapshot.copy(state = state, allocated = buffer!!.size.toLong(), elapsedNs = elapsed,
                        totalBytes = total, loops = loops, cycles = cycles, toggles = toggles,
                        currentBps = if (idle) 0.0 else bandwidth)
                    previousState = state
                    nextPublish = now + 100_000_000
                }
                if (idle) {
                    val phaseEnd = if (c.pattern == "IDLE") deadline else start + (cycles + 1) * period
                    val wait = min(min(phaseEnd, deadline) - System.nanoTime(), 100_000_000L)
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                } else {
                    val size = buffer!!.size
                    val end = min(offset + 64 * 1024, size)
                    if (c.mode == Mode.TOGGLE) buffer!!.fill(if (loops % 2 == 0L) 0xAA.toByte() else 0x55, offset, end)
                    else writer.write(buffer!!, offset, end)
                    total += end - offset
                    offset = end
                    if (offset == size) {
                        loops++
                        // Count transitions between completed full-buffer AA/55 passes.
                        toggles = if (c.mode == Mode.TOGGLE) (loops - 1).coerceAtLeast(0) else 0
                        offset = 0
                    }
                }
            }
            if (cancelled.get()) reason = stopReason
        } catch (_: InterruptedException) {
            reason = stopReason
        } catch (_: OutOfMemoryError) {
            buffer = null
            error = "内存分配或运行失败（OutOfMemoryError），请减小 Memory Size。"
            reason = "内存不足"
        } catch (e: Exception) {
            error = "${e.javaClass.simpleName}: ${e.message}"
            reason = "运行异常"
        } finally {
            buffer = null
            val elapsed = if (start == 0L) 0L else System.nanoTime() - start
            cycles = if (start != 0L && c.mode == Mode.BURST) elapsed / ((c.writeMs + c.idleMs) * 1_000_000) else 0
            snapshot = snapshot.copy(state = if (error == null) "STOPPED" else "ERROR / STOPPED",
                stopTime = System.currentTimeMillis(), allocated = 0, elapsedNs = elapsed,
                totalBytes = total, loops = loops, cycles = cycles, toggles = toggles,
                currentBps = 0.0, error = error, reason = reason)
            lastResult = snapshot
        }
    }
}

/** Replaceable managed-memory writer; a JNI backend can implement this boundary later. */
class PatternWriter(private val pattern: ByteArray) {
    private val template = ByteArray(64 * 1024 + pattern.size) { pattern[it % pattern.size] }
    fun write(target: ByteArray, from: Int, until: Int) {
        if (pattern.size == 1) target.fill(pattern[0], from, until)
        else System.arraycopy(template, from % pattern.size, target, from, until - from)
    }
}

object Experiments { val engine = ExperimentEngine() }
