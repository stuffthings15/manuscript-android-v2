package com.echolink.mobile

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactModelQualificationDeviceTest {
    @Test fun exactCompactModelPassesRealSelfTestAndBecomesInstalled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(QWEN_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString("verified_sha256_basic", Qwen3Tier.BASIC.sha256)
            .commit()

        val manager = Qwen3ModelManager.get(context)
        val model = manager.downloadedModel(Qwen3Tier.BASIC)
        assertNotNull("The exact compact model is missing.", model)

        val startedAt = SystemClock.elapsedRealtime()
        val passed = Qwen3ModelSelfTester.verify(
            context,
            checkNotNull(model),
            stopped = { false },
        )
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        Log.i(TAG, "passed=$passed;elapsedMs=$elapsedMs;bytes=${model?.length()}")
        assertTrue("The compact model failed real local inference.", passed)

        manager.markSelfTested(Qwen3Tier.BASIC, checkNotNull(model))
        assertNotNull(
            "The qualified compact model did not become an installed model.",
            manager.installedModel(Qwen3Tier.BASIC),
        )
    }

    private companion object {
        const val TAG = "MANUSCRIPT_COMPACT_SELF_TEST"
        const val QWEN_PREFERENCES = "manuscript_qwen3"
    }
}
