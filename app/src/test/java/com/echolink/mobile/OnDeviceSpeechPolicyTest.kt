package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceSpeechPolicyTest {
    @Test
    fun requiresAndroidTwelveAndAnOnDeviceService() {
        val oldDevice = OnDeviceSpeechPolicy.availability(30, true)
        val missingService = OnDeviceSpeechPolicy.availability(31, false)
        val ready = OnDeviceSpeechPolicy.availability(31, true)

        assertTrue(oldDevice is SpeechCaptureState.Unavailable)
        assertTrue(missingService is SpeechCaptureState.Unavailable)
        assertEquals(SpeechCaptureState.Ready, ready)
        assertFalse(oldDevice.canStart)
        assertTrue(ready.canStart)
    }

    @Test
    fun acceptsOnlyTheBestNonBlankTranscriptForReview() {
        assertEquals("first result", OnDeviceSpeechPolicy.bestTranscript(listOf("  first result  ", "other")))
        assertNull(OnDeviceSpeechPolicy.bestTranscript(listOf("  ")))
        assertNull(OnDeviceSpeechPolicy.bestTranscript(null))
    }

    @Test
    fun finalSpeechRemainsAReviewDraft() {
        val review = SpeechCaptureState.Review("draft text")

        assertEquals("draft text", review.transcript)
        assertTrue(review.canStart)
        assertFalse(review.canStop)
    }

    @Test
    fun languagePackMatchingAcceptsRegionalVariantsButNotAnotherLanguage() {
        assertTrue(OnDeviceSpeechPolicy.languageMatches("en-US", listOf("en-GB")))
        assertTrue(OnDeviceSpeechPolicy.languageMatches("fr-CA", listOf("fr-CA")))
        assertFalse(OnDeviceSpeechPolicy.languageMatches("en-US", listOf("de-DE")))
    }

    @Test
    fun missingLanguageModelHasAnExplicitLocalError() {
        assertEquals(
            "The on-device language model is not installed.",
            OnDeviceSpeechPolicy.errorMessage(13),
        )
    }
}
