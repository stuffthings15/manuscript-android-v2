package com.echolink.mobile

enum class ManuscriptStartupAction {
    ENABLE_ACROSS_APPS,
    INSTALL_MODEL,
    WAIT_FOR_MODEL,
    RETRY_MODEL,
    READY,
}

object ManuscriptStartupReadiness {
    fun isModelReady(state: AiModelState): Boolean =
        state is AiModelState.Ready

    fun nextAction(
        acrossAppsEnabled: Boolean,
        modelState: AiModelState,
    ): ManuscriptStartupAction = when {
        !acrossAppsEnabled -> ManuscriptStartupAction.ENABLE_ACROSS_APPS
        modelState is AiModelState.ConsentRequired ->
            ManuscriptStartupAction.INSTALL_MODEL
        modelState is AiModelState.Failed ||
            modelState is AiModelState.InsufficientStorage ->
            ManuscriptStartupAction.RETRY_MODEL
        !isModelReady(modelState) -> ManuscriptStartupAction.WAIT_FOR_MODEL
        else -> ManuscriptStartupAction.READY
    }

    fun nextAction(
        acrossAppsEnabled: Boolean,
        modelReady: Boolean,
        hasDownloadConsent: Boolean,
        presentation: DownloadTelemetryPresentation?,
    ): ManuscriptStartupAction = when {
        !acrossAppsEnabled -> ManuscriptStartupAction.ENABLE_ACROSS_APPS
        modelReady -> ManuscriptStartupAction.READY
        !hasDownloadConsent -> ManuscriptStartupAction.INSTALL_MODEL
        presentation?.actions?.contains(DownloadAction.RETRY) == true ->
            ManuscriptStartupAction.RETRY_MODEL
        else -> ManuscriptStartupAction.WAIT_FOR_MODEL
    }
}
