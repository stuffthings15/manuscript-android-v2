package com.echolink.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
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
        val targetContext = instrumentation.targetContext
        val promptSnapshots = listOf("manuscript_ai", "manuscript_qwen3").map { name ->
            PromptPreferenceSnapshot.capture(
                targetContext.getSharedPreferences(name, Context.MODE_PRIVATE),
            )
        }
        promptSnapshots.forEach { snapshot -> snapshot.markPromptSeen() }
        val activity = instrumentation.startActivitySync(
            Intent(targetContext, MainActivity::class.java).apply {
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

            val trigger = activity.findViewById<View>(R.id.writing_assist_icon)
            assertTrue(
                "The visible in-app trigger did not return after entering text.",
                waitUntil { trigger.visibility == View.VISIBLE && trigger.isShown },
            )
            val openedAt = SystemClock.elapsedRealtime()
            tap(trigger)
            val card = activity.findViewById<View>(R.id.writing_suggestion_card)
            val close = activity.findViewById<View>(R.id.writing_close_action)
            val closeVisibleAt = waitUntilTimeBefore(
                deadline = openedAt + OPEN_CLOSE_VISIBLE_LIMIT_MS,
            ) {
                card.visibility == View.VISIBLE && hasGlobalVisibleBounds(close)
            }
            assertTrue(
                "Correction card and Close were not visible within 500 ms. " +
                    "trigger=${viewChain(trigger)} card=${viewChain(card)} close=${viewChain(close)}",
                closeVisibleAt != null,
            )
            val started = SystemClock.elapsedRealtime()
            tap(close)
            val cardRemovedAt = waitUntilTime(
                timeoutMs = CLOSE_LIMIT_MS,
                failureMessage = "Close touch did not remove the card. " +
                    "card=${viewChain(card)} close=${viewChain(close)}",
            ) { card.visibility != View.VISIBLE }
            val triggerReturned = waitUntil(TRIGGER_LIMIT_MS) {
                trigger.visibility == View.VISIBLE && trigger.isShown
            }
            assertTrue(
                "Trigger did not return within 500 ms. " +
                    "trigger=${viewChain(trigger)} editor=${viewChain(editor)} card=${viewChain(card)}",
                triggerReturned,
            )
            val triggerReturnedAt = SystemClock.elapsedRealtime()

            assertTrue("Card remained visible past 300 ms.", cardRemovedAt - started <= CLOSE_LIMIT_MS)
            assertTrue("Trigger did not return within 500 ms.", triggerReturnedAt - started <= TRIGGER_LIMIT_MS)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            promptSnapshots.forEach { snapshot -> snapshot.restore() }
        }
    }

    private fun dismissStartupDialogIfPresent(activity: Activity) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(650L)
        instrumentation.waitForIdleSync()
        onView(withText(R.string.qwen_model_not_now))
            .inRoot(isDialog())
            .perform(click())
        instrumentation.waitForIdleSync()
        assertTrue(
            "MainActivity did not regain focus after startup guidance.",
            waitUntil { activity.hasWindowFocus() },
        )
    }

    private fun tap(view: View) {
        check(view.isShown && view.isEnabled && view.isClickable) {
            "Real tap requires a shown, enabled, clickable view."
        }
        val visibleBounds = fullyVisibleBounds(view)
        check(visibleBounds != null) {
            "Real tap requires nonempty global visible bounds. ${viewChain(view)}"
        }
        val x = visibleBounds.exactCenterX()
        val y = visibleBounds.exactCenterY()
        val downAt = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downAt,
            downAt,
            MotionEvent.ACTION_DOWN,
            x,
            y,
            0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(
            downAt,
            downAt + 50L,
            MotionEvent.ACTION_UP,
            x,
            y,
            0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
            SystemClock.sleep(50L)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun hasGlobalVisibleBounds(view: View): Boolean {
        return fullyVisibleBounds(view) != null
    }

    private fun fullyVisibleBounds(view: View): Rect? {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2).also(view::getLocationOnScreen)
        val bounds = Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height,
        )
        var ancestor = view.parent as? View
        while (ancestor != null) {
            val ancestorLocation = IntArray(2).also(ancestor::getLocationOnScreen)
            val ancestorBounds = Rect(
                ancestorLocation[0],
                ancestorLocation[1],
                ancestorLocation[0] + ancestor.width,
                ancestorLocation[1] + ancestor.height,
            )
            if (!ancestorBounds.contains(bounds)) return null
            ancestor = ancestor.parent as? View
        }
        return bounds
    }

    private fun viewChain(view: View): String = buildString {
        var current: View? = view
        while (current != null) {
            val location = IntArray(2).also(current::getLocationOnScreen)
            append(current.javaClass.simpleName)
            append("(id=")
            append(current.id)
            append(",visibility=")
            append(current.visibility)
            append(",shown=")
            append(current.isShown)
            append(",size=")
            append(current.width)
            append('x')
            append(current.height)
            append(",screen=")
            append(location.contentToString())
            append(",scrollY=")
            append(current.scrollY)
            append(") ")
            current = current.parent as? View
        }
    }

    private fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (condition()) return true
            SystemClock.sleep(20L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return condition()
    }

    private fun waitUntilTime(
        timeoutMs: Long,
        failureMessage: String = "Condition did not become true in time.",
        condition: () -> Boolean,
    ): Long {
        assertTrue(failureMessage, waitUntil(timeoutMs, condition))
        return SystemClock.elapsedRealtime()
    }

    private fun waitUntilTimeBefore(
        deadline: Long,
        condition: () -> Boolean,
    ): Long? {
        do {
            if (condition()) return SystemClock.elapsedRealtime()
            SystemClock.sleep(10L)
        } while (SystemClock.elapsedRealtime() <= deadline)
        return SystemClock.elapsedRealtime().takeIf { condition() && it <= deadline }
    }

    private data class PromptPreferenceSnapshot(
        val preferences: SharedPreferences,
        val containedValue: Boolean,
        val value: Boolean,
    ) {
        fun markPromptSeen() {
            check(preferences.edit().putBoolean(PROMPT_SEEN_KEY, true).commit())
        }

        fun restore() {
            val editor = preferences.edit()
            if (containedValue) editor.putBoolean(PROMPT_SEEN_KEY, value)
            else editor.remove(PROMPT_SEEN_KEY)
            check(editor.commit())
        }

        companion object {
            fun capture(preferences: SharedPreferences): PromptPreferenceSnapshot =
                PromptPreferenceSnapshot(
                    preferences = preferences,
                    containedValue = preferences.contains(PROMPT_SEEN_KEY),
                    value = preferences.getBoolean(PROMPT_SEEN_KEY, false),
                )
        }
    }

    companion object {
        private const val PROMPT_SEEN_KEY = "download_prompt_seen"
        private const val OPEN_CLOSE_VISIBLE_LIMIT_MS = 500L
        private const val CLOSE_LIMIT_MS = 300L
        private const val TRIGGER_LIMIT_MS = 500L
    }
}
