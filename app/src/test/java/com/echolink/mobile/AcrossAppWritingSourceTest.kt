package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcrossAppWritingSourceTest {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val mainRoot = File(androidRoot, "app/src/main")

    @Test
    fun serviceIsExplicitOptInAndUsesTheAccessibilityOverlayType() {
        val manifest = File(mainRoot, "AndroidManifest.xml").readText()
        val config = File(mainRoot, "res/xml/accessibility_service_config.xml").readText()
        val service = source("ManuscriptAccessibilityService.kt")

        assertTrue(manifest.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertTrue(manifest.contains("@xml/accessibility_service_config"))
        assertTrue(config.contains("android:canRetrieveWindowContent=\"true\""))
        assertTrue(config.contains("flagInputMethodEditor"))
        assertTrue(service.contains("TYPE_ACCESSIBILITY_OVERLAY"))
        assertTrue(service.contains("ExternalWritingPolicy.metadataSnapshot"))
        assertTrue(service.contains("AccessibilityTraversalBudget()"))
        assertFalse(service.contains("node.text?.isNotBlank()"))
        assertFalse(manifest.contains("android.permission.SYSTEM_ALERT_WINDOW"))
    }

    @Test
    fun reviewPanelCoversTheKeyboardAreaAndUsesGuardedDirectReplacement() {
        val service = source("ManuscriptAccessibilityService.kt")
        val strings = File(mainRoot, "res/values/strings.xml").readText()

        assertTrue(service.contains("gravity = Gravity.BOTTOM or Gravity.START"))
        assertTrue(service.contains("bounds.widthPx"))
        assertTrue(service.contains("x = bounds.leftOffsetPx"))
        assertTrue(service.contains("WritingReviewSession.create"))
        assertTrue(service.contains("ACTION_SET_TEXT"))
        assertTrue(service.contains("onCreateInputMethod"))
        assertTrue(service.contains("currentInputConnection"))
        assertTrue(service.contains("retainCurrentInputConnection()"))
        assertTrue(service.contains("retainedInputConnection"))
        assertTrue(service.contains("connection.commitText(updated, 1, null)"))
        assertTrue(service.contains("connectionMatches"))
        assertTrue(service.contains("ReplacementRequestPolicy.initialRoute"))
        assertTrue(service.contains("initialRoute == ReplacementInitialRoute.ACCESSIBILITY_NODE &&"))
        assertTrue(service.contains("captured.node.refresh()"))
        assertTrue(service.contains("rootForPackage(captured.packageName)"))
        assertTrue(service.contains("requestAccessibilityTextReplacement(node, updated)"))
        assertTrue(service.contains("connection != null && connectionMatches"))
        assertFalse(service.contains("connection != null && (connectionMatches || nodeMatches)"))
        assertTrue(service.contains("toneAtRequest"))
        assertTrue(service.contains("replacementInFlight"))
        assertTrue(service.contains("dismissButton?.isEnabled = enabled"))
        assertTrue(service.contains("if (replacementInFlight) return"))
        assertTrue(service.contains("val nodeVerified"))
        assertTrue(service.contains("verifyReplacement(attempt + 1)"))
        assertTrue(service.contains("ReplacementVerificationPolicy.nextAction"))
        assertTrue(service.contains("freshConnectionText == workingText"))
        assertTrue(service.contains("requestInputConnectionTextReplacement"))
        assertTrue(service.contains("external_replace_unverified"))
        assertTrue(service.contains("WindowInsets.Type.ime()"))
        assertTrue(service.contains("AccessibilityWindowInfo.TYPE_INPUT_METHOD"))
        assertTrue(service.contains("lastVisibleKeyboardFrame"))
        assertTrue(service.contains("KeyboardPanelMemoryPolicy.resolve"))
        assertTrue(service.contains("KeyboardWindowGeometryPolicy.fromWindow"))
        assertTrue(service.contains("fallbackHeightPx = dp(256)"))
        assertTrue(service.contains("hideSystemKeyboardBehindPanel()"))
        assertTrue(service.contains("panelView != null && systemKeyboardVisible()"))
        assertTrue(service.contains("performGlobalAction(GLOBAL_ACTION_BACK)"))
        assertTrue(service.contains("systemNavigationBarHeight()"))
        assertTrue(service.contains("navigation_bar_gesture_height"))
        assertTrue(service.contains("getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())"))
        assertTrue(service.contains("FLAG_NOT_TOUCH_MODAL"))
        assertTrue(service.contains("WindowManager.LayoutParams.FLAG_SECURE"))
        assertTrue(service.contains("ExternalWritingPolicy.scrubbedSnapshot"))
        assertFalse(service.contains("originalView"))
        assertFalse(strings.contains("Text being replaced"))
        assertTrue(service.contains("id = R.id.writing_close_action"))
        assertTrue(service.contains("text = \"×\""))
        assertTrue(service.contains("closePanel(restoreKeyboard = true)"))
        assertTrue(service.contains("R.string.qwen_model_retry_action"))
        assertTrue(service.contains("id = R.id.writing_regenerate_action"))
        assertTrue(service.contains("id = R.id.writing_replace_action"))
        assertTrue(service.contains("id = R.id.writing_dismiss_action"))
        assertTrue(service.contains("id = R.id.writing_status"))
        assertTrue(service.contains("review_block=\${block.index}/\${block.total}"))
        assertTrue(service.contains("REPLACEMENT_VERIFY_ATTEMPTS"))
        assertTrue(service.contains("R.drawable.ic_manuscript"))
        assertTrue(service.contains("R.string.open_manuscript_settings"))
        assertTrue(service.contains("openManuscriptSettings()"))
        assertTrue(service.contains("Intent.FLAG_ACTIVITY_NEW_TASK"))
        assertTrue(service.contains("putExtra(EXTRA_OPEN_PERSONALIZE, true)"))
        assertTrue(service.contains("targetCandidateScore(candidate, captured)"))
        assertTrue(service.contains("idMatches.ifEmpty { candidates }"))
        assertTrue(service.contains("restoreTargetRunnable"))
        assertTrue(service.contains("target !== captured"))
        assertTrue(service.contains("cancelKeyboardRestore()"))
        assertTrue(service.contains("first.node == second.node"))
        assertFalse(service.contains("android.os.Handler(mainLooper).postDelayed"))
    }

    @Test
    fun triggerSurvivesTransientAccessibilityEventsWithoutIgnoringPasswords() {
        val service = source("ManuscriptAccessibilityService.kt")

        assertTrue(service.contains("scheduleTargetClear()"))
        // 800 ms rides out Google Messages' accessibility event storms; the
        // press guard keeps a finger on the trigger from clearing it.
        assertTrue(service.contains("TARGET_CLEAR_GRACE_MS = 800L"))
        assertTrue(service.contains("PRESS_GUARD_MS = 600L"))
        assertTrue(service.contains("triggerPressed = true"))
        assertTrue(service.contains("cancelTargetClear()"))
        assertTrue(service.contains("if (snapshot.password) clearTarget()"))
        assertTrue(service.contains("rootInActiveWindow?.packageName?.toString() == packageName"))
    }

    @Test
    fun onboardingDisclosesActiveTextAccessAndLocalOnlyHandling() {
        val activity = source("MainActivity.kt")
        val strings = File(mainRoot, "res/values/strings.xml").readText()

        assertTrue(activity.contains("Settings.ACTION_ACCESSIBILITY_SETTINGS"))
        assertTrue(activity.contains("showExternalAccessDisclosure"))
        assertTrue(
            activity.contains(
                "runStartupReadinessCheck(showDialog = savedInstanceState == null)",
            ),
        )
        assertTrue(activity.contains("showStartupReadinessCheckIfNeeded()"))
        assertTrue(activity.contains("isExternalAccessEnabled()"))
        assertTrue(activity.contains("showModelInstallPromptIfNeeded(force = true)"))
        assertTrue(activity.contains("SETTINGS_HIGHLIGHT_KEY"))
        assertTrue(activity.contains("SETTINGS_SHOW_FRAGMENT_ARGUMENTS"))
        assertTrue(activity.contains("AOSP_INSTALLED_SERVICES_KEY"))
        assertTrue(activity.contains("SAMSUNG_INSTALLED_SERVICES_KEY"))
        assertTrue(activity.contains("Build.MANUFACTURER.equals(\"samsung\""))
        assertTrue(activity.contains("\"accessibility_installed_services\""))
        assertFalse(activity.contains("\"installed_service_key\""))
        assertTrue(activity.contains("\"com.samsung.accessibility.ACCESSIBILITY_SETTINGS\""))
        assertTrue(activity.contains("ActivityNotFoundException"))
        assertFalse(activity.contains("com.samsung.accessibility.installed_service"))
        assertTrue(activity.contains("Toast.LENGTH_LONG"))
        assertTrue(strings.contains("checks whether that field contains text"))
        assertTrue(strings.contains("reads the field only after you tap the Manuscript icon"))
        assertTrue(strings.contains("tap Installed apps"))
        assertTrue(strings.contains("Manuscript across-app writing helper"))
        assertTrue(strings.contains("Password fields are ignored"))
        assertTrue(strings.contains("not sent to Manuscript servers"))
    }

    @Test
    fun accessibilitySettingsGuidanceFitsAndroidsTwoLineToastLimit() {
        val strings = File(mainRoot, "res/values/strings.xml").readText()
        listOf(
            "external_access_installed_apps_prompt",
            "external_access_service_prompt",
        ).forEach { name ->
            val encoded = checkNotNull(
                Regex("""<string name="$name">(.*?)</string>""")
                    .find(strings)
                    ?.groupValues
                    ?.get(1),
            )
            val lines = encoded.replace("\\n", "\n").lines()

            assertTrue("$name must use no more than two lines", lines.size <= 2)
            assertTrue(
                "$name lines must remain readable without toast truncation",
                lines.all { it.length <= 32 },
            )
        }
    }

    @Test
    fun firstLaunchRunsAndDisplaysARealReadinessCheck() {
        val activity = source("MainActivity.kt")
        val manager = source("AiModelManager.kt")
        val selfTester = source("GemmaModelSelfTester.kt")
        val strings = File(mainRoot, "res/values/strings.xml").readText()

        assertTrue(
            activity.contains(
                "runStartupReadinessCheck(showDialog = savedInstanceState == null)",
            ),
        )
        assertTrue(activity.contains("showStartupReadinessCheckIfNeeded()"))
        assertTrue(activity.contains("ManuscriptStartupReadiness.nextAction"))
        assertFalse(activity.contains("if (!force && manager.hasSeenDownloadPrompt()) return"))
        assertTrue(manager.contains("AiModelState.Checking"))
        assertTrue(manager.contains("verifyInstalledModelAsync"))
        assertTrue(selfTester.contains("withTimeout"))
        assertTrue(selfTester.contains("READY"))
        assertTrue(strings.contains("First-launch check"))
        assertTrue(strings.contains("Across-app writing"))
        assertTrue(strings.contains("AI: %2\$s"))
        assertFalse(strings.contains("Qwen"))
    }

    private fun source(name: String) = File(
        mainRoot,
        "java/com/echolink/mobile/$name",
    ).readText()
}
