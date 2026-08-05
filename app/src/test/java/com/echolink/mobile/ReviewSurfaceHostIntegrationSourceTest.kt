package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSurfaceHostIntegrationSourceTest {
    @Test
    fun triggerCreationDiagnosticsBoundAddAttachLayoutAndDrawWithoutContent() {
        val source = source("ManuscriptAccessibilityService.kt")
        val showStart = source.indexOf("private fun showTrigger(bounds: Rect)")
        val showEnd = source.indexOf("private fun visibleTriggerState", showStart)
        val showBody = source.substring(showStart, showEnd)
        assertTrue(showBody.contains("event=external-trigger-add-start"))
        assertTrue(showBody.contains("event=external-trigger-add-complete"))
        assertTrue(showBody.contains("event=external-trigger-first-layout"))
        assertTrue(showBody.contains("event=external-trigger-first-draw"))
        assertTrue(showBody.contains("removeOnDrawListener(this)"))
        assertFalse(showBody.contains("snapshot.text"))
        assertFalse(showBody.contains("workingText"))
        assertFalse(showBody.contains("contentDescription="))
    }

    @Test
    fun invalidatedPanelCloseRemovesTriggerBeforeDetachWhileUserCloseStillParksIt() {
        val source = source("ManuscriptAccessibilityService.kt")
        val renderStart = source.indexOf("override fun renderReviewSurfaceState")
        val renderEnd = source.indexOf("override fun recordLifecycleDiagnostic", renderStart)
        val renderBody = source.substring(renderStart, renderEnd)
        assertTrue(renderBody.contains("is ReviewSurfaceState.Closing"))
        assertTrue(renderBody.contains("state.retainedTarget.eligibility == ReviewTargetEligibility.ELIGIBLE"))
        assertTrue(renderBody.contains("parkTriggerWindowForReview()"))
        assertTrue(renderBody.contains("removeTrigger()"))
    }

    @Test
    fun retainedTriggerWindowIsRenderedInertAndReusedWithoutInterceptingInput() {
        val source = source("ManuscriptAccessibilityService.kt")
        val parkStart = source.indexOf("private fun parkTriggerWindowForReview()")
        val parkEnd = source.indexOf("private fun label", parkStart)
        assertTrue(parkStart >= 0)
        val parkBody = source.substring(parkStart, parkEnd)
        assertTrue(parkBody.contains("trigger.visibility = View.VISIBLE"))
        assertTrue(parkBody.contains("trigger.alpha = 0f"))
        assertTrue(parkBody.contains("trigger.isClickable = false"))
        assertTrue(
            parkBody.contains(
                "trigger.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS",
            ),
        )
        assertTrue(parkBody.contains("WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE"))
        assertTrue(parkBody.contains("windowManager.updateViewLayout(trigger, params)"))
        assertTrue(parkBody.contains("removeTrigger()"))

        val showStart = source.indexOf("private fun showTrigger(bounds: Rect)")
        val showEnd = source.indexOf("private fun publishVisibleTriggerAccessibilityAfterLayout", showStart)
        val showBody = source.substring(showStart, showEnd)
        val retainedStart = showBody.indexOf("val current = triggerView")
        val retainedEnd = showBody.indexOf("val button = ManuscriptTriggerViewFactory.create")
        val retainedBody = showBody.substring(retainedStart, retainedEnd)
        assertFalse(retainedBody.contains("addView"))
        assertTrue(retainedBody.contains("triggerWindowParams(bounds, touchable = true)"))
        assertTrue(retainedBody.contains("current.visibility = View.VISIBLE"))
        assertTrue(retainedBody.contains("current.alpha = ManuscriptTriggerPresentation.PULSE_ALPHA_FROM"))
        assertTrue(retainedBody.contains("current.isClickable = true"))
        assertTrue(
            retainedBody.contains(
                "current.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES",
            ),
        )
        assertTrue(retainedBody.contains("windowManager.updateViewLayout(current, params)"))
        assertTrue(retainedBody.contains("TriggerPulseController.applyState(current, state)"))
        assertTrue(retainedBody.contains("current.postInvalidateOnAnimation()"))
        val restoreVisible = retainedBody.indexOf("current.visibility = View.VISIBLE")
        val restoreAlpha = retainedBody.indexOf(
            "current.alpha = ManuscriptTriggerPresentation.PULSE_ALPHA_FROM",
        )
        val restoreAccessibility = retainedBody.indexOf(
            "current.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES",
        )
        val restoreClick = retainedBody.indexOf("current.isClickable = true")
        val restorePulse = retainedBody.indexOf("TriggerPulseController.applyState(current, state)")
        val invalidate = retainedBody.indexOf("current.postInvalidateOnAnimation()")
        val makeTouchable = retainedBody.indexOf("windowManager.updateViewLayout(current, params)")
        val publish = retainedBody.indexOf("publishVisibleTriggerAccessibility(current, state)")
        listOf(
            restoreVisible,
            restoreAlpha,
            restoreAccessibility,
            restoreClick,
            restorePulse,
            invalidate,
        ).forEach { restorationStep ->
            assertTrue(restorationStep >= 0)
            assertTrue(restorationStep < makeTouchable)
        }
        assertTrue(makeTouchable < publish)

        val clearStart = source.indexOf("private fun clearTarget()")
        val clearEnd = source.indexOf("private fun clearTargetRetaining", clearStart)
        assertTrue(source.substring(clearStart, clearEnd).contains("removeTrigger()"))
        assertTrue(source.contains("windowManager.removeViewImmediate(panel)"))
        assertTrue(source.contains("windowManager.removeViewImmediate(trigger)"))
    }

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

        assertTrue(source.contains("val applicationContext = verifiedApplicationEventContext(event)"))
        assertTrue(source.contains("applicationContext.use { context ->"))
        assertTrue(source.contains("focusedEditableNode(context.applicationRoot, context.metadata)"))
        assertTrue(source.contains("VerifiedApplicationEventPolicy.acceptsFocus("))
        assertTrue(source.contains("applicationRoot = verifiedApplicationRoot"))
        assertTrue(source.contains("if (matchingWindows.size != 1) return null"))
        assertTrue(source.contains("processFocusedEvent(eventPackage) { focusedEditableNode() }"))
        assertTrue(source.contains("if (event.eventType.isApplicationLifecycleEvent()) return"))
        val verifiedStart = source.indexOf("val applicationContext = verifiedApplicationEventContext(event)")
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
    fun serviceObservesVerifiedWindowAndContentLifecycleWithoutPanelBypass() {
        val source = source("ManuscriptAccessibilityService.kt")
        val config = File(
            System.getProperty("user.dir"),
            "src/main/res/xml/accessibility_service_config.xml",
        ).readText()

        assertTrue(config.contains("typeWindowContentChanged"))
        assertTrue(config.contains("typeWindowsChanged"))
        assertTrue(config.contains("android:notificationTimeout=\"0\""))
        assertTrue(source.contains("verifiedActiveApplicationEventContext()"))
        assertTrue(source.contains("if (!event.eventType.isApplicationLifecycleEvent()) return null"))
        assertTrue(source.contains("val applicationRoot = rootInActiveWindow ?: return null"))
        assertTrue(source.contains("window.id == applicationRoot.windowId"))
        assertTrue(source.contains("observeRetainedTargetLifecycle(context)"))
        assertTrue(source.contains("ReviewTargetLifecyclePolicy.action(evidence)"))
        assertTrue(source.contains("window.type == AccessibilityWindowInfo.TYPE_APPLICATION"))
        assertTrue(source.contains("window.isActive"))
        assertTrue(source.contains("matchingWindows.size != 1"))
        assertTrue(source.contains("windowManager.removeViewImmediate(panel)"))
        assertTrue(source.contains("windowManager.removeViewImmediate(trigger)"))
        val detachStart = source.indexOf("override fun detachReviewPanelSafely()")
        val terminalStart = source.indexOf("override fun terminallyDetachReviewSurface")
        val detachBody = source.substring(detachStart, terminalStart)
        val hidePanel = detachBody.indexOf("panel.visibility = View.GONE")
        val detachPanel = detachBody.indexOf("windowManager.removeViewImmediate(panel)")
        assertTrue(hidePanel >= 0)
        assertTrue(hidePanel < detachPanel)
        assertFalse(detachBody.contains("removeTrigger()"))
        assertTrue(source.contains("parkTriggerWindowForReview()"))
        assertTrue(source.contains("publishVisibleTriggerAccessibility"))
        assertTrue(source.contains("sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)"))
        val triggerCreationStart = source.indexOf("val button = ManuscriptTriggerViewFactory.create")
        val triggerCreationEnd = source.indexOf("private fun publishVisibleTriggerAccessibility", triggerCreationStart)
        val triggerCreation = source.substring(triggerCreationStart, triggerCreationEnd)
        val visibleBeforeAttach = triggerCreation.indexOf("TriggerPulseController.applyState(button, state)")
        val attach = triggerCreation.indexOf("windowManager.addView(button, params)")
        assertTrue(visibleBeforeAttach >= 0)
        assertTrue(visibleBeforeAttach < attach)
        assertTrue(triggerCreation.contains("publishVisibleTriggerAccessibilityAfterLayout(button, state)"))
        val afterLayoutStart = source.indexOf("private fun publishVisibleTriggerAccessibilityAfterLayout")
        val afterLayoutEnd = source.indexOf("private fun publishVisibleTriggerAccessibility", afterLayoutStart + 1)
        val afterLayoutBody = source.substring(afterLayoutStart, afterLayoutEnd)
        val removeListener = afterLayoutBody.indexOf("removeOnLayoutChangeListener(this)")
        val publishAfterLayout = afterLayoutBody.indexOf("publishVisibleTriggerAccessibility(trigger, state)")
        assertTrue(removeListener >= 0)
        assertTrue(removeListener < publishAfterLayout)
        val publicationStart = source.indexOf("private fun publishVisibleTriggerAccessibility")
        val publicationEnd = source.indexOf("private fun visibleTriggerState", publicationStart)
        val publicationBody = source.substring(publicationStart, publicationEnd)
        assertTrue(publicationBody.contains("trigger.sendAccessibilityEvent"))
        assertFalse(publicationBody.contains("event.text"))
        assertFalse(publicationBody.contains("contentDescription"))
        assertFalse(publicationBody.contains("performClick"))
        assertFalse(publicationBody.contains("OpenRequested"))
        assertFalse(publicationBody.contains("FocusedTargetAvailable"))
        assertFalse(publicationBody.contains("snapshot"))
        val deferredClear = source.substring(
            source.indexOf("private val deferredTargetClear"),
            source.indexOf("private var triggerPressed"),
        )
        assertFalse(deferredClear.contains("panelView == null"))
        assertTrue(deferredClear.contains("ReviewSurfaceEvent.TargetLost"))
    }

    @Test
    fun deviceActivePackageRecyclesTheBorrowedActiveRoot() {
        val source = externalFixtureSource("FixtureDeviceSupport.kt")
        val activePackage = source.substring(
            source.indexOf("protected fun activePackage()"),
            source.indexOf("protected fun fixtureApplicationRoot()"),
        )

        assertTrue(activePackage.contains("try"))
        assertTrue(activePackage.contains("finally"))
        assertTrue(activePackage.contains("root.recycle()"))
    }

    @Test
    fun terminalDetachMakesOwnedWindowsInertBeforeIndependentCompletionAndCleanup() {
        val service = source("ManuscriptAccessibilityService.kt")
        val coordinator = source("TerminalDetachLifecycleCoordinator.kt")
        val deactivateBody = service.substring(
            service.indexOf("private fun deactivateTerminalView"),
            service.indexOf("private fun detachTerminalReviewSurface"),
        )
        val beginBody = coordinator.substring(
            coordinator.indexOf("fun begin("),
            coordinator.indexOf("fun retryFromEvent"),
        )

        assertTrue(deactivateBody.contains("view.visibility = View.GONE"))
        assertTrue(deactivateBody.contains("view.alpha = 0f"))
        assertTrue(deactivateBody.contains("view.isClickable = false"))
        assertTrue(deactivateBody.contains("FLAG_NOT_TOUCHABLE"))
        assertTrue(deactivateBody.contains("IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"))
        assertTrue(service.contains("if (panelView === ownership.panel) panelView = null"))
        assertTrue(service.contains("if (triggerView === ownership.trigger) triggerView = null"))
        assertTrue(beginBody.indexOf("deactivate(surface)") < beginBody.indexOf("detach(entry)"))
        assertTrue(beginBody.contains("logicalCompletionDeadlineMillis"))
        assertTrue(service.contains("terminalDetachLifecycle.retryFromEvent()"))
        assertTrue(service.contains("terminalDetachLifecycle.destroy()"))
    }

    private fun source(name: String): String = File(
        System.getProperty("user.dir"),
        "src/main/java/com/echolink/mobile/$name",
    ).readText()

    private fun externalFixtureSource(name: String): String = File(
        System.getProperty("user.dir"),
        "../external-fixture/src/androidTest/java/com/echolink/mobile/fixture/$name",
    ).readText()
}
