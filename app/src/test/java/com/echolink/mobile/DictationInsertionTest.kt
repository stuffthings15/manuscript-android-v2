package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationInsertionTest {

    @Test
    fun insertsIntoEmptyEditor() {
        val result = DictationInsertion.insert(
            existing = "",
            selectionStart = 0,
            selectionEnd = 0,
            rawTranscript = "hello world",
        )
        assertEquals("Hello world.", result.text)
        assertEquals(result.text.length, result.cursor)
    }

    @Test
    fun appendsWithSpaceAfterExistingText() {
        val existing = "The report is done."
        val result = DictationInsertion.insert(
            existing = existing,
            selectionStart = existing.length,
            selectionEnd = existing.length,
            rawTranscript = "send it tomorrow",
        )
        assertEquals("The report is done. Send it tomorrow.", result.text)
        assertEquals(result.text.length, result.cursor)
    }

    @Test
    fun replacesSelectionAndKeepsSurroundingSpacing() {
        val existing = "Alpha OLD TEXT omega"
        val result = DictationInsertion.insert(
            existing = existing,
            selectionStart = 6,
            selectionEnd = 14,
            rawTranscript = "bravo charlie",
        )
        assertEquals("Alpha Bravo charlie. omega", result.text)
        assertEquals("Alpha Bravo charlie.".length, result.cursor)
    }

    @Test
    fun spokenPunctuationAndFillersAreCleaned() {
        val result = DictationInsertion.insert(
            existing = "",
            selectionStart = 0,
            selectionEnd = 0,
            rawTranscript = "um send the invoice period",
        )
        assertEquals("Send the invoice.", result.text)
    }

    @Test
    fun emptyTranscriptChangesNothing() {
        val result = DictationInsertion.insert(
            existing = "Keep me.",
            selectionStart = 3,
            selectionEnd = 3,
            rawTranscript = "   ",
        )
        assertEquals("Keep me.", result.text)
        assertEquals(3, result.cursor)
    }

    @Test
    fun outOfRangeSelectionIsClamped() {
        val result = DictationInsertion.insert(
            existing = "Hi",
            selectionStart = 90,
            selectionEnd = 5,
            rawTranscript = "there",
        )
        assertEquals("Hi There.", result.text)
    }

    @Test
    fun vocabularyIsApplied() {
        val result = DictationInsertion.insert(
            existing = "",
            selectionStart = 0,
            selectionEnd = 0,
            rawTranscript = "email echo link support",
            vocabulary = mapOf("echo link" to "Echo-Link"),
        )
        assertEquals("Email Echo-Link support.", result.text)
    }
}
