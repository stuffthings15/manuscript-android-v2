package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The small icon inside the editor sits at the text edge, so taps near it
 * land in the EditText and open the keyboard instead of the review. Two
 * people failed to open review that way, so a full-width button is the
 * primary entry point and the card must land on screen by itself.
 */
class ReviewEntryPointSourceTest {
    private val mainRoot = File(
        checkNotNull(System.getProperty("echoLinkAndroidDir")),
        "app/src/main",
    )
    private val activity = File(
        mainRoot,
        "java/com/echolink/mobile/MainActivity.kt",
    ).readText()
    private val strings = File(mainRoot, "res/values/strings.xml").readText()

    @Test
    fun writePageHasAFullWidthCorrectButton() {
        assertTrue(activity.contains("correctButton = primaryButton(R.string.correct_writing_action)"))
        assertTrue(activity.contains("id = R.id.correct_writing_action"))
        assertTrue(activity.contains("content.addView(correctButton, matchWidth("))
        assertTrue(strings.contains("<string name=\"correct_writing_action\">"))
    }

    @Test
    fun correctButtonOpensReviewAndTracksAvailableText() {
        assertTrue(
            activity.contains(
                "if (editor.text?.isNotBlank() == true) openReview() else editor.requestFocus()",
            ),
        )
        assertTrue(activity.contains("correctButton.isEnabled = hasText && !cardVisible"))
    }

    @Test
    fun openingReviewScrollsTheCardIntoView() {
        assertTrue(
            activity.contains("mainScroll?.smoothScrollTo(0, correctionCard.top.coerceAtLeast(0))"),
        )
    }

    @Test
    fun guidanceNamesTheButtonNotTheIcon() {
        assertTrue(strings.contains("Then tap Correct my writing."))
        assertTrue(strings.contains("then tap Correct my writing."))
    }
}
