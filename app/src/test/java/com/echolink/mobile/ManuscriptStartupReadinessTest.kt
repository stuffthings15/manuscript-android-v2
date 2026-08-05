package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ManuscriptStartupReadinessTest {
    @Test
    fun durableStartupTruthTableHonorsEveryPriorityAcrossAllInputCombinations() {
        val presentations = listOf(
            "missing" to null,
            "retry" to DownloadTelemetryPresentation.uiFailure(),
            "cancel" to presentation(DownloadPhase.PREPARING),
            "ready" to presentation(DownloadPhase.READY),
        )

        listOf(false, true).forEach { acrossAppsEnabled ->
            listOf(false, true).forEach { modelReady ->
                listOf(false, true).forEach { hasDownloadConsent ->
                    presentations.forEach { (label, presentation) ->
                        val expected = when {
                            !acrossAppsEnabled -> ManuscriptStartupAction.ENABLE_ACROSS_APPS
                            modelReady -> ManuscriptStartupAction.READY
                            !hasDownloadConsent -> ManuscriptStartupAction.INSTALL_MODEL
                            presentation?.actions?.contains(DownloadAction.RETRY) == true ->
                                ManuscriptStartupAction.RETRY_MODEL
                            else -> ManuscriptStartupAction.WAIT_FOR_MODEL
                        }

                        assertEquals(
                            "across=$acrossAppsEnabled ready=$modelReady " +
                                "consent=$hasDownloadConsent presentation=$label",
                            expected,
                            ManuscriptStartupReadiness.nextAction(
                                acrossAppsEnabled = acrossAppsEnabled,
                                modelReady = modelReady,
                                hasDownloadConsent = hasDownloadConsent,
                                presentation = presentation,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun legacyStateTruthTableCoversEveryStateAndAcrossAppsAlwaysWinsFirst() {
        val selection = AiRuntimeSelection(GemmaModelCatalog.fast, BackendKind.LITERT_CPU)
        val states = listOf(
            AiModelState.Ready(
                selection,
                InstalledAiModel(GemmaModelCatalog.fast, File("ready.litertlm")),
            ) to ManuscriptStartupAction.READY,
            AiModelState.Downloading(selection, 10L, 100L) to
                ManuscriptStartupAction.WAIT_FOR_MODEL,
            AiModelState.Checking(selection) to ManuscriptStartupAction.WAIT_FOR_MODEL,
            AiModelState.ConsentRequired(selection) to ManuscriptStartupAction.INSTALL_MODEL,
            AiModelState.WaitingForWifi(selection) to ManuscriptStartupAction.WAIT_FOR_MODEL,
            AiModelState.InsufficientStorage(selection) to ManuscriptStartupAction.RETRY_MODEL,
            AiModelState.Failed(selection, "fixed safe failure") to
                ManuscriptStartupAction.RETRY_MODEL,
            AiModelState.NotInstalled(selection) to ManuscriptStartupAction.WAIT_FOR_MODEL,
        )

        states.forEach { (state, expected) ->
            assertEquals(
                state::class.java.simpleName,
                expected,
                ManuscriptStartupReadiness.nextAction(true, state),
            )
            assertEquals(
                state::class.java.simpleName,
                ManuscriptStartupAction.ENABLE_ACROSS_APPS,
                ManuscriptStartupReadiness.nextAction(false, state),
            )
        }
    }

    private fun presentation(phase: DownloadPhase): DownloadTelemetryPresentation {
        val ready = phase == DownloadPhase.READY
        val telemetry = DownloadTelemetryRestoreFactory().restore(
            observation = DownloadObservation(
                generation = DownloadGeneration(1L),
                phase = phase,
                downloadedBytes = if (ready) 100L else 0L,
                totalBytes = 100L,
                network = DownloadNetwork.WIFI,
                verificationSucceeded = ready,
                cancellationAllowed = !ready,
            ),
            updatedAtMillis = 1L,
            samples = emptyList(),
        )
        return DownloadTelemetryPresentation.from(telemetry)
    }
}
