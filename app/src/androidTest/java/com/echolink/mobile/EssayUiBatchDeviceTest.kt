package com.echolink.mobile

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the essay inbox through the REAL screen: the editor, the "Correct my
 * writing" button, the review card, and Accept all. The headless batch proves
 * the engine corrects a paragraph; only this test proves the user actually
 * receives the correction, because everything between the engine and the
 * editor text is exercised here too.
 *
 * Sources and reports use app-owned internal storage. This avoids Android's
 * scoped-storage rule that can make shell-created external fixtures invisible
 * to the target app even when adb can list them.
 */
@RunWith(AndroidJUnit4::class)
class EssayUiBatchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun correctsEveryEssayThroughTheUserInterface() {
        val context = instrumentation.targetContext
        val inbox = File(context.filesDir, "essays")
        // -e essay 05 runs one essay per invocation, which is how the review
        // loop paces itself: one document, judge it, fix, run the next.
        val only = InstrumentationRegistry.getArguments().getString("essay").orEmpty()
        val essays = inbox.listFiles { file -> file.extension == "txt" }
            ?.filter { only.isEmpty() || it.name.startsWith(only) }
            ?.sortedBy(File::getName)
            .orEmpty()
        assumeTrue("No essays pushed to ${inbox.absolutePath}", essays.isNotEmpty())
        val outbox = File(context.filesDir, "essays-ui-corrected")
        outbox.mkdirs()
        val failures = mutableListOf<String>()

