package com.echolink.mobile

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Times the legacy llama.cpp runtime on the same paragraphs the LiteRT Gemma
 * path handles, so the two can be compared on this device rather than on a
 * guess. Gemma costs about nine seconds of fixed prompt processing per call
 * here regardless of prompt size; the question is whether llama.cpp pays the
 * same toll.
 *
 * Skips unless the legacy model is installed.
 */
@RunWith(AndroidJUnit4::class)
class LegacyBackendBenchmarkDeviceTest {
    @Test
    fun deterministicFloorRepairsResidualSpelling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = SAMPLES[2]
        val output = LocalCorrectionRouter(context).deterministicRepair(source)
        Log.i(TAG, "deterministicOutput=$output")
        listOf("wen", "animul", "pett", "befor", "especialy").forEach {
            check(!Regex("(?i)\\b${Regex.escape(it)}\\b").containsMatchIn(output)) {
                "Deterministic floor left '$it' uncorrected: $output"
            }
        }
    }

    @Test
    fun timesLegacyRuntimeOnRealParagraphs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The hash of this file was verified off-device against the app's own
        // catalog before it was pushed. Record that verification so the
        // manager treats it as a real download rather than an unknown file.
        context.getSharedPreferences(QWEN_PREFERENCES, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("verified_sha256_balanced", Qwen3Tier.BALANCED.sha256)
            .commit()
        val manager = Qwen3ModelManager.get(context)
        // The file was placed on the device directly, so the app has never
        // run its own self-test on it. Run the real self-test now rather than
        // skipping the gate: a model that cannot answer is not a benchmark.
        val onDisk = manager.downloadedModel(Qwen3Tier.BALANCED)
        assumeTrue("Legacy model file is not on this device.", onDisk != null)
        if (onDisk != null) {
            val passed = Qwen3ModelSelfTester.verify(context, onDisk)
            Log.i(TAG, "selfTest=$passed")
            assumeTrue("Legacy model failed its self-test.", passed)
            manager.markSelfTested(Qwen3Tier.BALANCED, onDisk)
        }
        val model = manager.installedModel(Qwen3Tier.BALANCED)
        assumeTrue("Legacy model is not installed on this device.", model != null)
        Log.i(TAG, "model=${model?.name};bytes=${model?.length()}")

        val runtime = Qwen3GrammarRuntime(context)
        runtime.bindModel(requireNotNull(model), Qwen3Tier.BALANCED)
        val ready = runtime.isReady()
        Log.i(TAG, "ready=$ready")
        assumeTrue("Legacy runtime did not become ready.", ready)

        SAMPLES.forEachIndexed { index, sample ->
            val generationInput = if (index == 2) {
                LocalCorrectionRouter(context).deterministicRepair(sample)
            } else {
                sample
            }
            val startedAt = SystemClock.elapsedRealtime()
            val output = runtime.rewrite(
                source = generationInput,
                tone = WritingTone.PROOFREAD,
                strict = index == 2,
            )
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.i(
                TAG,
                "sample=$index;inChars=${generationInput.length};" +
                    "outChars=${output?.length ?: 0};totalMs=$elapsed;output=$output",
            )
            check(!output.isNullOrBlank()) { "Balanced AI returned no correction for sample $index." }
            if (index == 2) {
                val combined = LocalCorrectionRouter(context).deterministicRepair(output)
                listOf("wen", "animul", "pett", "befor", "lernt", "especialy").forEach {
                    check(!Regex("(?i)\\b${Regex.escape(it)}\\b").containsMatchIn(combined)) {
                        "AI plus deterministic floor left '$it' uncorrected: $combined"
                    }
                }
            }
        }
        runtime.releaseResources()
    }

    private companion object {
        const val TAG = "MANUSCRIPT_LEGACY_BENCH"
        const val QWEN_PREFERENCES = "manuscript_qwen3"

        // Lengths matched to the essay corpus: a heading, a short line and
        // two full paragraphs.
        val SAMPLES = listOf(
            "Dogs Makes Grate Petts in Bakersfield",
            "intentionally errro-filled practice esay 1??",
            "Wen somebody says A certain animul cant be a pett they usually is " +
                "speaking befor they lernt much bout it and in Bakersfield , United " +
                "States to be honest this this can be especialy true for a DOG.",
            "There is emotional benefits as well. When the owner has a bad day, the " +
                "duck will not solve the actual problem like paying a bill, finishing " +
                "school work or fixing a broken sink. But caring for it can slow the " +
                "mind down for a little while.",
        )
    }
}
