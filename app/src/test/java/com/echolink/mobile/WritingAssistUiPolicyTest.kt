package com.echolink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingAssistUiPolicyTest {
    @Test fun iconAppearsOnlyForFocusedNonBlankWriting() {
        assertFalse(WritingAssistUiPolicy.shouldShowIcon(editorHasFocus = false, text = "Words"))
        assertFalse(WritingAssistUiPolicy.shouldShowIcon(editorHasFocus = true, text = "   "))
        assertTrue(WritingAssistUiPolicy.shouldShowIcon(editorHasFocus = true, text = "Words"))
    }

    @Test fun cardNeedsClickSuggestionAndCurrentEditorFocus() {
        assertFalse(WritingAssistUiPolicy.shouldShowCard(true, false, true))
        assertFalse(WritingAssistUiPolicy.shouldShowCard(true, true, false))
        assertFalse(WritingAssistUiPolicy.shouldShowCard(false, true, true))
        assertTrue(WritingAssistUiPolicy.shouldShowCard(true, true, true))
    }

    @Test fun launcherIsVisuallySmallButKeepsAnErgonomicTouchTarget() {
        assertTrue(WritingAssistUiPolicy.ICON_VISUAL_SIZE_DP in 24..40)
        assertTrue(WritingAssistUiPolicy.ICON_TOUCH_TARGET_DP >= 48)
        assertTrue(WritingAssistUiPolicy.SENTENCES_PER_SUGGESTION in 1..3)
    }
}
