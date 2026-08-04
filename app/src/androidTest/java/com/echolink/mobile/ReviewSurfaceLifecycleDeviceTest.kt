package com.echolink.mobile

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewSurfaceLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun mainActivityRealCloseRemovesCardAndReturnsTriggerWithinContractTimes() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        try {
            dismissStartupDialogIfPresent(activity)
            val editor = activity.findViewById<EditText>(R.id.review_editor)
            instrumentation.runOnMainSync {
                editor.setText("These generated sentence need correction.")
                editor.setSelection(editor.text.length)
                editor.requestFocus()
            }
            instrumentation.waitForIdleSync()

            tap(activity.findViewById(R.id.correct_writing_action))
            val card = activity.findViewById<View>(R.id.writing_suggestion_card)
            assertTrue("Correction card did not open.", waitUntil { card.visibility == View.VISIBLE })

            val started = SystemClock.elapsedRealtime()
            tap(activity.findViewById(R.id.writing_close_action))
            val cardRemovedAt = waitUntilTime(CLOSE_LIMIT_MS) { card.visibility != View.VISIBLE }
            val trigger = activity.findViewById<View>(R.id.writing_assist_icon)
            val triggerReturnedAt = waitUntilTime(TRIGGER_LIMIT_MS) {
                trigger.visibility == View.VISIBLE && trigger.isShown
            }

            assertTrue("Card remained visible past 300 ms.", cardRemovedAt - started <= CLOSE_LIMIT_MS)
            assertTrue("Trigger did not return within 500 ms.", triggerReturnedAt - started <= TRIGGER_LIMIT_MS)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun dismissStartupDialogIfPresent(activity: Activity) {
        instrumentation.waitForIdleSync()
        if (!activity.hasWindowFocus()) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
        }
    }

    private fun tap(view: View) {
        val location = IntArray(2).also(view::getLocationOnScreen)
        val x = location[0] + view.width / 2f
        val y = location[1] + view.height / 2f
        val downAt = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0),
        )
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0),
        )
    }

    private fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (condition()) return true
            SystemClock.sleep(20L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return condition()
    }

    private fun waitUntilTime(timeoutMs: Long, condition: () -> Boolean): Long {
        assertTrue(waitUntil(timeoutMs, condition))
        return SystemClock.elapsedRealtime()
    }

    companion object {
        private const val CLOSE_LIMIT_MS = 300L
        private const val TRIGGER_LIMIT_MS = 500L
    }
}
