package com.echolink.mobile

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gpt56FivePageCorpusContractTest {
    @Test
    fun sealedCorpusContainsFiveFullPagesAndEveryRequiredErrorClass() {
        val root = File(requireNotNull(System.getProperty("echoLinkAndroidDir")))
        val assetDirectory = File(root, "app/src/androidTest/assets/device_tests")
        val metadata = Json.parseToJsonElement(
            File(assetDirectory, "gpt56-five-page-all-tones-v2.json").readText(),
        ).jsonObject
        val source = File(
            assetDirectory,
            metadata.getValue("source_file").jsonPrimitive.content,
        ).readText()
        val reference = File(
            assetDirectory,
            metadata.getValue("proofread_reference_file").jsonPrimitive.content,
        ).readText()

        assertEquals("gpt-5.6-sol", metadata.getValue("generator_model").jsonPrimitive.content)
        assertEquals(5, metadata.getValue("page_count").jsonPrimitive.content.toInt())
        assertTrue(wordCount(source) >= 1_250)
        assertTrue(wordCount(reference) >= 1_200)
        assertEquals(5, Regex("(?m)^## Page [1-5]:").findAll(source).count())
        assertEquals(5, Regex("(?m)^## Page [1-5]:").findAll(reference).count())
        assertEquals(
            setOf(
                "grammar",
                "spelling",
                "vocabulary",
                "sentence_structure",
                "punctuation",
                "capitalization",
                "agreement",
                "formatting",
            ),
            metadata.getValue("error_classes").jsonArray
                .map { it.jsonPrimitive.content }
                .toSet(),
        )
        assertTrue(metadata.getValue("protected_facts").jsonArray.size >= 30)
        assertTrue(metadata.getValue("objective_error_inventory").jsonArray.size >= 75)
        assertFalse(source.contains('\u2014'))
        assertFalse(reference.contains('\u2014'))
    }

    @Test
    fun everyToneMustEarnPerfectIndependentGrade() {
        val root = File(requireNotNull(System.getProperty("echoLinkAndroidDir")))
        val metadata = Json.parseToJsonElement(
            File(
                root,
                "app/src/androidTest/assets/device_tests/gpt56-five-page-all-tones-v2.json",
            ).readText(),
        ).jsonObject

        assertEquals(23, metadata.getValue("required_tones").jsonArray.size)
        assertEquals(100, metadata.getValue("required_score_percent").jsonPrimitive.content.toInt())
        assertEquals(0, metadata.getValue("allowed_introduced_errors").jsonPrimitive.content.toInt())
        assertEquals(0, metadata.getValue("allowed_em_dashes").jsonPrimitive.content.toInt())
    }

    @Test
    fun sealedCampaignDefinesEveryToneAndRejectsAnyEmDash() {
        val root = File(requireNotNull(System.getProperty("echoLinkAndroidDir")))
        val sealed = Json.parseToJsonElement(
            File(root, SEALED_CAMPAIGN_PATH).readText(),
        ).jsonObject
        val sha256 = sealed.getValue("sha256").jsonObject

        assertEquals(2, sealed.getValue("schema_version").jsonPrimitive.content.toInt())
        assertEquals(EXPECTED_DOCUMENT_ID, sealed.getValue("document_id").jsonPrimitive.content)
        assertEquals(EXPECTED_CORPUS_PATH, sealed.getValue("corpus_path").jsonPrimitive.content)
        assertEquals(EXPECTED_SOURCE_PATH, sealed.getValue("source_path").jsonPrimitive.content)
        assertEquals(EXPECTED_REFERENCE_PATH, sealed.getValue("reference_path").jsonPrimitive.content)
        assertEquals(EXPECTED_RUBRIC_PATH, sealed.getValue("rubric_path").jsonPrimitive.content)
        assertEquals(100, sealed.getValue("required_score_percent").jsonPrimitive.content.toInt())
        assertEquals(0, sealed.getValue("allowed_introduced_errors").jsonPrimitive.content.toInt())
        assertEquals(0, sealed.getValue("allowed_em_dashes").jsonPrimitive.content.toInt())
        assertEquals(EXPECTED_TONE_ORDER, sealed.getValue("tone_order").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(EXPECTED_CORPUS_SHA256, sha256.getValue("corpus").jsonPrimitive.content)
        assertEquals(EXPECTED_SOURCE_SHA256, sha256.getValue("source").jsonPrimitive.content)
        assertEquals(EXPECTED_REFERENCE_SHA256, sha256.getValue("reference").jsonPrimitive.content)
        assertEquals(EXPECTED_RUBRIC_SHA256, sha256.getValue("rubric").jsonPrimitive.content)

        val corpusPath = sealed.getValue("corpus_path").jsonPrimitive.content
        val sourcePath = sealed.getValue("source_path").jsonPrimitive.content
        val referencePath = sealed.getValue("reference_path").jsonPrimitive.content
        val rubricPath = sealed.getValue("rubric_path").jsonPrimitive.content
        val rubrics = Json.parseToJsonElement(File(root, rubricPath).readText()).jsonObject
        val requiredTones = sealed.getValue("tone_order").jsonArray
            .map { it.jsonPrimitive.content }

        assertEquals(EXPECTED_CORPUS_SHA256, sha256Of(File(root, corpusPath)))
        assertEquals(EXPECTED_SOURCE_SHA256, sha256Of(File(root, sourcePath)))
        assertEquals(EXPECTED_REFERENCE_SHA256, sha256Of(File(root, referencePath)))
        assertEquals(EXPECTED_RUBRIC_SHA256, sha256Of(File(root, rubricPath)))
        assertEquals(23, requiredTones.size)
        assertEquals(requiredTones.size, requiredTones.toSet().size)
        assertEquals(WritingTone.entries.map(WritingTone::name).toSet(), requiredTones.toSet())
        assertEquals(requiredTones.toSet(), rubrics.keys)
        rubrics.forEach { (_, element) ->
            val rubric = element.jsonObject
            assertEquals(
                EXPECTED_OBJECTIVE_CORRECTNESS_GATE,
                rubric.getValue("objective_correctness_gate").jsonPrimitive.content,
            )
            assertEquals(3, rubric.getValue("acceptance_criteria").jsonArray.size)
        }
        assertFalse(File(root, sourcePath).readText().contains('\u2014'))
        assertFalse(File(root, referencePath).readText().contains('\u2014'))
    }

    private fun wordCount(value: String): Int =
        value.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    private fun sha256Of(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val SEALED_CAMPAIGN_PATH = "quality/gpt56/sealed-campaign-v2.json"
        const val EXPECTED_DOCUMENT_ID = "northstar-five-page-all-tones-sealed-v2"
        const val EXPECTED_CORPUS_PATH = "app/src/androidTest/assets/device_tests/gpt56-five-page-all-tones-v2.json"
        const val EXPECTED_SOURCE_PATH = "app/src/androidTest/assets/device_tests/gpt56-five-page-errors-v2.md"
        const val EXPECTED_REFERENCE_PATH = "app/src/androidTest/assets/device_tests/gpt56-five-page-proofread-reference-v2.md"
        const val EXPECTED_RUBRIC_PATH = "quality/gpt56/gpt56-five-page-tone-rubrics-v2.json"
        const val EXPECTED_CORPUS_SHA256 = "a2aca4386415a30df8f97320051529002011a17cc5f1110d168a0191f8756e78"
        const val EXPECTED_SOURCE_SHA256 = "4358a8521631c2d962de3ffb7a55f7ea04afdc1b95c2eedbffb0ea641c656dd9"
        const val EXPECTED_REFERENCE_SHA256 = "7a82fec7bf4637ad80494152c8ec30cc09047d26238f03b768296941b8557fda"
        const val EXPECTED_RUBRIC_SHA256 = "29167bc4c1ce6e0fcf2e174de5bc2e87240e35b67bc84a0788e07a42954c0d79"
        const val EXPECTED_OBJECTIVE_CORRECTNESS_GATE =
            "Correct every planted objective error, introduce zero objective errors, preserve every protected fact and operational meaning, preserve required formatting, and use zero em dashes before tone is evaluated."
        val EXPECTED_TONE_ORDER = listOf(
            "PROOFREAD", "IMPROVE", "REPHRASE", "SHORTEN", "CONCISE",
            "SIMPLIFIED", "HIGH_SCHOOL", "CAVEMAN",
            "FORMAL", "PROFESSIONAL", "ACADEMIC", "COLLEGE", "TECHNICAL", "DETAILED",
            "FRIENDLY", "CONVERSATIONAL", "CASUAL", "COOPERATIVE", "EMPATHETIC", "DIPLOMATIC",
            "PERSUASIVE", "CONFIDENT", "ASSERTIVE",
        )
    }
}
