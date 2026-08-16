package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expanded review from the GUI build manual, and the rules that keep it
 * from becoming a second, disagreeing review.
 */
class ExpandedReviewSheetSourceTest {
    private val mainRoot = File(
        System.getProperty("echoLinkAndroidDir") ?: ".",
        "app/src/main",
    )

    private fun source(name: String): String =
        File(mainRoot, "java/com/echolink/mobile/$name").readText()

    /**
     * The source with its comments removed.
     *
     * A rule about what the code does must be checked against code. This test
     * first failed on the sheet's own KDoc, which names ReviewSurfaceController
     * in the course of explaining that the sheet deliberately does not touch
     * it. Reading prose as if it were code would make the honest comment the
     * thing that breaks the build.
     */
    private fun codeOf(name: String): String = source(name)
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(?m)//.*$"""), "")

    @Test
    fun theSheetCarriesTheManualsStructure() {
        val sheet = source("ExpandedReviewSheet.kt")

        // Drag handle, navigator with index of total, stacked original and
        // suggested, Apply and Dismiss.
        listOf(
            "expanded_review_handle",
            "expanded_review_navigator",
            "expanded_review_original",
            "expanded_review_suggested",
            "expanded_review_apply",
            "expanded_review_dismiss",
        ).forEach { id ->
            assertTrue("Sheet is missing $id", sheet.contains(id))
        }
        assertTrue(sheet.contains("R.string.review_counter"))
        assertTrue(sheet.contains("R.string.original_label"))
        assertTrue(sheet.contains("R.string.suggestion_label"))
    }

    @Test
    fun theHandleCanBeHitByAFinger() {
        // The bar is 4 dp. If the pressable row were 4 dp too, the manual's own
        // touch target rule would be broken by the control that opens and
        // closes the whole surface.
        val sheet = source("ExpandedReviewSheet.kt")
        assertTrue(
            "The handle must be a full touch target, not just the visible bar",
            sheet.contains("minimumHeight = context.manuscriptDp(ManuscriptMetrics.TOUCH_TARGET_DP)"),
        )
    }

    @Test
    fun theSheetOwnsNoReviewState() {
        val sheet = codeOf("ExpandedReviewSheet.kt")
        // It draws and reports taps. The session and ReviewSurfaceController
        // stay the single answer to what is under review, so a correction
        // cannot be accepted in one view and still pending in the other.
        assertFalse(sheet.contains("WritingReviewSession"))
        assertFalse(sheet.contains("reviewSession"))
        assertFalse(sheet.contains("ReviewSurfaceController"))
    }

    @Test
    fun everySheetActionIsTheCardsOwnAction() {
        val activity = source("MainActivity.kt")
        // Same functions the compact card calls. A sheet with its own copy of
        // accept would be a second implementation of the thing most worth
        // getting right.
        assertTrue(activity.contains("onApply = { replaceCurrentBlock() }"))
        assertTrue(activity.contains("onDismiss = { dismissCurrentBlock() }"))
        assertTrue(activity.contains("onUndo = { undoReviewAction() }"))
    }

    @Test
    fun onlyOneOfTheTwoViewsIsEverShown() {
        val activity = codeOf("MainActivity.kt")
        // Assert the behaviour, not the punctuation. This first pinned two
        // exact lines and broke the moment the swap was rewritten to hand
        // focus over, while the rule it exists to protect never changed.
        val swap = activity.substringAfter("private fun setReviewExpanded(")
            .substringBefore("private fun ")
        listOf(
            "sheet.visibility = View.VISIBLE",
            "correctionCard.visibility = View.GONE",
            "correctionCard.visibility = View.VISIBLE",
            "sheet.visibility = View.GONE",
        ).forEach { line ->
            assertTrue("setReviewExpanded must contain: $line", swap.contains(line))
        }
        // Focus is handed over rather than dropped. A hidden focused view
        // sends focus wherever the tree likes next, and the focus listener
        // reads an unrecognised destination as the writing target being lost,
        // which closed the whole review when the sheet was first wired up.
        assertTrue(swap.contains("sheet.requestFocus()"))
        assertTrue(swap.contains("correctionCard.requestFocus()"))
        assertTrue(
            "The focus listener must know the sheet is part of the review",
            activity.contains("insideSheet"),
        )
        // And a review always opens compact, or a closed expanded sheet comes
        // back alongside the card on the next open.
        assertTrue(activity.contains("reviewExpanded = false"))
    }

    @Test
    fun noSecondaryActionIsDrawnWithoutSomethingBehindIt() {
        val sheet = codeOf("ExpandedReviewSheet.kt")
        // The manual names four secondary actions. Add to Dictionary needs an
        // ignore store and a correction-pipeline change, and Report needs a
        // destination this product deliberately does not have. Neither is
        // drawn as a button that does nothing.
        assertFalse(sheet.contains("add_to_dictionary"))
        assertFalse(sheet.contains("expanded_review_report"))
    }
}
