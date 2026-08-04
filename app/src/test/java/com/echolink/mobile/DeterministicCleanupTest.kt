package com.echolink.mobile

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicCleanupTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun matchesStableExpandedDesktopCleanupCorpus() {
        val androidRoot = File(requireNotNull(System.getProperty("echoLinkAndroidDir")))
        val corpusFile = File(androidRoot, "docs/expanded_speech_cleanup.json").canonicalFile
        val corpus = json.parseToJsonElement(corpusFile.readText()).jsonObject
        val cases = corpus.getValue("cases").jsonArray.map { it.jsonObject }

        assertTrue(cases.size >= 14)
        cases.forEach { case ->
            val id = case.getValue("id").jsonPrimitive.content
            val raw = case.getValue("raw").jsonPrimitive.content
            val expected = case.getValue("expected").jsonPrimitive.content
            assertEquals(id, expected, DeterministicCleanup.clean(raw).text)
        }
    }

    @Test
    fun matchesEveryAuthoritativeV1DictationCleanupGolden() {
        val root = File(requireNotNull(System.getProperty("echoLinkContractDir")))
        val fixture = json.parseToJsonElement(File(root, "fixtures/golden.json").readText()).jsonObject
        val cases = fixture.getValue("cases").jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("operation").jsonPrimitive.content == "dictation_cleanup" }

        assertEquals(2, cases.size)
        cases.forEach { case ->
            val input = case.getValue("input").jsonObject
            val vocabulary = input.getValue("vocabulary").jsonObject
                .mapValues { it.value.jsonPrimitive.content }
            val actual = DeterministicCleanup.clean(
                text = input.getValue("text").jsonPrimitive.content,
                vocabulary = vocabulary,
                appendTrailingSpace = input.getValue("append_trailing_space").jsonPrimitive.boolean,
            )
            val expected = case.getValue("expected_output").jsonObject
            assertEquals(case.getValue("id").jsonPrimitive.content, expected.getValue("text").jsonPrimitive.content, actual.text)
            assertEquals(expected.getValue("source").jsonPrimitive.content, actual.source)
            assertEquals(expected.getValue("changed").jsonPrimitive.boolean, actual.changed)
        }
    }

    @Test
    fun matchesAuthoritativeV1ProtectedTokenGolden() {
        val source = "Email jane@example.com at 2:30 from C:\\Work\\notes.txt about Echo-Link."
        val actual = DeterministicCleanup.protect(source, listOf("Echo-Link"))

        assertEquals(
            "Email ECHOLINKTOKEN1X at ECHOLINKTOKEN3X from ECHOLINKTOKEN0X about ECHOLINKTOKEN2X.",
            actual.text,
        )
        assertEquals(
            listOf(
                ProtectedToken("ECHOLINKTOKEN0X", "C:\\Work\\notes.txt"),
                ProtectedToken("ECHOLINKTOKEN1X", "jane@example.com"),
                ProtectedToken("ECHOLINKTOKEN2X", "Echo-Link"),
                ProtectedToken("ECHOLINKTOKEN3X", "2:30"),
            ),
            actual.tokens,
        )
        assertEquals(source, DeterministicCleanup.restore(actual.text, actual.tokens))
    }

    @Test
    fun removesOnlyStableFillersAndKeepsMeaningfulLike() {
        assertEquals(
            "I think this works.",
            DeterministicCleanup.clean("uh like, I think this works, you know").text,
        )
        assertEquals(
            "I like green apples.",
            DeterministicCleanup.clean("I like green apples").text,
        )
        assertEquals(
            "Open the file. Now save it.",
            DeterministicCleanup.clean("open the file [pause] now save it").text,
        )
    }

    @Test
    fun handlesNaturalBacktrackingWithoutAWritingModel() {
        assertEquals(
            "Send it monday.",
            DeterministicCleanup.clean("send it friday no wait monday").text,
        )
        assertEquals(
            "Meet me at three.",
            DeterministicCleanup.clean("meet me at two... actually, make that three").text,
        )
        assertEquals(
            "Keep the second draft.",
            DeterministicCleanup.clean("discard this scratch that keep the second draft").text,
        )
    }

    @Test
    fun formatsStableSpokenListForms() {
        assertEquals(
            "- Apples\n- Bananas\n- Oranges",
            DeterministicCleanup.clean("one apples two bananas three oranges").text,
        )
        assertEquals(
            "- Draft\n- Review\n- Publish",
            DeterministicCleanup.clean("first draft second review third publish").text,
        )
        assertEquals(
            "- Milk\n- Bread\n- Coffee",
            DeterministicCleanup.clean("bullet point milk bullet point bread bullet point coffee").text,
        )
        assertEquals(
            "- Buy milk\n- Buy bread\n- Buy coffee.",
            DeterministicCleanup.clean("buy milk new line buy bread new line buy coffee").text,
        )
    }

    @Test
    fun preservesProtectedValuesAcrossCapitalizationAndBoundaries() {
        val actual = DeterministicCleanup.clean(
            "the report at https://echo-link.local/docs is ready can you email jane.doe@example.com",
        )
        assertEquals(
            "The report at https://echo-link.local/docs is ready. Can you email jane.doe@example.com?",
            actual.text,
        )
        assertTrue(actual.text.contains("https://echo-link.local/docs"))
        assertTrue(actual.text.contains("jane.doe@example.com"))
    }

    @Test
    fun appliesVocabularyByLongestPhraseBeforeProtection() {
        val actual = DeterministicCleanup.clean(
            "um echo link echo link comma ready",
            mapOf("echo" to "ECHO", "echo link" to "Echo-Link"),
        )
        assertEquals("Echo-Link, ready.", actual.text)
    }

    @Test
    fun rejectsUnboundedContractInputs() {
        assertFails { DeterministicCleanup.clean("x".repeat(200_001)) }
        assertFails {
            DeterministicCleanup.clean(
                "hello",
                (0..1_000).associate { "spoken-$it" to "replacement-$it" },
            )
        }
        assertFails { DeterministicCleanup.clean("hello", mapOf("" to "replacement")) }
    }

    @Test
    fun coreHasNoAndroidStorageProcessOrNetworkDependency() {
        val androidRoot = File(requireNotNull(System.getProperty("echoLinkAndroidDir")))
        val source = File(
            androidRoot,
            "app/src/main/java/com/echolink/mobile/DeterministicCleanup.kt",
        ).readText()

        listOf("android.", "java.io", "java.net", "ProcessBuilder", "Runtime.getRuntime").forEach {
            assertFalse("Forbidden dependency: $it", source.contains(it))
        }
    }

    @Test
    fun keepsAReportingClauseAttachedToItsEmbeddedQuestion() {
        val source =
            "When Priya wrote “blue after midnight” did she mean the lanterns turn blue after 12:00 a.m., " +
                "or that blue lanterns cant be used after then?"
        val actual = DeterministicCleanup.clean(source).text

        assertFalse(actual.contains("”. Did"))
        assertTrue(actual.startsWith("When Priya wrote “blue after midnight” did she mean"))
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Expected bounded cleanup rejection.", failed)
    }
}
