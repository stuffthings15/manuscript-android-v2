package com.echolink.mobile

import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException

internal interface ExactModelDownloadOperations<Model : Any> {
    val target: DownloadTargetKey
    val artifact: AiModelArtifact

    fun exactInstalledModel(): Model?

    fun candidateModel(): Model?

    fun activeUnverifiedModel(): Model?

    fun partialBytes(): Long

    fun download(
        stopped: () -> Boolean,
        progress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Model

    fun qualify(
        model: Model,
        stopped: () -> Boolean,
    ): ExactDownloadQualification?

    fun activate(qualification: ExactDownloadQualification): Model

    fun managerTargetMatches(): Boolean

    fun onWaiting()

    fun onFailed(failureCode: DownloadFailureCode)

    fun onReady(model: Model)
}

internal interface ExactDownloadServiceOperations {
    fun isCurrent(attempt: DownloadAttempt): Boolean

    fun restoreTelemetry(target: DownloadTargetKey): DownloadTelemetry?

    fun networkDecision(target: DownloadTargetKey): DownloadNetworkDecision

    fun publish(
        attempt: DownloadAttempt,
        observation: DownloadObservation,
        finalByteCount: Boolean,
    ): DownloadPublishResult

    fun commitReadyIfCurrent(
        attempt: DownloadAttempt,
        readyObservation: DownloadObservation,
        action: () -> Unit,
    ): Boolean
}

internal class AndroidExactDownloadServiceOperations(
    private val services: AndroidDownloadServices,
) : ExactDownloadServiceOperations {
    override fun isCurrent(attempt: DownloadAttempt): Boolean = services.isCurrent(attempt)

    override fun restoreTelemetry(target: DownloadTargetKey): DownloadTelemetry? =
        services.restoreTelemetry(target)

    override fun networkDecision(target: DownloadTargetKey): DownloadNetworkDecision =
        services.networkDecision(target)

    override fun publish(
        attempt: DownloadAttempt,
        observation: DownloadObservation,
        finalByteCount: Boolean,
    ): DownloadPublishResult = services.publish(attempt, observation, finalByteCount)

    override fun commitReadyIfCurrent(
        attempt: DownloadAttempt,
        readyObservation: DownloadObservation,
        action: () -> Unit,
    ): Boolean = services.commitReadyIfCurrent(attempt, readyObservation, action)
}

internal class ExactManagerDownloadAttemptPort<Model : Any>(
    private val exactAttempt: DownloadAttempt,
    private val modelOperations: ExactModelDownloadOperations<Model>,
    private val services: ExactDownloadServiceOperations,
    private val stopped: () -> Boolean,
) : ExactDownloadAttemptPort {
    private var availableModel: Model? = null
    private var readyModel: Model? = null
    private var completeLocalBytes = false
    private var activationCalled = false
    private var readyCallbackSent = false

    init {
        require(modelOperations.target == exactAttempt.target) {
            "The model operations must match the exact download target."
        }
        require(modelOperations.artifact == DownloadTargetCatalog.artifactFor(exactAttempt.target)) {
            "The model operations must use the canonical pinned artifact."
        }
    }

    override val canonicalTotalBytes: Long
        get() = modelOperations.artifact.sizeBytes

    override fun isCurrent(attempt: DownloadAttempt): Boolean {
        if (attempt != exactAttempt) return false
        if (stopped()) return false
        return services.isCurrent(exactAttempt)
    }

    override fun restoreTelemetry(target: DownloadTargetKey): DownloadTelemetry? {
        if (target != exactAttempt.target) return null
        return services.restoreTelemetry(exactAttempt.target)
    }

    override fun networkDecision(target: DownloadTargetKey): DownloadNetworkDecision {
        require(target == exactAttempt.target) { "Network lookup must use the exact target." }
        return services.networkDecision(exactAttempt.target)
    }

    override fun publish(
        attempt: DownloadAttempt,
        observation: DownloadObservation,
        finalByteCount: Boolean,
    ): DownloadPublishResult {
        if (attempt != exactAttempt || observation.generation != exactAttempt.generation) {
            return DownloadPublishResult.Stale
        }
        return services.publish(exactAttempt, observation, finalByteCount)
    }

    override fun commitReadyIfCurrent(
        attempt: DownloadAttempt,
        readyObservation: DownloadObservation,
        action: () -> Unit,
    ): Boolean {
        if (attempt != exactAttempt || readyObservation.generation != exactAttempt.generation) {
            return false
        }
        return services.commitReadyIfCurrent(exactAttempt, readyObservation, action)
    }

    override fun modelAvailability(): ExactDownloadModelAvailability {
        modelOperations.exactInstalledModel()?.let { installed ->
            availableModel = installed
            readyModel = installed
            completeLocalBytes = true
            return ExactDownloadModelAvailability.INSTALLED
        }
        val recoverable = modelOperations.candidateModel()
            ?: modelOperations.activeUnverifiedModel()
        if (recoverable != null) {
            availableModel = recoverable
            completeLocalBytes = true
            return ExactDownloadModelAvailability.CANDIDATE
        }
        availableModel = null
        completeLocalBytes = false
        return ExactDownloadModelAvailability.MISSING
    }

    override fun partialBytes(): Long = if (completeLocalBytes) {
        canonicalTotalBytes
    } else {
        modelOperations.partialBytes().coerceIn(0L, canonicalTotalBytes)
    }

    override fun download(
        stopped: () -> Boolean,
        progress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val downloaded = try {
            modelOperations.download(
                stopped = { this.stopped() || stopped() },
                progress = progress,
            )
        } catch (interrupted: InterruptedIOException) {
            if (this.stopped()) throw CancellationException("Exact download work stopped.")
            throw interrupted
        }
        availableModel = downloaded
        completeLocalBytes = true
    }

    override fun qualifyCandidate(): ExactDownloadQualification? {
        val model = availableModel ?: return null
        return modelOperations.qualify(model) { stopped() }
    }

    override fun activateCandidate(qualification: ExactDownloadQualification) {
        check(!activationCalled) { "The exact model can only be activated once." }
        readyModel = modelOperations.activate(qualification)
        activationCalled = true
        completeLocalBytes = true
    }

    override fun onWaiting() {
        if (modelOperations.managerTargetMatches()) modelOperations.onWaiting()
    }

    override fun onFailed(failureCode: DownloadFailureCode) {
        if (modelOperations.managerTargetMatches()) modelOperations.onFailed(failureCode)
    }

    override fun onReady() {
        if (readyCallbackSent || !modelOperations.managerTargetMatches()) return
        val exactReady = readyModel ?: availableModel ?: return
        modelOperations.onReady(exactReady)
        readyCallbackSent = true
    }
}

private sealed interface GemmaModelQualification : ExactDownloadQualification {
    data class Candidate(val value: AiModelQualifiedCandidate) : GemmaModelQualification
    data class Active(val value: AiModelQualifiedActive) : GemmaModelQualification
}

internal class GemmaDownloadModelOperations(
    private val manager: AiModelManager,
    private val selection: AiRuntimeSelection,
    private val progressObserver: (downloadedBytes: Long, totalBytes: Long) -> Unit,
) : ExactModelDownloadOperations<File> {
    override val target: DownloadTargetKey = DownloadTargetCatalog.targetFor(selection)
    override val artifact: AiModelArtifact = selection.artifact

    override fun exactInstalledModel(): File? = manager.exactInstalledModel(selection)?.file

    override fun candidateModel(): File? = manager.candidateModel(selection)

    override fun activeUnverifiedModel(): File? = manager.unverifiedActiveModel(selection)

    override fun partialBytes(): Long = manager.partialBytes(selection)

    override fun download(
        stopped: () -> Boolean,
        progress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File = try {
        manager.stageDownload(selection, stopped) { downloaded, total ->
            progress(downloaded, total)
            progressObserver(downloaded, total)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (interrupted: InterruptedIOException) {
        throw interrupted
    }

    override fun qualify(
        model: File,
        stopped: () -> Boolean,
    ): ExactDownloadQualification? = if (model.name == artifact.fileName) {
        manager.qualifyActiveModel(selection, model, stopped)?.let {
            GemmaModelQualification.Active(it)
        }
    } else {
        manager.qualifyCandidate(selection, model, stopped)?.let {
            GemmaModelQualification.Candidate(it)
        }
    }

    override fun activate(qualification: ExactDownloadQualification): File = when (qualification) {
        is GemmaModelQualification.Candidate ->
            manager.activateQualifiedCandidate(selection, qualification.value).file
        is GemmaModelQualification.Active ->
            manager.acceptQualifiedActive(selection, qualification.value).file
        else -> error("The Gemma qualification does not match this operation.")
    }

    override fun managerTargetMatches(): Boolean = manager.selectedRuntime() == selection

    override fun onWaiting() = manager.onDownloadWaiting(selection)

    override fun onFailed(failureCode: DownloadFailureCode) =
        manager.onDownloadFailed(selection, failureCode)

    override fun onReady(model: File) =
        manager.onDownloadReady(selection, InstalledAiModel(artifact, model))
}

private sealed interface QwenModelQualification : ExactDownloadQualification {
    data class Candidate(val value: AiModelQualifiedCandidate) : QwenModelQualification
    data class Active(val value: AiModelQualifiedActive) : QwenModelQualification
}

internal class QwenDownloadModelOperations(
    private val manager: Qwen3ModelManager,
    private val tier: Qwen3Tier,
    private val progressObserver: (downloadedBytes: Long, totalBytes: Long) -> Unit,
) : ExactModelDownloadOperations<File> {
    private val selection = AiRuntimeSelection(
        LegacyQwenModelCatalog.forTier(tier),
        BackendKind.LEGACY_LLAMA_CPU,
    )

    override val target: DownloadTargetKey = DownloadTargetCatalog.targetFor(selection)
    override val artifact: AiModelArtifact = selection.artifact

    override fun exactInstalledModel(): File? = manager.installedModel(tier)

    override fun candidateModel(): File? = manager.candidateModel(tier)

    override fun activeUnverifiedModel(): File? = manager.downloadedModel(tier)

    override fun partialBytes(): Long = manager.partialBytes(tier)

    override fun download(
        stopped: () -> Boolean,
        progress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File = try {
        manager.downloadTier(tier, stopped) { downloaded, total ->
            progress(downloaded, total)
            progressObserver(downloaded, total)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (interrupted: InterruptedIOException) {
        throw interrupted
    }

    override fun qualify(
        model: File,
        stopped: () -> Boolean,
    ): ExactDownloadQualification? = if (model.name == artifact.fileName) {
        manager.qualifyActiveModel(tier, model, stopped)?.let {
            QwenModelQualification.Active(it)
        }
    } else {
        manager.qualifyCandidate(tier, model, stopped)?.let {
            QwenModelQualification.Candidate(it)
        }
    }

    override fun activate(qualification: ExactDownloadQualification): File = when (qualification) {
        is QwenModelQualification.Candidate ->
            manager.activateQualifiedCandidate(tier, qualification.value)
        is QwenModelQualification.Active ->
            manager.acceptQualifiedActive(tier, qualification.value)
        else -> error("The legacy qualification does not match this operation.")
    }

    override fun managerTargetMatches(): Boolean = manager.selectedTier() == tier

    override fun onWaiting() = manager.onDownloadWaiting(tier)

    override fun onFailed(failureCode: DownloadFailureCode) =
        manager.onDownloadFailed(tier, failureCode)

    override fun onReady(model: File) = manager.onDownloadReady(tier, model)
}
