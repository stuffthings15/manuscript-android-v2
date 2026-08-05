package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTelemetryUiSourceTest {
    private val mainRoot = File(
        checkNotNull(System.getProperty("echoLinkAndroidDir")),
        "app/src/main",
    )
    private val sourceRoot = File(mainRoot, "java/com/echolink/mobile")

    @Test
    fun mainDownloadSurfaceRendersEverySharedPresentationFieldAndAction() {
        val activity = source("MainActivity.kt")
        val render = functionBody(activity, "renderDownloadTelemetry")

        assertTrue(render.contains("DownloadTelemetryPresentation"))
        listOf(
            "statusText",
            "detailText",
            "progressText",
            "percent",
            "speedText",
            "etaText",
            "networkText",
            "actions",
        ).forEach { field ->
            assertTrue("Download UI omits $field", render.contains("presentation.$field"))
        }
        assertTrue(render.contains("DownloadAction.RETRY"))
        assertTrue(render.contains("DownloadAction.CANCEL"))
    }

    @Test
    fun downloadStatusAndActionsExposeStableUserLikeControls() {
        val activity = source("MainActivity.kt")
        val ids = File(mainRoot, "res/values/ids.xml").readText()
        val requiredIds = listOf(
            "local_ai_download_status",
            "local_ai_download_detail",
            "local_ai_download_progress",
            "local_ai_retry_action",
            "local_ai_cancel_action",
        )

        requiredIds.forEach { id ->
            assertTrue("Missing stable download control ID: $id", ids.contains("name=\"$id\""))
            assertTrue("MainActivity does not bind $id", activity.contains("R.id.$id"))
        }
    }

    @Test
    fun onlyTheDownloadProgressNodeIsALiveRegionWithinTheDownloadSurface() {
        val activity = source("MainActivity.kt")
        val status = initializerBody(activity, "qwenStatus = TextView(this).apply")
        val detail = initializerBody(activity, "qwenDownloadDetail = TextView(this).apply")
        val progress = initializerBody(activity, "qwenDownloadProgress = TextView(this).apply")

        assertFalse(status.contains("accessibilityLiveRegion"))
        assertFalse(detail.contains("accessibilityLiveRegion"))
        assertTrue(progress.contains("accessibilityLiveRegion"))
        assertTrue(progress.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
    }

    @Test
    fun visibleDownloadStateComesFromTheExactDurableTarget() {
        val activity = source("MainActivity.kt")
        val current = functionBody(activity, "currentDownloadPresentation")

        assertTrue(current.contains("DownloadTargetCatalog.targetFor"))
        assertTrue(current.contains("selectedRuntime()"))
        assertTrue(current.contains("AndroidDownloadServices.get"))
        assertTrue(current.contains("services.uiSnapshot(target)"))
        assertTrue(current.contains("snapshot.actionableAttempt"))
        assertTrue(current.contains("DownloadTelemetryPresentation.from(snapshot.telemetry)"))
        assertFalse(current.contains("restoreTelemetry("))
        assertFalse(current.contains("terminalAttempt("))
        assertFalse(current.contains("activeAttempt("))
        assertFalse(current.contains("AiModelState"))
        assertFalse(current.contains("manager.state"))
    }

    @Test
    fun lifecycleRehydratesTheDurablePresentationWithoutSavingTelemetryInBundle() {
        val activity = source("MainActivity.kt")
        val create = functionBody(activity, "onCreate")
        val resume = functionBody(activity, "onResume")
        val pause = functionBody(activity, "onPause")
        val destroy = functionBody(activity, "onDestroy")
        val save = functionBody(activity, "onSaveInstanceState")

        assertTrue(create.contains("refreshDownloadTelemetry"))
        assertTrue(resume.contains("refreshDownloadTelemetry"))
        assertTrue(pause.contains("stopDownloadTelemetryUpdates"))
        assertTrue(destroy.contains("stopDownloadTelemetryUpdates"))
        assertFalse(save.contains("DownloadTelemetry"))
        assertFalse(save.contains("downloadedBytes"))
        assertFalse(save.contains("etaText"))
    }

    @Test
    fun retryAndCancelStayBoundToTheExactAttemptShownEvenIfSelectionChanges() {
        val activity = source("MainActivity.kt")
        val current = functionBody(activity, "currentDownloadPresentation")
        val action = functionBody(activity, "performDownloadAction")

        assertTrue(activity.contains("displayedDownloadTarget"))
        assertTrue(activity.contains("displayedDownloadAttempt"))
        assertTrue(current.contains("displayedDownloadTarget = null"))
        assertTrue(current.contains("displayedDownloadTarget = target"))
        assertTrue(current.contains("services.uiSnapshot(target)"))
        assertTrue(current.contains("displayedDownloadAttempt = snapshot.actionableAttempt"))
        assertTrue(current.contains("displayedDownloadAttempt = null"))
        assertTrue(action.contains("DownloadAction.RETRY"))
        assertTrue(action.contains("val target = displayedDownloadTarget"))
        assertTrue(action.contains("if (target == null)"))
        assertTrue(action.contains("downloadUiFallbackRetry"))
        assertTrue(action.contains("services.retryOrEnsure(target)"))
        assertTrue(action.contains("val attempt = displayedDownloadAttempt"))
        assertTrue(action.contains("attempt.target != target"))
        assertTrue(action.contains("services.retry(attempt)"))
        assertTrue(action.contains("DownloadAction.CANCEL"))
        assertTrue(action.contains("services.cancel(attempt)"))
        assertFalse(action.contains("selectedRuntime()"))
        assertFalse(action.contains("DownloadTargetCatalog.targetFor"))
        assertFalse(action.contains("terminalAttempt("))
        assertFalse(action.contains("activeAttempt("))
        assertFalse(action.contains("ensureModelAsync"))
        assertFalse(action.contains("cancelAll"))
    }

    @Test
    fun onePollingStartSeamOwnsTheImmediateStatusRunnablePost() {
        val activity = source("MainActivity.kt")
        val start = functionBody(activity, "startDownloadTelemetryUpdates")
        val resume = functionBody(activity, "onResume")
        val action = functionBody(activity, "performDownloadAction")

        assertTrue(start.contains("statusHandler.removeCallbacks(qwenStatusRefresh)"))
        assertTrue(start.contains("statusHandler.post(qwenStatusRefresh)"))
        assertTrue(resume.contains("startDownloadTelemetryUpdates()"))
        assertTrue(action.contains("startDownloadTelemetryUpdates()"))
        assertTrue(occurrenceCount(activity, "statusHandler.post(qwenStatusRefresh)") == 1)
    }

    @Test
    fun missingConsentOverridesRetryAndHidesBothDownloadActions() {
        val activity = source("MainActivity.kt")
        val render = functionBody(activity, "renderDownloadTelemetry")
        val consentIndex = render.indexOf("if (!manager.hasDownloadConsent())")
        val retryIndex = render.indexOf("DownloadAction.RETRY in presentation.actions")
        val consentBranch = render.substring(consentIndex, retryIndex)

        assertTrue(consentIndex >= 0)
        assertTrue(retryIndex > consentIndex)
        assertTrue(consentBranch.contains("qwenRetryButton.visibility = View.GONE"))
        assertTrue(consentBranch.contains("qwenCancelButton.visibility = View.GONE"))
        assertTrue(consentBranch.contains("qwenInstallButton.visibility = View.VISIBLE"))
        assertTrue(consentBranch.contains("return"))
    }

    @Test
    fun everyDownloadUiOperationMapsExceptionsToOneVisibleRetrySurface() {
        val activity = source("MainActivity.kt")
        val current = functionBody(activity, "currentDownloadPresentation")
        val action = functionBody(activity, "performDownloadAction")
        val safeOperation = activity
            .substringAfter("private fun runDownloadUiOperation(")
            .substringBefore("private fun performDownloadAction(")
        val install = functionBody(activity, "requestModelInstallation")
        val settings = functionBody(activity, "showModelSettings")
        val prompt = functionBody(activity, "showModelInstallPromptIfNeeded")

        listOf(current, action, safeOperation).forEach { body ->
            assertTrue(body.contains("catch (failure: Exception)"))
            assertTrue(body.contains("DownloadTelemetryPresentation.uiFailure()"))
        }
        assertTrue(install.contains("runDownloadUiOperation("))
        assertTrue(settings.contains("runDownloadUiOperation("))
        assertTrue(prompt.contains("runDownloadUiOperation("))
    }

    @Test
    fun startupReadinessAndInstallPromptUseTheSameDurablePresentation() {
        val activity = source("MainActivity.kt")
        val startup = functionBody(activity, "refreshStartupReadinessDialog")
        val installPrompt = functionBody(activity, "showModelInstallPromptIfNeeded")

        assertTrue(startup.contains("currentDownloadPresentation"))
        assertTrue(startup.contains("DownloadTelemetryPresentation"))
        assertFalse(startup.contains("manager.state"))
        assertFalse(startup.contains("AiModelState"))
        assertTrue(installPrompt.contains("currentDownloadPresentation"))
        assertTrue(installPrompt.contains("renderDownloadTelemetry"))
    }

    @Test
    fun legacyManagerStatePollingCannotDriveVisibleDownloadText() {
        val activity = source("MainActivity.kt")
        val statusUpdate = functionBody(activity, "updateQwenStatus")
        val refresh = functionBody(activity, "refreshDownloadTelemetry")

        assertFalse(statusUpdate.contains("manager.state"))
        assertFalse(statusUpdate.contains("AiModelState"))
        assertTrue(statusUpdate.contains("currentDownloadPresentation"))
        assertTrue(statusUpdate.contains("renderDownloadTelemetry"))
        assertTrue(refresh.contains("currentDownloadPresentation"))
        assertTrue(refresh.contains("renderDownloadTelemetry"))
        assertFalse(refresh.contains("AiModelState"))
    }

    private fun source(name: String): String = File(sourceRoot, name).readText()

    private fun occurrenceCount(source: String, value: String): Int =
        source.windowed(value.length).count { it == value }

    private fun initializerBody(source: String, declaration: String): String {
        val declarationIndex = source.indexOf(declaration)
        check(declarationIndex >= 0) { "Missing production seam: $declaration" }
        val openBrace = source.indexOf('{', declarationIndex + declaration.length)
        check(openBrace >= 0) { "Missing body for production seam: $declaration" }
        return balancedBody(source, declarationIndex, openBrace, declaration)
    }

    private fun functionBody(source: String, name: String): String {
        val signature = Regex("fun\\s+$name\\s*\\(")
        val match = checkNotNull(signature.find(source)) {
            "Missing production seam: $name"
        }
        val openBrace = source.indexOf('{', match.range.last + 1)
        check(openBrace >= 0) { "Missing body for production seam: $name" }
        return balancedBody(source, match.range.first, openBrace, name)
    }

    private fun balancedBody(
        source: String,
        start: Int,
        openBrace: Int,
        seam: String,
    ): String {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in openBrace until source.length) {
            val character = source[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed body for production seam: $seam")
    }
}
