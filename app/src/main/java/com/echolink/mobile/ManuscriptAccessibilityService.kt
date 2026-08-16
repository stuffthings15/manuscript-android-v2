package com.echolink.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.AccessibilityServiceInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ManuscriptAccessibilityService : AccessibilityService(), AccessibilityOverlayActions {
    private lateinit var windowManager: WindowManager
    private var triggerView: View? = null
    private var panelView: View? = null
    private var target: Target? = null
    private var reviewSession: WritingReviewSession? = null
    private var workingText = ""
    private var selectedTone = WritingTone.PROOFREAD
    private var suggestionListView: LinearLayout? = null
    private var suggestionScrollView: ScrollView? = null
    private var counterView: TextView? = null
    private var statusView: TextView? = null
    private var replaceButton: Button? = null
    private var dismissButton: Button? = null
    private var acceptChangeButton: Button? = null
    private var rejectChangeButton: Button? = null
    private val reviewToneButtons = linkedMapOf<WritingTone, Button>()
    private var activeReviewChangeId: String? = null
    private var collapsedPanelHeightPx = 0
    private var maximumPanelHeightPx = 0
    private var replacementInFlight = false
    private var retainedInputConnection: Any? = null
    private var lastVisibleKeyboardFrame: KeyboardPanelSnapshot? = null
    private val grammarEngine by lazy { OnDeviceGrammarEngine.get(this) }
    private val modelRequests = RewriteRequestBatch()
    private var modelRequestEpoch = 0
    private val mainHandler by lazy { Handler(mainLooper) }
    private val targetLossDeadline = ReviewTargetLossDeadline(TARGET_CLEAR_GRACE_MS)
    private val rawEventDiagnosticSampler = ReviewRawEventDiagnosticSampler(
        RAW_EVENT_DIAGNOSTIC_INTERVAL_MS,
    )
    private val terminalDetachEventRetrySampler = ReviewRawEventDiagnosticSampler(
        TERMINAL_DETACH_EVENT_RETRY_INTERVAL_MS,
    )
    private val terminalDetachLifecycle by lazy {
        TerminalDetachLifecycleCoordinator<TerminalDetachOwnership>(
            scheduler = object : DelayedWorkScheduler {
                override fun postDelayed(runnable: Runnable, delayMillis: Long) {
                    mainHandler.postDelayed(runnable, delayMillis)
                }

                override fun remove(runnable: Runnable) {
                    mainHandler.removeCallbacks(runnable)
                }
            },
            retryDelayMillis = TERMINAL_DETACH_RETRY_MS,
            logicalCompletionDeadlineMillis = TERMINAL_DETACH_COMPLETION_DEADLINE_MS,
            postCompletionRetryDelayMillis = TERMINAL_DETACH_EVENT_RETRY_INTERVAL_MS,
            rapidRetryAttempts = MAX_TERMINAL_DETACH_SCHEDULED_RETRIES,
            scheduledCleanupAttempts = MAX_TERMINAL_DETACH_CLEANUP_RETRIES,
            deactivate = ::deactivateTerminalReviewSurface,
            attemptDetach = ::detachTerminalReviewSurface,
            onPhysicalDetach = ::releaseTerminalReviewSurfaceOwnership,
        )
    }
    private val replacementVerificationWork by lazy {
        TrackedDelayedWork(
            object : DelayedWorkScheduler {
                override fun postDelayed(runnable: Runnable, delayMillis: Long) {
                    mainHandler.postDelayed(runnable, delayMillis)
                }

                override fun remove(runnable: Runnable) {
                    mainHandler.removeCallbacks(runnable)
                }
            },
        )
    }
    private val reviewSurfaceController = ReviewSurfaceController()
    private val reviewSurfaceExecutor by lazy {
        ReviewSurfaceEffectExecutor(
            reviewSurfaceController,
            object : ReviewSurfaceEffectActions {
                override fun cancelStaleCorrectionAndCallbackWork() {
                    OnDeviceGrammarEngine.get(this@ManuscriptAccessibilityService).setSessionWarm(false)
                    mainHandler.removeCallbacks(keyboardHideRunnable)
                    replacementVerificationWork.cancelAll()
                    modelRequests.cancelAll()
                    modelRequestEpoch += 1
                    replacementInFlight = false
                }

                override fun detachReviewPanelSafely(): ReviewPanelDetachOutcome {
                    val panel = panelView
                    if (panel != null) {
                        panel.visibility = View.GONE
                        runCatching { windowManager.removeViewImmediate(panel) }
                    }
                    if (panel != null && !panel.isAttachedToWindow) panelView = null
                    return if (panelView != null) {
                        ReviewPanelDetachOutcome.STILL_ATTACHED
                    } else if (panel == null) {
                        ReviewPanelDetachOutcome.ALREADY_ABSENT
                    } else {
                        ReviewPanelDetachOutcome.REMOVED
                    }
                }

                override fun terminallyDetachReviewSurface(onTerminalCompletion: () -> Unit) {
                    val panel = panelView
                    val trigger = triggerView
                    val ownership = TerminalDetachOwnership(panel, trigger)
                    terminalDetachLifecycle.begin(ownership, onTerminalCompletion)
                }

                override fun hasPendingTerminalDetachOwnership(): Boolean =
                    terminalDetachLifecycle.hasOwnership

                override fun scrubTransientTextHolders() {
                    reviewSession = null
                    suggestionListView = null
                    suggestionScrollView = null
                    counterView = null
                    statusView = null
                    replaceButton = null
                    dismissButton = null
                    acceptChangeButton = null
                    rejectChangeButton = null
                    reviewToneButtons.clear()
                    activeReviewChangeId = null
                    retainedInputConnection = null
                    workingText = ""
                    target?.let { current ->
                        setTarget(
                            current.copy(
                                snapshot = ExternalWritingPolicy.scrubbedSnapshot(current.snapshot),
                            ),
                        )
                    }
                }

                override fun reacquireFocusedTarget(mode: ReviewTargetReacquisition): ReviewTarget =
                    reacquireReviewTarget(mode)

                override fun restoreKeyboard(targetId: ReviewTargetId) {
                    restoreKeyboardToReacquiredTarget(targetId)
                }

                override fun renderReviewSurfaceState(state: ReviewSurfaceState) {
                    when (state) {
                        is ReviewSurfaceState.TriggerVisible -> showTriggerForReacquiredTarget(state.targetId)
                        ReviewSurfaceState.Hidden -> clearTarget()
                        ReviewSurfaceState.Destroyed -> removeTrigger()
                        is ReviewSurfaceState.Closing -> {
                            if (
                                state.retainedTarget.eligibility == ReviewTargetEligibility.ELIGIBLE
                            ) {
                                parkTriggerWindowForReview()
                            } else {
                                removeTrigger()
                            }
                        }
                        else -> parkTriggerWindowForReview()
                    }
                }

                override fun recordLifecycleDiagnostic(diagnostic: String) {
                    Log.i(REVIEW_LOG_TAG, diagnostic)
                }
            },
        )
    }
    private val keyboardHideRunnable = Runnable {
        if (panelView != null && systemKeyboardVisible()) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }
    private val deferredTargetClear = Runnable {
        val current = target ?: return@Runnable
        if (
            targetLossDeadline.consumeIfDue(
                current.reviewTargetId,
                android.os.SystemClock.uptimeMillis(),
            )
        ) {
            reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
            clearTarget()
        } else {
            scheduleTargetClear()
        }
    }
    private var triggerPressed = false
    private val releaseTriggerPress = Runnable { triggerPressed = false }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
            }
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        selectedTone = WritingTone.fromLabel(
            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .getString(TONE_KEY, WritingTone.PROOFREAD.label).orEmpty(),
        )
        reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.ServiceReconnected)
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): InputMethod = InputMethod(this)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isOverlaySelfEvent(event)) return
        if (
            terminalDetachLifecycle.hasOwnership &&
            terminalDetachEventRetrySampler.shouldLog(android.os.SystemClock.uptimeMillis())
        ) {
            terminalDetachLifecycle.retryFromEvent()
        }
        if (invalidateDetachedRetainedNodeOnWindowsChanged(event)) return
        val eventPackage = event.packageName?.toString().orEmpty()
        if (hideForEarlyApplicationWindowStateChange(event, eventPackage)) return
        if (hideTriggerForClearedFocusedEditor(event, eventPackage)) return
        val applicationContext = verifiedApplicationEventContext(event)
            ?: if (event.eventType.isApplicationLifecycleEvent()) {
                verifiedActiveApplicationEventContext()
            } else {
                null
            }
        val provisionalTransitionHint = target?.let { current ->
            ExternalPackageChangePolicy.provisionalTransitionHint(
                retainedPackage = current.packageName,
                eventPackage = eventPackage,
                eventWindowId = event.windowId,
                isApplicationLifecycleEvent = event.eventType.isApplicationLifecycleEvent(),
                isInputMethodWindow = runCatching {
                    windows.any { window ->
                        window.id == event.windowId &&
                            window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    }
                }.getOrDefault(false),
                matchingApplicationWindowCount = runCatching {
                    windows.count { window ->
                        window.id == event.windowId &&
                            window.type == AccessibilityWindowInfo.TYPE_APPLICATION
                    }
                }.getOrDefault(0),
            )
        } ?: false
        if (rawEventDiagnosticSampler.shouldLog(android.os.SystemClock.uptimeMillis())) {
            Log.i(
                REVIEW_LOG_TAG,
                ReviewLifecycleDiagnostics.accessibilityEvent(
                    eventType = event.eventType,
                    eventPackage = eventPackage,
                    eventWindowId = event.windowId,
                    verifiedPackage = applicationContext?.metadata?.eventPackage,
                    verifiedWindowId = applicationContext?.metadata?.eventWindowId,
                    verified = applicationContext != null,
                ),
            )
        }
        if (provisionalTransitionHint && applicationContext?.metadata?.eventPackage == target?.packageName) {
            applicationContext?.close()
            cancelTargetClear()
            reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
            clearTarget()
            return
        }
        if (applicationContext == null && provisionalTransitionHint) {
            cancelTargetClear()
            reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
            clearTarget()
            return
        }
        if (applicationContext != null) {
            applicationContext.use { context ->
                if (observeRetainedTargetLifecycle(context)) return
                processFocusedEvent(context.metadata.eventPackage, context.applicationRoot) {
                    focusedEditableNode(context.applicationRoot, context.metadata)
                }
            }
            return
        }
        if (event.eventType.isApplicationLifecycleEvent()) return
        processUnverifiedFocusedEvent(eventPackage)
    }

    private fun observeRetainedTargetLifecycle(
        context: VerifiedApplicationEventContext,
    ): Boolean {
        val evidence = retainedTargetEvidence(context)
        return when (ReviewTargetLifecyclePolicy.action(evidence)) {
            ReviewTargetLifecycleAction.KEEP -> false
            ReviewTargetLifecycleAction.ADOPT_VERIFIED_TARGET -> false
            ReviewTargetLifecycleAction.DEFER_TARGET_LOSS -> {
                scheduleTargetClear()
                true
            }
            ReviewTargetLifecycleAction.TARGET_LOST -> {
                cancelTargetClear()
                reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
                clearTarget()
                true
            }
            ReviewTargetLifecycleAction.PASSWORD_FOCUSED -> {
                cancelTargetClear()
                reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.PasswordFocused)
                clearTarget()
                true
            }
            ReviewTargetLifecycleAction.APPLICATION_SWITCHED -> {
                cancelTargetClear()
                reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.ApplicationSwitched)
                clearTarget()
                true
            }
        }
    }

    private fun invalidateDetachedRetainedNodeOnWindowsChanged(
        event: AccessibilityEvent,
    ): Boolean {
        val current = target ?: return false
        val evidence = RetainedTargetWindowChangeInspection.inspect(
            isWindowsChanged = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            exactNodeStillAttached = {
                current.node.refresh() &&
                    current.node.packageName?.toString() == current.packageName &&
                    current.node.windowId >= 0
            },
        )
        if (evidence != RetainedTargetEvidence.VERIFIED_EXACT_TARGET_DETACHED) return false
        cancelTargetClear()
        reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
        clearTarget()
        return true
    }

    private fun hideForEarlyApplicationWindowStateChange(
        event: AccessibilityEvent,
        eventPackage: String,
    ): Boolean {
        val current = target ?: return false
        val applicationWindows = runCatching {
            windows.filter { window ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION
            }
        }.getOrElse { return false }
        val exactWindowStateChange =
            ExternalPackageChangePolicy.confirmedEarlyApplicationWindowStateChange(
                retainedPackage = current.packageName,
                eventPackage = eventPackage,
                eventWindowId = event.windowId,
                matchingApplicationWindowCount = applicationWindows.count { window ->
                    window.id == event.windowId
                },
                isWindowStateChanged = event.eventType ==
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            )
        if (!exactWindowStateChange) return false
        cancelTargetClear()
        reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.ApplicationSwitched)
        clearTarget()
        return true
    }

    private fun retainedTargetEvidence(
        context: VerifiedApplicationEventContext,
    ): RetainedTargetEvidence {
        val current = target ?: return RetainedTargetEvidence.UNVERIFIED_EVENT
        return RetainedTargetInspectionBoundary.inspect {
            if (
                ExternalPackageChangePolicy.confirmed(
                    retainedPackage = current.packageName,
                    verifiedApplicationWindowPackage = context.metadata.eventPackage,
                )
            ) return@inspect RetainedTargetEvidence.VERIFIED_APPLICATION_SWITCH

            val focused = context.applicationRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                try {
                    if (
                        focused !== context.applicationRoot &&
                        VerifiedApplicationEventPolicy.acceptsFocus(
                            context.metadata,
                            verifiedFocusMetadata(focused),
                        )
                    ) {
                        if (focused.isPassword) {
                            return@inspect RetainedTargetEvidence.VERIFIED_PASSWORD_FOCUS
                        }
                        if (reviewTargetId(context.applicationRoot, focused) == current.reviewTargetId) {
                            return@inspect RetainedTargetEvidence.VERIFIED_EXACT_TARGET_ELIGIBLE
                        }
                        return@inspect RetainedTargetEvidence.VERIFIED_DIFFERENT_ELIGIBLE_TARGET
                    }
                } finally {
                    recycleIfDetached(focused, context.applicationRoot)
                }
            }

            if (
                !current.node.refresh() ||
                current.node.packageName?.toString() != current.packageName ||
                current.node.windowId != context.metadata.eventWindowId ||
                reviewTargetId(context.applicationRoot, current.node) != current.reviewTargetId
            ) return@inspect RetainedTargetEvidence.VERIFIED_EXACT_TARGET_DETACHED
            if (current.node.isPassword) return@inspect RetainedTargetEvidence.VERIFIED_PASSWORD_FOCUS
            if (current.node.isFocused && current.node.isEditable) {
                RetainedTargetEvidence.VERIFIED_EXACT_TARGET_ELIGIBLE
            } else {
                RetainedTargetEvidence.VERIFIED_ELIGIBLE_FOCUS_LOST
            }
        }
    }

    private fun reviewTargetId(
        applicationRoot: AccessibilityNodeInfo,
        node: AccessibilityNodeInfo,
    ): ReviewTargetId? {
        val nodePackage = node.packageName?.toString().orEmpty()
        if (
            nodePackage.isBlank() ||
            nodePackage != applicationRoot.packageName?.toString() ||
            node.windowId != applicationRoot.windowId
        ) return null
        return ExternalFieldIdentity.from(
            packageName = nodePackage,
            platformUniqueId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                node.uniqueId
            } else {
                null
            },
            viewId = node.viewIdResourceName,
            structuralLocator = AccessibilityNodeStructuralLocator.from(applicationRoot, node),
        )?.asReviewTargetId()
    }

    private fun Int.isApplicationLifecycleEvent(): Boolean =
        this == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            this == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            this == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            this == AccessibilityEvent.TYPE_WINDOWS_CHANGED

    private fun hideTriggerForClearedFocusedEditor(
        event: AccessibilityEvent,
        eventPackage: String,
    ): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return false
        val current = target ?: return false
        if (eventPackage.isBlank() || eventPackage != current.packageName) return false
        val source = runCatching { event.source }.getOrNull() ?: return false
        return try {
            val showingHint = source.isShowingHintText
            val updatedTextIsEmpty = showingHint ||
                event.text.isEmpty() ||
                event.text.all { it.isNullOrEmpty() }
            Log.i(
                REVIEW_LOG_TAG,
                "event=external-text-change" +
                    ";editable=${source.isEditable}" +
                    ";focused=${source.isFocused}" +
                    ";password=${source.isPassword}" +
                    ";showingHint=$showingHint" +
                    ";updatedEmpty=$updatedTextIsEmpty" +
                    ";sourceLength=${source.text?.length ?: -1}",
            )
            if (
                source.packageName?.toString() != eventPackage ||
                !source.isEditable ||
                !source.isFocused ||
                source.isPassword ||
                !updatedTextIsEmpty
            ) {
                false
            } else {
                Log.i(REVIEW_LOG_TAG, "event=clear-empty-external-trigger")
                reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
                clearTarget()
                true
            }
        } finally {
            recycleLegacy(source)
        }
    }

    private fun processFocusedEvent(
        eventPackage: String,
        verifiedApplicationRoot: AccessibilityNodeInfo? = null,
        focusedNodeProvider: () -> AccessibilityNodeInfo?,
    ) {
        if (eventPackage == packageName) {
            if (panelView != null) {
                reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.ApplicationSwitched)
                clearTarget()
            } else scheduleTargetClear()
            return
        }
        val node = focusedNodeProvider() ?: run {
            if (panelView != null) {
                reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
            } else scheduleTargetClear()
            return
        }
        val snapshot = ExternalWritingPolicy.metadataSnapshot(
            packageName = node.packageName?.toString().orEmpty(),
            text = editableText(node),
            editable = node.isEditable,
            password = node.isPassword,
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
        )
        if (!ExternalWritingPolicy.canOffer(snapshot, packageName)) {
            recycleIfDetached(node, target?.node)
            if (panelView != null) {
                when {
                    snapshot.password ->
                        reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.PasswordFocused)
                    target?.packageName != snapshot.packageName -> {
                        reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.ApplicationSwitched)
                        clearTarget()
                    }
                    else -> reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
                }
            } else if (snapshot.password) {
                reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.PasswordFocused)
                clearTarget()
            } else scheduleTargetClear()
            return
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) {
            recycleIfDetached(node, target?.node)
            if (panelView != null) {
                reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
            } else scheduleTargetClear()
            return
        }
        cancelTargetClear()
        val candidate = acquireTarget(
            packageName = snapshot.packageName,
            viewId = node.viewIdResourceName,
            bounds = Rect(bounds),
            snapshot = snapshot,
            node = node,
            applicationRoot = verifiedApplicationRoot,
        ) ?: run {
            recycleIfDetached(node, target?.node)
            reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
            return
        }
        if (panelView != null) {
            val current = target
            if (current == null) {
                recycleIfDetached(node, null)
                reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
            } else if (candidate.packageName != current.packageName) {
                recycleIfDetached(node, current.node)
                reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.ApplicationSwitched)
                clearTarget()
            } else if (candidate.reviewTargetId != current.reviewTargetId) {
                recycleIfDetached(node, current.node)
                reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
            } else {
                setTarget(candidate)
            }
            return
        }
        synchronizeReviewSurfaceTarget(candidate)
    }

    override fun onInterrupt() {
        reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
        clearTarget()
    }

    private fun processUnverifiedFocusedEvent(eventPackage: String) {
        processFocusedEvent(eventPackage) { focusedEditableNode() }
    }

    override fun onDestroy() {
        replacementVerificationWork.cancelAll()
        mainHandler.removeCallbacks(keyboardHideRunnable)
        mainHandler.removeCallbacks(releaseTriggerPress)
        reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.Destroy)
        terminalDetachLifecycle.destroy()
        cancelTargetClear()
        cancelKeyboardRestore()
        clearTarget()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        OnDeviceGrammarEngine.trimIfCreated(level)
        super.onTrimMemory(level)
    }

    private fun focusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused?.isEditable == true) {
            recycleIfDetached(root, focused)
            return focused
        }
        focused?.let(::recycleLegacy)
        val found = findEditable(root, budget = AccessibilityTraversalBudget())
        recycleIfDetached(root, found)
        return found
    }

    private fun editableText(node: AccessibilityNodeInfo): CharSequence? =
        node.text.takeUnless { node.isShowingHintText }

    private fun focusedEditableNode(
        applicationRoot: AccessibilityNodeInfo,
        metadata: VerifiedApplicationEventMetadata,
    ): AccessibilityNodeInfo? {
        val focused = applicationRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            if (
                focused === applicationRoot ||
                !VerifiedApplicationEventPolicy.acceptsFocus(
                    metadata,
                    verifiedFocusMetadata(focused),
                )
            ) {
                recycleIfDetached(focused, applicationRoot)
                return null
            }
            return focused
        }
        val found = findEditable(
            applicationRoot,
            budget = AccessibilityTraversalBudget(),
        ) ?: return null
        if (
            found === applicationRoot ||
            !VerifiedApplicationEventPolicy.acceptsFocus(
                metadata,
                verifiedFocusMetadata(found),
            )
        ) {
            recycleIfDetached(found, applicationRoot)
            return null
        }
        return found
    }

    private fun verifiedFocusMetadata(node: AccessibilityNodeInfo) =
        VerifiedApplicationFocusMetadata(
            packageName = node.packageName?.toString().orEmpty(),
            windowId = node.windowId,
            focused = node.isFocused,
            editable = node.isEditable,
            password = node.isPassword,
        )

    private fun verifiedApplicationEventContext(
        event: AccessibilityEvent,
    ): VerifiedApplicationEventContext? {
        if (!event.eventType.isApplicationLifecycleEvent()) return null
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isBlank()) return null
        val matchingWindows = windows.filter { window ->
            window.id == event.windowId && window.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }
        if (matchingWindows.size != 1) return null
        val applicationWindow = matchingWindows.single()
        val applicationRoot = applicationWindow.root ?: return null
        val metadata = VerifiedApplicationEventMetadata(
            eventPackage = eventPackage,
            eventWindowId = event.windowId,
            rootPackage = applicationRoot.packageName?.toString().orEmpty(),
            rootWindowId = applicationRoot.windowId,
            applicationWindow = applicationWindow.type == AccessibilityWindowInfo.TYPE_APPLICATION,
            matchingApplicationWindowCount = matchingWindows.size,
        )
        if (!VerifiedApplicationEventPolicy.acceptsEvent(metadata)) {
            recycleLegacy(applicationRoot)
            return null
        }
        return VerifiedApplicationEventContext(metadata, applicationRoot)
    }

    private fun verifiedActiveApplicationEventContext(): VerifiedApplicationEventContext? {
        val applicationRoot = rootInActiveWindow ?: return null
        val matchingWindows = windows.filter { window ->
            window.id == applicationRoot.windowId &&
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                window.isActive
        }
        if (matchingWindows.size != 1) {
            recycleLegacy(applicationRoot)
            return null
        }
        val window = matchingWindows.single()
        val rootPackage = applicationRoot.packageName?.toString().orEmpty()
        val metadata = VerifiedApplicationEventMetadata(
            eventPackage = rootPackage,
            eventWindowId = window.id,
            rootPackage = rootPackage,
            rootWindowId = applicationRoot.windowId,
            applicationWindow = window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
            matchingApplicationWindowCount = matchingWindows.size,
        )
        if (!VerifiedApplicationEventPolicy.acceptsEvent(metadata)) {
            recycleLegacy(applicationRoot)
            return null
        }
        return VerifiedApplicationEventContext(metadata, applicationRoot)
    }

    private fun isOverlaySelfEvent(event: AccessibilityEvent): Boolean {
        if (event.packageName?.toString() != packageName) return false
        return windows.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                window.id == event.windowId
        }
    }

    private fun findEditable(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        budget: AccessibilityTraversalBudget,
    ): AccessibilityNodeInfo? {
        if (!budget.tryVisit(depth)) return null
        if (node.isFocused && node.isEditable) return node
        if (!budget.canDescendFrom(depth)) return null
        for (index in 0 until node.childCount) {
            if (!budget.canDescendFrom(depth)) break
            val child = node.getChild(index) ?: continue
            val found = findEditable(child, depth + 1, budget)
            if (found != null) {
                recycleIfDetached(child, found)
                return found
            }
            recycleLegacy(child)
        }
        return null
    }

    private fun showTrigger(bounds: Rect) {
        if (terminalDetachLifecycle.hasOwnership) return
        rememberVisibleKeyboardFrame()
        val current = triggerView
        if (current != null) {
            val params = triggerWindowParams(bounds, touchable = true)
            current.visibility = View.VISIBLE
            current.alpha = ManuscriptTriggerPresentation.PULSE_ALPHA_FROM
            current.isClickable = true
            current.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            val state = visibleTriggerState()
            TriggerPulseController.applyState(current, state)
            current.postInvalidateOnAnimation()
            if (runCatching { windowManager.updateViewLayout(current, params) }.isFailure) {
                removeTrigger()
                if (triggerView == null) showTrigger(bounds)
                return
            }
            publishVisibleTriggerAccessibility(current, state)
            return
        }
        val params = triggerWindowParams(bounds, touchable = true)
        val button = ManuscriptTriggerViewFactory.create(
            context = this,
            contentDescription = getString(R.string.external_assistant_open),
            onClick = {
                Log.i(REVIEW_LOG_TAG, "event=external-trigger-click")
                openPanel()
            },
        )
        button.setOnTouchListener { _, motionEvent ->
            when (motionEvent.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    Log.i(REVIEW_LOG_TAG, "event=external-trigger-down")
                    triggerPressed = true
                    mainHandler.removeCallbacks(releaseTriggerPress)
                    cancelTargetClear()
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                -> {
                    Log.i(REVIEW_LOG_TAG, "event=external-trigger-release")
                    // Keep the press guard alive briefly so the click handler
                    // and its focus fallout cannot race the trigger away.
                    mainHandler.postDelayed(releaseTriggerPress, PRESS_GUARD_MS)
                }
            }
            false
        }
        val state = visibleTriggerState()
        TriggerPulseController.applyState(button, state)
        triggerView = button
        Log.i(REVIEW_LOG_TAG, "event=external-trigger-add-start")
        if (runCatching { windowManager.addView(button, params) }.isFailure) {
            TriggerPulseController.stop(button)
            triggerView = null
        } else {
            Log.i(
                REVIEW_LOG_TAG,
                "event=external-trigger-add-complete;attached=${button.isAttachedToWindow}",
            )
            publishVisibleTriggerAccessibilityAfterLayout(button, state)
        }
    }

    private fun triggerWindowParams(
        bounds: Rect,
        touchable: Boolean,
    ): WindowManager.LayoutParams {
        val metrics = resources.displayMetrics
        val size = dp(48)
        val x = (bounds.right - size).coerceIn(0, maxOf(0, metrics.widthPixels - size))
        val preferredY = if (bounds.top >= size + dp(8)) {
            bounds.top - size - dp(4)
        } else {
            bounds.bottom + dp(4)
        }
        val y = preferredY.coerceIn(0, maxOf(0, metrics.heightPixels - size))
        val touchabilityFlag = if (touchable) {
            0
        } else {
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        // The floating assistant of the GUI build manual is an application
        // overlay, which is what lets it be dragged and kept where the writer
        // put it. Manuscript did not hold that permission before this manual
        // was adopted, and it still does not require it: without the grant the
        // trigger falls back to the accessibility overlay it has always used,
        // which cannot be dragged but works. A refused permission leaves a
        // complete product, so nothing here assumes the answer is yes.
        val windowType = if (OverlayPermission.isGranted(this)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        return WindowManager.LayoutParams(
            size,
            size,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                touchabilityFlag,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun publishVisibleTriggerAccessibilityAfterLayout(
        trigger: View,
        state: ManuscriptTriggerState,
    ) {
        trigger.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                trigger.removeOnLayoutChangeListener(this)
                Log.i(
                    REVIEW_LOG_TAG,
                    "event=external-trigger-first-layout;attached=${trigger.isAttachedToWindow}",
                )
                val observer = trigger.viewTreeObserver
                observer.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                    private var recorded = false

                    override fun onDraw() {
                        if (recorded) return
                        recorded = true
                        Log.i(
                            REVIEW_LOG_TAG,
                            "event=external-trigger-first-draw;attached=${trigger.isAttachedToWindow}",
                        )
                        trigger.post {
                            if (observer.isAlive) {
                                observer.removeOnDrawListener(this)
                            }
                        }
                    }
                })
                publishVisibleTriggerAccessibility(trigger, state)
            }
        })
    }

    private fun publishVisibleTriggerAccessibility(
        trigger: View,
        state: ManuscriptTriggerState,
    ) {
        val currentTarget = target
        val surfaceState = reviewSurfaceController.state
        val verifiedEligibleTarget =
            currentTarget != null &&
                surfaceState is ReviewSurfaceState.TriggerVisible &&
                currentTarget.reviewTargetId == surfaceState.targetId
        if (
            !ManuscriptTriggerPolicy.shouldPublishAccessibilitySurface(
                attached = trigger.isAttachedToWindow,
                state = state,
                verifiedEligibleTarget = verifiedEligibleTarget,
            )
        ) {
            return
        }
        trigger.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    private fun visibleTriggerState(): ManuscriptTriggerState =
        ManuscriptTriggerPolicy.state(
            focused = true,
            editable = true,
            hasText = true,
            password = false,
            reviewOpen = panelView != null,
            animationsEnabled = android.animation.ValueAnimator.areAnimatorsEnabled(),
        )

    private fun openPanel() {
        if (terminalDetachLifecycle.hasOwnership) return
        if (
            !LocalAiCorrectionGate.isReady(
                AiModelManager.get(this).installedFile(),
            )
        ) {
            openModelSetup()
            return
        }
        cancelKeyboardRestore()
        val captured = target ?: run {
            announceTriggerMiss()
            return
        }
        // Event-storm editors (Google Messages) can detach the captured node
        // between trigger tap-down and click. Fall back to the currently
        // focused editable field in the same app before giving up, and never
        // swallow the tap silently.
        val reacquired = reacquireTarget(captured)
            ?: focusedEditableNode()?.let { node ->
                val snapshot = ExternalWritingPolicy.metadataSnapshot(
                    packageName = node.packageName?.toString().orEmpty(),
                    text = editableText(node),
                    editable = node.isEditable,
                    password = node.isPassword,
                    selectionStart = node.textSelectionStart,
                    selectionEnd = node.textSelectionEnd,
                )
                val bounds = Rect().also(node::getBoundsInScreen)
                acquireTarget(
                    packageName = snapshot.packageName,
                    viewId = node.viewIdResourceName,
                    bounds = bounds,
                    snapshot = snapshot,
                    node = node,
                )?.takeIf { target -> target.reviewTargetId == captured.reviewTargetId }
                    .also { target -> if (target == null) recycleIfDetached(node, captured.node) }
            }
            ?: run {
                announceTriggerMiss()
                return
            }
        val liveNode = reacquired.node
        if (liveNode.isPassword) return
        val liveSnapshot = ExternalWritingPolicy.capturedSnapshot(
            snapshot = captured.snapshot,
            text = liveNode.text,
            selectionStart = liveNode.textSelectionStart,
            selectionEnd = liveNode.textSelectionEnd,
        ) ?: return
        val currentText = liveSnapshot.text
        val liveTarget = reacquired.copy(snapshot = liveSnapshot)
        val selection = ExternalWritingPolicy.safeSelection(liveSnapshot)
        reviewSession = WritingReviewSession.createParagraphs(
            source = currentText,
            selectionStart = selection?.first ?: 0,
            selectionEnd = selection?.last?.plus(1) ?: 0,
        ) ?: return
        if (!synchronizeReviewSurfaceTarget(liveTarget)) {
            reviewSession = null
            workingText = ""
            announceTriggerMiss()
            return
        }
        workingText = currentText
        reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.OpenRequested)
        if (reviewSurfaceController.state !is ReviewSurfaceState.Opening) {
            reviewSession = null
            workingText = ""
            return
        }
        retainCurrentInputConnection()
        val panelBounds = keyboardPanelBounds()
        val panelAttached = AccessibilityOverlayHandoff.replaceTriggerWithPanel(
            actions = this,
            bounds = panelBounds,
        )
        if (!panelAttached) {
            reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.CloseRequested)
            return
        }
        reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.ReviewAttached)
    }

    override fun addKeyboardAreaPanel(bounds: KeyboardPanelBounds) {
        if (terminalDetachLifecycle.hasOwnership) return
        OnDeviceGrammarEngine.get(this).setSessionWarm(true)
        val panel = buildPanel()
        val metrics = resources.displayMetrics
        val protectedBottom = currentSystemBottomInsets().protectedBottomPx
        collapsedPanelHeightPx = bounds.heightPx
        maximumPanelHeightPx = ReviewPanelExpansionPolicy.maximumHeight(
            screenHeightPx = metrics.heightPixels,
            protectedBottomPx = protectedBottom,
            collapsedHeightPx = bounds.heightPx,
        )
        (panel as? ExpandableReviewPanel)?.configureExpansion(
            collapsedHeightPx = collapsedPanelHeightPx,
            maximumHeightPx = maximumPanelHeightPx,
            onResize = ::resizePanel,
        )
        val params = WindowManager.LayoutParams(
            bounds.widthPx,
            bounds.heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = bounds.leftOffsetPx
            y = bounds.bottomOffsetPx
        }
        windowManager.addView(panel, params)
        panelView = panel
    }

    override fun initializeKeyboardAreaPanel() {
        renderCurrentBlock()
    }

    override fun removeKeyboardAreaPanel() {
        panelView?.let(windowManager::removeView)
    }

    override fun clearKeyboardAreaPanelReference() {
        panelView = null
    }

    override fun removeWritingTrigger() {
        removeTrigger()
    }

    override fun onKeyboardAreaPanelReady() {
        hideSystemKeyboardBehindPanel()
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun retainCurrentInputConnection() {
        retainedInputConnection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            inputMethod?.currentInputStarted == true
        ) {
            inputMethod?.currentInputConnection
        } else null
    }

    /**
     * Android gives the active IME first claim on its touch area, even when an
     * accessibility overlay is drawn above it. Hide only a confirmed visible
     * IME after the Manuscript panel is attached, then restore it on close.
     */
    private fun hideSystemKeyboardBehindPanel() {
        mainHandler.removeCallbacks(keyboardHideRunnable)
        if (!systemKeyboardVisible()) return
        mainHandler.postDelayed(keyboardHideRunnable, KEYBOARD_HIDE_DELAY_MS)
    }

    private fun systemKeyboardVisible(): Boolean {
        val metrics = resources.displayMetrics
        val insets = currentSystemBottomInsets()
        return currentKeyboardPanelBounds(
            screenWidthPx = metrics.widthPixels,
            screenHeightPx = metrics.heightPixels,
            protectedBottomPx = insets.protectedBottomPx,
            imeBottomInsetPx = insets.imeBottomInsetPx,
        ) != null
    }

    private fun keyboardPanelBounds(): KeyboardPanelBounds {
        val metrics = resources.displayMetrics
        val insets = currentSystemBottomInsets()
        val current = currentKeyboardPanelBounds(
            screenWidthPx = metrics.widthPixels,
            screenHeightPx = metrics.heightPixels,
            protectedBottomPx = insets.protectedBottomPx,
            imeBottomInsetPx = insets.imeBottomInsetPx,
        )
        if (current != null) {
            lastVisibleKeyboardFrame = KeyboardPanelSnapshot(
                panelBounds = current,
                screenWidthPx = metrics.widthPixels,
                screenHeightPx = metrics.heightPixels,
                protectedBottomPx = insets.protectedBottomPx,
            )
        }
        return KeyboardPanelMemoryPolicy.resolve(
            current = current,
            lastVisible = lastVisibleKeyboardFrame,
            currentScreenWidthPx = metrics.widthPixels,
            currentScreenHeightPx = metrics.heightPixels,
            currentProtectedBottomPx = insets.protectedBottomPx,
        ) ?: KeyboardPanelGeometry.calculate(
            screenWidthPx = metrics.widthPixels,
            screenHeightPx = metrics.heightPixels,
            imeBottomInsetPx = 0,
            navigationBottomInsetPx = insets.navigationBottomPx,
            gestureBottomInsetPx = insets.gestureBottomPx,
            fallbackHeightPx = dp(256),
        )
    }

    private fun rememberVisibleKeyboardFrame() {
        val metrics = resources.displayMetrics
        val insets = currentSystemBottomInsets()
        val current = currentKeyboardPanelBounds(
            screenWidthPx = metrics.widthPixels,
            screenHeightPx = metrics.heightPixels,
            protectedBottomPx = insets.protectedBottomPx,
            imeBottomInsetPx = insets.imeBottomInsetPx,
        ) ?: return
        lastVisibleKeyboardFrame = KeyboardPanelSnapshot(
            panelBounds = current,
            screenWidthPx = metrics.widthPixels,
            screenHeightPx = metrics.heightPixels,
            protectedBottomPx = insets.protectedBottomPx,
        )
    }

    private fun currentKeyboardPanelBounds(
        screenWidthPx: Int,
        screenHeightPx: Int,
        protectedBottomPx: Int,
        imeBottomInsetPx: Int,
    ): KeyboardPanelBounds? = visibleKeyboardWindowPanelBounds(
        screenWidthPx = screenWidthPx,
        screenHeightPx = screenHeightPx,
        protectedBottomPx = protectedBottomPx,
    ) ?: imeBottomInsetPx.takeIf { it > 0 }?.let { imeBottom ->
        KeyboardPanelGeometry.calculate(
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            imeBottomInsetPx = imeBottom,
            navigationBottomInsetPx = protectedBottomPx,
            gestureBottomInsetPx = protectedBottomPx,
            fallbackHeightPx = 1,
        )
    }

    private fun visibleKeyboardWindowPanelBounds(
        screenWidthPx: Int,
        screenHeightPx: Int,
        protectedBottomPx: Int,
    ): KeyboardPanelBounds? {
        val candidates = mutableListOf<Pair<KeyboardPanelBounds, Long>>()
        windows.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@forEach
            val bounds = Rect().also(window::getBoundsInScreen)
            if (bounds.isEmpty) return@forEach
            val panel = KeyboardWindowGeometryPolicy.fromWindow(
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                windowLeftPx = bounds.left,
                windowTopPx = bounds.top,
                windowRightPx = bounds.right,
                windowBottomPx = bounds.bottom,
                protectedBottomPx = protectedBottomPx,
                minimumKeyboardHeightPx = dp(96),
            ) ?: return@forEach
            val area = panel.widthPx.toLong() * panel.heightPx.toLong()
            candidates += panel to area
        }
        return candidates.maxByOrNull { it.second }?.first
    }

    private fun currentSystemBottomInsets(): SystemBottomInsets {
        var navigationBottom = systemNavigationBarHeight()
        var gestureBottom = 0
        var imeBottom = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = triggerView?.rootWindowInsets ?: windowManager.currentWindowMetrics.windowInsets
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            navigationBottom = maxOf(
                navigationBottom,
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom,
            )
            gestureBottom = insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom
        }
        return SystemBottomInsets(
            navigationBottomPx = navigationBottom,
            gestureBottomPx = gestureBottom,
            imeBottomInsetPx = imeBottom,
        )
    }

    private fun systemNavigationBarHeight(): Int {
        val navigationHeightId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val gestureHeightId = resources.getIdentifier("navigation_bar_gesture_height", "dimen", "android")
        val navigationHeight = if (navigationHeightId > 0) {
            resources.getDimensionPixelSize(navigationHeightId)
        } else 0
        val gestureHeight = if (gestureHeightId > 0) {
            resources.getDimensionPixelSize(gestureHeightId)
        } else 0
        return maxOf(navigationHeight, gestureHeight)
    }

    private fun buildPanel(): View {
        val panel = ExpandableReviewPanel(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(ManuscriptMetrics.SPACE_CARD_DP),
                dp(ManuscriptMetrics.SPACE_MICRO_DP),
                dp(ManuscriptMetrics.SPACE_CARD_DP),
                dp(ManuscriptMetrics.SPACE_RELATED_DP),
            )
            // This panel is drawn over somebody else's application, and the
            // manual requires it to hold up against a light one and a dark one
            // alike. Manuscript's own surface is dark warm, which all but
            // disappears against a dark host, so the edge does the work: a
            // 2 dp accent border rather than a hairline, and elevation so the
            // system draws a shadow that separates the panel from whatever is
            // underneath, whatever colour that is.
            background = rounded(
                COLOR_BACKGROUND,
                ManuscriptMetrics.RADIUS_PANEL_DP.toFloat(),
                COLOR_ACCENT,
                strokeWidthDp = PANEL_BORDER_DP,
            )
            elevation = dp(PANEL_ELEVATION_DP).toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                accessibilityPaneTitle = getString(R.string.external_panel_title)
            }
        }
        panel.addView(TextView(this).apply {
            id = R.id.writing_resize_handle
            text = "\u2501\u2501\u2501"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            contentDescription = getString(R.string.resize_review_panel)
            setPadding(0, 0, 0, 0)
            installPanelResizeGesture(this)
        }, LinearLayout.LayoutParams(
            // The control that resizes the whole panel was a 24 dp strip, half
            // the manual's minimum. The bar still looks the same; the row a
            // finger has to land on is now a full target.
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ManuscriptMetrics.TOUCH_TARGET_DP),
        ))
        val headingRow = OfficialCorrectionHeader(
            context = this,
            title = getString(R.string.external_panel_title),
            onClose = { closePanel(restoreKeyboard = true) },
            onSettings = { openManuscriptSettings() },
        ).apply { showCounter() }
        counterView = headingRow.counterView
        panel.addView(headingRow)
        panel.addView(TextView(this).apply {
            text = getString(R.string.corrections_swipe_help)
            textSize = 12f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, 0, 0, dp(2))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        replaceButton = actionButton(R.string.writing_replace_action, true) { replaceCurrent() }.apply {
            id = R.id.writing_replace_action
        }
        actions.addView(replaceButton, weighted(end = 6))
        dismissButton = actionButton(R.string.writing_dismiss_action, false) { dismissCurrent() }.apply {
            id = R.id.writing_dismiss_action
        }
        actions.addView(dismissButton, weighted(start = 6))
        panel.addView(actions, rowParams().apply { bottomMargin = dp(3) })
        val correctionScroll = ScrollView(this).apply {
            id = R.id.writing_suggestion_scroll
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        suggestionScrollView = correctionScroll
        suggestionListView = LinearLayout(this).apply {
            id = R.id.writing_suggestion
            orientation = LinearLayout.VERTICAL
        }
        correctionScroll.addView(suggestionListView, rowParams())
        panel.addView(correctionScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply { bottomMargin = dp(3) })
        val changeActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        acceptChangeButton = actionButton(R.string.writing_accept_change_action, false) {
            acceptCurrentChange()
        }.apply {
            id = R.id.writing_accept_change_action
        }
        rejectChangeButton = actionButton(R.string.writing_reject_change_action, false) {
            rejectCurrentChange()
        }.apply {
            id = R.id.writing_reject_change_action
        }
        changeActions.addView(acceptChangeButton, weighted(end = 4))
        changeActions.addView(rejectChangeButton, weighted(start = 4))
        panel.addView(changeActions, rowParams().apply { bottomMargin = dp(3) })
        val tone = buildReviewToneMenu()
        val toneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toneRow.addView(tone, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply { marginEnd = dp(4) })
        toneRow.addView(actionButton(R.string.qwen_model_retry_action, false) {
            regenerateModelCorrections()
        }.apply {
            id = R.id.writing_regenerate_action
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        panel.addView(toneRow, rowParams().apply { bottomMargin = dp(2) })
        statusView = TextView(this).apply {
            id = R.id.writing_status
            textSize = 12f
            setTextColor(COLOR_ERROR)
            visibility = View.GONE
        }
        panel.addView(statusView)
        return panel
    }

    private fun buildReviewToneMenu(): HorizontalScrollView {
        reviewToneButtons.clear()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        WritingTone.entries.forEach { tone ->
            val button = Button(this).apply {
                text = tone.label
                contentDescription = getString(R.string.select_tone_description, tone.label)
                isAllCaps = false
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(44)
                minimumHeight = dp(44)
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    if (tone == selectedTone) return@setOnClickListener
                    selectedTone = tone
                    getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                        .edit().putString(TONE_KEY, selectedTone.label).apply()
                    updateReviewToneSelection()
                    regenerateModelCorrections()
                }
            }
            reviewToneButtons[tone] = button
            row.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(44),
                ).apply { marginEnd = dp(4) },
            )
        }
        return HorizontalScrollView(this).apply {
            id = R.id.writing_tone
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            clipToPadding = false
            addView(
                row,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            updateReviewToneSelection()
        }
    }

    private fun updateReviewToneSelection() {
        reviewToneButtons.forEach { (tone, button) ->
            val selected = tone == selectedTone
            button.isSelected = selected
            button.setTypeface(
                button.typeface,
                if (selected) Typeface.BOLD else Typeface.NORMAL,
            )
            button.setTextColor(if (selected) Color.WHITE else COLOR_TEXT)
            button.background = rounded(
                if (selected) COLOR_ACCENT_DARK else COLOR_SURFACE,
                10f,
                if (selected) COLOR_ACCENT else COLOR_BORDER,
            )
        }
    }

    private fun installPanelResizeGesture(handle: View) {
        var downRawY = 0f
        var startingHeight = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    startingHeight = panelView?.height ?: collapsedPanelHeightPx
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    resizePanel(
                        ReviewPanelExpansionPolicy.resizedHeight(
                            startingHeightPx = startingHeight,
                            upwardDragPx = (downRawY - event.rawY).toInt(),
                            collapsedHeightPx = collapsedPanelHeightPx,
                            maximumHeightPx = maximumPanelHeightPx,
                        ),
                    )
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handle.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun resizePanel(heightPx: Int) {
        val panel = panelView ?: return
        val params = panel.layoutParams as? WindowManager.LayoutParams ?: return
        if (params.height == heightPx) return
        params.height = heightPx
        params.y = currentSystemBottomInsets().protectedBottomPx
        windowManager.updateViewLayout(panel, params)
    }

    private fun openManuscriptSettings() {
        closePanel(restoreKeyboard = false)
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_OPEN_PERSONALIZE, true)
        })
    }

    private fun openModelSetup() {
        Log.i(REVIEW_LOG_TAG, "event=open-model-setup")
        closePanel(restoreKeyboard = false)
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_OPEN_MODEL_SETUP, true)
        })
    }

    private fun renderCurrentBlock(requestModel: Boolean = true) {
        val session = reviewSession
        val block = session?.current(selectedTone)
        if (block == null) {
            closePanel(restoreKeyboard = true)
            return
        }
        counterView?.text = getString(R.string.review_counter, block.index, block.total)
        counterView?.contentDescription = counterView?.text
        val modelReady = session.hasModelReplacement(block.index, selectedTone)
        val changes = if (modelReady) session.currentChanges(selectedTone) else emptyList()
        val focusedChange = changes.firstOrNull { it.id == activeReviewChangeId }
            ?: changes.firstOrNull()
        activeReviewChangeId = focusedChange?.id
        renderCorrectionList(session, block)
        val installedAi = AiModelManager.get(this).installedFile()
        val modelMissing = !LocalAiCorrectionGate.isReady(installedAi)
        replaceButton?.apply {
            text = getString(
                if (modelMissing) R.string.qwen_model_install_action
                else R.string.writing_replace_action,
            )
            contentDescription = text
        }
        replaceButton?.isEnabled = (modelReady || modelMissing) && !replacementInFlight
        acceptChangeButton?.isEnabled = focusedChange != null && !replacementInFlight
        rejectChangeButton?.isEnabled = focusedChange != null && !replacementInFlight
        statusView?.apply {
            text = when {
                modelReady -> ""
                modelMissing -> getString(R.string.local_ai_model_required)
                else -> getString(R.string.local_ai_checking)
            }
            setTextColor(if (modelReady) COLOR_TEXT_MUTED else COLOR_ACCENT)
            visibility = if (modelReady) View.GONE else View.VISIBLE
        }
        Log.i(REVIEW_LOG_TAG, "review_block=${block.index}/${block.total};tone=${selectedTone.name}")
        if (requestModel && !modelMissing) requestAllModelCorrections(session)
    }

    private fun regenerateModelCorrections() {
        val session = reviewSession ?: return
        modelRequests.cancelAll()
        session.clearModelReplacements(selectedTone)
        activeReviewChangeId = null
        modelRequestEpoch += 1
        renderCurrentBlock()
    }

    private fun requestAllModelCorrections(session: WritingReviewSession) {
        val pending = session.pending(selectedTone)
            .filterNot { session.hasModelReplacement(it.index, selectedTone) }
        if (pending.isEmpty()) return
        val epoch = modelRequestEpoch
        val callbackToken = reviewSurfaceExecutor.currentCallbackToken()
        val toneAtRequest = selectedTone
        val sourceAtRequest = pending.joinToString("\n\n", transform = WritingReviewBlock::original)
        val key =
            "${System.identityHashCode(session)}:document:${toneAtRequest.name}:" +
                sourceAtRequest.hashCode()
        if (!modelRequests.begin(key)) return
        var paragraphCursor = 0
        val request = grammarEngine.polishDocumentTerminal(
            source = sourceAtRequest,
            tone = toneAtRequest,
            sourceApplication = target?.packageName,
            onParagraphCompleted = { paragraph ->
                if (
                    reviewSurfaceExecutor.isCurrentCallback(callbackToken) &&
                    reviewSession === session &&
                    panelView != null &&
                    modelRequestEpoch == epoch &&
                    selectedTone == toneAtRequest
                ) {
                    pending.getOrNull(paragraphCursor)?.let { block ->
                        session.setModelReplacement(
                            block.index,
                            toneAtRequest,
                            paragraph.text,
                        )
                    }
                    paragraphCursor += 1
                    renderCurrentBlock(requestModel = false)
                }
            },
            onProgress = { completed, total ->
                if (
                    reviewSurfaceExecutor.isCurrentCallback(callbackToken) &&
                    reviewSession === session &&
                    panelView != null &&
                    modelRequestEpoch == epoch
                ) {
                    statusView?.apply {
                        text = getString(R.string.local_ai_processing, completed, total)
                        setTextColor(COLOR_ACCENT)
                        visibility = View.VISIBLE
                    }
                }
            },
        ) { terminalResult ->
            modelRequests.complete(key)
            if (
                !reviewSurfaceExecutor.isCurrentCallback(callbackToken) ||
                reviewSession !== session ||
                panelView == null ||
                modelRequestEpoch != epoch
            ) {
                return@polishDocumentTerminal
            }
            renderCurrentBlock(requestModel = false)
            when (terminalResult) {
                is CorrectionTerminalResult.Success -> Unit
                is CorrectionTerminalResult.RetryableFailure -> {
                    session.clearModelReplacements(toneAtRequest)
                    renderCurrentBlock(requestModel = false)
                    statusView?.apply {
                        text = terminalResult.userMessage
                        setTextColor(COLOR_ERROR)
                        visibility = View.VISIBLE
                    }
                }
                CorrectionTerminalResult.ModelRequired -> {
                    session.clearModelReplacements(toneAtRequest)
                    renderCurrentBlock(requestModel = false)
                    replaceButton?.isEnabled = !replacementInFlight
                    statusView?.apply {
                        text = getString(R.string.local_ai_model_required)
                        setTextColor(COLOR_ERROR)
                        visibility = View.VISIBLE
                    }
                }
            }
        }
        modelRequests.attach(key, request)
    }

    private fun renderCorrectionList(
        session: WritingReviewSession,
        focused: WritingReviewBlock,
    ) {
        val list = suggestionListView ?: return
        list.removeAllViews()
        var focusedCard: View? = null
        session.pending(selectedTone).forEach { block ->
            val isFocused = block.index == focused.index
            val modelReady = session.hasModelReplacement(block.index, selectedTone)
            val visibleBlock = PendingReviewPolicy.visible(block, modelReady)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                contentDescription = getString(
                    R.string.correction_focus_description,
                    block.index,
                    block.total,
                    InlineEditDiff.note(
                        visibleBlock.original,
                        visibleBlock.replacement,
                        selectedTone,
                    ),
                )
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = rounded(
                    if (isFocused) COLOR_SURFACE_FOCUSED else COLOR_SURFACE,
                    10f,
                    if (isFocused) COLOR_ACCENT else COLOR_BORDER,
                )
                setOnClickListener {
                    if (session.select(block.index - 1)) renderCurrentBlock()
                }
            }
            if (isFocused) focusedCard = card
            card.addView(TextView(this).apply {
                text = getString(
                    if (isFocused) R.string.correction_focused_heading else R.string.correction_heading,
                    block.index,
                    block.total,
                )
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isFocused) COLOR_ACCENT else COLOR_TEXT_MUTED)
            })
            card.addView(TextView(this).apply {
                text = if (modelReady) {
                    inlineCorrectionText(
                        visibleBlock,
                        if (isFocused) activeReviewChangeId else null,
                    )
                } else {
                    getString(R.string.local_ai_checking)
                }
                textSize = 15f
                setTextColor(COLOR_TEXT)
                setLineSpacing(0f, 1.12f)
                setPadding(0, dp(2), 0, dp(2))
                movementMethod = LinkMovementMethod.getInstance()
                highlightColor = Color.TRANSPARENT
            })
            card.addView(TextView(this).apply {
                text = if (modelReady) {
                    if (isFocused) {
                        session.currentChanges(selectedTone)
                            .firstOrNull { it.id == activeReviewChangeId }
                            ?.note
                            ?: InlineEditDiff.note(
                                block.original,
                                block.replacement,
                                selectedTone,
                            )
                    } else {
                        InlineEditDiff.note(block.original, block.replacement, selectedTone)
                    }
                } else {
                    getString(R.string.local_ai_on_device)
                }
                textSize = 12f
                setTypeface(typeface, Typeface.ITALIC)
                setTextColor(COLOR_TEXT_MUTED)
            })
            list.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) })
        }
        focusedCard?.let { card ->
            list.post { suggestionScrollView?.smoothScrollTo(0, card.top) }
        }
    }

    private fun inlineCorrectionText(
        block: WritingReviewBlock,
        focusedChangeId: String?,
    ): CharSequence {
        val output = SpannableStringBuilder()
        val changes = InlineEditDiff.changes(block.original, block.replacement, block.tone)
        if (changes.isEmpty()) return block.original
        var cursor = 0
        changes.forEach { change ->
            output.append(block.original, cursor, change.originalStart)
            val changeStart = output.length
            if (change.originalText.isNotEmpty()) {
                val removedStart = output.length
                output.append(change.originalText)
                output.setSpan(
                    StrikethroughSpan(),
                    removedStart,
                    output.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                output.setSpan(
                    ForegroundColorSpan(COLOR_REMOVED),
                    removedStart,
                    output.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (change.originalText.isNotEmpty() && change.replacementText.isNotEmpty()) {
                output.append(" → ")
            }
            if (change.replacementText.isNotEmpty()) {
                val insertedStart = output.length
                output.append(change.replacementText)
                output.setSpan(
                    BackgroundColorSpan(COLOR_INSERTED_BACKGROUND),
                    insertedStart,
                    output.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                output.setSpan(
                    ForegroundColorSpan(COLOR_INSERTED),
                    insertedStart,
                    output.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                output.setSpan(
                    StyleSpan(Typeface.BOLD),
                    insertedStart,
                    output.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            val changeEnd = output.length
            if (changeStart < changeEnd) {
                output.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            reviewSession?.select(block.index - 1)
                            activeReviewChangeId = change.id
                            renderCurrentBlock(requestModel = false)
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            ds.isUnderlineText = change.id == focusedChangeId
                        }
                    },
                    changeStart,
                    changeEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                }
            cursor = change.originalEnd
        }
        output.append(block.original, cursor, block.original.length)
        return output
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun replaceCurrent(changeId: String? = null) {
        if (replacementInFlight) return
        val verificationToken = replacementVerificationWork.beginGeneration()
        val surfaceCallbackToken = reviewSurfaceExecutor.currentCallbackToken()
        val pendingBlock = reviewSession?.current(selectedTone)
        if (
            pendingBlock != null &&
            reviewSession?.hasModelReplacement(pendingBlock.index, selectedTone) != true &&
            AiModelManager.get(this).installedModel() == null
        ) {
            openModelSetup()
            return
        }
        val initialTarget = target ?: return
        var operationTarget = reacquireTarget(initialTarget)
            ?: return showFailure(R.string.external_target_lost)
        val node = operationTarget.node
        if (operationTarget.node !== initialTarget.node) setTarget(operationTarget)
        val session = reviewSession ?: return showFailure(R.string.external_target_lost)
        val toneAtRequest = selectedTone
        val block = session.current(toneAtRequest) ?: return closePanel(restoreKeyboard = true)
        val updated = if (changeId == null) {
            workingText.replaceRange(block.start, block.end, block.replacement)
        } else {
            runCatching {
                session.previewAcceptChange(workingText, toneAtRequest, changeId)
            }.getOrElse {
                return showFailure(R.string.external_text_changed)
            }
        }
        if (ExternalWritingPolicy.materializeReviewText(updated) == null) {
            return showFailure(R.string.external_replace_failed)
        }
        val connection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (retainedInputConnection as? InputMethod.AccessibilityInputConnection)
                ?: inputMethod?.currentInputConnection
        } else null
        val surrounding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            connection?.getSurroundingText(workingText.length, workingText.length, 0)
        } else null
        val connectionMatches = surrounding?.text?.toString() == workingText
        val nodeMatches = editableText(node)?.toString().orEmpty() == workingText
        val initialRoute = ReplacementRequestPolicy.initialRoute(
            nodeMatchesSource = nodeMatches,
            connectionMatchesSource = connectionMatches,
        )
        if (initialRoute == ReplacementInitialRoute.REJECT) {
            return showFailure(R.string.external_text_changed)
        }

        replacementInFlight = true
        setReplacementControlsEnabled(false)
        // The accessibility node is the live editor after the system keyboard is hidden.
        // A retained input connection can become stale when the keyboard closes, so only
        // use it as a fallback when its own surrounding text still matches this editor.
        val nodeReplacementRequested = initialRoute == ReplacementInitialRoute.ACCESSIBILITY_NODE &&
            requestAccessibilityTextReplacement(node, updated)
        var connectionFallbackAttempted = false
        var replacementRequested = nodeReplacementRequested
        if (!nodeReplacementRequested &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            connection != null && connectionMatches
        ) {
            connectionFallbackAttempted = true
            replacementRequested = requestInputConnectionTextReplacement(
                connection = connection,
                sourceLength = surrounding?.text?.length ?: workingText.length,
                selectionStart = surrounding?.selectionStart ?: node.textSelectionStart,
                selectionEnd = surrounding?.selectionEnd ?: node.textSelectionEnd,
                updated = updated,
            )
        }
        if (!replacementRequested) {
            return showFailure(R.string.external_replace_failed)
        }
        fun verifyReplacement(attempt: Int) {
            if (
                !replacementVerificationWork.isCurrent(verificationToken) ||
                !reviewSurfaceExecutor.isCurrentCallback(surfaceCallbackToken) ||
                reviewSession !== session ||
                panelView == null
            ) return
            val verifiedTarget = reacquireTarget(operationTarget)
            val verifiedNode = verifiedTarget?.node
            verifiedNode?.refresh()
            if (verifiedTarget != null && verifiedNode !== operationTarget.node) {
                operationTarget = verifiedTarget
                setTarget(operationTarget)
            }
            val freshSurrounding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                connection != null
            ) {
                runCatching {
                    connection.getSurroundingText(
                        maxOf(workingText.length, updated.length),
                        maxOf(workingText.length, updated.length),
                        0,
                    )
                }.getOrNull()
            } else null
            val freshConnectionText = freshSurrounding?.text?.toString()
            val connectionVerified = freshConnectionText == updated
            val nodeVerified = verifiedNode?.text?.toString().orEmpty() == updated
            when (ReplacementVerificationPolicy.nextAction(
                nodeVerified = nodeVerified,
                connectionVerified = connectionVerified,
                attempt = attempt,
                maxAttempts = REPLACEMENT_VERIFY_ATTEMPTS,
                connectionFallbackAttempted = connectionFallbackAttempted,
                freshConnectionMatchesSource = freshConnectionText == workingText,
            )) {
                ReplacementVerificationAction.TRY_FRESH_CONNECTION -> {
                    connectionFallbackAttempted = true
                    val fallbackRequested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        connection != null && freshSurrounding != null
                    ) {
                        requestInputConnectionTextReplacement(
                            connection = connection,
                            sourceLength = freshSurrounding.text.length,
                            selectionStart = freshSurrounding.selectionStart,
                            selectionEnd = freshSurrounding.selectionEnd,
                            updated = updated,
                        )
                    } else false
                    if (!fallbackRequested) return showFailure(R.string.external_replace_failed)
                    replacementVerificationWork.schedule(
                        verificationToken,
                        REPLACEMENT_VERIFY_INTERVAL_MS,
                    ) { verifyReplacement(attempt + 1) }
                    return
                }
                ReplacementVerificationAction.RETRY -> {
                    replacementVerificationWork.schedule(
                        verificationToken,
                        REPLACEMENT_VERIFY_INTERVAL_MS,
                    ) { verifyReplacement(attempt + 1) }
                    return
                }
                ReplacementVerificationAction.FAIL -> {
                    showFailure(
                        if (verifiedNode == null) R.string.external_target_lost
                        else R.string.external_replace_unverified,
                    )
                    return
                }
                ReplacementVerificationAction.COMPLETE -> Unit
            }
            val advanced = try {
                if (changeId == null) {
                    session.replaceCurrent(workingText, toneAtRequest)
                } else {
                    session.acceptChange(workingText, toneAtRequest, changeId)
                }
            } catch (_: IllegalArgumentException) {
                return showFailure(R.string.external_text_changed)
            }
            if (advanced != updated) return showFailure(R.string.external_text_changed)
            workingText = updated
            val updatedSnapshot = ExternalWritingPolicy.capturedSnapshot(
                snapshot = operationTarget.snapshot,
                text = updated,
                selectionStart = updated.length,
                selectionEnd = updated.length,
            ) ?: return showFailure(R.string.external_replace_failed)
            setTarget(operationTarget.copy(snapshot = updatedSnapshot))
            replacementInFlight = false
            replacementVerificationWork.cancelAll()
            setReplacementControlsEnabled(true)
            activeReviewChangeId = null
            renderCurrentBlock()
        }
        replacementVerificationWork.schedule(
            verificationToken,
            REPLACEMENT_VERIFY_INTERVAL_MS,
        ) { verifyReplacement(0) }
    }

    private fun acceptCurrentChange() {
        if (replacementInFlight) return
        replaceCurrent(activeReviewChangeId ?: return)
    }

    private fun requestAccessibilityTextReplacement(
        node: AccessibilityNodeInfo,
        updated: String,
    ): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestInputConnectionTextReplacement(
        connection: InputMethod.AccessibilityInputConnection,
        sourceLength: Int,
        selectionStart: Int,
        selectionEnd: Int,
        updated: String,
    ): Boolean = runCatching {
        val safeLength = sourceLength.coerceAtLeast(0)
        val safeStart = selectionStart.coerceIn(0, safeLength)
        val safeEnd = selectionEnd.coerceIn(safeStart, safeLength)
        connection.deleteSurroundingText(safeStart, safeLength - safeEnd)
        connection.commitText(updated, 1, null)
        true
    }.getOrDefault(false)

    private fun dismissCurrent() {
        if (replacementInFlight) return
        reviewSession?.dismissCurrent()
        activeReviewChangeId = null
        renderCurrentBlock()
    }

    private fun rejectCurrentChange() {
        if (replacementInFlight) return
        val session = reviewSession ?: return
        val changeId = activeReviewChangeId ?: return
        runCatching {
            session.rejectChange(workingText, selectedTone, changeId)
        }.onSuccess {
            activeReviewChangeId = null
            renderCurrentBlock(requestModel = false)
        }.onFailure {
            showFailure(R.string.external_text_changed)
        }
    }

    private fun showFailure(message: Int) {
        Log.w(REPLACEMENT_LOG_TAG, resources.getResourceEntryName(message))
        replacementVerificationWork.cancelAll()
        replacementInFlight = false
        setReplacementControlsEnabled(true)
        statusView?.apply {
            text = getString(message)
            visibility = View.VISIBLE
        }
    }

    private fun setReplacementControlsEnabled(enabled: Boolean) {
        replaceButton?.isEnabled = enabled
        dismissButton?.isEnabled = enabled
        acceptChangeButton?.isEnabled = enabled
        rejectChangeButton?.isEnabled = enabled
    }

    private fun reacquireTarget(captured: Target): Target? {
        val node = if (captured.node.refresh() &&
            captured.node.packageName?.toString() == captured.packageName &&
            captured.node.isFocused &&
            captured.node.isEditable &&
            !captured.node.isPassword
        ) {
            captured.node
        } else {
            val root = rootForPackage(captured.packageName) ?: return null
            findMatchingEditable(root, captured) ?: return null
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) {
            recycleIfDetached(node, captured.node)
            return null
        }
        val reacquired = acquireTarget(
            packageName = captured.packageName,
            viewId = node.viewIdResourceName,
            bounds = bounds,
            snapshot = captured.snapshot,
            node = node,
        )
        if (reacquired?.reviewTargetId != captured.reviewTargetId) {
            recycleIfDetached(node, captured.node)
            return null
        }
        return reacquired
    }

    private fun acquireTarget(
        packageName: String,
        viewId: String?,
        bounds: Rect,
        snapshot: ExternalEditorSnapshot,
        node: AccessibilityNodeInfo,
        applicationRoot: AccessibilityNodeInfo? = null,
    ): Target? {
        val ownsApplicationRoot = applicationRoot == null
        val resolvedApplicationRoot = applicationRoot
            ?: verifiedApplicationRoot(packageName, node)
            ?: return null
        return try {
            val reviewTargetId = ExternalFieldIdentity.from(
                packageName = packageName,
                platformUniqueId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    node.uniqueId
                } else {
                    null
                },
                viewId = viewId,
                structuralLocator = AccessibilityNodeStructuralLocator.from(
                    resolvedApplicationRoot,
                    node,
                ),
            )?.asReviewTargetId() ?: return null
            Target(
                reviewTargetId = reviewTargetId,
                packageName = packageName,
                viewId = viewId,
                bounds = Rect(bounds),
                snapshot = snapshot,
                node = node,
            )
        } finally {
            if (ownsApplicationRoot) recycleLegacy(resolvedApplicationRoot)
        }
    }

    private fun verifiedApplicationRoot(
        candidatePackage: String,
        candidateNode: AccessibilityNodeInfo,
    ): AccessibilityNodeInfo? {
        if (
            candidatePackage.isBlank() ||
            candidateNode.packageName?.toString() != candidatePackage
        ) return null
        val matchingWindows = windows.filter { window ->
            window.id == candidateNode.windowId &&
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }
        if (matchingWindows.size != 1) return null
        val applicationWindow = matchingWindows.single()
        val applicationRoot = applicationWindow.root ?: return null
        if (
            applicationRoot.windowId != candidateNode.windowId ||
            applicationRoot.packageName?.toString() != candidatePackage
        ) {
            recycleLegacy(applicationRoot)
            return null
        }
        return applicationRoot
    }

    private fun currentReviewTargetId(): ReviewTargetId? = when (val state = reviewSurfaceController.state) {
        is ReviewSurfaceState.TriggerVisible -> state.targetId
        is ReviewSurfaceState.Opening -> state.targetId
        is ReviewSurfaceState.ReviewOpen -> state.targetId
        is ReviewSurfaceState.Closing -> state.retainedTarget.id
        is ReviewSurfaceState.RestoringFocus -> state.targetId
        ReviewSurfaceState.Hidden,
        ReviewSurfaceState.Destroyed,
        -> null
    }

    private fun synchronizeReviewSurfaceTarget(candidate: Target): Boolean {
        cancelKeyboardRestore()
        val candidateId = candidate.reviewTargetId
        val currentId = currentReviewTargetId()
        if (currentId != null && currentId != candidateId) {
            val switchedApplication = target?.packageName != candidate.packageName
            if (switchedApplication) {
                reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.ApplicationSwitched)
                clearTargetRetaining(candidate.node)
            } else {
                clearTargetRetaining(candidate.node)
                reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
            }
        }
        setTarget(candidate)
        reviewSurfaceExecutor.dispatch(
            ReviewSurfaceEvent.FocusedTargetAvailable(
                ReviewTarget(candidateId, ReviewTargetEligibility.ELIGIBLE),
            ),
        )
        if (reviewSurfaceController.state is ReviewSurfaceState.TriggerVisible) {
            showTrigger(candidate.bounds)
        }
        return true
    }

    private fun reacquireReviewTarget(mode: ReviewTargetReacquisition): ReviewTarget {
        val expectedTargetId = (mode as? ReviewTargetReacquisition.RestoreExact)?.targetId
        val captured = target
        val node = focusedEditableNode()
            ?: return ReviewTarget(null, ReviewTargetEligibility.MISSING)
        val packageName = node.packageName?.toString().orEmpty()
        val snapshot = ExternalWritingPolicy.metadataSnapshot(
            packageName = packageName,
            text = editableText(node),
            editable = node.isEditable,
            password = node.isPassword,
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
        )
        val baseEligibility = when {
            node.isPassword -> ReviewTargetEligibility.PASSWORD
            !ExternalWritingPolicy.canOffer(snapshot, this.packageName) ->
                ReviewTargetEligibility.INELIGIBLE
            else -> ReviewTargetEligibility.ELIGIBLE
        }
        val eligibility = FocusedReviewTargetPolicy.resolveEligibility(
            baseEligibility = baseEligibility,
            expectedTargetId = expectedTargetId,
            retainedPackage = captured?.packageName,
            focusedPackage = packageName,
        )
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) {
            recycleIfDetached(node, captured?.node)
            return ReviewTarget(null, ReviewTargetEligibility.DETACHED)
        }
        val candidate = acquireTarget(
            packageName = packageName,
            viewId = node.viewIdResourceName,
            bounds = bounds,
            snapshot = snapshot,
            node = node,
        )
        if (candidate == null) {
            recycleIfDetached(node, captured?.node)
            return ReviewTarget(null, ReviewTargetEligibility.DETACHED)
        }
        val targetId = candidate.reviewTargetId
        if (!FocusedReviewTargetPolicy.accepts(
                isInputFocused = node.isFocused,
                eligibility = eligibility,
                actualTargetId = targetId,
                expectedTargetId = expectedTargetId,
            )
        ) {
            recycleIfDetached(node, captured?.node)
            return ReviewTarget(
                null,
                if (eligibility == ReviewTargetEligibility.ELIGIBLE) {
                    ReviewTargetEligibility.DETACHED
                } else {
                    eligibility
                },
            )
        }
        setTarget(candidate)
        return ReviewTarget(targetId, ReviewTargetEligibility.ELIGIBLE)
    }

    private fun restoreKeyboardToReacquiredTarget(targetId: ReviewTargetId) {
        val current = target ?: return
        if (current.reviewTargetId != targetId) return
        val node = current.node
        if (!node.isEditable || node.isPassword) return
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun showTriggerForReacquiredTarget(targetId: ReviewTargetId) {
        val current = target
        if (
            current == null ||
            current.reviewTargetId != targetId ||
            !ExternalWritingPolicy.canOffer(current.snapshot, packageName)
        ) {
            reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
            return
        }
        showTrigger(current.bounds)
    }

    private fun rootForPackage(targetPackage: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() == targetPackage) return root
            recycleLegacy(root)
        }
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            if (root.packageName?.toString() == targetPackage) return root
            recycleLegacy(root)
        }
        return null
    }

    private fun findMatchingEditable(
        root: AccessibilityNodeInfo,
        captured: Target,
    ): AccessibilityNodeInfo? {
        val visited = mutableListOf<AccessibilityNodeInfo>()
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val budget = AccessibilityTraversalBudget()
        fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (!budget.tryVisit(depth)) return false
            visited += node
            if (
                node.isFocused &&
                node.isEditable &&
                !node.isPassword &&
                node.packageName?.toString() == captured.packageName
            ) {
                candidates += node
            }
            if (!budget.canDescendFrom(depth)) return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                if (!visit(child, depth + 1)) {
                    recycleLegacy(child)
                    break
                }
            }
            return true
        }
        visit(root, 0)
        val idMatches = if (captured.viewId.isNullOrBlank()) emptyList() else {
            candidates.filter { it.viewIdResourceName == captured.viewId }
        }
        val selected = (idMatches.ifEmpty { candidates }).minByOrNull { candidate ->
            targetCandidateScore(candidate, captured)
        }
        visited.forEach { recycleIfDetached(it, selected) }
        return selected
    }

    private fun targetCandidateScore(node: AccessibilityNodeInfo, captured: Target): Long {
        val bounds = Rect().also(node::getBoundsInScreen)
        return EditorTargetSelectionPolicy.score(
            textMatches = captured.snapshot.text.isNotEmpty() &&
                editableText(node)?.toString().orEmpty() == captured.snapshot.text,
            focused = node.isFocused,
            horizontalDistancePx = kotlin.math.abs(bounds.centerX() - captured.bounds.centerX()),
            verticalDistancePx = kotlin.math.abs(bounds.centerY() - captured.bounds.centerY()),
        )
    }

    private fun closePanel(restoreKeyboard: Boolean) {
        cancelKeyboardRestore()
        reviewSurfaceExecutor.dispatch(
            if (restoreKeyboard) ReviewSurfaceEvent.CloseRequested
            else ReviewSurfaceEvent.TargetLost,
        )
    }

    private fun announceTriggerMiss() {
        android.widget.Toast.makeText(
            this,
            getString(R.string.external_target_lost),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    private fun clearTarget() {
        cancelTargetClear()
        cancelKeyboardRestore()
        val previous = target
        target = null
        workingText = ""
        previous?.node?.let(::recycleLegacy)
        removeTrigger()
    }

    private fun clearTargetRetaining(retainedNode: AccessibilityNodeInfo) {
        cancelTargetClear()
        cancelKeyboardRestore()
        val previous = target
        target = null
        workingText = ""
        previous?.node?.let { recycleIfDetached(it, retainedNode) }
        removeTrigger()
    }

    private fun scheduleTargetClear() {
        val current = target ?: return
        val remainingMillis = targetLossDeadline.arm(
            current.reviewTargetId,
            android.os.SystemClock.uptimeMillis(),
        )
        mainHandler.removeCallbacks(deferredTargetClear)
        mainHandler.postDelayed(deferredTargetClear, remainingMillis)
    }

    private fun cancelTargetClear() {
        mainHandler.removeCallbacks(deferredTargetClear)
        targetLossDeadline.cancel()
    }

    private fun deactivateTerminalReviewSurface(ownership: TerminalDetachOwnership) {
        ownership.panel?.let(::deactivateTerminalView)
        ownership.trigger?.let { trigger ->
            TriggerPulseController.stop(trigger)
            deactivateTerminalView(trigger)
        }
        if (panelView === ownership.panel) panelView = null
        if (triggerView === ownership.trigger) triggerView = null
    }

    private fun deactivateTerminalView(view: View) {
        view.visibility = View.GONE
        view.alpha = 0f
        view.isEnabled = false
        view.isClickable = false
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = params.flags or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun detachTerminalReviewSurface(ownership: TerminalDetachOwnership): Boolean {
        val panelRemoval = ownership.panel?.let {
            runCatching { windowManager.removeViewImmediate(it) }
        }
        val triggerRemoval = ownership.trigger?.let {
            runCatching { windowManager.removeViewImmediate(it) }
        }
        if (
            ownership.panel?.isAttachedToWindow != true &&
            ownership.trigger?.isAttachedToWindow != true
        ) {
            return true
        }
        val failureType = panelRemoval?.exceptionOrNull()?.javaClass?.simpleName
            ?: triggerRemoval?.exceptionOrNull()?.javaClass?.simpleName
            ?: "still-attached"
        Log.w(
            REVIEW_LOG_TAG,
            "kind=surface-effect;effect=TerminalDetach;outcome=STILL_ATTACHED" +
                ";failure=$failureType",
        )
        return false
    }

    private fun releaseTerminalReviewSurfaceOwnership(ownership: TerminalDetachOwnership) {
        Log.i(
            REVIEW_LOG_TAG,
            "kind=surface-effect;effect=TerminalDetach;outcome=REMOVED" +
                ";panelOwned=${ownership.panel != null};triggerOwned=${ownership.trigger != null}",
        )
        if (reviewSurfaceController.state != ReviewSurfaceState.Destroyed) {
            reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.ServiceReconnected)
        }
    }

    private fun cancelKeyboardRestore() {
        retainedInputConnection = null
    }

    private fun setTarget(next: Target) {
        val previous = target
        target = next
        if (previous != null) recycleIfDetached(previous.node, next.node)
    }

    private fun recycleIfDetached(node: AccessibilityNodeInfo, retained: AccessibilityNodeInfo?) {
        if (node !== retained) recycleLegacy(node)
    }

    @Suppress("DEPRECATION")
    private fun recycleLegacy(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }

    private fun removeTrigger() {
        val trigger = triggerView ?: return
        TriggerPulseController.applyState(
            trigger,
            ManuscriptTriggerState.HIDDEN_UNFOCUSED,
        )
        runCatching { windowManager.removeViewImmediate(trigger) }
        if (!trigger.isAttachedToWindow) {
            triggerView = null
        }
    }

    private fun parkTriggerWindowForReview() {
        val trigger = triggerView ?: return
        val params = trigger.layoutParams as? WindowManager.LayoutParams ?: run {
            removeTrigger()
            return
        }
        TriggerPulseController.stop(trigger)
        trigger.visibility = View.VISIBLE
        trigger.alpha = 0f
        trigger.isClickable = false
        trigger.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (runCatching { windowManager.updateViewLayout(trigger, params) }.isFailure) {
            removeTrigger()
        }
    }

    private fun label(textRes: Int) = TextView(this).apply {
        text = getString(textRes)
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(COLOR_TEXT_MUTED)
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun body() = TextView(this).apply {
        textSize = 15f
        setTextColor(COLOR_TEXT)
        maxLines = 4
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = rounded(COLOR_SURFACE, 10f, COLOR_BORDER)
    }

    private fun actionButton(textRes: Int, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = getString(textRes)
        contentDescription = text
        isAllCaps = false
        textSize = 14f
        minHeight = dp(40)
        minimumHeight = dp(40)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) COLOR_BACKGROUND else COLOR_TEXT)
        background = rounded(if (primary) COLOR_ACCENT else COLOR_SURFACE, 12f, COLOR_ACCENT)
        setOnClickListener { action() }
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun weighted(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
    ).apply {
        marginStart = dp(start)
        marginEnd = dp(end)
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int, strokeWidthDp: Int = 1) =
        ManuscriptComponents.rounded(this, fill, radiusDp.toInt(), stroke, strokeWidthDp)

    private fun dp(value: Int) = manuscriptDp(value)

    private data class Target(
        val reviewTargetId: ReviewTargetId,
        val packageName: String,
        val viewId: String?,
        val bounds: Rect,
        val snapshot: ExternalEditorSnapshot,
        val node: AccessibilityNodeInfo,
    )

    private data class TerminalDetachOwnership(
        val panel: View?,
        val trigger: View?,
    )

    private inner class VerifiedApplicationEventContext(
        val metadata: VerifiedApplicationEventMetadata,
        val applicationRoot: AccessibilityNodeInfo,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            recycleLegacy(applicationRoot)
        }
    }

    private data class SystemBottomInsets(
        val navigationBottomPx: Int,
        val gestureBottomPx: Int,
        val imeBottomInsetPx: Int,
    ) {
        val protectedBottomPx: Int = maxOf(navigationBottomPx, gestureBottomPx)
    }

    private companion object {
        const val EXTRA_OPEN_PERSONALIZE = "com.remote.Manuscript.v2.OPEN_PERSONALIZE"
        const val EXTRA_OPEN_MODEL_SETUP = "com.remote.Manuscript.v2.OPEN_MODEL_SETUP"
        const val PREFERENCES_NAME = "manuscript_preferences_v1"
        const val TONE_KEY = "writing_tone"
        const val KEYBOARD_HIDE_DELAY_MS = 80L
        const val REPLACEMENT_VERIFY_INTERVAL_MS = 350L
        const val REPLACEMENT_VERIFY_ATTEMPTS = 8
        const val REPLACEMENT_LOG_TAG = "ManuscriptReplace"
        const val REVIEW_LOG_TAG = "ManuscriptReview"
        const val TARGET_CLEAR_GRACE_MS = 800L
        const val TERMINAL_DETACH_RETRY_MS = 16L
        const val TERMINAL_DETACH_COMPLETION_DEADLINE_MS = 64L
        const val MAX_TERMINAL_DETACH_SCHEDULED_RETRIES = 3
        const val MAX_TERMINAL_DETACH_CLEANUP_RETRIES = 8
        const val TERMINAL_DETACH_EVENT_RETRY_INTERVAL_MS = 250L
        const val RAW_EVENT_DIAGNOSTIC_INTERVAL_MS = 250L
        const val PRESS_GUARD_MS = 600L
        /**
         * The panel's edge treatment, which is what makes it legible over an
         * application Manuscript does not control. See buildPanel.
         */
        const val PANEL_BORDER_DP = 2
        const val PANEL_ELEVATION_DP = 8

        const val COLOR_BACKGROUND = ManuscriptPalette.BACKGROUND
        const val COLOR_SURFACE = ManuscriptPalette.SURFACE_RAISED
        const val COLOR_SURFACE_FOCUSED = ManuscriptPalette.SURFACE_FOCUSED
        const val COLOR_BORDER = ManuscriptPalette.BORDER
        const val COLOR_ACCENT = ManuscriptPalette.ACCENT
        const val COLOR_ACCENT_DARK = ManuscriptPalette.ACCENT_DARK
        const val COLOR_TEXT = ManuscriptPalette.TEXT
        const val COLOR_TEXT_MUTED = ManuscriptPalette.MUTED_TEXT
        const val COLOR_ERROR = ManuscriptPalette.ERROR
        const val COLOR_REMOVED = ManuscriptPalette.REMOVED
        const val COLOR_INSERTED = ManuscriptPalette.INSERTED
        const val COLOR_INSERTED_BACKGROUND = ManuscriptPalette.INSERTED_BACKGROUND
    }
}
