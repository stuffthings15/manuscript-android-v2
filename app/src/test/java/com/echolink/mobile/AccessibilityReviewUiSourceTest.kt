package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityReviewUiSourceTest {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val service = File(
        androidRoot,
        "app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt",
    ).readText()
    private val strings = File(androidRoot, "app/src/main/res/values/strings.xml").readText()

    @Test
    fun overlayShowsReadableApprovalActionsAndScrollableInlineCorrections() {
        assertTrue(
            strings.contains(
                "<string name=\"writing_replace_action\">Accept paragraph</string>",
            ),
        )
        assertTrue(strings.contains("writing_accept_change_action\">Accept change</string>"))
        assertTrue(strings.contains("writing_reject_change_action\">Reject change</string>"))
        assertTrue(strings.contains("Swipe up anywhere to enlarge."))
        assertTrue(service.contains("id = R.id.writing_suggestion_scroll"))
        assertTrue(service.contains("id = R.id.writing_resize_handle"))
        assertTrue(service.contains("installPanelResizeGesture"))
        assertTrue(service.contains("ExpandableReviewPanel"))
        assertTrue(service.contains("requestAllModelCorrections(session)"))
        assertTrue(service.contains("params.y = currentSystemBottomInsets().protectedBottomPx"))
        assertTrue(service.contains("windowManager.updateViewLayout(panel, params)"))
        assertTrue(service.contains("session.pending(selectedTone)"))
        assertTrue(service.contains("session.select(block.index - 1)"))
        assertTrue(service.contains("StrikethroughSpan()"))
        assertTrue(service.contains("BackgroundColorSpan(COLOR_INSERTED_BACKGROUND)"))
        assertTrue(service.contains("InlineEditDiff.note"))
        assertTrue(service.contains("session.acceptChange"))
        assertTrue(service.contains("session.rejectChange"))
        assertTrue(service.contains("textSize = 14f"))
        assertTrue(service.contains("minHeight = dp(40)"))
    }

    @Test
    fun selectingAToneRegeneratesEveryVisibleCorrectionImmediately() {
        assertTrue(service.contains("private fun buildReviewToneMenu(): HorizontalScrollView"))
        assertTrue(service.contains("if (tone == selectedTone) return@setOnClickListener"))
        assertTrue(service.contains("renderCorrectionList(session, block)"))
        assertTrue(service.contains("session.pending(selectedTone)"))
        assertTrue(service.contains("regenerateModelCorrections()"))
    }
}