        // -e backend legacy runs the same essays on the llama.cpp runtime so
        // the two can be compared on identical text.
        val backend = InstrumentationRegistry.getArguments().getString("backend").orEmpty()
        AiModelManager.get(context)
            .forceLegacyBackendForTesting(backend.equals("legacy", ignoreCase = true))
        context.getSharedPreferences("manuscript_preferences_v1", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(MainActivity.TONE_KEY, WritingTone.PROOFREAD.label)
            .commit()

        essays.forEach { essay ->
            LocalAiAttemptEvidence.reset()
            val source = essay.readText().trim()
            val report = File(outbox, essay.nameWithoutExtension + ".out.txt")
            val activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            try {
                instrumentation.waitForIdleSync()
                dismissCoveringDialog(activity)

                val startedAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "essay=${essay.name} STAGE=typing chars=${source.length}")
                // Paste the document the way a user does, then press the
                // button with a real touch event so the run is visibly a
                // person using the app, not code calling performClick.
                instrumentation.runOnMainSync {
                    activity.findViewById<EditText>(R.id.review_editor).apply {
                        setText(source)
                        setSelection(text.length)
                        // Focus as a person typing has: the review card only
                        // opens for a focused editor.
                        requestFocus()
                    }
                }
                instrumentation.waitForIdleSync()
                // Close the soft keyboard first. While the IME window is on
                // top, the app does not own the window under the finger and
                // the framework refuses the injected touch outright.
                dismissKeyboard(activity)
                dismissCoveringDialog(activity)
                // Press the full-width "Correct my writing" button, which is
                // what a person actually taps. The small in-editor assist
                // icon is hidden whenever the editor is not focused, so a tap
                // aimed at it silently hits nothing and no review ever opens.
                val correctButton =
                    activity.findViewById<Button>(R.id.correct_writing_action)
                val reviewCard = activity.findViewById<View>(R.id.writing_suggestion_card)
                pressUntil(correctButton, "correct") { reviewCard.isShown }

                // Walk the review card the way a person does: wait for each
                // suggestion, press Accept paragraph, move to the next, until
                // the card closes. This needs no Accept all, so a single
                // paragraph that never reports ready cannot strand the run.
                val card = activity.findViewById<View>(R.id.writing_suggestion_card)
                val acceptParagraph =
                    activity.findViewById<Button>(R.id.writing_replace_action)
                val counter = activity.findViewById<TextView>(R.id.writing_counter)
                // A launch dialog can appear while the correction runs and
                // cover the card; clear it the way a user would. The key must
                // be sent from the test thread, never from the main thread.
                var firstReady = false
                val readyDeadline = SystemClock.elapsedRealtime() + REVIEW_TIMEOUT_MS
                while (SystemClock.elapsedRealtime() < readyDeadline) {
                    if (!onMain { activity.hasWindowFocus() }) {
                        instrumentation.sendKeyDownUpSync(
                            android.view.KeyEvent.KEYCODE_BACK,
                        )
                        instrumentation.waitForIdleSync()
                    }
                    if (onMain { card.isShown && acceptParagraph.isEnabled }) {
                        firstReady = true
                        break
                    }
                    SystemClock.sleep(250L)
                }
                if (!firstReady) {
                    report.writeText("STATUS: TIMEOUT waiting for suggestions\n\n$source")
                    Log.e(TAG, "essay=${essay.name} STATUS=TIMEOUT_SUGGESTIONS")
                    failures += "${essay.name}: suggestions never became terminal"
                    return@forEach
                }
                val suggestionsMs = SystemClock.elapsedRealtime() - startedAt

                var accepted = 0
                while (accepted < MAX_SECTION_TAPS && onMain { card.isShown }) {
                    val live = waitUntil(SECTION_TIMEOUT_MS) {
                        !card.isShown || acceptParagraph.isEnabled
                    }
                    check(live) { "Section ${accepted + 1} exceeded the terminal timeout." }
                    if (!onMain { card.isShown }) break
                    val label = onMain { counter.text?.toString().orEmpty() }
                    val before = onMain { counter.text?.toString().orEmpty() }
                    pressUntil(acceptParagraph, "accept") {
                        !card.isShown || counter.text?.toString().orEmpty() != before
                    }
                    accepted += 1
                    Log.i(TAG, "essay=${essay.name} accepted=$accepted counter=$label")
                    SystemClock.sleep(400L)
                }
                SystemClock.sleep(1_000L)

                val corrected = onMain {
                    activity.findViewById<EditText>(R.id.review_editor).text.toString().trim()
                }
                val totalMs = SystemClock.elapsedRealtime() - startedAt
                val sourceBlocks = source.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
                val correctedBlocks =
                    corrected.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
                val untouched = sourceBlocks.count { block ->
                    correctedBlocks.any { it.trim() == block.trim() }
                }
                val aiEvidence = LocalAiAttemptEvidence.snapshot()
                val maximumAiSections = aiEvidence.values.maxOfOrNull { it.size } ?: 0
                check(maximumAiSections >= sourceBlocks.size) {
                    "Only $maximumAiSections of ${sourceBlocks.size} sections recorded live AI."
                }
                check(accepted == sourceBlocks.size) {
                    "Accepted $accepted of ${sourceBlocks.size} sections."
                }
                check(!onMain { card.isShown }) { "Review card remained open after the final section." }
                check(correctedBlocks.size == sourceBlocks.size) {
                    "Section count changed ${sourceBlocks.size}->${correctedBlocks.size}."
                }
                check(untouched == 0) { "$untouched sections were returned unchanged." }
                check(correctedBlocks.none { it.isBlank() }) { "A corrected section was blank." }
                check('\u2014' !in corrected) { "Correction introduced an em dash." }
                check(totalMs <= DOCUMENT_TIMEOUT_MS) {
                    "Document exceeded ${DOCUMENT_TIMEOUT_MS}ms: ${totalMs}ms."
                }
                report.writeText(
                    buildString {
                        appendLine("STATUS: OK")
                        appendLine("suggestionsMs: $suggestionsMs")
                        appendLine("totalMs: $totalMs")
                        appendLine("sourceBlocks: ${sourceBlocks.size}")
                        appendLine("correctedBlocks: ${correctedBlocks.size}")
                        appendLine("untouchedBlocks: $untouched")
                        appendLine("acceptedTaps: $accepted")
                        appendLine("aiDocumentGroups: ${aiEvidence.size}")
                        appendLine("maximumLiveAiSections: $maximumAiSections")
                        appendLine()
                        append(corrected)
                    },
                )
                Log.i(
                    TAG,
                    "essay=${essay.name} STATUS=OK totalMs=$totalMs " +
                        "suggestionsMs=$suggestionsMs accepted=$accepted blocks=${sourceBlocks.size}->" +
                        "${correctedBlocks.size} untouched=$untouched",
                )
            } catch (failure: Throwable) {
                report.writeText("STATUS: FAILED\nreason: ${failure.message}\n\n$source")
                Log.e(TAG, "essay=${essay.name} STATUS=FAILED reason=${failure.message}")
                failures += "${essay.name}: ${failure.message}"
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(500L)
            }
        }
        check(failures.isEmpty()) { failures.joinToString(separator = "\n") }
    }

    /**
     * Presses a view with a genuine touch down/up at its on-screen centre.
     * The framework routes this exactly like a finger, so the press is
     * visible on the device and exercises the real touch path.
     */
    private fun dismissKeyboard(activity: MainActivity) {
        onMain {
            val focused = activity.currentFocus
            if (focused != null) {
                val manager = activity.getSystemService(InputMethodManager::class.java)
                manager?.hideSoftInputFromWindow(focused.windowToken, 0)
            }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(600L)
    }

    /**
     * The readiness dialog can arrive after the 4B preload finishes. Wait for
     * that delayed window, then dismiss it with Back exactly as a user does.
     * Require the activity to keep focus for a full second before touching
     * its controls, so a dialog cannot appear between this check and the tap.
     */
    private fun dismissCoveringDialog(activity: MainActivity) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        var focusedSince = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain { activity.hasWindowFocus() }) {
                if (focusedSince == 0L) focusedSince = SystemClock.elapsedRealtime()
                if (SystemClock.elapsedRealtime() - focusedSince >= 1_000L) return
            } else {
                focusedSince = 0L
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
            }
            SystemClock.sleep(200L)
        }
        check(onMain { activity.hasWindowFocus() }) {
            "A blocking dialog still covers the editor."
        }
    }

    /** Presses a control and fails unless that genuine touch lands. */
    private fun pressUntil(view: View, label: String, landed: () -> Boolean) {
        tap(view)
        val landedByTouch = waitUntil(2_500L, landed)
        Log.i(TAG, "press=$label via=touch landed=$landedByTouch")
        check(landedByTouch) {
            "The real user touch did not activate $label."
        }
    }

    private fun tap(view: View) {
        val location = IntArray(2)
        onMain {
            // Bring the control on screen exactly as a user scrolling to it
            // would, then read where it actually sits.
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400L)
        onMain { view.getLocationOnScreen(location) }
        val x = (location[0] + onMain { view.width } / 2).toFloat()
        val y = (location[1] + onMain { view.height } / 2).toFloat()
        val downAt = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(
            downAt,
            downAt + 90L,
            MotionEvent.ACTION_UP,
            x,
            y,
            0,
        )
        // UiAutomation injects through the same system path a physical touch
        // takes, so it is not refused when another window (the IME) has been
        // in front, and the press shows on screen like a finger.
        val automation = instrumentation.uiAutomation
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        automation.injectInputEvent(down, true)
        SystemClock.sleep(90L)
        automation.injectInputEvent(up, true)
        down.recycle()
        up.recycle()
        instrumentation.waitForIdleSync()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return true
            SystemClock.sleep(250L)
        }
        return false
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val TAG = "MANUSCRIPT_UI_BATCH"
        const val REVIEW_TIMEOUT_MS = 90_000L
        const val SECTION_TIMEOUT_MS = 90_000L
        const val DOCUMENT_TIMEOUT_MS = 12L * 60_000L
        const val MAX_SECTION_TAPS = 60
    }
}
