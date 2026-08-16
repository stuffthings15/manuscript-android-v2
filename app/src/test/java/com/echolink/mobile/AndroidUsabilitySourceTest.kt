package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUsabilitySourceTest {
    @Test
    fun rotationRestoresReviewFromEncryptedJobIdWithoutPuttingWritingInBundle() {
        val source = File(
            requireNotNull(System.getProperty("echoLinkAndroidDir")),
            "app/src/main/java/com/echolink/mobile/MainActivity.kt",
        ).readText()

        assertTrue(source.contains("override fun onSaveInstanceState"))
        assertTrue(source.contains("override fun onRestoreInstanceState"))
        assertTrue(source.contains("STATE_REVIEW_JOB_ID"))
        assertTrue(source.contains("restoreEncryptedReview(jobId)"))
        val saveBlock = source
            .substringAfter("override fun onSaveInstanceState")
            .substringBefore("override fun onRestoreInstanceState")
        assertFalse(saveBlock.contains("editor.text"))
        assertFalse(saveBlock.contains("originalText"))
    }

    @Test
    fun activeWriteTabCannotRebuildOrEraseTheDraft() {
        val source = source("MainActivity.kt")
        val writePage = source
            .substringAfter("private fun showWritePage()")
            .substringBefore("private fun buildExternalAccessCard()")

        assertTrue(
            writePage.contains(
                "if (currentPage == Page.WRITE && ::editor.isInitialized && editor.isAttachedToWindow)",
            ),
        )
        assertTrue(writePage.substringBefore("currentPage = Page.WRITE").contains("return"))
    }

    @Test
    fun mainDraftUsesEncryptedRecoveryInsteadOfPlaintextViewState() {
        val activity = source("MainActivity.kt")
        val draftStore = source("EncryptedEditorDraftStore.kt")
        val editorBlock = activity
            .substringAfter("editor = EditText")
            .substringBefore("assistantTrigger = ImageButton")

        assertTrue(activity.contains("EncryptedEditorDraftStore(this)"))
        assertTrue(activity.contains("persistDraftNow()"))
        assertTrue(activity.contains("restoreEncryptedDraft()"))
        assertTrue(editorBlock.contains("isSaveEnabled = false"))
        assertFalse(editorBlock.contains("isSaveEnabled = true"))
        assertTrue(draftStore.contains("AndroidKeystoreAiJobTextCipher"))
        assertTrue(draftStore.contains("manuscript_main_editor_draft_v1"))
        assertFalse(draftStore.contains("putString(TEXT_KEY"))
    }

    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val mainRoot = File(androidRoot, "app/src/main")
    private val sourceRoot = File(mainRoot, "java/com/echolink/mobile")

    @Test
    fun appUsesAnOwnedNoActionBarTheme() {
        val manifest = File(mainRoot, "AndroidManifest.xml").readText()
        val styles = File(mainRoot, "res/values/styles.xml").readText()

        assertTrue(
            Regex("android:theme=\\\"@style/Theme\\.(?:Manuscript|EchoLink)\\\"")
                .containsMatchIn(manifest),
        )
        assertTrue(styles.contains("Theme.Material.NoActionBar"))
        assertTrue(styles.contains("name=\"android:windowNoTitle\">true</item>"))
        assertFalse(styles.contains("Theme.Material.Light.NoActionBar"))
    }

    @Test
    fun mainLayoutOwnsEdgeToEdgeAndConsumesEverySystemInset() {
        val source = source("MainActivity.kt")

        assertTrue(source.contains("window.setDecorFitsSystemWindows(false)"))
        assertTrue(source.contains("setOnApplyWindowInsetsListener"))
        assertTrue(source.contains("WindowInsets.Type.systemBars()"))
        assertTrue(source.contains("WindowInsets.Type.displayCutout()"))
        assertTrue(source.contains("insets.systemWindowInsetTop"))
        assertTrue(source.contains("view.requestApplyInsets()"))
        assertTrue(source.contains("id = R.id.main_scroll"))
    }

    @Test
    fun editorAndWritingControlsExposeStableAccessibilitySemantics() {
        val source = source("MainActivity.kt")
        val ids = File(mainRoot, "res/values/ids.xml").readText()

        for (id in listOf(
            "main_heading",
            "review_editor",
            "writing_assist_icon",
            "writing_suggestion_card",
            "writing_close_action",
            "writing_tone",
            "writing_regenerate_action",
            "writing_suggestion",
            "writing_status",
            "writing_replace_action",
            "writing_dismiss_action",
        )) {
            assertTrue("Missing stable ID: $id", ids.contains("name=\"$id\""))
        }
        assertTrue(source.contains("hint = getString(R.string.editor_hint)"))
        assertTrue(source.contains("isAccessibilityHeading = true"))
        assertTrue(source.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(source.contains("contentDescription = text"))
        assertTrue(source.contains("WritingAssistUiPolicy.ICON_TOUCH_TARGET_DP"))
        assertTrue(source.contains("OfficialCorrectionHeader("))
        assertTrue(source.contains("closeReview()"))
        assertTrue(source.contains("getBooleanExtra(EXTRA_OPEN_PERSONALIZE, false)"))
        assertTrue(source.contains("showPersonalizePage()"))
    }

    @Test
    fun typingAutomaticallyGatesTheSmallIconAndCompactCard() {
        val source = source("MainActivity.kt")

        assertTrue(source.contains("ImageButton"))
        assertTrue(source.contains("id = R.id.writing_assist_icon"))
        assertTrue(source.contains("id = R.id.writing_suggestion_card"))
        assertTrue(source.contains("ManuscriptTriggerViewFactory.create("))
        assertTrue(source.contains("ManuscriptTriggerPolicy.state("))
        assertTrue(source.contains("TriggerPulseController.applyState("))
        assertTrue(source.contains("WritingAssistUiPolicy.shouldShowCard"))
        assertTrue(source.contains("addTextChangedListener"))
        assertTrue(source.contains("setOnFocusChangeListener"))
        assertTrue(source.contains("View.GONE"))
        assertTrue(source.contains("LinearLayout.LayoutParams.WRAP_CONTENT"))
    }

    @Test
    fun longDraftOwnsVerticalSwipesAndSurvivesActivityRecreation() {
        val source = source("MainActivity.kt")
        val strings = File(mainRoot, "res/values/strings.xml").readText()
        val editorBlock = source
            .substringAfter("editor = EditText")
            .substringBefore("assistantTrigger = ImageButton")

        assertTrue(editorBlock.contains("requestDisallowInterceptTouchEvent(true)"))
        assertTrue(editorBlock.contains("requestDisallowInterceptTouchEvent(false)"))
        assertTrue(editorBlock.contains("isNestedScrollingEnabled = true"))
        assertFalse(editorBlock.contains("ScrollingMovementMethod"))
        assertFalse(source.contains("import android.text.method.ScrollingMovementMethod"))
        assertTrue(editorBlock.contains("setFreezesText(true)"))
        assertTrue(editorBlock.contains("isSaveEnabled = false"))
        assertFalse(editorBlock.contains("isSaveEnabled = true"))
        assertTrue(source.contains("startupCheckShown = savedInstanceState != null"))
        // The readiness check still runs on a fresh launch. What it no longer
        // does is open a modal to announce the result: the Assistant Status
        // card carries the same two facts with the same actions, so the dialog
        // was covering the thing it duplicated.
        assertTrue(source.contains("runStartupReadinessCheck(showDialog = false)"))
        assertTrue(source.contains("private fun runStartupReadinessCheck(showDialog: Boolean)"))
        assertTrue(source.contains("if (showDialog) showStartupReadinessCheckIfNeeded()"))
        assertFalse(
            source.contains("if (showDialog) showStartupReadinessCheckIfNeeded(force = true)"),
        )
        // The full-width Correct my writing button replaced the icon-only
        // entry point after two testers could not reliably tap the icon.
        assertTrue(strings.contains("tap Correct my writing"))
    }

    @Test
    fun userLikeEssayAcceptanceCannotHideAMissedTouchWithDirectClick() {
        val deviceTest = File(
            androidRoot,
            "app/src/androidTest/java/com/echolink/mobile/EssayUiBatchDeviceTest.kt",
        ).readText()
        val pressUntil = deviceTest
            .substringAfter("private fun pressUntil(")
            .substringBefore("private fun tap(")

        assertFalse(pressUntil.contains("performClick()"))
        assertTrue(pressUntil.contains("check(landedByTouch)"))
    }

    @Test
    fun userLikeEssayAcceptanceWritesBoundLiveModelEvidence() {
        val deviceTest = File(
            androidRoot,
            "app/src/androidTest/java/com/echolink/mobile/EssayUiBatchDeviceTest.kt",
        ).readText()

        assertTrue(deviceTest.contains("getString(\"roundId\")"))
        assertTrue(deviceTest.contains("getString(\"apkSha256\")"))
        assertTrue(deviceTest.contains("grammarEngine.cancelPrefetch()"))
        assertTrue(deviceTest.contains("LocalAiAttemptEvidence.snapshotDetails()"))
        assertTrue(deviceTest.contains("EssayQualityEvidenceBuilder.build("))
        assertTrue(deviceTest.contains("DeviceTestEvidenceWriter.writePrivate("))
    }

    @Test
    fun startupModelActionWaitsForTheReadinessDialogToReleaseWindowFocus() {
        val source = source("MainActivity.kt")

        assertTrue(source.contains("showModelInstallPromptAfterStartupDialog()"))
        assertTrue(source.contains("root.postDelayed"))
        assertTrue(source.contains("showModelInstallPromptIfNeeded(force = true)"))
    }

    @Test
    fun installAiButtonChecksTheSelectedTierInsteadOfAnOlderFallback() {
        val source = source("MainActivity.kt")
        val installAction = source
            .substringAfter("private fun requestModelInstallation()")
            .substringBefore("private fun showWritePage()")
        val installPrompt = source
            .substringAfter("private fun showModelInstallPromptIfNeeded")
            .substringBefore("private fun showModelSettings()")

        assertTrue(
            installAction.contains("manager.exactInstalledModel(manager.selectedRuntime()) != null"),
        )
        assertTrue(
            installPrompt.contains("manager.exactInstalledModel(manager.selectedRuntime()) != null"),
        )
    }

    @Test
    fun visibleModelStatusKeepsFollowingRuntimeTierChanges() {
        val source = source("MainActivity.kt")

        assertTrue(source.contains("private var activityResumed = false"))
        assertTrue(source.contains("activityResumed = true"))
        assertTrue(source.contains("activityResumed = false"))
        assertTrue(source.contains("if (activityResumed) {"))
        assertTrue(source.contains("statusHandler.postDelayed(this, 1_000L)"))
    }

    @Test
    fun cardHasAllTonesAndParagraphAndIndividualChangeActions() {
        val source = source("MainActivity.kt")
        val strings = File(mainRoot, "res/values/strings.xml").readText()

        assertTrue(source.contains("renderCurrentBlock()"))
        assertTrue(source.contains("R.string.writing_replace_action"))
        assertTrue(source.contains("replaceCurrentBlock()"))
        assertTrue(source.contains("R.string.qwen_model_retry_action"))
        assertTrue(source.contains("id = R.id.writing_regenerate_action"))
        assertTrue(source.contains("R.string.writing_dismiss_action"))
        assertTrue(source.contains("dismissCurrentBlock()"))
        assertTrue(
            strings.contains(
                "<string name=\"writing_replace_action\">Accept paragraph</string>",
            ),
        )
        assertTrue(strings.contains("writing_accept_change_action\">Accept change</string>"))
        assertTrue(strings.contains("writing_reject_change_action\">Reject change</string>"))
        assertTrue(strings.contains("<string name=\"writing_regenerate_action\">Regenerate</string>"))
        assertTrue(
            strings.contains(
                "<string name=\"writing_dismiss_action\">Reject paragraph</string>",
            ),
        )
        assertTrue(strings.contains("Swipe up anywhere to enlarge."))
        assertTrue(source.contains("InlineEditDiff.note"))
        assertTrue(source.contains("if (reviewSession?.complete == false) regenerateCurrentBlock()"))
        assertTrue(source.contains("OnDeviceGrammarEngine.get(this)"))
        assertTrue(source.contains("session.hasModelReplacement"))
        assertFalse(source.contains("R.id.writing_suggest_action"))
    }

    @Test
    fun acceptActionsStayAboveLongCorrectionContent() {
        val activity = source("MainActivity.kt")
        val activityCard = activity
            .substringAfter("private fun buildCorrectionCard()")
            .substringBefore("private fun buildToneMenu(")
        val service = source("ManuscriptAccessibilityService.kt")
        val servicePanel = service
            .substringAfter("private fun buildPanel(): View")
            .substringBefore("private fun installPanelResizeGesture(")

        assertTrue(
            "Main review Accept must be added before expandable correction text.",
            activityCard.indexOf("card.addView(actionRow") <
                activityCard.indexOf("reviewSuggestion = bodyText"),
        )
        assertTrue(
            "Opening a main review must scroll the Accept control into view.",
            activity.contains("replaceButton.requestRectangleOnScreen"),
        )
        assertTrue(
            "Across-app Accept must be added before the scrollable correction list.",
            servicePanel.indexOf("panel.addView(actions") <
                servicePanel.indexOf("panel.addView(correctionScroll"),
        )
    }

    private fun source(name: String): String = File(sourceRoot, name).readText()
}
