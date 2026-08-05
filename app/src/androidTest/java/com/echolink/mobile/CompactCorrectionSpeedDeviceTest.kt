package com.echolink.mobile

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactCorrectionSpeedDeviceTest {
    @Test
    fun exactCompactModelCorrectsGrammarAndReportsElapsedTime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(AI_PREFERENCES, Context.MODE_PRIVATE)
        val containedOverride = preferences.contains(FORCE_COMPACT_KEY)
        val previousOverride = preferences.getBoolean(FORCE_COMPACT_KEY, false)
        assertTrue(preferences.edit().putBoolean(FORCE_COMPACT_KEY, true).commit())

        try {
            val manager = AiModelManager.get(context)
            val selection = manager.selectedRuntime()
            assertEquals(AiModelTier.LEGACY_COMPACT, selection.artifact.tier)
            assertNotNull(
                "The exact qualified compact model must be installed.",
                manager.exactInstalledModel(selection),
            )

            val source = "Dogs wag their tails because it help peoples understand how they feels."
            val latch = CountDownLatch(1)
            var result: Result<String>? = null
            val startedAt = SystemClock.elapsedRealtime()
            OnDeviceGrammarEngine.get(context).polish(source, WritingTone.PROOFREAD) {
                result = it
                latch.countDown()
            }
            assertTrue("Compact local AI timed out.", latch.await(2, TimeUnit.MINUTES))
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val output = result?.getOrThrow()
            assertNotNull(output)
            val normalized = checkNotNull(output).lowercase()
            assertTrue(normalized.contains("dogs wag"))
            assertTrue(normalized.contains("it helps"))
            assertTrue(normalized.contains("people"))
            assertTrue(normalized.contains("they feel"))
            assertFalse(normalized.contains("peoples"))
            Log.i(RESULT_LOG_TAG, "tier=LEGACY_COMPACT;elapsed_ms=$elapsedMs")
            println("COMPACT_CORRECTION elapsed_ms=$elapsedMs")
        } finally {
            val editor = preferences.edit()
            if (containedOverride) editor.putBoolean(FORCE_COMPACT_KEY, previousOverride)
            else editor.remove(FORCE_COMPACT_KEY)
            assertTrue(editor.commit())
        }
    }

    private companion object {
        const val AI_PREFERENCES = "manuscript_ai"
        const val FORCE_COMPACT_KEY = "force_legacy_backend_debug"
        const val RESULT_LOG_TAG = "ManuscriptCompactSpeed"
    }
}
