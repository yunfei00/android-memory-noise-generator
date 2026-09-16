package com.example.memorynoise

import org.junit.Assert.*
import org.junit.Test

class ExperimentTest {
    @Test fun patternsStayAlignedAcrossChunksAndTail() {
        for (hex in listOf("00", "FF", "AA", "55", "AA55", "123456", "DEADBEEF")) {
            val pattern = parseHex(hex)
            val writer = PatternWriter(pattern)
            val array = ByteArray(131079)
            for (start in array.indices step 65536) writer.write(array, start, minOf(start + 65536, array.size))
            array.forEachIndexed { i, b -> assertEquals("$hex byte $i", pattern[i % pattern.size], b) }
        }
    }
    @Test fun invalidInputsRejected() {
        for (s in listOf("", "A", "GG", "AA 55", "0xAA")) {
            assertThrows(IllegalArgumentException::class.java) { parseHex(s) }
        }
        assertThrows(IllegalArgumentException::class.java) { Config(2048, "AA").validate() }
        assertThrows(IllegalArgumentException::class.java) { Config(16, "AA", writeMs = 0).validate() }
    }
    @Test fun timedRunCountsActualBytesAndReleasesBuffer() {
        val e = ExperimentEngine()
        assertTrue(e.start(Config(1, "AA55", durationMs = 180)))
        assertTrue(e.awaitStopped(3000))
        val s = e.snapshot
        assertFalse(e.hasBuffer)
        assertEquals(0, s.allocated)
        assertTrue(s.totalBytes > 0)
        assertEquals(s.totalBytes / 1048576, s.loops)
        assertEquals(s.totalBytes * 1e9 / s.elapsedNs, s.averageBps, 0.001)
        assertEquals("STOPPED", s.state)
    }
    @Test fun repeatedStartStopNeverOverlaps() {
        val e = ExperimentEngine()
        repeat(25) {
            assertTrue(e.start(Config(1, "AA")))
            assertFalse(e.start(Config(1, "55")))
            e.stop(); e.stop()
            assertTrue(e.awaitStopped(2000))
            assertFalse(e.hasBuffer)
        }
    }
    @Test fun outOfMemoryIsReportedAndEngineCanRestart() {
        var fail = true
        val e = ExperimentEngine { if (fail) throw OutOfMemoryError("injected 512 MB failure") else ByteArray(it) }
        e.start(Config(512, "AA"))
        assertTrue(e.awaitStopped(2000))
        assertNotNull(e.snapshot.error)
        assertFalse(e.hasBuffer)
        fail = false
        assertTrue(e.start(Config(1, "55", durationMs = 40)))
        assertTrue(e.awaitStopped(2000))
        assertNull(e.snapshot.error)
    }
    @Test fun idleDoesNotWrite() {
        val e = ExperimentEngine()
        e.start(Config(1, "IDLE", durationMs = 120))
        assertTrue(e.awaitStopped(2000))
        assertEquals(0L, e.snapshot.totalBytes)
        assertEquals(0.0, e.snapshot.averageBps, 0.0)
    }
    @Test fun burstSleepsAndStopInterruptsLongIdle() {
        val e = ExperimentEngine()
        e.start(Config(1, "AA", mode = Mode.BURST, writeMs = 40, idleMs = 5000))
        val until = System.nanoTime() + 2_000_000_000
        while (e.snapshot.state != "RUNNING / IDLE" && System.nanoTime() < until) Thread.sleep(5)
        assertEquals("RUNNING / IDLE", e.snapshot.state)
        val before = e.snapshot.totalBytes
        Thread.sleep(130)
        assertEquals(before, e.snapshot.totalBytes)
        assertEquals(0.0, e.snapshot.currentBps, 0.0)
        e.stop()
        assertTrue(e.awaitStopped(500))
        assertFalse(e.hasBuffer)
    }
    @Test fun burstCycleCountUsesFixedTimeOrigin() {
        val e = ExperimentEngine()
        e.start(Config(1, "AA", mode = Mode.BURST, durationMs = 350, writeMs = 50, idleMs = 50))
        assertTrue(e.awaitStopped(2000))
        assertEquals(e.snapshot.elapsedNs / 100_000_000, e.snapshot.cycles)
        assertTrue(e.snapshot.cycles >= 3)
    }
    @Test fun toggleCountsCompletedTransitions() {
        val e = ExperimentEngine()
        e.start(Config(1, "AA", mode = Mode.TOGGLE, durationMs = 150))
        assertTrue(e.awaitStopped(2000))
        assertTrue(e.snapshot.loops > 1)
        assertEquals(e.snapshot.loops - 1, e.snapshot.toggles)
    }
}
