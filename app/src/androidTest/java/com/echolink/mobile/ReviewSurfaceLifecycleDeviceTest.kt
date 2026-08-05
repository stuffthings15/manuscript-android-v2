package com.echolink.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
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
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReviewSurfaceLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun mainActivityTenConsecutiveWarmCyclesAcceptHostDrivenPhysicalClose() {
        repeat(10) { cycle ->
            runHostDrivenPhysicalClose(
                waitForActiveRequest = false,
                hostCycle = cycle + 1,
            )
        }
    }

    @Test
    fun mainActivityRealCloseRemovesCardAndReturnsTriggerWithinContractTimes() {
        runCloseCycle()
    }

    @Test
    fun mainActivityStartupPhaseAcceptsHostDrivenPhysicalClose() {
        val delayMs = InstrumentationRegistry.getArguments()
            .getString(HOST_CLOSE_DELAY_ARGUMENT)
            ?.toLongOrNull()
            ?: 0L
        require(delayMs in HOST_CLOSE_DELAYS) { "Unsupported host close delay: $delayMs" }
        runHostDrivenPhysicalClose(waitForActiveRequest = false, closeDelayMs = delayMs)
    }

    @Test
    fun mainActivityActiveRequestAcceptsHostDrivenPhysicalClose() {
        runHostDrivenPhysicalClose(waitForActiveRequest = true)
    }

    private fun runHostDrivenPhysicalClose(
        waitForActiveRequest: Boolean,
        closeDelayMs: Long = 0L,
        hostCycle: Int = 1,
    ) {
        val targetContext = instrumentation.targetContext
        val promptSnapshots = listOf("manuscript_ai", "manuscript_qwen3").map { name ->
            PromptPreferenceSnapshot.capture(
                targetContext.getSharedPreferences(name, Context.MODE_PRIVATE),
            )
        }
        promptSnapshots.forEach(PromptPreferenceSnapshot::markPromptSeen)
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
            sendRequestedTrimMemory(targetContext.packageName)
            val trigger = activity.findViewById<View>(R.id.writing_assist_icon)
            assertTrue(waitUntil { fullyVisibleBounds(trigger) != null })
            tap(trigger)
            val card = activity.findViewById<View>(R.id.writing_suggestion_card)
            val close = activity.findViewById<View>(R.id.writing_close_action)
            val progress = activity.findViewById<View>(R.id.writing_progress)
            assertTrue(waitUntil { card.visibility == View.VISIBLE })
            if (waitForActiveRequest) {
                assertTrue(
                    "The request never reached active inference.",
                    waitUntil(ACTIVE_REQUEST_LIMIT_MS) { progress.tag == ACTIVE_REQUEST_TAG },
                )
            } else if (closeDelayMs > 0L) {
                SystemClock.sleep(closeDelayMs)
            }
            val closeBounds = awaitStableVisibleBoundsAcrossFrames(close)
            assertTrue(
                "Close did not keep identical full touch bounds across two draw frames.",
                closeBounds != null,
            )
            val stableCloseBounds = requireNotNull(closeBounds)
            val phase = if (waitForActiveRequest) "active" else "delay-${closeDelayMs}ms"
            Log.i(
                REVIEW_LOG_TAG,
                "event=host-touch-ready;phase=$phase;cycle=$hostCycle;" +
                    "x=${stableCloseBounds.exactCenterX().toInt()};" +
                    "y=${stableCloseBounds.exactCenterY().toInt()};" +
                    "elapsed_ms=${SystemClock.elapsedRealtime()}",
            )

            assertTrue(
                "Host touch did not close the active review.",
                waitUntil(HOST_TOUCH_LIMIT_MS) { card.visibility != View.VISIBLE },
            )
            assertTrue(
                "Trigger did not return after host-driven Close.",
                waitUntil(TRIGGER_LIMIT_MS) { fullyVisibleBounds(trigger) != null },
            )
        } finally {
            instrumentation.runOnMainSync(activity::finish)
            instrumentation.waitForIdleSync()
            promptSnapshots.forEach(PromptPreferenceSnapshot::restore)
        }
    }

    @Test
    fun mainActivityDestroyDuringActiveRequestRejectsLateUiCallbacks() {
        val targetContext = instrumentation.targetContext
        val promptSnapshots = listOf("manuscript_ai", "manuscript_qwen3").map { name ->
            PromptPreferenceSnapshot.capture(
                targetContext.getSharedPreferences(name, Context.MODE_PRIVATE),
            )
        }
        promptSnapshots.forEach(PromptPreferenceSnapshot::markPromptSeen)
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
            assertTrue(waitUntil { fullyVisibleBounds(trigger) != null })
            tap(trigger)
            val card = activity.findViewById<View>(R.id.writing_suggestion_card)
            val progress = activity.findViewById<View>(R.id.writing_progress)
            assertTrue(waitUntil { card.visibility == View.VISIBLE })
            assertTrue(
                "The request never reached the active worker phase.",
                waitUntil(ACTIVE_REQUEST_LIMIT_MS) { progress.tag == ACTIVE_REQUEST_TAG },
            )

            instrumentation.runOnMainSync(activity::finish)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(LATE_CALLBACK_GUARD_MS)
            instrumentation.waitForIdleSync()

            assertTrue(activity.isDestroyed)
            assertTrue(!card.isAttachedToWindow)
            assertTrue(progress.visibility != View.VISIBLE)
            assertTrue(progress.tag == null)
        } finally {
            if (!activity.isDestroyed) {
                instrumentation.runOnMainSync(activity::finish)
                instrumentation.waitForIdleSync()
            }
            promptSnapshots.forEach(PromptPreferenceSnapshot::restore)
        }
    }

    private fun runCloseCycle(
        closeDelayMs: Long = 0L,
        waitForActiveRequest: Boolean = false,
    ) {
        val phase = if (waitForActiveRequest) "active-request" else "delay-${closeDelayMs}ms"
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
            sendRequestedTrimMemory(targetContext.packageName)

            val trigger = activity.findViewById<View>(R.id.writing_assist_icon)
            assertTrue(
                "The rendered in-app trigger did not return after entering text. ${viewChain(trigger)}",
                waitUntil { fullyVisibleBounds(trigger) != null },
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
                "$phase: Correction card and Close were not visible within 500 ms. " +
                    "trigger=${viewChain(trigger)} card=${viewChain(card)} close=${viewChain(close)}",
                closeVisibleAt != null,
            )
            if (waitForActiveRequest) {
                val progress = activity.findViewById<View>(R.id.writing_progress)
                assertTrue(
                    "$phase: The model request did not become active before Close. ${viewChain(progress)}",
                    waitUntil(ACTIVE_REQUEST_LIMIT_MS) { progress.tag == ACTIVE_REQUEST_TAG },
                )
            } else if (closeDelayMs > 0L) {
                SystemClock.sleep(closeDelayMs)
            }
            val started = SystemClock.elapsedRealtime()
            Log.i(REVIEW_LOG_TAG, "event=test-close-start;phase=$phase;elapsed_ms=$started")
            println("CLOSE_PHASE_START phase=$phase elapsed_ms=$started")
            tap(close)
            val cardRemovedAt = waitUntilTime(
                timeoutMs = CLOSE_LIMIT_MS,
                failureMessage = "$phase: Close touch did not remove the card. " +
                    "card=${viewChain(card)} close=${viewChain(close)}",
            ) { card.visibility != View.VISIBLE }
            val triggerReturned = waitUntil(TRIGGER_LIMIT_MS) {
                fullyVisibleBounds(trigger) != null
            }
            assertTrue(
                "$phase: Trigger did not return within 500 ms. " +
                    "trigger=${viewChain(trigger)} editor=${viewChain(editor)} card=${viewChain(card)}",
                triggerReturned,
            )
            val triggerReturnedAt = SystemClock.elapsedRealtime()

            assertTrue(
                "$phase: Card remained visible past 300 ms.",
                cardRemovedAt - started <= CLOSE_LIMIT_MS,
            )
            assertTrue(
                "$phase: Trigger did not return within 500 ms.",
                triggerReturnedAt - started <= TRIGGER_LIMIT_MS,
            )
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
        if (!activity.hasWindowFocus()) {
            onView(withText(R.string.qwen_model_not_now))
                .inRoot(isDialog())
                .perform(click())
        }
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
        println("REAL_TOUCH_BOUNDS id=${view.id} bounds=$visibleBounds chain=${viewChain(view)}")
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

    private fun sendRequestedTrimMemory(packageName: String) {
        val level = InstrumentationRegistry.getArguments().getString(TRIM_LEVEL_ARGUMENT) ?: return
        require(level in SUPPORTED_TRIM_LEVELS) { "Unsupported trim level: $level" }
        val output = instrumentation.uiAutomation
            .executeShellCommand("am send-trim-memory $packageName $level")
            .use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText().trim() }
            }
        assertTrue("System trim-memory command failed: $output", output.isBlank())
        SystemClock.sleep(250L)
        instrumentation.waitForIdleSync()
        println("SYSTEM_TRIM_MEMORY level=$level")
    }

    private fun hasGlobalVisibleBounds(view: View): Boolean {
        return fullyVisibleBounds(view) != null
    }

    private fun awaitStableVisibleBoundsAcrossFrames(
        view: View,
        timeoutMs: Long = 5_000L,
    ): Rect? {
        val completed = CountDownLatch(1)
        var stableBounds: Rect? = null
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        instrumentation.runOnMainSync {
            var previous: Rect? = null
            listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val current = fullyVisibleBounds(view)?.let(::Rect)
                    if (current != null && current == previous) {
                        stableBounds = current
                        if (view.viewTreeObserver.isAlive) {
                            view.viewTreeObserver.removeOnPreDrawListener(this)
                        }
                        completed.countDown()
                    } else {
                        previous = current
                        view.postInvalidateOnAnimation()
                    }
                    return true
                }
            }
            view.viewTreeObserver.addOnPreDrawListener(listener)
            view.postInvalidateOnAnimation()
        }
        if (!completed.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            instrumentation.runOnMainSync {
                if (view.viewTreeObserver.isAlive) {
                    view.viewTreeObserver.removeOnPreDrawListener(listener)
                }
            }
        }
        return stableBounds
    }

    private fun fullyVisibleBounds(view: View): Rect? {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return null
        val bounds = Rect()
        if (!view.getGlobalVisibleRect(bounds)) return null
        val minimumTouchTarget = (48 * view.resources.displayMetrics.density).toInt()
        if (bounds.width() < minimumTouchTarget || bounds.height() < minimumTouchTarget) return null
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
        private const val ACTIVE_REQUEST_LIMIT_MS = 10_000L
        private const val HOST_TOUCH_LIMIT_MS = 30_000L
        private const val HOST_CLOSE_DELAY_ARGUMENT = "closeDelayMs"
        private const val LATE_CALLBACK_GUARD_MS = 1_000L
        private const val ACTIVE_REQUEST_TAG = "active-review-request"
        private const val REVIEW_LOG_TAG = "ManuscriptReview"
        private const val TRIM_LEVEL_ARGUMENT = "trimLevel"
        private val SUPPORTED_TRIM_LEVELS = setOf("RUNNING_LOW", "RUNNING_CRITICAL")
        private val HOST_CLOSE_DELAYS = setOf(0L, 100L, 500L, 1_000L)
    }
}
