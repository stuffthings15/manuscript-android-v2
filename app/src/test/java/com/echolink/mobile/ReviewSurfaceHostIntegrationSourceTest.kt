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
        assertTrue(source.contains("structuralLocator = AccessibilityNodeStructuralLocator.from("))
        assertTrue(source.contains("resolvedApplicationRoot"))
        assertTrue(source.contains("val reviewTargetId: ReviewTargetId"))
        assertFalse(source.contains("private fun reviewTargetId(candidate"))
        assertTrue(source.contains("FocusedReviewTargetPolicy.resolveEligibility("))
        assertTrue(source.contains("verifiedApplicationEventContext(event)"))
        val packageGuard = source.indexOf("verifiedApplicationEventContext(event)")
        val focusedLookup = source.indexOf("val node = focusedNodeProvider()")
        assertTrue(packageGuard >= 0)
        assertTrue(packageGuard < focusedLookup)
        assertTrue(source.contains("cancelTargetClear()"))
    }

    @Test
    fun accessibilityServiceUsesSharedExecutorForCloseAndDestroy() {
        val source = source("ManuscriptAccessibilityService.kt")

        assertTrue(source.contains("ReviewSurfaceEffectExecutor"))
        assertTrue(source.contains("ReviewSurfaceEvent.CloseRequested"))
        assertTrue(source.contains("ReviewSurfaceEvent.Destroy"))
    }

    @Test
    fun onlyTheAccessibilityResolverConstructsProvenStructuralLocatorsInProduction() {
        val sourceRoot = File(
            System.getProperty("user.dir"),
            "src/main/java/com/echolink/mobile",
        )
        val callers = sourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filter { file -> file.readText().contains("FieldStructuralLocator.proven(") }
            .map(File::getName)
            .toList()

        assertEquals(listOf("AccessibilityNodeStructuralLocator.kt"), callers)
    }

    @Test
    fun verifiedApplicationEventsUseOnlyTheirScopedRoot() {
        val source = source("ManuscriptAccessibilityService.kt")

        assertTrue(source.contains("verifiedApplicationEventContext(event)?.use { context ->"))
        assertTrue(source.contains("focusedEditableNode(context.applicationRoot, context.metadata)"))
        assertTrue(source.contains("VerifiedApplicationEventPolicy.acceptsFocus("))
        assertTrue(source.contains("applicationRoot = verifiedApplicationRoot"))
        assertTrue(source.contains("if (matchingWindows.size != 1) return null"))
        assertTrue(source.contains("processFocusedEvent(eventPackage) { focusedEditableNode() }"))
        assertTrue(source.contains("if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return"))
        val verifiedStart = source.indexOf("verifiedApplicationEventContext(event)?.use { context ->")
        val legacyStart = source.indexOf("processFocusedEvent(eventPackage) { focusedEditableNode() }")
        assertTrue(verifiedStart >= 0)
        assertTrue(legacyStart > verifiedStart)
        val verifiedBranch = source.substring(verifiedStart, legacyStart)
        assertFalse(verifiedBranch.contains("rootInActiveWindow"))
        assertFalse(verifiedBranch.contains("focusedEditableNode()"))
        val eventSlice = source.substring(
            source.indexOf("override fun onAccessibilityEvent"),
            source.indexOf("override fun onInterrupt"),
        )
        assertFalse(eventSlice.contains("rootInActiveWindow"))
        assertFalse(eventSlice.contains("focusedEditableNode()"))
    }

    @Test
    fun deviceActivePackageRecyclesTheBorrowedActiveRoot() {
        val source = androidTestSource("ExternalEditorLifecycleFixtureDeviceTest.kt")
        val activePackage = Regex(
            "private fun activePackage\\(\\): String \\{[\\s\\S]*?\\n    \\}",
        ).find(source)?.value.orEmpty()

        assertTrue(activePackage.contains("try"))
        assertTrue(activePackage.contains("finally"))
        assertTrue(activePackage.contains("recycleDeviceNode(root)"))
    }

    private fun source(name: String): String = File(
        System.getProperty("user.dir"),
        "src/main/java/com/echolink/mobile/$name",
    ).readText()

    private fun androidTestSource(name: String): String = File(
        System.getProperty("user.dir"),
        "src/androidTest/java/com/echolink/mobile/$name",
    ).readText()
}
