package com.echolink.mobile

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Corrects a folder of essays through exactly the path the "Correct my
 * writing" button uses, then writes each result next to its source so the
 * output can be read and judged off-device.
 *
 * The essays are pushed in and pulled out by the operator; nothing is stored
 * in the repository. Sources live in the app's own external files directory,
 * which needs no runtime permission:
 *
 * Every .txt file directly under:
 *
 *   /sdcard/Android/data/[package]/files/essays
 */
@RunWith(AndroidJUnit4::class)
class EssayBatchDeviceTest {
    @Test
    fun correctsEveryEssayInTheInbox() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val inbox = File(context.getExternalFilesDir(null), "essays")
        val essays = inbox.listFiles { file -> file.extension == "txt" }
            ?.sortedBy(File::getName)
            .orEmpty()
        assumeTrue("No essays pushed to ${inbox.absolutePath}", essays.isNotEmpty())

        val outbox = File(context.getExternalFilesDir(null), "essays-corrected")
        outbox.mkdirs()
        val engine = OnDeviceGrammarEngine.get(context)
        // A real user opens the app once and works through their documents, so
        // the session stays warm across the batch exactly as it would in use.
        engine.setSessionWarm(true)
        val failures = mutableListOf<String>()

        essays.forEach { essay ->
            val source = essay.readText()
            val startedAt = SystemClock.elapsedRealtime()
            val latch = CountDownLatch(1)
            var outcome: Result<DocumentCorrectionResult>? = null
            var lastParagraphAt = startedAt
            var stalledParagraph = -1
            var worstGapMs = 0L

            engine.polishDocument(
                source = source,
                tone = WritingTone.PROOFREAD,
                sourceApplication = context.packageName,
                qualityModeOverride = QualityMode.FAST,
                onParagraphCompleted = { paragraph ->
                    val now = SystemClock.elapsedRealtime()
                    val gap = now - lastParagraphAt
                    if (gap > worstGapMs) {
                        worstGapMs = gap
                        stalledParagraph = paragraph.index
                    }
                    lastParagraphAt = now
                    Log.i(
                        TAG,
                        "essay=${essay.name} paragraph=${paragraph.index} gapMs=$gap " +
                            "validated=${paragraph.validated} fallback=${paragraph.usedFallback} " +
                            "risks=${paragraph.risks.joinToString("|")}",
                    )
                },
            ) { result ->
                outcome = result
                latch.countDown()
            }

            val finished = latch.await(TIMEOUT_MINUTES, TimeUnit.MINUTES)
            val totalMs = SystemClock.elapsedRealtime() - startedAt
            val report = File(outbox, essay.nameWithoutExtension + ".out.txt")

            if (!finished) {
                report.writeText("STATUS: TIMEOUT after ${TIMEOUT_MINUTES}m\n\n$source")
                Log.e(TAG, "essay=${essay.name} STATUS=TIMEOUT totalMs=$totalMs")
                failures += "${essay.name}: document timeout"
                return@forEach
            }

            val value = outcome?.getOrNull()
            val failure = outcome?.exceptionOrNull()
            if (value == null) {
                val paragraphFailure = failure as? ParagraphCorrectionException
                report.writeText(
                    "STATUS: FAILED\nreason: ${failure?.message}\n" +
                        "paragraph: ${paragraphFailure?.paragraphIndex}\n" +
                        "stage: ${paragraphFailure?.stage}\n\n$source",
                )
                Log.e(
                    TAG,
                    "essay=${essay.name} STATUS=FAILED totalMs=$totalMs " +
                        "paragraph=${paragraphFailure?.paragraphIndex} " +
                        "reason=${failure?.message}",
                )
                failures += "${essay.name}: ${failure?.message ?: "unknown failure"}"
                return@forEach
            }

            val kept = value.paragraphs.count { !it.validated }
            val sourceBlocks = source.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
            val correctedBlocks = value.text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
            runCatching {
                check(value.paragraphs.size == sourceBlocks.size) { "paragraph count changed" }
                check(correctedBlocks.size == sourceBlocks.size) { "section count changed" }
                check(kept == 0) { "$kept sections were not validated" }
                check(worstGapMs <= SECTION_TIMEOUT_MS) { "section exceeded terminal timeout" }
                check('\u2014' !in value.text) { "correction introduced an em dash" }
            }.exceptionOrNull()?.let { failures += "${essay.name}: ${it.message}" }
            report.writeText(
                buildString {
                    appendLine("STATUS: OK")
                    appendLine("totalMs: $totalMs")
                    appendLine("paragraphs: ${value.paragraphs.size}")
                    appendLine("keptOriginal: $kept")
                    appendLine("worstGapMs: $worstGapMs (paragraph $stalledParagraph)")
                    appendLine()
                    append(value.text)
                },
            )
            Log.i(
                TAG,
                "essay=${essay.name} STATUS=OK totalMs=$totalMs paragraphs=" +
                    "${value.paragraphs.size} keptOriginal=$kept worstGapMs=$worstGapMs",
            )
        }

        engine.setSessionWarm(false)
        check(failures.isEmpty()) { failures.joinToString("\n") }
    }

    private companion object {
        const val TAG = "MANUSCRIPT_ESSAY_BATCH"
        const val TIMEOUT_MINUTES = 12L
        const val SECTION_TIMEOUT_MS = 90_000L
    }
}
