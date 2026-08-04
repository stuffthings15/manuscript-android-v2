package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSurfaceHostIntegrationSourceTest {
    @Test
    fun mainActivityRoutesReviewLifecycleThroughSharedExecutor() {
        val source = source("MainActivity.kt")

        assertTrue(source.contains("ReviewSurfaceEffectExecutor"))
        assertTrue(source.contains("ReviewSurfaceEvent.OpenRequested"))
        assertTrue(source.contains("ReviewSurfaceEvent.CloseRequested"))
        assertTrue(source.contains("ReviewSurfaceEvent.Destroy"))
        assertTrue(source.contains("mainEditorInvalidationEvent"))
        assertTrue(source.contains("eligibility != ReviewTargetEligibility.ELIGIBLE"))
        assertEquals(3, Regex("beginMainReviewSurface\\(\\)").findAll(source).count())
        assertTrue(source.contains("onClose = { closeReview() }"))
        assertTrue(source.contains("invalidateMainReviewSurface(ReviewSurfaceEvent.TargetLost)"))
        assertFalse(Regex("closeReview\\(\\)\\s*editor\\.requestFocus").containsMatchIn(source))
        assertFalse(Regex("closeReview\\(\\)\\s*editor\\.post\\s*\\{\\s*showSystemKeyboard").containsMatchIn(source))
    }

    @Test
    fun accessibilityServiceValidatesRealTargetsWhilePanelIsOpen() {
        val source = source("ManuscriptAccessibilityService.kt")

        assertFalse(source.contains("event == null || panelView != null"))
        assertTrue(source.contains("isOverlaySelfEvent"))
        assertTrue(source.contains("ReviewSurfaceEvent.PasswordFocused"))
        assertTrue(source.contains("ReviewSurfaceEvent.ApplicationSwitched"))
        assertTrue(source.contains("ReviewSurfaceEvent.TargetLost"))
        assertTrue(source.contains("ReviewSurfaceEvent.ServiceReconnected"))
        assertTrue(source.contains("synchronizeReviewSurfaceTarget(candidate)"))
        assertTrue(source.contains("currentReviewTargetId()"))
        assertTrue(source.contains("reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)"))
        assertTrue(source.contains("replacementVerificationWork.cancelAll()"))
        assertFalse(source.contains("candidate.node.hashCode()"))
        assertTrue(source.contains("ExternalFieldIdentity.from("))
        assertEquals(2, Regex("reacquireReviewTarget\\(").findAll(source).count())
    }

    @Test
    fun accessibilityServiceUsesSharedExecutorForCloseAndDestroy() {
        val source = source("ManuscriptAccessibilityService.kt")

        assertTrue(source.contains("ReviewSurfaceEffectExecutor"))
        assertTrue(source.contains("ReviewSurfaceEvent.CloseRequested"))
        assertTrue(source.contains("ReviewSurfaceEvent.Destroy"))
    }

    private fun source(name: String): String = File(
        System.getProperty("user.dir"),
        "src/main/java/com/echolink/mobile/$name",
    ).readText()
}
