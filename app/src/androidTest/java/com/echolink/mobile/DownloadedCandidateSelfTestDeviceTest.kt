package com.echolink.mobile

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the exact downloaded candidate can load and answer before activation. */
@RunWith(AndroidJUnit4::class)
class DownloadedCandidateSelfTestDeviceTest {
    @Test
    fun exactSelectedCandidatePassesRealLocalInference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = Qwen3ModelManager.get(context)
        val tier = manager.selectedTier()
        val candidate = manager.candidateModel(tier)
        assertNotNull("The exact selected candidate is missing.", candidate)

        val startedAt = SystemClock.elapsedRealtime()
        val passed = Qwen3ModelSelfTester.verify(context, checkNotNull(candidate))
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        Log.i(TAG, "passed=$passed;elapsedMs=$elapsed;tier=${tier.name}")

        assertTrue("The exact downloaded candidate failed local inference.", passed)
    }

    private companion object {
        const val TAG = "MANUSCRIPT_CANDIDATE_SELF_TEST"
    }
}
