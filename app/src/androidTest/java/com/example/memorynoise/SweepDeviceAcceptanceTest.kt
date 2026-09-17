package com.example.memorynoise

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SweepDeviceAcceptanceTest {
    private val engine get() = Experiments.engine
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun cleanup() { engine.stop("验收结束"); assertTrue(engine.awaitStopped(3000)) }

    private fun waitFor(timeoutMs: Long = 5000, predicate: (Snapshot) -> Boolean): Snapshot {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!predicate(engine.snapshot) && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("Timed out: ${engine.snapshot}", predicate(engine.snapshot))
        return engine.snapshot
    }

    private fun button(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) button(view.getChildAt(i), text)?.let { return it }
        return null
    }

    private fun click(scenario: ActivityScenario<MainActivity>, text: String) {
        scenario.onActivity { activity ->
            val b = button(activity.window.decorView, text)!!
            assertTrue("$text must be enabled", b.isEnabled)
            b.performClick()
        }
    }

    private fun configure(scenario: ActivityScenario<MainActivity>, gapPosition: Int) {
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            val time = root.findViewWithTag<Spinner>("select:Sweep /组")
            // Verify official defaults before applying temporary acceptance settings.
            assertEquals("20 s", time.selectedItem.toString())
            assertEquals("2 s", root.findViewWithTag<Spinner>("select:Idle Gap").selectedItem.toString())
            root.findViewWithTag<Spinner>("select:Memory").setSelection(0)
            root.findViewWithTag<Spinner>("select:Mode").setSelection(3)
            time.setSelection(4)
            root.findViewWithTag<EditText>("custom:Sweep /组").setText("2")
            root.findViewWithTag<Spinner>("select:Idle Gap").setSelection(gapPosition)
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertSweepDisplay(scenario: ActivityScenario<MainActivity>, pair: String, next: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(150)
        scenario.onActivity { activity ->
            val label = activity.window.decorView.findViewWithTag<TextView>("sweepStatus")
            assertEquals(View.VISIBLE, label.visibility)
            assertTrue(label.text.contains("Pattern Sweep"))
            assertTrue(label.text.contains(pair))
            assertTrue(label.text.contains("Remaining:"))
            assertTrue(label.text.contains("Next: $next"))
        }
    }

    // Test-only screenshots in app cache; production code performs no file I/O.
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val path = File(instrumentation.targetContext.cacheDir, "$name.png")
        path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun sweepButtonsCountdownGapPauseResumeAndRapidRestart() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            configure(scenario, 1)
            click(scenario, "START")
            val first = waitFor { it.sweepIndex == 0 && it.loops > 5 }
            assertEquals("00 ↔ FF", first.sweepCurrent)
            assertSweepDisplay(scenario, "00 ↔ FF", "01 ↔ FE")
            screenshot("sweep-write")
            val later = waitFor { it.sweepRemainingMs < first.sweepRemainingMs - 250 }
            assertTrue(later.totalBytes > first.totalBytes)
            click(scenario, "PAUSE")
            val paused = waitFor { it.state == "PAUSED" }
            Thread.sleep(2300)
            assertEquals(paused.totalBytes, engine.snapshot.totalBytes)
            assertEquals(paused.sweepRemainingMs, engine.snapshot.sweepRemainingMs)
            assertEquals(0.0, engine.snapshot.currentBps, 0.0)
            click(scenario, "RESUME")
            val resumed = waitFor { it.totalBytes > paused.totalBytes }
            assertEquals(paused.sweepIndex, resumed.sweepIndex)
            waitFor { it.sweepInGap }
            click(scenario, "PAUSE")
            val gap = waitFor { it.state == "PAUSED" && it.sweepInGap }
            assertSweepDisplay(scenario, "00 ↔ FF", "01 ↔ FE")
            screenshot("sweep-gap-paused")
            Thread.sleep(1200)
            assertEquals(gap.totalBytes, engine.snapshot.totalBytes)
            assertEquals(gap.sweepRemainingMs, engine.snapshot.sweepRemainingMs)
            click(scenario, "RESUME")
            waitFor { it.sweepIndex == 1 && it.totalBytes > gap.totalBytes }
            assertSweepDisplay(scenario, "01 ↔ FE", "02 ↔ FD")
            click(scenario, "STOP")
            assertTrue(engine.awaitStopped(3000)); assertFalse(engine.hasBuffer)
            assertEquals("STOPPED", engine.snapshot.state)
            Thread.sleep(200)
            repeat(10) {
                click(scenario, "START")
                assertFalse(engine.start(Config(16, "AA", mode = Mode.SWEEP)))
                click(scenario, "STOP")
                assertTrue(engine.awaitStopped(3000)); assertFalse(engine.hasBuffer)
                Thread.sleep(150)
            }
        }
    }

    @Test fun sweepActuallyReaches55AAWithoutSkippingAndResumesThere() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            configure(scenario, 0) // Real 2 s per pair, zero gap; no clock shortcuts or changed defaults.
            click(scenario, "START")
            waitFor { it.sweepIndex == 0 && it.loops > 1 }
            var previous = 0
            val deadline = System.nanoTime() + 200_000_000_000L
            while (engine.snapshot.sweepIndex < 0x55 && System.nanoTime() < deadline) {
                val s = engine.snapshot
                assertTrue(engine.isRunning)
                assertTrue("Skipped group $previous -> ${s.sweepIndex}", s.sweepIndex in previous..previous + 1)
                if (s.sweepIndex != previous && s.sweepIndex % 16 == 0) Log.i("SweepAcceptance", "Reached ${s.sweepCurrent}, loops=${s.loops}")
                previous = s.sweepIndex
                Thread.sleep(100)
            }
            assertEquals(0x55, engine.snapshot.sweepIndex)
            click(scenario, "PAUSE")
            val paused = waitFor { it.state == "PAUSED" }
            assertEquals("55 ↔ AA", paused.sweepCurrent)
            assertEquals("56 ↔ A9", paused.sweepNext)
            assertSweepDisplay(scenario, "55 ↔ AA", "56 ↔ A9")
            screenshot("sweep-55-paused")
            Thread.sleep(2200)
            assertEquals(paused.totalBytes, engine.snapshot.totalBytes)
            assertEquals(paused.sweepRemainingMs, engine.snapshot.sweepRemainingMs)
            click(scenario, "RESUME")
            val writing = waitFor { it.sweepIndex == 0x55 && it.loops > paused.loops + 2 }
            assertTrue(writing.toggles > paused.toggles)
            waitFor { it.sweepIndex == 0x56 }
            click(scenario, "STOP")
            assertTrue(engine.awaitStopped(3000)); assertFalse(engine.hasBuffer)
        }
    }

    @Test fun sweepRotationBackgroundAndPausedStopReleaseWorker() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            configure(scenario, 1)
            click(scenario, "START"); waitFor { it.loops > 2 }
            click(scenario, "PAUSE"); waitFor { it.state == "PAUSED" }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            assertTrue(engine.awaitStopped(5000)); assertFalse(engine.hasBuffer)
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            click(scenario, "START"); waitFor { it.loops > 2 }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue(engine.awaitStopped(3000)); assertFalse(engine.hasBuffer)
            assertEquals("STOPPED", engine.snapshot.state)
        }
    }

    @Test fun originalModesStillWriteAndStopOnDevice() {
        for (mode in listOf(Mode.CONTINUOUS, Mode.BURST, Mode.TOGGLE)) {
            assertTrue(engine.start(Config(16, "AA55", mode = mode, durationMs = 500, writeMs = 100, idleMs = 100)))
            assertTrue(engine.awaitStopped(5000))
            assertNull(engine.snapshot.error); assertTrue(engine.snapshot.totalBytes > 0)
            assertFalse(engine.hasBuffer)
            if (mode == Mode.TOGGLE) assertEquals(engine.snapshot.loops - 1, engine.snapshot.toggles)
            if (mode == Mode.BURST) assertTrue(engine.snapshot.cycles >= 2)
        }
    }
}
