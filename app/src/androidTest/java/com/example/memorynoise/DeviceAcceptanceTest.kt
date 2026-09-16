package com.example.memorynoise

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import org.junit.After
import org.junit.Test
import org.junit.Assert.*

@Suppress("DEPRECATION")
class DeviceAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val engine get() = Experiments.engine
    @After fun tearDown() {
        engine.stop("test cleanup")
        engine.awaitStopped(3000)
    }

    @Test fun testReal512MbAllocationFailureOrSuccessfulStop() {
        assertTrue(engine.start(Config(512, "AA", durationMs = 150)))
        assertTrue(engine.awaitStopped(15000))
        assertFalse(engine.hasBuffer)
        assertEquals(0L, engine.snapshot.allocated)
        assertTrue(engine.snapshot.error != null || engine.snapshot.totalBytes > 0)
        if (Runtime.getRuntime().maxMemory() < 512L * 1024 * 1024) assertNotNull(engine.snapshot.error)
        assertTrue(engine.start(Config(16, "55", durationMs = 100)))
        assertTrue(engine.awaitStopped(5000))
        assertNull(engine.snapshot.error)
        assertTrue(engine.snapshot.totalBytes > 0)
    }

    @Test fun testButtonsAndScreenOnFlag() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
        fun findButton(view: android.view.View, text: String): android.widget.Button? {
            if (view is android.widget.Button && view.text.toString() == text) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                findButton(view.getChildAt(i), text)?.let { return it }
            }
            return null
        }
        scenario.onActivity { activity -> findButton(activity.window.decorView, "START")!!.performClick() }
        Thread.sleep(300)
        assertTrue(engine.isRunning)
        scenario.onActivity { activity ->
            assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            assertFalse(findButton(activity.window.decorView, "START")!!.isEnabled)
            findButton(activity.window.decorView, "STOP")!!.performClick()
        }
        assertTrue(engine.awaitStopped(3000))
        assertFalse(engine.hasBuffer)
        val refreshDeadline = System.nanoTime() + 3_000_000_000
        var ready = false
        while (!ready && System.nanoTime() < refreshDeadline) {
            scenario.onActivity { activity -> ready = findButton(activity.window.decorView, "START")!!.isEnabled }
            if (!ready) Thread.sleep(50)
        }
        scenario.onActivity { activity ->
            assertEquals(0, activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            assertTrue(findButton(activity.window.decorView, "START")!!.isEnabled)
        }
        } finally { scenario.close() }
    }

    @Test fun testRotationStopsWorkerAndReleasesBuffer() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
        assertTrue(engine.start(Config(16, "AA")))
        Thread.sleep(250)
        scenario.onActivity { activity -> activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        val until = System.nanoTime() + 5_000_000_000
        while (engine.isRunning && System.nanoTime() < until) Thread.sleep(20)
        assertFalse("Activity recreation must cancel worker", engine.isRunning)
        assertFalse(engine.hasBuffer)
        assertEquals("页面离开 / Activity 重建", engine.snapshot.reason)
        } finally { scenario.close() }
    }

    @Test fun testBurstAndRapidRestartOnDevice() {
        repeat(10) {
            assertTrue(engine.start(Config(16, "AA55", mode = Mode.BURST, writeMs = 100, idleMs = 100)))
            assertFalse(engine.start(Config(16, "AA")))
            Thread.sleep(25)
            engine.stop()
            assertTrue(engine.awaitStopped(3000))
            assertFalse(engine.hasBuffer)
        }
        engine.start(Config(16, "AA55", mode = Mode.BURST, durationMs = 650, writeMs = 100, idleMs = 100))
        assertTrue(engine.awaitStopped(5000))
        assertTrue(engine.snapshot.cycles >= 3)
        assertTrue(engine.snapshot.totalBytes > 0)
    }
}
