package com.echolink.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EssayQualityEvidenceBuilderDeviceTest {
    @Test
    fun buildsContentFreeTenSectionLiveModelReceipt() {
        val source = (1..10).joinToString("\n\n") { "Broken section $it" }
        val output = (1..10).joinToString("\n\n") { "Corrected section $it." }
        val details = (1..10).associateWith { index ->
            LocalAiSectionAttemptEvidence(
                attempts = index,
                terminalProvenance = if (index == 4) {
                    LocalAiTerminalProvenance.MODEL_PLUS_SAFE_REPAIRS
                } else {
                    LocalAiTerminalProvenance.MODEL_ACCEPTED
                },
            )
        }
        val report = EssayQualityEvidenceBuilder.build(
            essayId = "01_Dogs_in_Bakersfield_USA",
            tone = WritingTone.PROOFREAD,
            source = source,
            output = output,
            acceptedSections = 10,
            sectionDurationsMs = (1..10).associateWith { it * 100L },
            liveAiDetails = details,
            productPackage = "com.remote.Manuscript.v2.debug",
            productApkSha256 = "285f68e4c4311f24a180c4a1831d23d7e8d372b498d40020e3e0fdd49828986c",
            model = EssayQualityModelEvidence(
                identifier = "local-model.gguf",
                tier = "BALANCED",
                backend = "LEGACY_LLAMA_CPU",
                sha256 = "f".repeat(64),
            ),
            totalDurationMs = 5_500L,
        )

        assertEquals(1, report.getInt("schema_version"))
        assertEquals(10, report.getInt("section_count"))
        assertEquals(10, report.getInt("accepted_section_count"))
        assertTrue(report.getBoolean("source_has_known_errors"))
        assertEquals(10, report.getJSONArray("sections").length())
        assertEquals(4, report.getJSONArray("sections").getJSONObject(3).getInt("live_ai_attempts"))
        assertEquals(
            "model_plus_safe_repairs",
            report.getJSONArray("sections").getJSONObject(3).getString("terminal_provenance"),
        )
        assertEquals(0, report.getJSONArray("hard_gate_failures").length())
        assertEquals(64, report.getString("output_sha256").length)
        assertTrue(report.toString().contains("Broken section").not())
        assertTrue(report.toString().contains("Corrected section").not())
    }

    /**
     * A document is not required to have ten sections. The second corpus runs
     * thirteen to nineteen, and while the builder hardcoded ten every essay in
     * it failed on section count before its real quality was ever measured.
     */
    @Test
    fun buildsAReceiptForADocumentThatIsNotTenSections() {
        val sectionCount = 18
        val source = (1..sectionCount).joinToString("\n\n") { "Broken section $it" }
        val output = (1..sectionCount).joinToString("\n\n") { "Corrected section $it." }
        val details = (1..sectionCount).associateWith {
            LocalAiSectionAttemptEvidence(
                attempts = 1,
                terminalProvenance = LocalAiTerminalProvenance.MODEL_ACCEPTED,
            )
        }
        val report = EssayQualityEvidenceBuilder.build(
            essayId = "01_urban_beekeeping_and_the_ecology_of_cities",
            tone = WritingTone.PROOFREAD,
            source = source,
            output = output,
            acceptedSections = sectionCount,
            sectionDurationsMs = (1..sectionCount).associateWith { 100L },
            liveAiDetails = details,
            productPackage = "com.remote.Manuscript.v2.debug",
            productApkSha256 = "285f68e4c4311f24a180c4a1831d23d7e8d372b498d40020e3e0fdd49828986c",
            model = EssayQualityModelEvidence(
                identifier = "local-model.gguf",
                tier = "BALANCED",
                backend = "LEGACY_LLAMA_CPU",
                sha256 = "f".repeat(64),
            ),
            totalDurationMs = 9_000L,
        )

        assertEquals(sectionCount, report.getInt("section_count"))
        assertEquals(sectionCount, report.getInt("accepted_section_count"))
        assertEquals(sectionCount, report.getJSONArray("sections").length())
        assertEquals(0, report.getJSONArray("hard_gate_failures").length())
    }

    /** A section the app failed to accept still has to fail, at any length. */
    @Test
    fun failsWhenASectionOfANonTenSectionDocumentWasNotAccepted() {
        val sectionCount = 14
        val source = (1..sectionCount).joinToString("\n\n") { "Broken section $it" }
        val output = (1..sectionCount).joinToString("\n\n") { "Corrected section $it." }
        val report = EssayQualityEvidenceBuilder.build(
            essayId = "08_cybersecurity_for_small_businesses",
            tone = WritingTone.PROOFREAD,
            source = source,
            output = output,
            acceptedSections = sectionCount - 1,
            sectionDurationsMs = (1..sectionCount).associateWith { 100L },
            liveAiDetails = (1..sectionCount).associateWith {
                LocalAiSectionAttemptEvidence(
                    attempts = 1,
                    terminalProvenance = LocalAiTerminalProvenance.MODEL_ACCEPTED,
                )
            },
            productPackage = "com.remote.Manuscript.v2.debug",
            productApkSha256 = "285f68e4c4311f24a180c4a1831d23d7e8d372b498d40020e3e0fdd49828986c",
            model = EssayQualityModelEvidence(
                identifier = "local-model.gguf",
                tier = "BALANCED",
                backend = "LEGACY_LLAMA_CPU",
                sha256 = "f".repeat(64),
            ),
            totalDurationMs = 9_000L,
        )

        val failures = report.getJSONArray("hard_gate_failures")
        val names = (0 until failures.length()).map { failures.getString(it) }
        assertTrue("accepted_section_count" in names)
    }
}
