package com.echolink.mobile

import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveFullPageReviewDeviceTest {
    @Test
    fun proofreadsFullPageAndAcceptsEverySuggestionInOneTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = instrumentation.context.assets
            .open("device_tests/gpt56-full-page-unseen-v3.txt")
            .bufferedReader()
            .use { it.readText().trim() }
        val modelManager = AiModelManager.get(context)

        if (modelManager.installedFile() == null) {
            modelManager.grantDownloadConsent(
                AiDownloadNetworkPolicy.ALLOW_MOBILE_DATA,
            )
            waitForAi(modelManager)
        }
        assertTrue(
            "The live full-page test requires a verified local AI file.",
            modelManager.installedFile()?.isFile == true,
        )

        context.getSharedPreferences(
            "manuscript_preferences_v1",
            android.content.Context.MODE_PRIVATE,
        ).edit().putString(
            MainActivity.TONE_KEY,
            WritingTone.PROOFREAD.label,
        ).commit()

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity

        try {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(700L)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()

            val startedAt = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync {
                activity.findViewById<EditText>(R.id.review_editor).apply {
                    setText(source)
                    setSelection(text.length)
                    requestFocus()
                }
                activity.findViewById<View>(R.id.writing_assist_icon).performClick()
            }

            val acceptAll = activity.findViewById<Button>(
                R.id.writing_accept_all_action,
            )
            waitUntil(15 * 60_000L) { acceptAll.isEnabled }
            val suggestionsReadyMs = SystemClock.elapsedRealtime() - startedAt

            instrumentation.runOnMainSync {
                assertTrue("Accept all must be enabled.", acceptAll.isEnabled)
                assertTrue("Accept all must respond to one tap.", acceptAll.performClick())
            }
            instrumentation.waitForIdleSync()

            val corrected = onMain(instrumentation) {
                activity.findViewById<EditText>(R.id.review_editor)
                    .text
                    .toString()
                    .trim()
            }
            val totalMs = SystemClock.elapsedRealtime() - startedAt

            assertNotEquals(source, corrected)
            assertTrue(corrected.isNotBlank())
            assertFalse(corrected.contains('\u2014'))
            for (fact in REQUIRED_FACTS) {
                assertTrue("Correction changed required fact: $fact", corrected.contains(fact))
            }
            for (error in FORBIDDEN_RESIDUAL_ERRORS) {
                assertFalse(
                    "Correction retained known error: $error",
                    corrected.contains(error, ignoreCase = true),
                )
            }
            assertEquals(5, corrected.split(Regex("\\n\\s*\\n")).size)

            Log.i(
                RESULT_TAG,
                "source_sha256=${sha256(source)};" +
                    "suggestions_ready_ms=$suggestionsReadyMs;" +
                    "total_ms=$totalMs;" +
                    "chars=${corrected.length};" +
                    "backend=${modelManager.selectedRuntime().backend.name}",
            )
            Log.i(
                OUTPUT_TAG,
                Base64.encodeToString(
                    corrected.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                ),
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun waitForAi(manager: AiModelManager) {
        val deadline = SystemClock.elapsedRealtime() + 35 * 60_000L
        var lastState = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = manager.state
            val stateText = when (state) {
                is AiModelState.Downloading ->
                    "Downloading:${state.downloaded}/${state.total}"
                else -> state.javaClass.simpleName
            }
            if (stateText != lastState) {
                Log.i(MODEL_TAG, stateText)
                lastState = stateText
            }
            when (state) {
                is AiModelState.Ready -> return
                is AiModelState.Failed ->
                    throw AssertionError("AI setup failed: ${state.message}")
                is AiModelState.InsufficientStorage ->
                    throw AssertionError("AI setup failed: insufficient storage")
                else -> SystemClock.sleep(1_000L)
            }
        }
        throw AssertionError("AI setup did not finish within 35 minutes.")
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val ready = onMain(instrumentation, condition)
            if (ready) return
            SystemClock.sleep(100L)
        }
        throw AssertionError("Suggestions were not ready within $timeoutMs ms.")
    }

    private fun <T> onMain(
        instrumentation: android.app.Instrumentation,
        action: () -> T,
    ): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync {
            result = runCatching(action)
        }
        return checkNotNull(result).getOrThrow()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val RESULT_TAG = "MANUSCRIPT_LIVE_RESULT"
        const val OUTPUT_TAG = "MANUSCRIPT_LIVE_OUTPUT"
        const val MODEL_TAG = "MANUSCRIPT_MODEL_SETUP"

        val REQUIRED_FACTS = listOf(
            "September 15, 2026",
            "Maya Chen",
            "Luis Ortega",
            "Priya Nair",
            "$31,480",
            "$34,915",
            "invoice 7319",
            "$2,600",
            "214 households",
            "38",
            "twelve families",
            "Eighteen volunteers",
            "Seven supply crates",
            "forty-three school kits",
            "Room 6",
            "September 18",
            "252 registration entries",
            "September 21",
        )

        val FORBIDDEN_RESIDUAL_ERRORS = listOf(
            "committee meet",
            "there fall",
            "agenda were",
            "nobody knowed",
            "never recieved",
            "Priya Nair bringed",
            "each copy contain",
            "expenses totals",
            "he didnt",
            "until friday this",
            "problems creates",
            "form list",
            "sheets lists",
            "families appears",
            "database have",
            "numbers is",
            "could loose",
            "there delivery",
            "accidently",
            "plan also need",
            "volunteers is",
            "they was",
            "crates sits",
            "kits has",
            "without no label",
            "battery are",
            "doors close",
            "get effected",
            "Maya give",
            "instructions was",
            "is suppose",
            "assignments needs",
            "communities trust",
        )
    }
}
