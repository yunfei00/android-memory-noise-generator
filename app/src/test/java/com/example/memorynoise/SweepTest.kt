package com.example.memorynoise

import org.junit.Assert.*
import org.junit.Test

class SweepTest {
    private fun waitFor(engine: ExperimentEngine, timeoutMs: Long = 3000, condition: (Snapshot) -> Boolean): Snapshot {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition(engine.snapshot) && System.nanoTime() < end) Thread.sleep(5)
        assertTrue("Timed out: ${engine.snapshot}", condition(engine.snapshot))
        return engine.snapshot
    }

    @Test fun allPairsRepeatedlyOverwriteTheSameBufferIncludingChunkTail() {
        val buffer = ByteArray(65536 * 2 + 19)
        val writer = ComplementWriter()
        for (index in 0..127) {
            writer.select(index)
            repeat(4) { pass ->
                for (from in buffer.indices step 65536) writer.write(buffer, from, minOf(from + 65536, buffer.size), pass.toLong())
                val expected = (if (pass % 2 == 0) index else index xor 255).toByte()
                assertTrue("Pair $index pass $pass", buffer.all { it == expected })
            }
        }
    }

    @Test fun timelineVisitsEveryPairWithGapAndNoTrailingGap() {
        val t = SweepTimeline(2000, 1000)
        for (i in 0..127) {
            assertEquals(i, t.index); assertEquals(pairLabel(i), t.currentLabel)
            assertEquals(if (i == 127) "完成" else pairLabel(i + 1), t.nextLabel)
            assertFalse(t.inGap)
            t.advance(500_000_000)
            assertEquals(1_500_000_000, t.remainingNs)
            t.advance(1_500_000_000)
            if (i < 127) {
                assertTrue(t.inGap); assertEquals(i, t.index)
                t.advance(1_000_000_000)
            }
        }
        assertTrue(t.finished); assertEquals(0L, t.remainingNs)
    }

    @Test fun zeroGapAndLateSchedulingNeverSkipPairs() {
        val t = SweepTimeline(2000, 0)
        repeat(127) { i ->
            t.advance(50_000_000_000)
            assertEquals(i + 1, t.index); assertFalse(t.inGap)
            assertEquals(2_000_000_000, t.remainingNs)
        }
        t.advance(2_000_000_000); assertTrue(t.finished)
    }

    @Test fun pauseFreezesRealBufferCounterAndRemainingThenResumesCurrentPair() {
        lateinit var actual: ByteArray
        val engine = ExperimentEngine { size -> ByteArray(size).also { actual = it } }
        try {
            engine.start(Config(1, "AA", mode = Mode.SWEEP, sweepPatternMs = 2000, sweepGapMs = 1000))
            waitFor(engine) { it.loops > 5 }
            assertTrue(engine.pause())
            val paused = waitFor(engine) { it.state == "PAUSED" }
            val saved = actual.copyOf()
            assertTrue(saved.all { it == 0.toByte() || it == (-1).toByte() })
            Thread.sleep(2200) // Longer than a full pair: neither bytes nor the sweep clock may advance.
            assertArrayEquals(saved, actual)
            assertEquals(paused.totalBytes, engine.snapshot.totalBytes)
            assertEquals(paused.sweepRemainingMs, engine.snapshot.sweepRemainingMs)
            assertEquals(paused.sweepIndex, engine.snapshot.sweepIndex)
            assertEquals(0.0, engine.snapshot.currentBps, 0.0)
            assertFalse(engine.start(Config(1, "AA", mode = Mode.SWEEP)))
            assertTrue(engine.resume())
            val resumed = waitFor(engine) { it.totalBytes > paused.totalBytes }
            assertEquals(paused.sweepIndex, resumed.sweepIndex)
            assertTrue(resumed.sweepRemainingMs < paused.sweepRemainingMs)
        } finally { engine.stop(); assertTrue(engine.awaitStopped(1000)); assertFalse(engine.hasBuffer) }
    }

    @Test fun gapIsWriteFreeAndCanBePausedResumedAndStopped() {
        lateinit var actual: ByteArray
        val engine = ExperimentEngine { ByteArray(it).also { b -> actual = b } }
        try {
            engine.start(Config(1, "AA", mode = Mode.SWEEP, sweepPatternMs = 1000, sweepGapMs = 1000))
            val gap = waitFor(engine) { it.sweepInGap }
            val bytes = actual.copyOf()
            Thread.sleep(150)
            assertEquals(gap.totalBytes, engine.snapshot.totalBytes); assertArrayEquals(bytes, actual)
            engine.pause(); val paused = waitFor(engine) { it.state == "PAUSED" }
            assertTrue(paused.sweepInGap)
            Thread.sleep(1200)
            assertEquals(paused.sweepRemainingMs, engine.snapshot.sweepRemainingMs)
            engine.resume()
            waitFor(engine) { it.sweepIndex == 1 && it.loops > paused.loops }
            engine.pause(); waitFor(engine) { it.state == "PAUSED" }
            engine.stop(); assertTrue(engine.awaitStopped(500)); assertFalse(engine.hasBuffer)
            assertEquals("STOPPED", engine.snapshot.state)
            assertTrue(engine.start(Config(1, "AA", mode = Mode.SWEEP)))
            waitFor(engine) { it.sweepIndex == 0 }; assertFalse(engine.isPauseRequested)
        } finally { engine.stop(); engine.awaitStopped(1000) }
    }

    @Test fun rapidSweepStartPauseResumeStopDoesNotLeaveWorkers() {
        val engine = ExperimentEngine()
        repeat(25) {
            assertTrue(engine.start(Config(1, "AA", mode = Mode.SWEEP)))
            engine.pause(); engine.resume(); engine.pause(); engine.stop(); engine.stop()
            assertTrue(engine.awaitStopped(1000)); assertFalse(engine.hasBuffer)
            assertEquals("STOPPED", engine.snapshot.state)
        }
    }
}
