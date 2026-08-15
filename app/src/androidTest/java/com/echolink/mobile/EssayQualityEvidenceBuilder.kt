package com.echolink.mobile

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class EssayQualityModelEvidence(
    val identifier: String,
    val tier: String,
    val backend: String,
    val sha256: String,
)

object EssayQualityEvidenceBuilder {
    fun build(
        essayId: String,
        tone: WritingTone,
        source: String,
        output: String,
        acceptedSections: Int,
        sectionDurationsMs: Map<Int, Long>,
        liveAiDetails: Map<Int, LocalAiSectionAttemptEvidence>,
        productPackage: String,
        productApkSha256: String,
        model: EssayQualityModelEvidence,
        totalDurationMs: Long,
    ): JSONObject {
        val sourceSections = sections(source)
        val outputSections = sections(output)
        val reportFailures = mutableListOf<String>()
        // The document decides how many sections it has. This used to require
        // exactly ten, which was the shape of the animal-pet corpus rather than
        // anything the product guarantees, so every essay in a corpus of
        // thirteen to nineteen sections failed on section count alone and the
        // real result was never reached. What must hold is that the output
        // kept the writer's sections and that every one of them was accepted.
        val expectedSections = sourceSections.size
        if (expectedSections == 0) reportFailures += "source_section_count"
        if (outputSections.size != expectedSections) reportFailures += "output_section_count"
        if ('\u2014' in output) reportFailures += "em_dash"
        if (acceptedSections != expectedSections) reportFailures += "accepted_section_count"

        val sectionReports = JSONArray()
        for (offset in 0 until maxOf(sourceSections.size, outputSections.size)) {
            val index = offset + 1
            val sourceSection = sourceSections.getOrNull(offset).orEmpty()
            val outputSection = outputSections.getOrNull(offset).orEmpty()
            val detail = liveAiDetails[index]
            val sectionFailures = mutableListOf<String>()
            if (sourceSection.isBlank() || outputSection.isBlank()) sectionFailures += "blank"
            if ((detail?.attempts ?: 0) < 1) sectionFailures += "live_ai_attempt"
            val provenance = detail?.terminalProvenance?.wireValue.orEmpty()
            if (
                provenance !in setOf(
                    LocalAiTerminalProvenance.MODEL_ACCEPTED.wireValue,
                    LocalAiTerminalProvenance.MODEL_PLUS_SAFE_REPAIRS.wireValue,
                )
            ) {
                sectionFailures += "terminal_provenance"
            }
            if (sourceSection.isNotEmpty() && sha256(sourceSection) == sha256(outputSection)) {
                sectionFailures += "known_error_unchanged"
            }
            if (sectionFailures.isNotEmpty()) {
                reportFailures += sectionFailures.map { "section_${index}_$it" }
            }
            sectionReports.put(
                JSONObject()
                    .put("index", index)
                    .put("nonblank", outputSection.isNotBlank())
                    .put("live_ai_attempts", detail?.attempts ?: 0)
                    .put("terminal_provenance", provenance)
                    .put(
                        "terminal_state",
                        if (outputSection.isNotBlank()) "valid_text" else "retry",
                    )
                    .put("accepted", index <= acceptedSections)
                    .put("source_sha256", sha256(sourceSection))
                    .put("output_sha256", sha256(outputSection))
                    .put("duration_ms", sectionDurationsMs[index] ?: 0L)
                    .put("hard_gate_failures", JSONArray(sectionFailures)),
            )
        }

        return JSONObject()
            .put("schema_version", 1)
            .put("essay_id", essayId)
            .put("tone", tone.name)
            .put("source_has_known_errors", true)
            .put("source_sha256", sha256(source))
            .put("fixture_sha256", sha256(source))
            .put("output_sha256", sha256(output))
            .put("section_count", sourceSections.size)
            .put("accepted_section_count", acceptedSections)
            .put(
                "product",
                JSONObject()
                    .put("package", productPackage)
                    .put("apk_sha256", productApkSha256.lowercase()),
            )
            .put(
                "model",
                JSONObject()
                    .put("identifier", model.identifier)
                    .put("tier", model.tier)
                    .put("backend", model.backend)
                    .put("sha256", model.sha256.lowercase()),
            )
            .put("sections", sectionReports)
            .put("hard_gate_failures", JSONArray(reportFailures.distinct()))
            .put("total_duration_ms", totalDurationMs)
    }

    private fun sections(text: String): List<String> = text
        .split(Regex("\\n\\s*\\n"))
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
