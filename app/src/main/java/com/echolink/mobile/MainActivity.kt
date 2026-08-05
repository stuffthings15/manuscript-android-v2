package com.echolink.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.Log
import android.text.TextPaint
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UpdateAppearance
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var writeTab: Button
    private lateinit var personalizeTab: Button
    private lateinit var editor: EditText
    private lateinit var correctButton: Button
    private var speechSession: OnDeviceSpeechSession? = null
    private var dictateButton: Button? = null
    private var dictationStatus: TextView? = null
    private lateinit var assistantTrigger: ImageButton
    private lateinit var mainToneMenu: HorizontalScrollView
    private lateinit var reviewToneMenu: HorizontalScrollView
    private lateinit var correctionCard: LinearLayout
    private lateinit var reviewCounter: TextView
    private lateinit var reviewSuggestion: TextView
    private lateinit var reviewNote: TextView
    private lateinit var reviewProgress: ProgressBar
    private var progressStartedAt = 0L
    private var progressSectionStartedAt = 0L
    private var progressCompleted = 0
    private var progressTotal = 0
    private var progressPerSectionMs = 0L
    private lateinit var replaceButton: Button
    private lateinit var dismissButton: Button
    private lateinit var acceptChangeButton: Button
    private lateinit var rejectChangeButton: Button
    private lateinit var acceptAllButton: Button
    private lateinit var undoReviewButton: Button
    private lateinit var restoreOriginalButton: Button
    private lateinit var localToolsStatus: TextView
    private lateinit var vocabularySpoken: EditText
    private lateinit var vocabularyWritten: EditText
    private lateinit var vocabularyCategory: EditText
    private lateinit var vocabularySummary: TextView
    private lateinit var snippetTrigger: EditText
    private lateinit var snippetContent: EditText
    private lateinit var snippetSummary: TextView
    private var externalAccessStatus: TextView? = null
    private var externalAccessButton: Button? = null
    private lateinit var qwenStatus: TextView
    private lateinit var qwenDownloadDetail: TextView
    private lateinit var qwenDownloadProgress: TextView
    private lateinit var qwenRetryButton: Button
    private lateinit var qwenCancelButton: Button
    private lateinit var qwenInstallButton: Button
    private var displayedDownloadTarget: DownloadTargetKey? = null
    private var displayedDownloadAttempt: DownloadAttempt? = null
    private var downloadUiFallbackRetry = false
    private var pendingReviewAttach: Runnable? = null
    private var correctionCardOverlay: ScrollView? = null

    private lateinit var localTools: AndroidLocalPersonalToolsStore
    private lateinit var draftStore: EncryptedEditorDraftStore
    private var mainScroll: ScrollView? = null
    private var reviewSession: WritingReviewSession? = null
    private var activeTargetSpan: TargetUnderlineSpan? = null
    private val mainToneButtons = linkedMapOf<WritingTone, Button>()
    private val reviewToneButtons = linkedMapOf<WritingTone, Button>()
    private var internalTextChange = false
    private var iconWasClicked = false
    private var selectedTone = WritingTone.PROOFREAD
    private var currentPage = Page.WRITE
    private var accessPromptVisible = false
    private var openingAccessibilitySettings = false
    private var modelPromptShown = false
    private var startupCheckShown = false
    private var activityResumed = false
    private var startupCheckDialog: AlertDialog? = null
    private val grammarEngine by lazy { OnDeviceGrammarEngine.get(this) }
    private val modelRequests = RewriteRequestBatch()
    private var modelRequestEpoch = 0
    private var activeReviewJobId: String? = null
    private var activeReviewChangeId: String? = null
    private val reviewSurfaceController = ReviewSurfaceController()
    private val reviewSurfaceExecutor by lazy {
        ReviewSurfaceEffectExecutor(
            reviewSurfaceController,
            object : ReviewSurfaceEffectActions {
                override fun cancelStaleCorrectionAndCallbackWork() {
                    mainKeyboardRestore.cancel()
                    pendingReviewAttach?.let { pending ->
                        if (::root.isInitialized) root.removeCallbacks(pending)
                    }
                    pendingReviewAttach = null
                    grammarEngine.setSessionWarm(false)
                    modelRequests.cancelAll()
                    modelRequestEpoch += 1
                    activeReviewJobId = null
                }

                override fun detachReviewPanelSafely(): ReviewPanelDetachOutcome {
                    clearTargetUnderline()
                    if (::correctionCard.isInitialized) {
                        // A hidden focused card can keep focus until the next
                        // traversal, which strands the trigger after Close.
                        // Release focus before detaching so the retained editor
                        // can be reacquired in this same close transaction.
                        correctionCard.clearFocus()
                        correctionCard.visibility = View.GONE
                        correctionCardOverlay?.visibility = View.GONE
                    }
                    return ReviewPanelDetachOutcome.REMOVED
                }

                override fun terminallyDetachReviewSurface(onTerminalCompletion: () -> Unit) {
                    clearTargetUnderline()
                    if (::correctionCard.isInitialized) correctionCard.visibility = View.GONE
                    correctionCardOverlay?.visibility = View.GONE
                    onTerminalCompletion()
                }

                override fun scrubTransientTextHolders() {
                    reviewSession = null
                    activeReviewChangeId = null
                    iconWasClicked = false
                    if (::reviewSuggestion.isInitialized) reviewSuggestion.text = ""
                    if (::reviewNote.isInitialized) reviewNote.text = ""
                    hideReviewProgress()
                }

                override fun reacquireFocusedTarget(mode: ReviewTargetReacquisition): ReviewTarget {
                    val eligibility = mainEditorEligibility()
                    val expectedTargetId = (mode as? ReviewTargetReacquisition.RestoreExact)?.targetId
                    return ReviewTarget(
                        id = MAIN_EDITOR_TARGET_ID.takeIf {
                            eligibility == ReviewTargetEligibility.ELIGIBLE &&
                                (expectedTargetId == null || expectedTargetId == it)
                        },
                        eligibility = eligibility,
                    )
                }

                override fun restoreKeyboard(targetId: ReviewTargetId) {
                    if (targetId != MAIN_EDITOR_TARGET_ID || mainEditorEligibility() != ReviewTargetEligibility.ELIGIBLE) {
                        return
                    }
                    editor.requestFocus()
                    mainKeyboardRestore.schedule(targetId)
                }

                override fun renderReviewSurfaceState(state: ReviewSurfaceState) {
                    if (!::assistantTrigger.isInitialized) return
                    when (state) {
                        is ReviewSurfaceState.TriggerVisible -> updateAssistantTrigger()
                        ReviewSurfaceState.Hidden -> updateAssistantTrigger()
                        else -> assistantTrigger.visibility = View.GONE
                    }
                }
            },
        )
    }
    private val mainKeyboardRestore by lazy {
        ReviewKeyboardRestore(
            scheduler = object : ReviewKeyboardRestoreScheduler {
                override fun post(runnable: Runnable) {
                    if (::editor.isInitialized) editor.post(runnable)
                }

                override fun remove(runnable: Runnable) {
                    if (::editor.isInitialized) editor.removeCallbacks(runnable)
                }
            },
            currentState = { reviewSurfaceController.state },
            isEligible = { targetId ->
                targetId == MAIN_EDITOR_TARGET_ID &&
                    mainEditorEligibility() == ReviewTargetEligibility.ELIGIBLE
            },
            restore = { showSystemKeyboard(editor) },
        )
    }
    private val statusHandler = Handler(Looper.getMainLooper())
    private val draftSave = Runnable { persistDraftNow() }
    private val triggerRefresh = Runnable {
        if (::editor.isInitialized) updateAssistantTrigger()
    }
    private val prefetchCorrections = Runnable {
        // Correct-while-typing: after a typing pause, silently run the
        // document through the engine so the correction cache is warm and
        // the visible run returns instantly for unchanged paragraphs.
        if (
            currentPage == Page.WRITE &&
            ::editor.isInitialized &&
            correctionCardOrNull()?.visibility != View.VISIBLE &&
            AiModelManager.get(this).installedModel() != null
        ) {
            val text = editor.text?.toString().orEmpty()
            if (text.length >= PREFETCH_MIN_CHARS) {
                grammarEngine.prefetchDocument(text, selectedTone)
            }
        }
    }
    private val qwenStatusRefresh = object : Runnable {
        override fun run() {
            if (!::qwenStatus.isInitialized) return
            refreshDownloadTelemetry()
            refreshStartupReadinessDialog()
            if (activityResumed) {
                statusHandler.postDelayed(this, 1_000L)
            }
        }
    }

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (currentPage != Page.WRITE || !::editor.isInitialized) return@OnGlobalFocusChangeListener
        val state = reviewSurfaceController.state
        if (
            newFocus == null &&
            (state is ReviewSurfaceState.Closing || state is ReviewSurfaceState.RestoringFocus)
        ) {
            // Detaching the focused card briefly reports no focused view.
            // This is the expected handoff to the retained editor, not target
            // loss. Invalidating here strands the trigger in Hidden.
            return@OnGlobalFocusChangeListener
        }
        val insideCard = newFocus != null && isDescendant(newFocus, correctionCard)
        val insideToneMenu = newFocus != null &&
            ::mainToneMenu.isInitialized &&
            isDescendant(newFocus, mainToneMenu)
        if (newFocus !== editor && newFocus !== assistantTrigger && !insideCard && !insideToneMenu) {
            invalidateMainReviewSurface(ReviewSurfaceEvent.TargetLost)
        }
        updateAssistantTrigger()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startupCheckShown = savedInstanceState != null
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        configureEdgeToEdge()
        localTools = AndroidLocalPersonalToolsStore(this)
        draftStore = EncryptedEditorDraftStore(this)
        selectedTone = WritingTone.fromLabel(
            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .getString(TONE_KEY, WritingTone.PROOFREAD.label).orEmpty(),
        )
        buildShell()
        resumeActiveDownload()
        refreshDownloadTelemetry()
        val openModelSetup = intent.getBooleanExtra(EXTRA_OPEN_MODEL_SETUP, false)
        if (intent.getBooleanExtra(EXTRA_OPEN_PERSONALIZE, false)) {
            showPersonalizePage()
        } else {
            showWritePage()
        }
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        runStartupReadinessCheck(showDialog = savedInstanceState == null)
        if (openModelSetup) {
            root.postDelayed({ requestModelInstallation() }, STARTUP_DIALOG_HANDOFF_DELAY_MS)
        }
        if (BuildConfig.SUBSCRIPTION_ENFORCED) {
            root.visibility = View.INVISIBLE
            startActivityForResult(
                Intent(this, AccountActivity::class.java).putExtra(
                    AccountActivity.EXTRA_VERIFY_ONLY,
                    true,
                ),
                AccountActivity.REQUEST_VERIFY_ACCOUNT,
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AccountActivity.REQUEST_VERIFY_ACCOUNT) return
        if (resultCode == RESULT_OK) {
            root.visibility = View.VISIBLE
        } else {
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when {
            intent.getBooleanExtra(EXTRA_OPEN_PERSONALIZE, false) -> showPersonalizePage()
            intent.getBooleanExtra(EXTRA_OPEN_MODEL_SETUP, false) -> {
                showWritePage()
                root.postDelayed({ requestModelInstallation() }, STARTUP_DIALOG_HANDOFF_DELAY_MS)
            }
        }
    }

    override fun onDestroy() {
        if (::root.isInitialized && root.viewTreeObserver.isAlive) {
            root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        }
        mainKeyboardRestore.cancel()
        reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.Destroy)
        statusHandler.removeCallbacks(draftSave)
        statusHandler.removeCallbacks(triggerRefresh)
        statusHandler.removeCallbacks(prefetchCorrections)
        stopDownloadTelemetryUpdates()
        grammarEngine.cancelPrefetch()
        speechSession?.close()
        speechSession = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            STATE_REVIEW_OPEN,
            correctionCardOrNull()?.visibility == View.VISIBLE && reviewSession != null,
        )
        outState.putString(STATE_REVIEW_JOB_ID, activeReviewJobId)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (!savedInstanceState.getBoolean(STATE_REVIEW_OPEN, false)) return
        val jobId = savedInstanceState.getString(STATE_REVIEW_JOB_ID) ?: return
        activeReviewJobId = jobId
        restoreEncryptedReview(jobId)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        updateExternalAccessStatus()
        refreshDownloadTelemetry()
        startDownloadTelemetryUpdates()
        if (::root.isInitialized) {
            root.postDelayed({ showStartupReadinessCheckIfNeeded() }, 500L)
        }
    }

    override fun onPause() {
        activityResumed = false
        stopDownloadTelemetryUpdates()
        persistDraftNow()
        speechSession?.stop()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        OnDeviceGrammarEngine.trimIfCreated(level)
        super.onTrimMemory(level)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::editor.isInitialized) editor.post { updateAssistantTrigger() }
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            id = R.id.main_content
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        applySystemBarInsets(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_manuscript)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) })
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                id = R.id.main_heading
                text = getString(R.string.app_name)
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(COLOR_TEXT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
            })
            addView(TextView(this@MainActivity).apply {
                id = R.id.app_slogan
                text = getString(R.string.app_slogan)
                textSize = 14f
                setTextColor(COLOR_ACCENT)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(Button(this).apply {
            text = getString(R.string.account_open)
            contentDescription = text
            isAllCaps = false
            minHeight = dp(MIN_TOUCH_TARGET_DP)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AccountActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val privacy = TextView(this).apply {
            id = R.id.privacy_summary
            text = getString(R.string.privacy_summary)
            textSize = 13f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(dp(20), 0, dp(20), dp(10))
        }
        qwenStatus = TextView(this).apply {
            id = R.id.local_ai_download_status
            textSize = 12f
            setTextColor(COLOR_ACCENT)
            setPadding(dp(20), 0, dp(20), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val manager = AiModelManager.get(this@MainActivity)
                if (manager.exactInstalledModel(manager.selectedRuntime()) == null) {
                    showModelInstallPromptIfNeeded(force = true)
                } else {
                    showModelSettings()
                }
            }
        }
        qwenDownloadDetail = TextView(this).apply {
            id = R.id.local_ai_download_detail
            textSize = 12f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(dp(20), 0, dp(20), dp(4))
        }
        qwenDownloadProgress = TextView(this).apply {
            id = R.id.local_ai_download_progress
            textSize = 12f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(dp(20), 0, dp(20), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        qwenRetryButton = primaryButton(R.string.qwen_model_retry_action) {
            performDownloadAction(DownloadAction.RETRY)
        }.apply {
            id = R.id.local_ai_retry_action
            visibility = View.GONE
        }
        qwenCancelButton = primaryButton(R.string.qwen_download_cancel) {
            performDownloadAction(DownloadAction.CANCEL)
        }.apply {
            id = R.id.local_ai_cancel_action
            visibility = View.GONE
        }
        val qwenDownloadActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), 0, dp(20), dp(8))
            addView(qwenRetryButton, weightedParams(endMargin = 6))
            addView(qwenCancelButton, weightedParams(startMargin = 6))
        }
        qwenInstallButton = primaryButton(R.string.qwen_model_install_action) {
            requestModelInstallation()
        }.apply {
            id = R.id.local_ai_install_action
            contentDescription = getString(R.string.qwen_model_install_action)
        }

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(10))
        }
        writeTab = tabButton(R.string.write_tab) { showWritePage() }
        personalizeTab = tabButton(R.string.personalize_tab) { showPersonalizePage() }
        tabs.addView(writeTab, weightedParams(endMargin = 6))
        tabs.addView(personalizeTab, weightedParams(startMargin = 6))

        pageHost = FrameLayout(this).apply { id = R.id.page_host }
        root.addView(header)
        root.addView(privacy)
        root.addView(qwenStatus)
        root.addView(qwenDownloadDetail)
        root.addView(qwenDownloadProgress)
        root.addView(qwenDownloadActions)
        root.addView(qwenInstallButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp(20)
            marginEnd = dp(20)
            bottomMargin = dp(8)
        })
        root.addView(tabs)
        root.addView(pageHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        setContentView(root)
        updateQwenStatus()
    }

    private fun updateQwenStatus() {
        val presentation = currentDownloadPresentation()
        renderDownloadTelemetry(presentation)
    }

    private fun currentDownloadPresentation(): DownloadTelemetryPresentation? {
        displayedDownloadTarget = null
        displayedDownloadAttempt = null
        return try {
            val manager = AiModelManager.get(this)
            val target = DownloadTargetCatalog.targetFor(manager.selectedRuntime())
            val services = AndroidDownloadServices.get(this)
            displayedDownloadTarget = target
            val snapshot = services.uiSnapshot(target)
            downloadUiFallbackRetry = false
            if (snapshot == null) {
                null
            } else {
                displayedDownloadAttempt = snapshot.actionableAttempt
                DownloadTelemetryPresentation.from(snapshot.telemetry)
            }
        } catch (failure: Exception) {
            Log.e(DOWNLOAD_LOG, "Could not load durable AI download status.", failure)
            displayedDownloadAttempt = null
            downloadUiFallbackRetry = true
            DownloadTelemetryPresentation.uiFailure()
        }
    }

    private fun renderDownloadTelemetry(
        presentation: DownloadTelemetryPresentation?,
    ) {
        if (!::qwenStatus.isInitialized) return
        val manager = AiModelManager.get(this)
        if (!manager.hasDownloadConsent()) {
            displayedDownloadAttempt = null
            downloadUiFallbackRetry = false
            qwenStatus.text = getString(R.string.qwen_model_consent_required)
            qwenDownloadDetail.text = ""
            qwenDownloadProgress.text = ""
            qwenRetryButton.visibility = View.GONE
            qwenCancelButton.visibility = View.GONE
            qwenInstallButton.visibility = View.VISIBLE
            qwenInstallButton.isEnabled = true
            return
        }
        if (presentation == null) {
            val selection = manager.selectedRuntime()
            val installed = manager.exactInstalledModel(selection)
            qwenStatus.text = if (installed != null) {
                getString(
                    R.string.qwen_model_ready,
                    formatModelSize(installed.artifact.sizeBytes),
                    manager.userMode().displayName,
                )
            } else {
                getString(R.string.local_ai_model_required)
            }
            qwenDownloadDetail.text = ""
            qwenDownloadProgress.text = ""
            qwenRetryButton.visibility = View.GONE
            qwenCancelButton.visibility = View.GONE
            qwenInstallButton.visibility = if (installed == null) View.VISIBLE else View.GONE
            qwenInstallButton.isEnabled = installed == null
            return
        }

        qwenStatus.text = presentation.statusText
        qwenDownloadDetail.text = ""
        qwenDownloadProgress.text = presentation.progressText?.let {
            listOfNotNull(
                it,
                presentation.percent?.let { percent -> "$percent%" },
                presentation.speedText,
                presentation.etaText,
                presentation.networkText,
            ).distinct().joinToString(" | ")
        } ?: presentation.detailText
        qwenRetryButton.visibility = if (
            DownloadAction.RETRY in presentation.actions
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        qwenCancelButton.visibility = if (
            DownloadAction.CANCEL in presentation.actions
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        qwenInstallButton.visibility = View.GONE
    }

    private fun refreshDownloadTelemetry() {
        val presentation = currentDownloadPresentation()
        renderDownloadTelemetry(presentation)
    }

    private fun resumeActiveDownload() {
        try {
            val manager = AiModelManager.get(this)
            if (!manager.hasDownloadConsent()) return
            val target = DownloadTargetCatalog.targetFor(manager.selectedRuntime())
            AndroidDownloadServices.get(this).resumeActive(target)
        } catch (failure: Exception) {
            Log.e(DOWNLOAD_LOG, "Could not resume the exact durable AI download.", failure)
        }
    }

    private fun stopDownloadTelemetryUpdates() {
        statusHandler.removeCallbacks(qwenStatusRefresh)
    }

    private fun startDownloadTelemetryUpdates() {
        statusHandler.removeCallbacks(qwenStatusRefresh)
        if (activityResumed) statusHandler.post(qwenStatusRefresh)
    }

    private fun runDownloadUiOperation(
        operation: String,
        action: () -> Unit,
    ): Boolean = try {
        action()
        true
    } catch (failure: Exception) {
        Log.e(DOWNLOAD_LOG, "$operation failed safely.", failure)
        displayedDownloadAttempt = null
        downloadUiFallbackRetry = true
        renderDownloadTelemetry(DownloadTelemetryPresentation.uiFailure())
        false
    }

    private fun performDownloadAction(action: DownloadAction) {
        val target = displayedDownloadTarget
        if (target == null) {
            if (action == DownloadAction.RETRY && downloadUiFallbackRetry) {
                refreshDownloadTelemetry()
                startDownloadTelemetryUpdates()
            }
            return
        }
        try {
            val services = AndroidDownloadServices.get(this)
            val attempt = displayedDownloadAttempt
            if (attempt == null) {
                if (action != DownloadAction.RETRY || !downloadUiFallbackRetry) return
                services.retryOrEnsure(target)
            } else {
                if (attempt.target != target) return
                when (action) {
                    DownloadAction.RETRY -> services.retry(attempt)
                    DownloadAction.CANCEL -> services.cancel(attempt)
                }
            }
        } catch (failure: Exception) {
            Log.e(DOWNLOAD_LOG, "AI download action failed safely: $action", failure)
            displayedDownloadAttempt = null
            downloadUiFallbackRetry = true
            renderDownloadTelemetry(DownloadTelemetryPresentation.uiFailure())
            return
        }
        refreshDownloadTelemetry()
        startDownloadTelemetryUpdates()
    }

    private fun requestModelInstallation() {
        val manager = AiModelManager.get(this)
        if (manager.exactInstalledModel(manager.selectedRuntime()) != null) {
            showModelSettings()
            return
        }
        if (!manager.hasDownloadConsent()) {
            showModelInstallPromptIfNeeded(force = true)
            return
        }
        val presentation = currentDownloadPresentation()
        if (presentation?.actions?.contains(DownloadAction.RETRY) == true) {
            performDownloadAction(DownloadAction.RETRY)
        } else if (presentation?.actions?.contains(DownloadAction.CANCEL) == true) {
            renderDownloadTelemetry(presentation)
        } else {
            runDownloadUiOperation("Start AI download") {
                manager.ensureModelAsync()
            }
        }
        updateQwenStatus()
        startDownloadTelemetryUpdates()
    }

    private fun showWritePage() {
        if (currentPage == Page.WRITE && ::editor.isInitialized && editor.isAttachedToWindow) {
            updateTabs()
            return
        }
        currentPage = Page.WRITE
        // Load the model in the background now so the first Correct my
        // writing tap does not pay model-load latency.
        grammarEngine.preloadAsync()
        invalidateMainReviewSurface(ReviewSurfaceEvent.TargetLost)
        updateTabs()
        val restoredDraft = restoreEncryptedDraft()
        val scroll = ScrollView(this).apply {
            id = R.id.main_scroll
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(16), 0, dp(16), dp(20))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.write_heading)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        }, matchWidth(bottomMargin = 4))
        content.addView(TextView(this).apply {
            text = getString(R.string.write_help)
            textSize = 14f
            setTextColor(COLOR_TEXT_MUTED)
        }, matchWidth(bottomMargin = 12))

        content.addView(buildExternalAccessCard(), matchWidth(bottomMargin = 12))

        content.addView(TextView(this).apply {
            id = R.id.review_draft_label
            text = getString(R.string.editor_label)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(COLOR_TEXT_MUTED)
            labelFor = R.id.review_editor
        }, matchWidth(bottomMargin = 4))

        content.addView(caption(R.string.main_tone_menu_label), matchWidth(bottomMargin = 4))
        mainToneMenu = buildToneMenu(R.id.main_tone_menu, mainToneButtons)
        content.addView(mainToneMenu, matchWidth(bottomMargin = 10))

        val editorFrame = FrameLayout(this).apply {
            id = R.id.editor_frame
            background = rounded(COLOR_SURFACE, 18f, COLOR_BORDER)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        editor = EditText(this).apply {
            id = R.id.review_editor
            hint = getString(R.string.editor_hint)
            setHintTextColor(COLOR_TEXT_FAINT)
            setTextColor(COLOR_TEXT)
            textSize = 17f
            gravity = Gravity.TOP or Gravity.START
            minLines = 12
            maxLines = 18
            setPadding(dp(16), dp(14), dp(56), dp(18))
            background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isVerticalScrollBarEnabled = true
            isNestedScrollingEnabled = true
            setFreezesText(true)
            isSaveEnabled = false
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE,
                    -> view.parent?.requestDisallowInterceptTouchEvent(true)

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                    -> view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!internalTextChange && correctionCardOrNull()?.visibility == View.VISIBLE) closeReview()
                    scheduleDraftPersistence()
                    // One coalesced pass per typing burst. The old code ran
                    // the trigger measurement twice on every keystroke, which
                    // is layout work on a document-sized editor.
                    statusHandler.removeCallbacks(triggerRefresh)
                    statusHandler.postDelayed(triggerRefresh, TRIGGER_REFRESH_DELAY_MS)
                    statusHandler.removeCallbacks(prefetchCorrections)
                    statusHandler.postDelayed(prefetchCorrections, PREFETCH_IDLE_DELAY_MS)
                }
            })
            setOnFocusChangeListener { _, _ -> post { updateAssistantTrigger() } }
        }
        restoredDraft?.let { draft ->
            internalTextChange = true
            editor.setText(draft.text)
            editor.setSelection(draft.selectionStart, draft.selectionEnd)
            internalTextChange = false
        }
        assistantTrigger = ManuscriptTriggerViewFactory.create(
            context = this,
            contentDescription = getString(R.string.assistant_open),
            onClick = { openReview() },
        ).apply {
            id = R.id.writing_assist_icon
        }
        editorFrame.addView(editor, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        content.addView(editorFrame, matchWidth(bottomMargin = 10))

        // Primary way in. The small icon inside the editor sits at the text
        // edge, so taps near it land in the EditText and pop the keyboard
        // instead of opening review. A full-width button cannot be missed.
        correctButton = primaryButton(R.string.correct_writing_action) {
            if (editor.text?.isNotBlank() == true) openReview() else editor.requestFocus()
        }.apply {
            id = R.id.correct_writing_action
            minHeight = dp(56)
            textSize = 17f
        }
        content.addView(correctButton, matchWidth(bottomMargin = 10))

        content.addView(buildDictationRow(), matchWidth(bottomMargin = 10))

        correctionCard = buildCorrectionCard()

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(secondaryButton(R.string.new_document_action) {
            invalidateMainReviewSurface(ReviewSurfaceEvent.TargetLost)
            statusHandler.removeCallbacks(draftSave)
            editor.text.clear()
            draftStore.clear()
        }, weightedParams(endMargin = 6))
        actions.addView(secondaryButton(R.string.share_document_action) { shareDocument() }, weightedParams(startMargin = 6))
        content.addView(actions, matchWidth(bottomMargin = 10))
        content.addView(TextView(this).apply {
            id = R.id.system_voice_note
            text = getString(R.string.system_voice_note)
            textSize = 13f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        })

        scroll.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        pageHost.removeAllViews()
        pageHost.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        // Keep the editor trigger inside the visible writing viewport. When
        // the keyboard scrolls a long editor to its cursor, a trigger inside
        // the editor can be clipped while still reporting itself as shown.
        pageHost.addView(assistantTrigger, FrameLayout.LayoutParams(
            dp(WritingAssistUiPolicy.ICON_TOUCH_TARGET_DP),
            dp(WritingAssistUiPolicy.ICON_TOUCH_TARGET_DP),
            Gravity.END or Gravity.TOP,
        ).apply { setMargins(0, dp(10), dp(10), 0) })
        val reviewOverlay = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            visibility = View.GONE
            addView(correctionCard, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        correctionCardOverlay = reviewOverlay
        pageHost.addView(reviewOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.TOP,
        ).apply {
            setMargins(dp(16), dp(8), dp(16), dp(8))
        })
        mainScroll = scroll
        restoredDraft?.let { draft ->
            scroll.post { scroll.scrollTo(0, draft.scrollY) }
        }
        editor.requestFocus()
        editor.post { showSystemKeyboard(editor) }
    }

    private fun buildDictationRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val button = secondaryButton(R.string.speech_start) { onDictateClicked() }.apply {
            id = R.id.dictate_button
        }
        dictateButton = button
        row.addView(button, weightedParams(endMargin = 6))
        addView(row, matchWidth(bottomMargin = 4))
        val status = TextView(this@MainActivity).apply {
            id = R.id.dictation_status
            text = getString(R.string.speech_idle_status)
            textSize = 13f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        dictationStatus = status
        addView(status, matchWidth())
        ensureSpeechSession().refreshAvailability()
    }

    private fun ensureSpeechSession(): OnDeviceSpeechSession {
        speechSession?.let { return it }
        val session = OnDeviceSpeechSession(this) { state ->
            runOnUiThread { handleSpeechState(state) }
        }
        speechSession = session
        return session
    }

    private fun onDictateClicked() {
        val session = ensureSpeechSession()
        if (session.state.canStop) {
            session.stop()
            return
        }
        if (
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            session.markPermissionRequired()
            requestPermissions(
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO,
            )
            return
        }
        session.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        val session = ensureSpeechSession()
        if (
            grantResults.isNotEmpty() &&
            grantResults.first() == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            session.start()
        } else {
            session.permissionDenied()
        }
    }

    private fun handleSpeechState(state: SpeechCaptureState) {
        val button = dictateButton ?: return
        val status = dictationStatus ?: return
        when (state) {
            is SpeechCaptureState.Unavailable -> {
                status.text = state.reason
                button.text = getString(R.string.speech_start)
                button.isEnabled = false
            }
            SpeechCaptureState.Ready -> {
                status.text = getString(R.string.speech_idle_status)
                button.text = getString(R.string.speech_start)
                button.isEnabled = true
            }
            SpeechCaptureState.PermissionRequired -> {
                status.text = getString(R.string.speech_permission_status)
                button.text = getString(R.string.speech_start)
                button.isEnabled = true
            }
            SpeechCaptureState.Listening -> {
                status.text = getString(R.string.speech_listening_status)
                button.text = getString(R.string.speech_stop)
                button.isEnabled = true
            }
            is SpeechCaptureState.Partial -> {
                status.text = state.transcript.takeLast(120)
                button.text = getString(R.string.speech_stop)
                button.isEnabled = true
            }
            SpeechCaptureState.Processing -> {
                status.text = getString(R.string.speech_finishing_status)
                button.text = getString(R.string.speech_stop)
                button.isEnabled = false
            }
            is SpeechCaptureState.ModelSetup -> {
                status.text = state.message
                button.text = getString(R.string.speech_start)
                button.isEnabled = false
            }
            is SpeechCaptureState.Review -> {
                insertDictatedTranscript(state.transcript)
                status.text = getString(R.string.speech_inserted_status)
                button.text = getString(R.string.speech_start)
                button.isEnabled = true
            }
            is SpeechCaptureState.Error -> {
                status.text = state.message
                button.text = getString(R.string.speech_start)
                button.isEnabled = true
            }
        }
        button.contentDescription = button.text
    }

    private fun insertDictatedTranscript(transcript: String) {
        if (!::editor.isInitialized) return
        val vocabulary = runCatching { localTools.vocabularyMap().value }
            .getOrNull() ?: emptyMap()
        val result = DictationInsertion.insert(
            existing = editor.text.toString(),
            selectionStart = editor.selectionStart,
            selectionEnd = editor.selectionEnd,
            rawTranscript = transcript,
            vocabulary = vocabulary,
        )
        editor.setText(result.text)
        editor.setSelection(result.cursor.coerceIn(0, editor.text.length))
        editor.requestFocus()
    }

    private fun restoreEncryptedDraft(): EditorDraft? = draftStore.load()

    private fun scheduleDraftPersistence() {
        if (!::draftStore.isInitialized) return
        statusHandler.removeCallbacks(draftSave)
        statusHandler.postDelayed(draftSave, DRAFT_SAVE_DELAY_MS)
    }

    private fun persistDraftNow() {
        statusHandler.removeCallbacks(draftSave)
        if (
            !::draftStore.isInitialized ||
            currentPage != Page.WRITE ||
            !::editor.isInitialized ||
            !editor.isAttachedToWindow
        ) {
            return
        }
        draftStore.save(
            EditorDraft(
                text = editor.text.toString(),
                selectionStart = editor.selectionStart.coerceAtLeast(0),
                selectionEnd = editor.selectionEnd.coerceAtLeast(0),
                scrollY = mainScroll?.scrollY ?: 0,
            ),
        )
    }

    private fun buildExternalAccessCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(COLOR_SURFACE, 14f, COLOR_ACCENT_DARK)
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.external_access_heading)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(COLOR_TEXT)
        }, matchWidth(bottomMargin = 4))
        externalAccessStatus = TextView(this@MainActivity).apply {
            textSize = 13f
            setTextColor(COLOR_TEXT_MUTED)
        }.also { addView(it, matchWidth(bottomMargin = 8)) }
        externalAccessButton = secondaryButton(R.string.external_access_enable) {
            showExternalAccessDisclosure()
        }.also { addView(it, matchWidth()) }
        updateExternalAccessStatus()
    }

    private fun showExternalAccessDisclosure() {
        accessPromptVisible = true
        AlertDialog.Builder(this)
            .setTitle(R.string.external_disclosure_title)
            .setMessage(R.string.external_disclosure_message)
            .setNegativeButton(R.string.external_disclosure_not_now) { _, _ ->
                openingAccessibilitySettings = false
            }
            .setPositiveButton(R.string.external_disclosure_continue) { _, _ ->
                openingAccessibilitySettings = true
                openAccessibilitySettingsWithGuidance()
            }
            .create()
            .apply {
                setOnDismissListener {
                    accessPromptVisible = false
                    if (!openingAccessibilitySettings && ::root.isInitialized) {
                        root.postDelayed({ showModelInstallPromptIfNeeded() }, 250L)
                    }
                    openingAccessibilitySettings = false
                }
                show()
            }
    }

    private fun runStartupReadinessCheck(showDialog: Boolean) {
        val manager = AiModelManager.get(this)
        manager.verifyInstalledModelAsync {
            if (isFinishing || isDestroyed) return@verifyInstalledModelAsync
            updateQwenStatus()
            // Do not reopen the readiness dialog after the user dismisses it.
            // Verification can finish after the first prompt has already been
            // handled, and forcing a second dialog blocks the active editor.
            if (showDialog) showStartupReadinessCheckIfNeeded()
        }
        if (showDialog) root.post { showStartupReadinessCheckIfNeeded() }
    }

    private fun showStartupReadinessCheckIfNeeded(force: Boolean = false) {
        if (startupCheckDialog?.isShowing == true) {
            if (!force) return
            startupCheckDialog?.dismiss()
        }
        if (!hasWindowFocus()) {
            if (force) startupCheckShown = false
            return
        }
        if (
            (!force && startupCheckShown) ||
            accessPromptVisible ||
            modelPromptShown ||
            isFinishing ||
            isDestroyed ||
            // Never cover an open review. This dialog arrives when the
            // readiness check finishes, which can land in the middle of a
            // correction, and it then sits on top of the suggestion card so
            // the user cannot accept or reject anything.
            correctionCardOrNull()?.visibility == View.VISIBLE
        ) {
            return
        }
        startupCheckShown = true
        startupCheckDialog = AlertDialog.Builder(this)
            .setTitle(R.string.startup_check_title)
            .setMessage("")
            .setNegativeButton(R.string.qwen_model_not_now, null)
            .setPositiveButton(R.string.startup_check_wait, null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (startupCheckDialog === dialog) startupCheckDialog = null
                }
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    performCurrentStartupAction()
                }
                refreshStartupReadinessDialog()
            }
    }

    private fun refreshStartupReadinessDialog() {
        val dialog = startupCheckDialog?.takeIf { it.isShowing } ?: return
        val manager = AiModelManager.get(this)
        val acrossAppsEnabled = isExternalAccessEnabled()
        val selection = manager.selectedRuntime()
        val modelReady = manager.exactInstalledModel(selection) != null
        val presentation: DownloadTelemetryPresentation? = currentDownloadPresentation()
        val action = ManuscriptStartupReadiness.nextAction(
            acrossAppsEnabled = acrossAppsEnabled,
            modelReady = modelReady,
            hasDownloadConsent = manager.hasDownloadConsent(),
            presentation = presentation,
        )
        val modelStatus = when {
            modelReady -> getString(R.string.startup_check_ready)
            presentation != null -> "${presentation.statusText}. ${presentation.detailText}"
            !manager.hasDownloadConsent() -> getString(R.string.startup_check_model_install)
            else -> getString(R.string.startup_check_model_installing)
        }
        dialog.setMessage(
            getString(
                R.string.startup_check_message,
                getString(
                    if (acrossAppsEnabled) {
                        R.string.startup_check_ready
                    } else {
                        R.string.startup_check_needs_setup
                    },
                ),
                modelStatus,
            ),
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setText(
            when (action) {
                ManuscriptStartupAction.ENABLE_ACROSS_APPS ->
                    R.string.startup_check_enable_access
                ManuscriptStartupAction.INSTALL_MODEL ->
                    R.string.startup_check_install_model
                ManuscriptStartupAction.RETRY_MODEL ->
                    R.string.startup_check_retry_model
                ManuscriptStartupAction.WAIT_FOR_MODEL ->
                    R.string.startup_check_wait
                ManuscriptStartupAction.READY ->
                    R.string.startup_check_start
            },
        )
    }

    private fun performCurrentStartupAction() {
        val manager = AiModelManager.get(this)
        val selection = manager.selectedRuntime()
        val presentation = currentDownloadPresentation()
        val action = ManuscriptStartupReadiness.nextAction(
            acrossAppsEnabled = isExternalAccessEnabled(),
            modelReady = manager.exactInstalledModel(selection) != null,
            hasDownloadConsent = manager.hasDownloadConsent(),
            presentation = presentation,
        )
        startupCheckDialog?.dismiss()
        when (action) {
            ManuscriptStartupAction.ENABLE_ACROSS_APPS -> {
                startupCheckShown = false
                showExternalAccessDisclosure()
            }
            ManuscriptStartupAction.INSTALL_MODEL -> {
                startupCheckShown = false
                showModelInstallPromptAfterStartupDialog()
            }
            ManuscriptStartupAction.RETRY_MODEL -> {
                startupCheckShown = false
                performDownloadAction(DownloadAction.RETRY)
                updateQwenStatus()
                startDownloadTelemetryUpdates()
            }
            ManuscriptStartupAction.WAIT_FOR_MODEL -> {
                runDownloadUiOperation("Resume AI download") {
                    manager.ensureModelAsync()
                }
                startDownloadTelemetryUpdates()
            }
            ManuscriptStartupAction.READY -> Unit
        }
    }

    private fun showModelInstallPromptAfterStartupDialog() {
        // AlertDialog positive callbacks run before the readiness window has
        // completely released focus. Wait for that dismissal to finish so the
        // model disclosure is not rejected by hasWindowFocus().
        root.postDelayed(
            { showModelInstallPromptIfNeeded(force = true) },
            STARTUP_DIALOG_HANDOFF_DELAY_MS,
        )
    }

    private fun showModelInstallPromptIfNeeded(force: Boolean = false) {
        if (
            modelPromptShown ||
            accessPromptVisible ||
            isFinishing ||
            isDestroyed ||
            !hasWindowFocus()
        ) {
            return
        }
        val manager = AiModelManager.get(this)
        val presentation = currentDownloadPresentation()
        renderDownloadTelemetry(presentation)
        if (manager.exactInstalledModel(manager.selectedRuntime()) != null) return
        if (manager.hasDownloadConsent() && !force) return
        modelPromptShown = true
        manager.markDownloadPromptSeen()
        val artifact = manager.selectedArtifact()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.qwen_model_install_title)
            .setMessage(
                getString(
                    R.string.qwen_model_install_message,
                    formatModelSize(artifact.sizeBytes),
                    formatModelSize(manager.requiredStorageBytes(artifact)),
                ),
            )
            .setNegativeButton(R.string.qwen_model_not_now, null)
            .setNeutralButton(R.string.qwen_model_wifi_only) { _, _ ->
                runDownloadUiOperation("Grant Wi-Fi AI download consent") {
                    manager.grantDownloadConsent(AiDownloadNetworkPolicy.WIFI_ONLY)
                }
                updateQwenStatus()
                startDownloadTelemetryUpdates()
            }
            .setPositiveButton(R.string.qwen_model_allow_mobile_data) { _, _ ->
                runDownloadUiOperation("Grant mobile AI download consent") {
                    manager.grantDownloadConsent(AiDownloadNetworkPolicy.ALLOW_MOBILE_DATA)
                }
                updateQwenStatus()
                startDownloadTelemetryUpdates()
            }
            .create()
        dialog.setOnDismissListener { modelPromptShown = false }
        dialog.show()
    }

    private fun showModelSettings() {
        val manager = AiModelManager.get(this)
        val modes = AiUserMode.entries
        var selected = modes.indexOf(manager.userMode()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.qwen_model_settings)
            .setSingleChoiceItems(
                modes.map(AiUserMode::displayName).toTypedArray(),
                selected,
            ) { _, which -> selected = which }
            .setNegativeButton(R.string.close_action, null)
            .setNeutralButton(R.string.qwen_model_remove) { _, _ ->
                OnDeviceGrammarEngine.trimIfCreated(
                    android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                )
                runDownloadUiOperation("Remove local AI") {
                    manager.removeAllModels()
                }
                updateQwenStatus()
            }
            .setPositiveButton(R.string.qwen_model_settings_apply) { _, _ ->
                manager.setUserMode(modes[selected])
                if (manager.hasDownloadConsent()) {
                    runDownloadUiOperation("Apply AI model mode") {
                        manager.ensureModelAsync()
                    }
                    startDownloadTelemetryUpdates()
                } else {
                    modelPromptShown = false
                    root.post { showModelInstallPromptIfNeeded(force = true) }
                }
                updateQwenStatus()
            }
            .show()
    }

    private fun formatModelSize(bytes: Long): String {
        val gib = bytes.toDouble() / 1_073_741_824.0
        val readable = if (gib >= 1.0) {
            String.format(java.util.Locale.US, "%.2f GB", gib)
        } else {
            String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
        }
        val exact = java.text.NumberFormat.getIntegerInstance().format(bytes)
        return "$readable ($exact bytes)"
    }

    private fun openAccessibilitySettingsWithGuidance() {
        val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val installedServicesKey = if (isSamsung) {
            SAMSUNG_INSTALLED_SERVICES_KEY
        } else {
            AOSP_INSTALLED_SERVICES_KEY
        }
        fun highlightedIntent(action: String) = Intent(action).apply {
            putExtra(SETTINGS_HIGHLIGHT_KEY, installedServicesKey)
            putExtra(
                SETTINGS_SHOW_FRAGMENT_ARGUMENTS,
                Bundle().apply {
                    putString(SETTINGS_HIGHLIGHT_KEY, installedServicesKey)
                },
            )
        }
        val primaryAction = if (isSamsung) {
            SAMSUNG_ACCESSIBILITY_SETTINGS_ACTION
        } else {
            Settings.ACTION_ACCESSIBILITY_SETTINGS
        }
        try {
            startActivity(highlightedIntent(primaryAction))
        } catch (_: ActivityNotFoundException) {
            startActivity(highlightedIntent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val guidancePrompt = AccessibilitySettingsGuide.prompt(
            isSamsung = isSamsung,
        )
        Toast.makeText(
            this,
            when (guidancePrompt) {
                AccessibilitySettingsPrompt.INSTALLED_APPS ->
                    R.string.external_access_installed_apps_prompt
                AccessibilitySettingsPrompt.MANUSCRIPT ->
                    R.string.external_access_service_prompt
            },
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun updateExternalAccessStatus() {
        val enabled = isExternalAccessEnabled()
        externalAccessStatus?.text = getString(
            if (enabled) R.string.external_access_on else R.string.external_access_off,
        )
        externalAccessButton?.apply {
            text = getString(
                if (enabled) R.string.external_access_settings else R.string.external_access_enable,
            )
            contentDescription = text
            setOnClickListener {
                if (enabled) openAccessibilitySettingsWithGuidance()
                else showExternalAccessDisclosure()
            }
        }
    }

    private fun isExternalAccessEnabled(): Boolean {
        val component = ComponentName(this, ManuscriptAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabledServices.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun buildCorrectionCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            id = R.id.writing_suggestion_card
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(COLOR_CARD, 18f, COLOR_ACCENT_DARK)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                accessibilityPaneTitle = getString(R.string.correction_card_title)
            }
        }
        val top = OfficialCorrectionHeader(
            context = this,
            title = getString(R.string.correction_card_title),
            onClose = { closeReview() },
            onSettings = null,
        ).apply { showCounter() }
        reviewCounter = top.counterView
        card.addView(top, matchWidth(bottomMargin = 8))

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        replaceButton = primaryButton(R.string.writing_replace_action) {
            val session = reviewSession
            val block = session?.current(selectedTone)
            when {
                block == null || session == null -> Unit
                session.hasModelReplacement(block.index, selectedTone) ->
                    replaceCurrentBlock()
                AiModelManager.get(this).installedModel() == null ->
                    requestModelInstallation()
                else -> {
                    // The block has no completed AI result (failed or still
                    // pending). A tap must never be a silent no-op: restart
                    // the correction for the remaining blocks.
                    reviewNote.text = getString(R.string.local_ai_retrying)
                    requestAllModelCorrections(session)
                }
            }
        }.apply {
            id = R.id.writing_replace_action
        }
        dismissButton = secondaryButton(R.string.writing_dismiss_action) { dismissCurrentBlock() }.apply {
            id = R.id.writing_dismiss_action
        }
        actionRow.addView(replaceButton, weightedParams(endMargin = 6))
        actionRow.addView(dismissButton, weightedParams(startMargin = 6))
        card.addView(actionRow, matchWidth(bottomMargin = 10))

        val documentActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        acceptAllButton = secondaryButton(R.string.writing_accept_all_action) {
            acceptAllCorrections()
        }.apply {
            id = R.id.writing_accept_all_action
            // Starts disabled. Until the first card render sets its real
            // state, a fast tap landed on a button that looked live but did
            // nothing, because accepting is refused while any paragraph is
            // still being corrected.
            isEnabled = false
        }
        undoReviewButton = secondaryButton(R.string.writing_undo_action) {
            undoReviewAction()
        }.apply {
            id = R.id.writing_undo_action
        }
        restoreOriginalButton = secondaryButton(R.string.writing_restore_original_action) {
            restoreOriginalReview()
        }.apply {
            id = R.id.writing_restore_original_action
        }
        documentActionRow.addView(acceptAllButton, weightedParams(endMargin = 4))
        documentActionRow.addView(
            undoReviewButton,
            weightedParams(startMargin = 2, endMargin = 2),
        )
        documentActionRow.addView(restoreOriginalButton, weightedParams(startMargin = 4))
        card.addView(documentActionRow, matchWidth(bottomMargin = 8))

        card.addView(caption(R.string.suggestion_label))
        reviewSuggestion = bodyText(R.id.writing_suggestion).apply {
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
        card.addView(reviewSuggestion, matchWidth(bottomMargin = 4))
        val changeActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        acceptChangeButton = secondaryButton(R.string.writing_accept_change_action) {
            acceptCurrentChange()
        }.apply {
            id = R.id.writing_accept_change_action
        }
        rejectChangeButton = secondaryButton(R.string.writing_reject_change_action) {
            rejectCurrentChange()
        }.apply {
            id = R.id.writing_reject_change_action
        }
        changeActionRow.addView(acceptChangeButton, weightedParams(endMargin = 4))
        changeActionRow.addView(rejectChangeButton, weightedParams(startMargin = 4))
        card.addView(changeActionRow, matchWidth(bottomMargin = 4))
        // Correcting a page takes minutes on a phone. A line of text is not
        // enough feedback for that: the bar shows how far along the document
        // is and that work is still happening.
        reviewProgress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            id = R.id.writing_progress
            max = 100
            isIndeterminate = true
            visibility = View.GONE
        }
        card.addView(
            reviewProgress,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(6),
            ).apply { bottomMargin = dp(6) },
        )
        reviewNote = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.ITALIC)
            setTextColor(COLOR_TEXT_MUTED)
        }
        card.addView(reviewNote, matchWidth(bottomMargin = 10))
        card.addView(caption(R.string.tone_label))
        reviewToneMenu = buildToneMenu(R.id.writing_tone, reviewToneButtons)
        val toneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toneRow.addView(reviewToneMenu, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply { marginEnd = dp(8) })
        toneRow.addView(secondaryButton(R.string.qwen_model_retry_action) {
            if (reviewSession?.complete == false) regenerateCurrentBlock()
        }.apply {
            id = R.id.writing_regenerate_action
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        card.addView(toneRow, matchWidth(bottomMargin = 10))
        return card
    }

    private fun buildToneMenu(
        menuId: Int,
        buttons: MutableMap<WritingTone, Button>,
    ): HorizontalScrollView {
        buttons.clear()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ToneMenuPolicy.items(selectedTone).forEach { item ->
            val button = Button(this).apply {
                text = item.label
                contentDescription = getString(R.string.select_tone_description, item.label)
                isAllCaps = false
                textSize = 14f
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(MIN_TOUCH_TARGET_DP)
                minimumHeight = dp(MIN_TOUCH_TARGET_DP)
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { selectTone(item.tone) }
            }
            buttons[item.tone] = button
            row.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(MIN_TOUCH_TARGET_DP),
                ).apply { marginEnd = dp(6) },
            )
        }
        return HorizontalScrollView(this).apply {
            id = menuId
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
            updateToneMenuSelection(buttons)
            post { revealSelectedTone(this, buttons) }
        }
    }

    private fun selectTone(nextTone: WritingTone) {
        val shouldRegenerate = ToneMenuPolicy.shouldRegenerate(
            current = selectedTone,
            next = nextTone,
            reviewOpen = reviewSession?.complete == false,
        )
        if (nextTone == selectedTone) return
        selectedTone = nextTone
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
            .putString(TONE_KEY, selectedTone.label)
            .apply()
        updateToneMenuSelection(mainToneButtons)
        updateToneMenuSelection(reviewToneButtons)
        mainToneMenu.post { revealSelectedTone(mainToneMenu, mainToneButtons) }
        if (::reviewToneMenu.isInitialized) {
            reviewToneMenu.post { revealSelectedTone(reviewToneMenu, reviewToneButtons) }
        }
        if (shouldRegenerate) regenerateCurrentBlock()
    }

    private fun updateToneMenuSelection(buttons: Map<WritingTone, Button>) {
        ToneMenuPolicy.items(selectedTone).forEach { item ->
            buttons[item.tone]?.apply {
                isSelected = item.selected
                setTypeface(typeface, if (item.selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (item.selected) Color.WHITE else COLOR_TEXT)
                background = rounded(
                    if (item.selected) COLOR_ACCENT_DARK else COLOR_SURFACE_RAISED,
                    12f,
                    if (item.selected) COLOR_ACCENT else COLOR_BORDER,
                )
            }
        }
    }

    private fun revealSelectedTone(
        menu: HorizontalScrollView,
        buttons: Map<WritingTone, Button>,
    ) {
        val selected = buttons[selectedTone] ?: return
        menu.smoothScrollTo(
            (selected.left - menu.paddingLeft).coerceAtLeast(0),
            0,
        )
    }

    private fun openReview() {
        Log.i(REVIEW_LOG, "event=open-review-request state=${reviewSurfaceController.state.javaClass.simpleName}")
        if (!beginMainReviewSurface()) {
            Log.i(REVIEW_LOG, "event=open-review-rejected state=${reviewSurfaceController.state.javaClass.simpleName}")
            return
        }
        // A user who has already opened correction must not be interrupted by
        // the delayed startup readiness dialog after they close this review.
        startupCheckShown = true
        startupCheckDialog?.dismiss()
        iconWasClicked = true
        // Pressing "Correct my writing" IS the intent to review, so take
        // focus back first. The card requires editor focus, and focus is
        // easily elsewhere by then (keyboard dismissed, page scrolled), which
        // made the button do nothing at all with no message to the user.
        if (!editor.hasFocus() && editor.text?.isNotBlank() == true) {
            editor.requestFocus()
        }
        hideSystemKeyboard(editor)
        statusHandler.removeCallbacks(prefetchCorrections)
        grammarEngine.cancelPrefetch()
        grammarEngine.setSessionWarm(true)
        reviewSession = WritingReviewSession.createParagraphs(
            source = editor.text.toString(),
            selectionStart = editor.selectionStart,
            selectionEnd = editor.selectionEnd,
        )
        val hasSuggestion = reviewSession?.current(selectedTone) != null
        val show = WritingAssistUiPolicy.shouldShowCard(editor.hasFocus(), iconWasClicked, hasSuggestion)
        correctionCard.visibility = View.GONE
        assistantTrigger.visibility = View.GONE
        if (show) {
            // The review is a pinned overlay, so it can open on this first
            // frame while the keyboard finishes closing underneath it.
            attachStableReviewCard()
        } else {
            reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.CloseRequested)
        }
    }

    private fun scheduleReviewAttachmentAfterKeyboard() {
        pendingReviewAttach?.let { pending -> root.removeCallbacks(pending) }
        val callbackToken = reviewSurfaceExecutor.currentCallbackToken()
        val deadline = SystemClock.elapsedRealtime() + REVIEW_KEYBOARD_HIDE_LIMIT_MS
        val pending = object : Runnable {
            override fun run() {
                if (
                    !reviewSurfaceExecutor.isCurrentCallback(callbackToken) ||
                    reviewSurfaceController.state !is ReviewSurfaceState.Opening
                ) {
                    pendingReviewAttach = null
                    return
                }
                val keyboardVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                } else {
                    SystemClock.elapsedRealtime() < deadline
                }
                if (
                    keyboardVisible &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    hideSystemKeyboard(editor)
                    root.postDelayed(this, REVIEW_KEYBOARD_POLL_MS)
                    return
                }
                pendingReviewAttach = null
                attachStableReviewCard()
            }
        }
        pendingReviewAttach = pending
        root.post(pending)
    }

    private fun attachStableReviewCard() {
        if (
            reviewSurfaceController.state !is ReviewSurfaceState.Opening ||
            mainEditorEligibility() != ReviewTargetEligibility.ELIGIBLE ||
            reviewSession?.current(selectedTone) == null
        ) {
            reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.TargetLost)
            return
        }
        showCorrectionOverlay()
        Log.i(REVIEW_LOG, "event=review-card-attached")
        reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.ReviewAttached)
        correctionCard.isFocusableInTouchMode = true
        correctionCard.requestFocus()
        renderCurrentBlock()
        correctionCard.announceForAccessibility(getString(R.string.correction_card_opened))
        correctionCard.requestLayout()
    }

    private fun showCorrectionOverlay() {
        correctionCard.visibility = View.VISIBLE
        correctionCardOverlay?.apply {
            val wasGone = visibility == View.GONE
            visibility = View.VISIBLE
            if (wasGone) {
                correctionCard.requestLayout()
                requestLayout()
                pageHost.requestLayout()
            }
            scrollTo(0, 0)
            bringToFront()
            post {
                replaceButton.requestRectangleOnScreen(
                    Rect(0, 0, replaceButton.width, replaceButton.height),
                    true,
                )
            }
        }
    }

    private fun renderCurrentBlock(requestModel: Boolean = true) {
        val session = reviewSession ?: return
        val block = session.current(selectedTone)
        clearTargetUnderline()
        if (block == null) {
            reviewCounter.text = getString(R.string.review_complete_counter, session.total, session.total)
            reviewSuggestion.text = getString(R.string.review_complete_suggestion)
            reviewNote.text = ""
            hideReviewProgress()
            replaceButton.text = getString(R.string.writing_replace_action)
            replaceButton.contentDescription = replaceButton.text
            replaceButton.isEnabled = false
            dismissButton.text = getString(R.string.close_action)
            acceptChangeButton.isEnabled = false
            rejectChangeButton.isEnabled = false
            acceptAllButton.isEnabled = false
            undoReviewButton.isEnabled = session.canUndo()
            restoreOriginalButton.isEnabled = session.canUndo()
            return
        }
        reviewCounter.text = getString(R.string.review_counter, block.index, block.total)
        val modelReady = session.hasModelReplacement(block.index, selectedTone)
        val visibleBlock = PendingReviewPolicy.visible(block, modelReady)
        val changes = if (modelReady) session.currentChanges(selectedTone) else emptyList()
        val focusedChange = changes.firstOrNull { it.id == activeReviewChangeId }
            ?: changes.firstOrNull()
        activeReviewChangeId = focusedChange?.id
        reviewSuggestion.text = inlineCorrectionText(visibleBlock, focusedChange?.id)
        val modelMissing = !LocalAiCorrectionGate.isReady(
            AiModelManager.get(this).installedFile(),
        )
        reviewNote.text = if (modelReady) {
            focusedChange?.note
                ?: InlineEditDiff.note(block.original, block.replacement, selectedTone)
        } else if (modelMissing) {
            getString(R.string.local_ai_model_required)
        } else {
            // Honest progress instead of a frozen screen: how much of the
            // document is already done while this paragraph is worked on.
            getString(
                R.string.local_ai_working_progress,
                session.readyCount(selectedTone),
                session.total,
            )
        }
        // Accept-ahead: the pipeline keeps running while the user reads, so a
        // block that is not ready shows a live Working label instead of a
        // dead Accept button; the completion callback re-renders as results
        // land.
        replaceButton.text = getString(
            when {
                modelMissing -> R.string.qwen_model_install_action
                modelReady -> R.string.writing_replace_action
                else -> R.string.local_ai_working_action
            },
        )
        replaceButton.contentDescription = replaceButton.text
        replaceButton.isEnabled = modelReady || modelMissing
        dismissButton.text = getString(R.string.writing_dismiss_action)
        acceptChangeButton.isEnabled = focusedChange != null
        rejectChangeButton.isEnabled = focusedChange != null
        acceptAllButton.isEnabled =
            !modelMissing && session.allModelReplacementsReady(selectedTone)
        undoReviewButton.isEnabled = session.canUndo()
        restoreOriginalButton.isEnabled = session.canUndo()
        underlineTarget(block)
        if (requestModel && !modelMissing) requestAllModelCorrections(session)
    }

    private fun regenerateCurrentBlock() {
        val session = reviewSession ?: return
        Log.i(REVIEW_LOG, "cancel=regenerate")
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
        val jobId = "review-${UUID.randomUUID()}"
        activeReviewJobId = jobId
        startReviewProgress()
        // The pipeline splits a long paragraph into several pieces of its
        // own, so results do NOT arrive one-per-review-block. Rebuild each
        // block by collecting pieces until their originals reconstruct that
        // block's text; assigning by a running counter silently shifted every
        // correction onto the wrong section once any block was split.
        var pendingCursor = 0
        val originalBuffer = StringBuilder()
        val correctedBuffer = StringBuilder()
        // The terminal wrapper owns timeout and once-only delivery around
        // grammarEngine.polishDocument( so surfaces cannot spin forever.
        val request = grammarEngine.polishDocumentTerminal(
            source = sourceAtRequest,
            tone = toneAtRequest,
            sourceApplication = packageName,
            requestedJobId = jobId,
            onParagraphCompleted = { paragraph ->
                if (
                    reviewSurfaceExecutor.isCurrentCallback(callbackToken) &&
                    reviewSession === session &&
                    modelRequestEpoch == epoch &&
                    selectedTone == toneAtRequest
                ) {
                    val block = pending.getOrNull(pendingCursor)
                    if (block != null) {
                        if (originalBuffer.isNotEmpty()) originalBuffer.append(BLOCK_JOIN)
                        originalBuffer.append(paragraph.original.trim())
                        if (correctedBuffer.isNotEmpty()) correctedBuffer.append(BLOCK_JOIN)
                        correctedBuffer.append(paragraph.text.trim())
                        val collected = originalBuffer.toString().replace(WHITESPACE, " ").trim()
                        val expected = block.original.replace(WHITESPACE, " ").trim()
                        if (collected == expected || collected.length >= expected.length) {
                            session.setModelReplacement(
                                block.index,
                                toneAtRequest,
                                // Sweep here as well: this is the text the
                                // review card shows and the user accepts, and
                                // the session splits the document differently
                                // from the pipeline, so a block can arrive
                                // without the pipeline's own residual repair
                                // having seen it.
                                grammarEngine.deterministicRepair(
                                    correctedBuffer.toString(),
                                ),
                            )
                            originalBuffer.setLength(0)
                            correctedBuffer.setLength(0)
                            pendingCursor += 1
                        }
                    }
                    renderCurrentBlock(requestModel = false)
                }
            },
            onProgress = { completed, total ->
                if (
                    reviewSurfaceExecutor.isCurrentCallback(callbackToken) &&
                    reviewSession === session &&
                    modelRequestEpoch == epoch &&
                    selectedTone == toneAtRequest
                ) {
                    reviewNote.text = getString(
                        R.string.local_ai_processing,
                        completed,
                        total,
                    )
                    showReviewProgress(completed, total)
                }
            },
        ) { terminalResult ->
            modelRequests.complete(key)
            hideReviewProgress()
            if (
                !reviewSurfaceExecutor.isCurrentCallback(callbackToken) ||
                reviewSession !== session ||
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
                    reviewNote.text = terminalResult.userMessage
                }
                CorrectionTerminalResult.ModelRequired -> {
                    session.clearModelReplacements(toneAtRequest)
                    renderCurrentBlock(requestModel = false)
                    reviewNote.text = getString(R.string.local_ai_model_required)
                }
            }
        }
        modelRequests.attach(key, request)
    }

    private fun replaceCurrentBlock() {
        val session = reviewSession ?: return
        if (session.complete) return
        try {
            val updated = session.replaceCurrent(editor.text.toString(), selectedTone)
            internalTextChange = true
            editor.setText(updated)
            internalTextChange = false
            activeReviewChangeId = null
            finishOrRenderReview()
        } catch (_: IllegalArgumentException) {
            internalTextChange = false
            closeReview()
        }
    }

    private fun dismissCurrentBlock() {
        val session = reviewSession
        if (session == null || session.complete) {
            closeReview()
            return
        }
        session.dismissCurrent()
        activeReviewChangeId = null
        finishOrRenderReview()
    }

    private fun acceptCurrentChange() {
        val session = reviewSession ?: return
        val changeId = activeReviewChangeId ?: return
        runCatching {
            session.acceptChange(editor.text.toString(), selectedTone, changeId)
        }.onSuccess { updated ->
            setEditorTextFromReview(updated)
            activeReviewChangeId = null
            finishOrRenderReview(requestModel = false)
        }.onFailure {
            closeReview()
        }
    }

    private fun rejectCurrentChange() {
        val session = reviewSession ?: return
        val changeId = activeReviewChangeId ?: return
        runCatching {
            session.rejectChange(editor.text.toString(), selectedTone, changeId)
        }.onSuccess { updated ->
            setEditorTextFromReview(updated)
            activeReviewChangeId = null
            finishOrRenderReview(requestModel = false)
        }.onFailure {
            closeReview()
        }
    }

    /** Close immediately after the final decision; otherwise show the next section. */
    private fun finishOrRenderReview(requestModel: Boolean = true) {
        if (reviewSession?.complete == true) {
            closeReview()
        } else {
            renderCurrentBlock(requestModel)
        }
    }

    /**
     * Shows how far the document has been corrected and how long is left.
     * The bar also creeps forward inside the section being worked on: a
     * section takes tens of seconds on a phone, and a bar that only moves
     * once per section reads as frozen.
     */
    private fun showReviewProgress(completed: Int, total: Int) {
        if (!::reviewProgress.isInitialized) return
        progressTotal = total
        progressCompleted = completed
        progressSectionStartedAt = SystemClock.elapsedRealtime()
        if (completed > 0) {
            val elapsed = SystemClock.elapsedRealtime() - progressStartedAt
            progressPerSectionMs = elapsed / completed
        }
        reviewProgress.visibility = View.VISIBLE
        reviewProgress.isIndeterminate = false
        reviewProgress.max = total.coerceAtLeast(1) * PROGRESS_STEPS
        reviewProgress.progress = completed.coerceIn(0, total) * PROGRESS_STEPS
        statusHandler.removeCallbacks(progressTick)
        statusHandler.post(progressTick)
    }

    private fun startReviewProgress() {
        if (!::reviewProgress.isInitialized) return
        progressStartedAt = SystemClock.elapsedRealtime()
        progressSectionStartedAt = progressStartedAt
        progressCompleted = 0
        progressTotal = 0
        progressPerSectionMs = 0L
        reviewProgress.isIndeterminate = true
        reviewProgress.visibility = View.VISIBLE
    }

    private fun hideReviewProgress() {
        if (!::reviewProgress.isInitialized) return
        statusHandler.removeCallbacks(progressTick)
        reviewProgress.visibility = View.GONE
        reviewProgress.isIndeterminate = false
        reviewProgress.progress = 0
    }

    /** Advances the bar inside the current section and refreshes the ETA. */
    private val progressTick = object : Runnable {
        override fun run() {
            if (!::reviewProgress.isInitialized) return
            if (reviewProgress.visibility != View.VISIBLE) return
            val total = progressTotal
            if (total <= 0) return
            val perSection = progressPerSectionMs.takeIf { it > 0 }
                ?: DEFAULT_SECTION_MS
            val inSection = SystemClock.elapsedRealtime() - progressSectionStartedAt
            // Creep at most 95% of one section so the bar never claims a
            // section finished before it did.
            val partial = ((inSection.toDouble() / perSection) * PROGRESS_STEPS)
                .toInt()
                .coerceIn(0, (PROGRESS_STEPS * 95) / 100)
            reviewProgress.progress =
                (progressCompleted * PROGRESS_STEPS + partial)
                    .coerceAtMost(total * PROGRESS_STEPS)
            val remaining = (total - progressCompleted).coerceAtLeast(0)
            val remainingMs =
                (remaining.toLong() * perSection - inSection).coerceAtLeast(0L)
            reviewNote.text = when {
                progressCompleted == 0 && progressPerSectionMs == 0L ->
                    getString(R.string.writing_progress_starting, total)
                remainingMs < 15_000L ->
                    getString(
                        R.string.writing_progress_finishing,
                        progressCompleted,
                        total,
                    )
                else -> getString(
                    R.string.writing_progress_eta,
                    progressCompleted,
                    total,
                    formatRemaining(remainingMs),
                )
            }
            statusHandler.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    private fun formatRemaining(millis: Long): String {
        val totalSeconds = (millis / 1000L).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return when {
            minutes >= 1 && seconds >= 5 ->
                getString(R.string.writing_progress_minutes_seconds, minutes, seconds)
            minutes >= 1 -> getString(R.string.writing_progress_minutes, minutes)
            else -> getString(R.string.writing_progress_seconds, seconds.coerceAtLeast(1))
        }
    }

    private fun acceptAllCorrections() {
        val session = reviewSession ?: return
        if (!session.allModelReplacementsReady(selectedTone)) return
        runCatching {
            session.acceptAll(editor.text.toString(), selectedTone)
        }.onSuccess { updated ->
            setEditorTextFromReview(updated)
            renderCurrentBlock(requestModel = false)
        }.onFailure {
            closeReview()
        }
    }

    private fun undoReviewAction() {
        val session = reviewSession ?: return
        runCatching {
            session.undo(editor.text.toString())
        }.onSuccess { updated ->
            setEditorTextFromReview(updated)
            renderCurrentBlock()
        }.onFailure {
            closeReview()
        }
    }

    private fun restoreOriginalReview() {
        val session = reviewSession ?: return
        runCatching {
            session.restoreOriginal(editor.text.toString())
        }.onSuccess { updated ->
            Log.i(REVIEW_LOG, "cancel=restoreOriginal")
            modelRequests.cancelAll()
            modelRequestEpoch += 1
            setEditorTextFromReview(updated)
            renderCurrentBlock()
        }.onFailure {
            closeReview()
        }
    }

    private fun setEditorTextFromReview(updated: String) {
        internalTextChange = true
        editor.setText(updated)
        internalTextChange = false
    }

    private fun closeReview() {
        Log.i(REVIEW_LOG, "cancel=closeReview")
        reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.CloseRequested)
    }

    private fun invalidateMainReviewSurface(event: ReviewSurfaceEvent) {
        mainKeyboardRestore.cancel()
        reviewSurfaceExecutor.dispatch(event)
    }

    private fun mainEditorEligibility(): ReviewTargetEligibility {
        if (!::editor.isInitialized || currentPage != Page.WRITE || !editor.isAttachedToWindow) {
            return ReviewTargetEligibility.DETACHED
        }
        if (editor.transformationMethod is android.text.method.PasswordTransformationMethod) {
            return ReviewTargetEligibility.PASSWORD
        }
        if (!editor.isEnabled || !editor.isFocusable || editor.text.isNullOrBlank()) {
            return ReviewTargetEligibility.INELIGIBLE
        }
        return ReviewTargetEligibility.ELIGIBLE
    }

    private fun beginMainReviewSurface(): Boolean {
        val eligibility = mainEditorEligibility()
        if (eligibility != ReviewTargetEligibility.ELIGIBLE) {
            reviewSurfaceExecutor.dispatch(mainEditorInvalidationEvent(eligibility))
            return false
        }
        reviewSurfaceExecutor.dispatch(
            ReviewSurfaceEvent.FocusedTargetAvailable(
                ReviewTarget(MAIN_EDITOR_TARGET_ID, eligibility),
            ),
        )
        reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.OpenRequested)
        return reviewSurfaceController.state is ReviewSurfaceState.Opening
    }

    private fun mainEditorInvalidationEvent(
        eligibility: ReviewTargetEligibility,
    ): ReviewSurfaceEvent = when (eligibility) {
        ReviewTargetEligibility.PASSWORD -> ReviewSurfaceEvent.PasswordFocused
        ReviewTargetEligibility.SWITCHED_APPLICATION -> ReviewSurfaceEvent.ApplicationSwitched
        else -> ReviewSurfaceEvent.TargetLost
    }

    private fun restoreEncryptedReview(jobId: String) {
        grammarEngine.restoreJob(jobId) { result ->
            if (isFinishing || isDestroyed || activeReviewJobId != jobId) {
                return@restoreJob
            }
            val job = result.getOrNull() ?: return@restoreJob
            if (editor.text.isNullOrEmpty()) {
                setEditorTextFromReview(job.originalText)
            }
            val session = WritingReviewSession.createParagraphs(
                source = editor.text.toString(),
                selectionStart = editor.selectionStart,
                selectionEnd = editor.selectionEnd,
            ) ?: return@restoreJob
            job.candidateRevisions.forEachIndexed { index, candidate ->
                session.setModelReplacement(index + 1, job.tone, candidate)
            }
            selectedTone = job.tone
            reviewSession = session
            iconWasClicked = true
            if (!beginMainReviewSurface()) {
                reviewSession = null
                iconWasClicked = false
                return@restoreJob
            }
            showCorrectionOverlay()
            assistantTrigger.visibility = View.GONE
            reviewSurfaceExecutor.dispatch(ReviewSurfaceEvent.ReviewAttached)
            renderCurrentBlock()
        }
    }

    private fun underlineTarget(block: WritingReviewBlock) {
        val editable = editor.editableText
        if (block.start !in 0..editable.length || block.end !in 0..editable.length || block.start >= block.end) return
        val span = TargetUnderlineSpan(COLOR_ERROR, dp(2).toFloat())
        editable.setSpan(span, block.start, block.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        activeTargetSpan = span
        // Moving the editor cursor while the correction card owns focus makes
        // the outer ScrollView jump back to the editor and hides the card's
        // official header. Only move the cursor when the editor owns focus.
        if (editor.hasFocus()) {
            editor.setSelection(block.start)
            editor.post { editor.bringPointIntoView(block.start) }
        }
    }

    private fun clearTargetUnderline() {
        if (!::editor.isInitialized) return
        activeTargetSpan?.let { editor.editableText.removeSpan(it) }
        activeTargetSpan = null
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

    private fun updateAssistantTrigger() {
        if (!::assistantTrigger.isInitialized || !::editor.isInitialized) return
        val cardVisible = correctionCardOrNull()?.visibility == View.VISIBLE
        if (::correctButton.isInitialized) {
            val hasText = editor.text?.isNotBlank() == true
            correctButton.isEnabled = hasText && !cardVisible
            correctButton.alpha = if (correctButton.isEnabled) 1f else 0.5f
        }
        val state = ManuscriptTriggerPolicy.state(
            focused = currentPage == Page.WRITE && editor.hasFocus(),
            editable = editor.isEnabled && editor.isFocusable,
            hasText = editor.text?.isNotBlank() == true,
            password = editor.transformationMethod is android.text.method.PasswordTransformationMethod,
            reviewOpen = cardVisible,
            animationsEnabled = android.animation.ValueAnimator.areAnimatorsEnabled(),
        )
        TriggerPulseController.applyState(assistantTrigger, state)
    }

    private fun showPersonalizePage() {
        persistDraftNow()
        currentPage = Page.PERSONALIZE
        invalidateMainReviewSurface(ReviewSurfaceEvent.TargetLost)
        updateTabs()
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(dp(16), 0, dp(16), dp(20))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(this).apply {
            text = getString(R.string.personalize_heading)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        }, matchWidth(bottomMargin = 4))
        content.addView(TextView(this).apply {
            text = getString(R.string.local_tools_help)
            textSize = 14f
            setTextColor(COLOR_TEXT_MUTED)
        }, matchWidth(bottomMargin = 12))

        localToolsStatus = TextView(this).apply {
            id = R.id.local_tools_status
            text = getString(R.string.local_tools_ready)
            setTextColor(COLOR_ACCENT)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(localToolsStatus, matchWidth(bottomMargin = 10))
        content.addView(personalizeCard(isVocabulary = true), matchWidth(bottomMargin = 12))
        content.addView(personalizeCard(isVocabulary = false), matchWidth(bottomMargin = 12))
        content.addView(TextView(this).apply {
            text = BuildIdentity.current(
                context = this@MainActivity,
                localAiReady = LocalAiCorrectionGate.isReady(
                    AiModelManager.get(this@MainActivity).installedFile(),
                ),
                accessibilityEnabled = isExternalAccessEnabled(),
            ).summary()
            textSize = 12f
            setTextColor(COLOR_TEXT_MUTED)
            setTextIsSelectable(true)
        }, matchWidth(bottomMargin = 12))
        scroll.addView(content)
        pageHost.removeAllViews()
        pageHost.addView(scroll)
        refreshLocalToolsSummary()
    }

    private fun personalizeCard(isVocabulary: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(COLOR_CARD, 18f, COLOR_BORDER)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        addView(TextView(this@MainActivity).apply {
            text = getString(if (isVocabulary) R.string.vocabulary_heading else R.string.snippet_heading)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(COLOR_TEXT)
        }, matchWidth(bottomMargin = 8))
        if (isVocabulary) {
            vocabularySpoken = formField(R.id.vocabulary_spoken, R.string.vocabulary_spoken_hint)
            vocabularyWritten = formField(R.id.vocabulary_written, R.string.vocabulary_written_hint)
            vocabularyCategory = formField(R.id.vocabulary_category, R.string.vocabulary_category_hint)
            addView(vocabularySpoken, matchWidth(bottomMargin = 8))
            addView(vocabularyWritten, matchWidth(bottomMargin = 8))
            addView(vocabularyCategory, matchWidth(bottomMargin = 8))
            val buttons = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(primaryButton(R.string.vocabulary_save_action) { saveVocabulary() }, weightedParams(endMargin = 6))
            buttons.addView(secondaryButton(R.string.vocabulary_delete_action) { deleteVocabulary() }, weightedParams(startMargin = 6))
            addView(buttons, matchWidth(bottomMargin = 8))
            vocabularySummary = bodyText(R.id.vocabulary_summary)
            addView(vocabularySummary)
        } else {
            snippetTrigger = formField(R.id.snippet_trigger, R.string.snippet_trigger_hint)
            snippetContent = formField(R.id.snippet_content, R.string.snippet_content_hint, 4)
            addView(snippetTrigger, matchWidth(bottomMargin = 8))
            addView(snippetContent, matchWidth(bottomMargin = 8))
            val buttons = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(primaryButton(R.string.snippet_save_action) { saveSnippet() }, weightedParams(endMargin = 6))
            buttons.addView(secondaryButton(R.string.snippet_delete_action) { deleteSnippet() }, weightedParams(startMargin = 6))
            addView(buttons, matchWidth(bottomMargin = 8))
            snippetSummary = bodyText(R.id.snippet_summary)
            addView(snippetSummary)
        }
    }

    private fun saveVocabulary() {
        val result = localTools.upsertVocabulary(
            vocabularySpoken.text.toString(),
            vocabularyWritten.text.toString(),
            vocabularyCategory.text.toString(),
        )
        if (result.value != null) {
            localToolsStatus.text = getString(R.string.vocabulary_saved)
            vocabularySpoken.text.clear(); vocabularyWritten.text.clear(); vocabularyCategory.text.clear()
            refreshLocalToolsSummary()
        } else localToolsStatus.text = result.error ?: getString(R.string.local_tools_failed)
    }

    private fun deleteVocabulary() {
        val result = localTools.deleteVocabulary(vocabularySpoken.text.toString())
        localToolsStatus.text = when (result.value) {
            true -> getString(R.string.vocabulary_deleted)
            false -> getString(R.string.vocabulary_not_found)
            null -> result.error ?: getString(R.string.local_tools_failed)
        }
        if (result.value == true) vocabularySpoken.text.clear()
        refreshLocalToolsSummary()
    }

    private fun saveSnippet() {
        val result = localTools.upsertSnippet(snippetTrigger.text.toString(), snippetContent.text.toString())
        if (result.value != null) {
            localToolsStatus.text = getString(R.string.snippet_saved)
            snippetTrigger.text.clear(); snippetContent.text.clear()
            refreshLocalToolsSummary()
        } else localToolsStatus.text = result.error ?: getString(R.string.local_tools_failed)
    }

    private fun deleteSnippet() {
        val result = localTools.deleteSnippet(snippetTrigger.text.toString())
        localToolsStatus.text = when (result.value) {
            true -> getString(R.string.snippet_deleted)
            false -> getString(R.string.snippet_not_found)
            null -> result.error ?: getString(R.string.local_tools_failed)
        }
        if (result.value == true) snippetTrigger.text.clear()
        refreshLocalToolsSummary()
    }

    private fun refreshLocalToolsSummary() {
        val vocabulary = localTools.listVocabulary()
        vocabularySummary.text = vocabulary.value?.let { entries ->
            if (entries.isEmpty()) getString(R.string.vocabulary_empty)
            else entries.take(SUMMARY_ENTRY_LIMIT).joinToString("\n") { "${it.spokenForm}  ->  ${it.writtenForm}" }
        } ?: getString(R.string.local_tools_unavailable)
        val snippets = localTools.listSnippets()
        snippetSummary.text = snippets.value?.let { entries ->
            if (entries.isEmpty()) getString(R.string.snippet_empty)
            else entries.take(SUMMARY_ENTRY_LIMIT).joinToString("\n") { it.trigger }
        } ?: getString(R.string.local_tools_unavailable)
        (vocabulary.error ?: snippets.error)?.let { localToolsStatus.text = it }
    }

    private fun shareDocument() {
        val reviewed = ReviewedTextSharePolicy.accept(editor.text) ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, reviewed)
        }, getString(R.string.share_document_chooser)))
    }

    private fun updateTabs() {
        if (!::writeTab.isInitialized) return
        val writeActive = currentPage == Page.WRITE
        writeTab.background = rounded(if (writeActive) COLOR_ACCENT_DARK else COLOR_SURFACE, 12f, if (writeActive) COLOR_ACCENT else COLOR_BORDER)
        personalizeTab.background = rounded(if (!writeActive) COLOR_ACCENT_DARK else COLOR_SURFACE, 12f, if (!writeActive) COLOR_ACCENT else COLOR_BORDER)
        writeTab.setTextColor(if (writeActive) Color.WHITE else COLOR_TEXT_MUTED)
        personalizeTab.setTextColor(if (!writeActive) Color.WHITE else COLOR_TEXT_MUTED)
        writeTab.isSelected = writeActive
        personalizeTab.isSelected = !writeActive
    }

    private fun configureEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
        else {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }

    private fun applySystemBarInsets(view: View) {
        view.setOnApplyWindowInsetsListener { target, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                target.setPadding(
                    maxOf(bars.left, cutout.left), maxOf(bars.top, cutout.top),
                    maxOf(bars.right, cutout.right), maxOf(bars.bottom, cutout.bottom, keyboard.bottom),
                )
            } else {
                @Suppress("DEPRECATION")
                target.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        view.requestApplyInsets()
    }

    private fun showSystemKeyboard(target: View) {
        getSystemService(InputMethodManager::class.java)?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSystemKeyboard(target: View) {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(target.windowToken, 0)
    }

    private fun correctionCardOrNull(): LinearLayout? = if (::correctionCard.isInitialized) correctionCard else null

    private fun isDescendant(view: View, parent: ViewGroup): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }

    private fun tabButton(textRes: Int, action: () -> Unit): Button = Button(this).apply {
        text = getString(textRes)
        isAllCaps = false
        minHeight = dp(MIN_TOUCH_TARGET_DP)
        minimumHeight = dp(MIN_TOUCH_TARGET_DP)
        setTypeface(typeface, Typeface.BOLD)
        setOnClickListener { action() }
    }

    private fun primaryButton(textRes: Int, action: () -> Unit): Button = Button(this).apply {
        text = getString(textRes)
        contentDescription = text
        isAllCaps = false
        textSize = 16f
        minHeight = dp(52)
        minimumHeight = dp(52)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(COLOR_ACCENT_DARK, 12f, COLOR_ACCENT)
        setOnClickListener { action() }
    }

    private fun secondaryButton(textRes: Int, action: () -> Unit): Button = Button(this).apply {
        text = getString(textRes)
        contentDescription = text
        isAllCaps = false
        textSize = 16f
        minHeight = dp(52)
        minimumHeight = dp(52)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(COLOR_TEXT)
        background = rounded(COLOR_SURFACE_RAISED, 12f, COLOR_BORDER)
        setOnClickListener { action() }
    }

    private fun caption(textRes: Int): TextView = TextView(this).apply {
        text = getString(textRes)
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(COLOR_TEXT_MUTED)
    }

    private fun bodyText(id: Int): TextView = TextView(this).apply {
        this.id = id
        textSize = 15f
        setTextColor(COLOR_TEXT)
        setTextIsSelectable(true)
    }

    private fun formField(id: Int, hintRes: Int, lines: Int = 1): EditText = EditText(this).apply {
        this.id = id
        hint = getString(hintRes)
        setHintTextColor(COLOR_TEXT_FAINT)
        setTextColor(COLOR_TEXT)
        textSize = 16f
        minLines = lines
        gravity = if (lines > 1) Gravity.TOP else Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(COLOR_SURFACE_RAISED, 12f, COLOR_BORDER)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            if (lines > 1) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        isSaveEnabled = false
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun matchWidth(bottomMargin: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { this.bottomMargin = dp(bottomMargin) }

    private fun weightedParams(startMargin: Int = 0, endMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(startMargin)
            marginEnd = dp(endMargin)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class TargetUnderlineSpan(
        private val color: Int,
        private val thickness: Float,
    ) : CharacterStyle(), UpdateAppearance {
        override fun updateDrawState(textPaint: TextPaint) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                textPaint.underlineColor = color
                textPaint.underlineThickness = thickness
            } else {
                textPaint.color = color
                textPaint.isUnderlineText = true
            }
        }
    }

    private enum class Page { WRITE, PERSONALIZE }

    internal companion object {
        const val EXTRA_OPEN_PERSONALIZE = "com.remote.Manuscript.v2.OPEN_PERSONALIZE"
        const val EXTRA_OPEN_MODEL_SETUP = "com.remote.Manuscript.v2.OPEN_MODEL_SETUP"
        const val SETTINGS_HIGHLIGHT_KEY = ":settings:fragment_args_key"
        const val SETTINGS_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
        const val AOSP_INSTALLED_SERVICES_KEY = "user_installed_services_category"
        const val SAMSUNG_INSTALLED_SERVICES_KEY = "accessibility_installed_services"
        const val SAMSUNG_ACCESSIBILITY_SETTINGS_ACTION =
            "com.samsung.accessibility.ACCESSIBILITY_SETTINGS"
        const val STATE_REVIEW_OPEN = "manuscript.review.open"
        const val STATE_REVIEW_JOB_ID = "manuscript.review.job_id"
        private const val STARTUP_DIALOG_HANDOFF_DELAY_MS = 200L
        private const val DRAFT_SAVE_DELAY_MS = 400L
        private const val TRIGGER_REFRESH_DELAY_MS = 120L
        private const val REVIEW_KEYBOARD_HIDE_LIMIT_MS = 360L
        private const val REVIEW_KEYBOARD_POLL_MS = 16L
        const val PREFERENCES_NAME = "manuscript_preferences_v1"
        const val TONE_KEY = "writing_tone"
        private const val REVIEW_LOG = "ManuscriptReview"
        private const val DOWNLOAD_LOG = "ManuscriptDownload"
        private val MAIN_EDITOR_TARGET_ID = ReviewTargetId("main-editor")
        // Sub-steps per section, so the bar can creep while one is running.
        private const val PROGRESS_STEPS = 100
        private const val PROGRESS_TICK_MS = 500L
        // Used only until the first section reports a real duration.
        private const val DEFAULT_SECTION_MS = 45_000L

        // Whitespace differs between the review block and the
        // pipeline pieces (join newlines, wrapping), so blocks are
        // matched on collapsed whitespace.
        private val WHITESPACE = Regex("\\s+")
        private const val BLOCK_JOIN = "\n\n"
        const val MIN_TOUCH_TARGET_DP = 48
        const val REQUEST_RECORD_AUDIO = 4801
        private const val PREFETCH_IDLE_DELAY_MS = 2_500L
        private const val PREFETCH_MIN_CHARS = 60
        const val SUMMARY_ENTRY_LIMIT = 8
        const val COLOR_BACKGROUND = ManuscriptPalette.BACKGROUND
        const val COLOR_SURFACE = ManuscriptPalette.SURFACE
        const val COLOR_SURFACE_RAISED = ManuscriptPalette.SURFACE_RAISED
        const val COLOR_CARD = ManuscriptPalette.SURFACE
        const val COLOR_BORDER = ManuscriptPalette.BORDER
        const val COLOR_ACCENT = ManuscriptPalette.ACCENT
        const val COLOR_ACCENT_DARK = ManuscriptPalette.ACCENT_DARK
        const val COLOR_TEXT = ManuscriptPalette.TEXT
        const val COLOR_TEXT_MUTED = ManuscriptPalette.MUTED_TEXT
        const val COLOR_TEXT_FAINT = ManuscriptPalette.BORDER
        const val COLOR_ERROR = ManuscriptPalette.ERROR
        const val COLOR_REMOVED = ManuscriptPalette.REMOVED
        const val COLOR_INSERTED = ManuscriptPalette.INSERTED
        const val COLOR_INSERTED_BACKGROUND = ManuscriptPalette.INSERTED_BACKGROUND
    }
}
