package com.echolink.mobile

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class AiUserMode(val displayName: String, val qualityMode: QualityMode) {
    AUTO("Auto", QualityMode.AUTO),
    FASTER("Faster", QualityMode.FAST),
    BEST_QUALITY("Best Quality", QualityMode.THOROUGH),
}

enum class AiDownloadNetworkPolicy {
    WIFI_ONLY,
    ALLOW_MOBILE_DATA,
}

sealed class AiModelState {
    data class Ready(
        val selection: AiRuntimeSelection,
        val installed: InstalledAiModel,
    ) : AiModelState()

    data class Downloading(
        val selection: AiRuntimeSelection,
        val downloaded: Long,
        val total: Long,
    ) : AiModelState()

    data class Checking(val selection: AiRuntimeSelection) : AiModelState()
    data class ConsentRequired(val selection: AiRuntimeSelection) : AiModelState()
    data class WaitingForWifi(val selection: AiRuntimeSelection) : AiModelState()
    data class InsufficientStorage(val selection: AiRuntimeSelection) : AiModelState()
    data class Failed(
        val selection: AiRuntimeSelection,
        val message: String,
    ) : AiModelState()

    data class NotInstalled(val selection: AiRuntimeSelection) : AiModelState()
}

internal fun safeAiModelDownloadFailure(failure: Throwable): SafeDownloadFailure = when (failure) {
    is AiModelIntegrityException -> SafeDownloadFailure.INTEGRITY_CHECK_FAILED
    is InterruptedIOException -> SafeDownloadFailure.DOWNLOAD_INTERRUPTED
    else -> SafeDownloadFailure.UNKNOWN
}

class AiModelManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val downloadCommands by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ModelDownloadCommandCore(AndroidDownloadServices.get(appContext))
    }
    private val modelDirectory = File(appContext.noBackupFilesDir, MODEL_DIRECTORY)
    private val installer = AiModelInstaller(modelDirectory)
    private val readinessRunning = AtomicBoolean(false)
    private val runtimeSelectionGate = GemmaRuntimeSelectionGate(restoredRuntimeSelection())

    @Volatile
    var state: AiModelState = initialState()
        private set

    fun userMode(): AiUserMode = preferences.getString(
        KEY_USER_MODE,
        AiUserMode.AUTO.name,
    )?.let { value -> AiUserMode.entries.firstOrNull { it.name == value } }
        ?: AiUserMode.AUTO

    fun setUserMode(mode: AiUserMode) {
        preferences.edit().putString(KEY_USER_MODE, mode.name).apply()
        refreshState()
    }

    /**
     * Debug-only backend override, used to compare runtimes on one device.
     * Release builds always follow the hardware policy.
     */
    fun forceLegacyBackendForTesting(enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        preferences.edit().putBoolean(KEY_FORCE_LEGACY, enabled).apply()
    }

    fun selectedRuntime(): AiRuntimeSelection {
        if (BuildConfig.DEBUG && preferences.getBoolean(KEY_FORCE_LEGACY, false)) {
            return AiRuntimeSelection(
                GemmaModelCatalog.legacyCompact,
                BackendKind.LEGACY_LLAMA_CPU,
            )
        }
        return runtimeSelectionGate.select(::hardwareSelectedRuntime)
    }

    private fun hardwareSelectedRuntime(): AiRuntimeSelection = AiHardwarePolicy.select(
        device = readDeviceProfile(),
        qualityMode = userMode().qualityMode,
        qualifications = BackendQualifications(
            gemmaFastCpuQualified = isBackendQualified(
                GemmaModelCatalog.fast,
                BackendKind.LITERT_CPU,
            ),
            gemmaFastGpuQualified = isBackendQualified(
                GemmaModelCatalog.fast,
                BackendKind.LITERT_GPU,
            ),
            gemmaFastCpuRejected = isBackendRejected(
                GemmaModelCatalog.fast,
                BackendKind.LITERT_CPU,
            ),
            gemmaFastGpuRejected = isBackendRejected(
                GemmaModelCatalog.fast,
                BackendKind.LITERT_GPU,
            ),
            gemmaThoroughCpuQualified = isBackendQualified(
                GemmaModelCatalog.thorough,
                BackendKind.LITERT_CPU,
            ),
            gemmaThoroughGpuQualified = isBackendQualified(
                GemmaModelCatalog.thorough,
                BackendKind.LITERT_GPU,
            ),
            gemmaThoroughCpuRejected = isBackendRejected(
                GemmaModelCatalog.thorough,
                BackendKind.LITERT_CPU,
            ),
            gemmaThoroughGpuRejected = isBackendRejected(
                GemmaModelCatalog.thorough,
                BackendKind.LITERT_GPU,
            ),
        ),
    )

    fun selectedArtifact(): AiModelArtifact = selectedRuntime().artifact

    fun exactInstalledModel(
        selection: AiRuntimeSelection = selectedRuntime(),
    ): InstalledAiModel? {
        if (selection.artifact.family == AiModelFamily.LEGACY_QWEN) {
            val target = DownloadTargetCatalog.targetFor(selection)
            val tier = DownloadTargetCatalog.qwenTierFor(target)
            val manager = Qwen3ModelManager.get(appContext)
            val file = manager.installedModel(tier) ?: return null
            return InstalledAiModel(selection.artifact, file)
        }
        if (isSelfTested(selection.artifact) && isBackendQualified(selection)) {
            val file = installer.activeFile(selection.artifact)
            if (activeProofMatches(selection.artifact)) {
                return InstalledAiModel(selection.artifact, file)
            }
        }
        return null
    }

    fun installedModel(
        selection: AiRuntimeSelection = selectedRuntime(),
    ): InstalledAiModel? {
        exactInstalledModel(selection)?.let { return it }
        // The selected Gemma artifact is not usable, but a validated legacy
        // model may already be installed. Correction must use it instead of
        // dead-ending every request at the install gate.
        return installedLegacyFallbackModel()
    }

    fun installedFile(): File? = installedModel()?.file

    fun installedLegacyFallbackModel(): InstalledAiModel? {
        val manager = Qwen3ModelManager.get(appContext)
        val file = manager.installedModel() ?: return null
        val tier = manager.tierForModel(file) ?: return null
        return InstalledAiModel(LegacyQwenModelCatalog.forTier(tier), file)
    }

    fun hasDownloadConsent(): Boolean =
        preferences.getBoolean(KEY_DOWNLOAD_CONSENT, false)

    fun hasSeenDownloadPrompt(): Boolean =
        preferences.getBoolean(KEY_DOWNLOAD_PROMPT_SEEN, false)

    fun markDownloadPromptSeen() {
        preferences.edit().putBoolean(KEY_DOWNLOAD_PROMPT_SEEN, true).apply()
    }

    fun requiredStorageBytes(artifact: AiModelArtifact = selectedArtifact()): Long =
        artifact.sizeBytes + DOWNLOAD_HEADROOM_BYTES

    fun downloadNetworkPolicy(): AiDownloadNetworkPolicy =
        preferences.getString(KEY_DOWNLOAD_NETWORK_POLICY, null)
            ?.let { value ->
                AiDownloadNetworkPolicy.entries.firstOrNull { it.name == value }
            }
            ?: AiDownloadNetworkPolicy.WIFI_ONLY

    fun grantDownloadConsent(
        networkPolicy: AiDownloadNetworkPolicy = AiDownloadNetworkPolicy.WIFI_ONLY,
    ) {
        preferences.edit()
            .putBoolean(KEY_DOWNLOAD_CONSENT, true)
            .putString(KEY_DOWNLOAD_NETWORK_POLICY, networkPolicy.name)
            .apply()
        mirrorConsentToLegacyManager(networkPolicy)
        val selection = selectedRuntime()
        val target = DownloadTargetCatalog.targetFor(selection)
        lockRuntimeSelection(selection)
        if (downloadCommands.retryTerminal(target) != null) {
            state = AiModelState.NotInstalled(selection)
            return
        }
        ensureModelAsync()
    }

    /**
     * The legacy Qwen manager keeps its own preferences file. The user grants
     * download consent once, in one dialog, so the answer must reach both
     * managers; otherwise a device that selects a legacy tier asks the empty
     * legacy file, sees no consent, and the install button does nothing.
     */
    private fun mirrorConsentToLegacyManager(networkPolicy: AiDownloadNetworkPolicy) {
        val legacy = Qwen3ModelManager.get(appContext)
        if (legacy.hasDownloadConsent()) return
        legacy.grantDownloadConsent(
            when (networkPolicy) {
                AiDownloadNetworkPolicy.WIFI_ONLY ->
                    Qwen3DownloadNetworkPolicy.WIFI_ONLY
                AiDownloadNetworkPolicy.ALLOW_MOBILE_DATA ->
                    Qwen3DownloadNetworkPolicy.ALLOW_MOBILE_DATA
            },
        )
    }

    fun ensureModelAsync() {
        val selection = selectedRuntime()
        exactInstalledModel(selection)?.let { installed ->
            state = AiModelState.Ready(selection, installed)
            return
        }
        if (selection.artifact.family == AiModelFamily.LEGACY_QWEN) {
            // Consent already given in the shared dialog must carry over, or
            // the legacy manager stops at ConsentRequired and the tap looks
            // like it did nothing.
            if (hasDownloadConsent()) {
                mirrorConsentToLegacyManager(downloadNetworkPolicy())
            }
            val target = DownloadTargetCatalog.targetFor(selection)
            val tier = DownloadTargetCatalog.qwenTierFor(target)
            Qwen3ModelManager.get(appContext).ensureModelAsync(tier)
            state = AiModelState.NotInstalled(selection)
            return
        }
        installedLegacyFallbackModel()?.let { fallback ->
            // Keep corrections terminal while the hardware-selected Gemma
            // upgrade downloads in the background.
            state = AiModelState.Ready(selection, fallback)
            if (hasDownloadConsent()) enqueueDownload(selection)
            return
        }
        if (!hasDownloadConsent()) {
            state = AiModelState.ConsentRequired(selection)
            return
        }
        enqueueDownload(selection)
    }

    fun retryModelDownload(): DownloadAttempt? {
        val selection = selectedRuntime()
        val target = DownloadTargetCatalog.targetFor(selection)
        val newlyLocked = lockRuntimeSelection(selection)
        val retried = downloadCommands.retryTerminal(target) ?: run {
            if (newlyLocked) clearRuntimeSelectionLock(selection)
            return null
        }
        state = AiModelState.NotInstalled(selection)
        return retried
    }

    fun verifyInstalledModelAsync(onFinished: () -> Unit) {
        val selection = selectedRuntime()
        val installed = installedModel(selection)
        if (installed == null) {
            ensureModelAsync()
            onFinished()
            return
        }
        if (!readinessRunning.compareAndSet(false, true)) return
        state = AiModelState.Checking(selection)
        Thread({
            try {
                val passed = if (installed.artifact.family == AiModelFamily.GEMMA) {
                    GemmaModelSelfTester.verify(
                        appContext,
                        installed,
                        selection.backend,
                        stopped = { false },
                    )
                } else {
                    Qwen3ModelSelfTester.verify(
                        appContext,
                        installed.file,
                        stopped = { false },
                    )
                }
                state = if (passed) {
                    readyStateAfterQualification(selection, installed)
                } else {
                    clearSelfTested(installed.artifact)
                    AiModelState.Failed(selection, "AI readiness check failed.")
                }
            } catch (_: Exception) {
                state = AiModelState.Failed(selection, "AI readiness check failed.")
                clearSelfTested(installed.artifact)
            } finally {
                readinessRunning.set(false)
                android.os.Handler(appContext.mainLooper).post(onFinished)
            }
        }, "Manuscript-AI-readiness").start()
    }

    /**
     * Deletes Gemma weights the hardware policy no longer selects. A device
     * that was downgraded to a legacy tier would otherwise keep a 2.4 GB file
     * it can never load, on a phone that is already short on storage.
     */
    fun pruneUnselectedGemmaWeights() {
        if (selectedRuntime().artifact.family == AiModelFamily.GEMMA) return
        if (!modelDirectory.isDirectory) return
        if (!downloadCommands.cancelActiveBeforeRemoval(
                DownloadTargetCatalog.targetsFor(AiModelFamily.GEMMA),
            )
        ) {
            return
        }
        AndroidDownloadServices.get(appContext).clearRecords(
            DownloadTargetCatalog.targetsFor(AiModelFamily.GEMMA),
        )
        val removed = modelDirectory.listFiles()
            ?.filter { it.parentFile == modelDirectory && it.isFile }
            ?.count { it.delete() }
            ?: 0
        if (removed > 0) {
            clearAnyRuntimeSelectionLock()
            clearSelfTested(GemmaModelCatalog.fast)
            clearSelfTested(GemmaModelCatalog.thorough)
        }
    }

    fun removeAllModels() {
        val allTargets = DownloadTargetCatalog.targetsFor(AiModelFamily.GEMMA) +
            DownloadTargetCatalog.targetsFor(AiModelFamily.LEGACY_QWEN)
        if (!downloadCommands.cancelActiveBeforeRemoval(allTargets)) {
            return
        }
        AndroidDownloadServices.get(appContext).clearRecords(
            DownloadTargetCatalog.targetsFor(AiModelFamily.GEMMA),
        )
        if (modelDirectory.isDirectory) {
            modelDirectory.listFiles()?.forEach { file ->
                if (file.parentFile == modelDirectory && file.isFile) file.delete()
            }
        }
        runtimeSelectionGate.clear()
        preferences.edit().clear().apply()
        Qwen3ModelManager.get(appContext).removeAllModels()
        state = AiModelState.ConsentRequired(selectedRuntime())
    }

    internal fun stageDownload(
        selection: AiRuntimeSelection,
        stopped: () -> Boolean,
        progress: (Long, Long) -> Unit,
    ): File {
        require(hasDownloadConsent()) { "AI download consent is required." }
        val artifact = selection.artifact
        val bytesNeeded = installer.remainingBytes(artifact)
        if (modelDirectory.parentFile?.usableSpace.orZero() < bytesNeeded + DOWNLOAD_HEADROOM_BYTES) {
            state = AiModelState.InsufficientStorage(selection)
            throw AiModelStorageException("Not enough free storage for AI.")
        }
        state = AiModelState.Downloading(selection, 0L, artifact.sizeBytes)
        return installer.stage(artifact, stopped) { downloaded, total ->
            state = AiModelState.Downloading(selection, downloaded, total)
            progress(downloaded, total)
        }
    }

    internal fun partialBytes(selection: AiRuntimeSelection): Long {
        val artifact = selection.artifact
        val completeLocalModel = candidateModel(selection) ?: unverifiedActiveModel(selection)
        return if (completeLocalModel != null) artifact.sizeBytes else installer.partialBytes(artifact)
    }

    internal fun candidateModel(selection: AiRuntimeSelection): File? =
        installer.candidateFile(selection.artifact).takeIf {
            it.isFile && it.length() == selection.artifact.sizeBytes
        }

    internal fun unverifiedActiveModel(selection: AiRuntimeSelection): File? =
        installer.activeFile(selection.artifact).takeIf {
            it.isFile && it.length() == selection.artifact.sizeBytes
        }

    internal fun onDownloadWaiting(selection: AiRuntimeSelection) {
        state = AiModelState.WaitingForWifi(selection)
    }

    internal fun onDownloadFailed(selection: AiRuntimeSelection, failure: Throwable) {
        state = AiModelState.Failed(
            selection,
            safeAiModelDownloadFailure(failure).userMessage,
        )
    }

    internal fun onDownloadFailed(
        selection: AiRuntimeSelection,
        failureCode: DownloadFailureCode,
    ) {
        state = AiModelState.Failed(selection, SafeDownloadFailure.from(failureCode).userMessage)
    }

    internal fun qualifyCandidate(
        selection: AiRuntimeSelection,
        candidate: File,
        stopped: () -> Boolean = { false },
    ): AiModelQualifiedCandidate? {
        var integrityVerified = false
        val qualification = installer.qualifyCandidate(
            artifact = selection.artifact,
            candidate = candidate,
            onIntegrityVerified = { integrityVerified = true },
            selfTest = { file ->
                GemmaModelSelfTester.verify(
                    appContext,
                    InstalledAiModel(selection.artifact, file),
                    selection.backend,
                    stopped,
                )
            },
            stopped = stopped,
        )
        if (qualification == null) {
            if (stopped()) throw InterruptedIOException("AI qualification stopped.")
            val rejected = integrityVerified && persistBackendRejection(selection)
            val fallback = hardwareSelectedRuntime().takeIf { rejected }
            if (
                fallback == null ||
                !shouldPreserveRejectedGemmaModel(selection, fallback)
            ) {
                installer.discardRejectedModel(selection.artifact, candidate)
            }
            if (fallback != null) transitionToFallbackAfterRejection(selection, fallback)
        }
        return qualification
    }

    internal fun qualifyActiveModel(
        selection: AiRuntimeSelection,
        active: File,
        stopped: () -> Boolean = { false },
    ): AiModelQualifiedActive? {
        var integrityVerified = false
        val qualification = installer.qualifyActive(
            artifact = selection.artifact,
            active = active,
            onIntegrityVerified = { integrityVerified = true },
            selfTest = { file ->
                GemmaModelSelfTester.verify(
                    appContext,
                    InstalledAiModel(selection.artifact, file),
                    selection.backend,
                    stopped,
                )
            },
            stopped = stopped,
        )
        if (qualification == null) {
            if (stopped()) throw InterruptedIOException("AI qualification stopped.")
            val rejected = integrityVerified && persistBackendRejection(selection)
            val fallback = hardwareSelectedRuntime().takeIf { rejected }
            if (
                fallback == null ||
                !shouldPreserveRejectedGemmaModel(selection, fallback)
            ) {
                installer.discardRejectedModel(selection.artifact, active)
            }
            clearSelfTested(selection.artifact)
            if (fallback != null) transitionToFallbackAfterRejection(selection, fallback)
        }
        return qualification
    }

    internal fun activateQualifiedCandidate(
        selection: AiRuntimeSelection,
        qualification: AiModelQualifiedCandidate,
    ): InstalledAiModel {
        require(qualification.artifact == selection.artifact) {
            "The qualified AI does not match the selected model."
        }
        val activation = installer.activateQualifiedCandidate(qualification)
        check(activation.activated) { "The qualified AI was not activated." }
        return finishActivation(selection, activation.activeFile)
    }

    internal fun acceptQualifiedActive(
        selection: AiRuntimeSelection,
        qualification: AiModelQualifiedActive,
    ): InstalledAiModel {
        require(qualification.artifact == selection.artifact) {
            "The qualified AI does not match the selected model."
        }
        return finishActivation(selection, installer.acceptQualifiedActive(qualification))
    }

    internal fun qualifyAndActivate(
        selection: AiRuntimeSelection,
        candidate: File,
        stopped: () -> Boolean = { false },
    ): InstalledAiModel? {
        val qualification = qualifyCandidate(selection, candidate, stopped) ?: return null
        return activateQualifiedCandidate(selection, qualification).also {
            qualifyGpuIfSupported(it)
        }
    }

    internal fun qualifyGpuAfterActivation(installed: InstalledAiModel) {
        qualifyGpuIfSupported(installed)
    }

    private fun finishActivation(
        selection: AiRuntimeSelection,
        activeFile: File,
    ): InstalledAiModel {
        markSelfTested(selection.artifact, activeFile)
        val installed = InstalledAiModel(selection.artifact, activeFile)
        persistBackendQualification(selection)
        return installed
    }

    // After durable activation, run one GPU self-test on qualifying hardware.
    // Persist proof or rejection for this exact artifact and backend only.
    private fun qualifyGpuIfSupported(installed: InstalledAiModel) {
        val gpuSelection = AiRuntimeSelection(installed.artifact, BackendKind.LITERT_GPU)
        if (isBackendQualified(gpuSelection) || isBackendRejected(gpuSelection)) return
        val device = readDeviceProfile()
        if (!device.hasVulkanHardware || device.severeThermalState) return
        val passed = try {
            GemmaModelSelfTester.verify(
                appContext,
                installed,
                BackendKind.LITERT_GPU,
                stopped = { false },
            )
        } catch (_: Exception) {
            runCatching { persistBackendRejection(gpuSelection) }
            return
        }
        if (passed) {
            runCatching { persistBackendQualification(gpuSelection) }
        } else {
            runCatching { persistBackendRejection(gpuSelection) }
        }
    }

    private fun isBackendQualified(selection: AiRuntimeSelection): Boolean =
        isBackendQualified(selection.artifact, selection.backend)

    private fun isBackendQualified(
        artifact: AiModelArtifact,
        backend: BackendKind,
    ): Boolean =
        GemmaAdaptiveQualificationRecords.isQualified(
            recordedSha256 = preferences.getString(
                GemmaAdaptiveQualificationRecords.qualificationKey(artifact, backend),
                null,
            ),
            artifact = artifact,
            backend = backend,
        )

    private fun isBackendRejected(selection: AiRuntimeSelection): Boolean =
        isBackendRejected(selection.artifact, selection.backend)

    private fun isBackendRejected(
        artifact: AiModelArtifact,
        backend: BackendKind,
    ): Boolean =
        GemmaAdaptiveQualificationRecords.isRejected(
            recordedSha256 = preferences.getString(
                GemmaAdaptiveQualificationRecords.rejectionKey(artifact, backend),
                null,
            ),
            artifact = artifact,
            backend = backend,
        )

    private fun persistBackendQualification(selection: AiRuntimeSelection) {
        require(selection.artifact.family == AiModelFamily.GEMMA)
        require(selection.backend != BackendKind.LEGACY_LLAMA_CPU)
        check(
            preferences.edit()
                .putString(
                    GemmaAdaptiveQualificationRecords.qualificationKey(
                        selection.artifact,
                        selection.backend,
                    ),
                    selection.artifact.sha256,
                )
                .remove(
                    GemmaAdaptiveQualificationRecords.rejectionKey(
                        selection.artifact,
                        selection.backend,
                    ),
                )
                .commit(),
        ) { "The qualified AI target could not be persisted." }
    }

    private fun persistBackendRejection(selection: AiRuntimeSelection): Boolean {
        if (
            selection.artifact.family != AiModelFamily.GEMMA ||
            selection.backend == BackendKind.LEGACY_LLAMA_CPU
        ) {
            return false
        }
        check(
            preferences.edit()
                .putString(
                    GemmaAdaptiveQualificationRecords.rejectionKey(
                        selection.artifact,
                        selection.backend,
                    ),
                    selection.artifact.sha256,
                )
                .remove(
                    GemmaAdaptiveQualificationRecords.qualificationKey(
                        selection.artifact,
                        selection.backend,
                    ),
                )
                .commit(),
        ) { "The rejected AI target could not be persisted." }
        clearRuntimeSelectionLock(selection)
        return true
    }

    private fun transitionToFallbackAfterRejection(
        rejected: AiRuntimeSelection,
        fallback: AiRuntimeSelection,
    ) {
        check(fallback != rejected) { "A rejected AI target cannot select itself again." }
        val installedFallback = exactInstalledModel(fallback)
        if (installedFallback != null) {
            state = AiModelState.Ready(fallback, installedFallback)
        } else if (hasDownloadConsent()) {
            enqueueDownload(fallback)
        } else {
            state = AiModelState.ConsentRequired(fallback)
        }
    }

    internal fun onDownloadReady(
        selection: AiRuntimeSelection,
        installed: InstalledAiModel,
    ) {
        state = readyStateAfterQualification(selection, installed)
    }

    private fun readyStateAfterQualification(
        selection: AiRuntimeSelection,
        installed: InstalledAiModel,
    ): AiModelState {
        if (
            installed.artifact.family != AiModelFamily.GEMMA ||
            installed.artifact != selection.artifact
        ) {
            return AiModelState.Ready(selection, installed)
        }
        val locked = selectedRuntime()
        if (locked != selection) {
            return AiModelState.NotInstalled(locked)
        }
        state = AiModelState.Ready(selection, installed)
        if (selection.backend == BackendKind.LITERT_CPU) qualifyGpuIfSupported(installed)
        clearRuntimeSelectionLock(selection)
        val current = selectedRuntime()
        val ready = exactInstalledModel(current)
        return if (current.artifact == installed.artifact && ready != null) {
            AiModelState.Ready(current, ready)
        } else {
            AiModelState.NotInstalled(current)
        }
    }

    private fun enqueueDownload(selection: AiRuntimeSelection) {
        lockRuntimeSelection(selection)
        state = AiModelState.NotInstalled(selection)
        val target = DownloadTargetCatalog.targetFor(selection)
        downloadCommands.ensure(target)
    }

    private fun restoredRuntimeSelection(): AiRuntimeSelection? {
        val restored = GemmaAdaptiveQualificationRecords.lockedSelection(
            preferences.getString(KEY_RUNTIME_LOCK_SHA256, null),
            preferences.getString(KEY_RUNTIME_LOCK_BACKEND, null),
        )
        if (restored != null && !isBackendRejected(restored)) return restored
        if (
            preferences.contains(KEY_RUNTIME_LOCK_SHA256) ||
            preferences.contains(KEY_RUNTIME_LOCK_BACKEND)
        ) {
            check(
                preferences.edit()
                    .remove(KEY_RUNTIME_LOCK_SHA256)
                    .remove(KEY_RUNTIME_LOCK_BACKEND)
                    .commit(),
            ) { "The stale AI target lock could not be cleared." }
        }
        return null
    }

    private fun lockRuntimeSelection(selection: AiRuntimeSelection): Boolean {
        if (selection.artifact.family != AiModelFamily.GEMMA) return false
        val newlyLocked = runtimeSelectionGate.lockedSelection() == null
        runtimeSelectionGate.lock(selection)
        val persisted = preferences.edit()
            .putString(KEY_RUNTIME_LOCK_SHA256, selection.artifact.sha256)
            .putString(KEY_RUNTIME_LOCK_BACKEND, selection.backend.name)
            .commit()
        if (!persisted) {
            runtimeSelectionGate.unlock(selection)
            error("The AI download target could not be locked.")
        }
        return newlyLocked
    }

    private fun clearRuntimeSelectionLock(selection: AiRuntimeSelection): Boolean {
        if (!runtimeSelectionGate.unlock(selection)) return false
        check(
            preferences.edit()
                .remove(KEY_RUNTIME_LOCK_SHA256)
                .remove(KEY_RUNTIME_LOCK_BACKEND)
                .commit(),
        ) { "The AI download target lock could not be cleared." }
        return true
    }

    private fun clearAnyRuntimeSelectionLock() {
        runtimeSelectionGate.clear()
        check(
            preferences.edit()
                .remove(KEY_RUNTIME_LOCK_SHA256)
                .remove(KEY_RUNTIME_LOCK_BACKEND)
                .commit(),
        ) { "The AI download target lock could not be cleared." }
    }

    private fun readDeviceProfile(): AiDeviceProfile {
        val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)
        val powerManager =
            appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val model = Build.MODEL.orEmpty().uppercase(Locale.US)
        val fingerprint = Build.FINGERPRINT.orEmpty().lowercase(Locale.US)
        val isChromeOs =
            appContext.packageManager.hasSystemFeature("org.chromium.arc.device_management") ||
                Build.DEVICE.orEmpty().contains("cheets", ignoreCase = true)
        val family = when {
            fingerprint.contains("generic") || fingerprint.contains("emulator") ->
                AiDeviceFamily.EMULATOR
            isChromeOs && Build.SUPPORTED_ABIS.any { it == "x86_64" } ->
                AiDeviceFamily.X86_64_CHROMEBOOK
            isChromeOs -> AiDeviceFamily.ARM64_CHROMEBOOK
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                (model.startsWith("SM-G98") || model.startsWith("SM-G988")) ->
                AiDeviceFamily.GALAXY_S20
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                (model.startsWith("SM-S91") || model.startsWith("SM-S911") ||
                    model.startsWith("SM-S916")) ->
                AiDeviceFamily.GALAXY_S23
            else -> AiDeviceFamily.UNKNOWN
        }
        return AiDeviceProfile(
            totalRamBytes = memory.totalMem,
            availableRamBytes = memory.availMem,
            usableStorageBytes =
                modelDirectory.parentFile?.usableSpace ?: modelDirectory.usableSpace,
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),
            supportedAbis = Build.SUPPORTED_ABIS.toSet(),
            androidApi = Build.VERSION.SDK_INT,
            hasVulkanHardware = appContext.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL,
            ),
            severeThermalState =
                Build.VERSION.SDK_INT >= 29 &&
                    powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE,
            lowMemoryDevice = activityManager.isLowRamDevice,
            deviceFamily = family,
        )
    }

    private fun initialState(): AiModelState {
        val selection = selectedRuntime()
        return installedModel(selection)?.let { AiModelState.Ready(selection, it) }
            ?: if (hasDownloadConsent()) {
                AiModelState.NotInstalled(selection)
            } else {
                AiModelState.ConsentRequired(selection)
            }
    }

    private fun refreshState() {
        val selection = selectedRuntime()
        state = installedModel(selection)?.let { AiModelState.Ready(selection, it) }
            ?: if (hasDownloadConsent()) {
                AiModelState.NotInstalled(selection)
            } else {
                AiModelState.ConsentRequired(selection)
            }
    }

    private fun markSelfTested(artifact: AiModelArtifact, activeFile: File) {
        require(activeFile == installer.activeFile(artifact)) {
            "The active proof must use the canonical model file."
        }
        val proof = checkNotNull(installer.activeProof(artifact)) {
            "The active AI proof could not be recorded."
        }
        val editor = preferences.edit()
            .putString(selfTestedKey(artifact), artifact.sha256)
            .putLong(activeProofSizeKey(artifact), proof.sizeBytes)
            .putLong(activeProofModifiedKey(artifact), proof.modifiedMillis)
            .putString(activeProofFingerprintKey(artifact), proof.fingerprint)
        if (proof.fileKey == null) {
            editor.remove(activeProofFileKeyKey(artifact))
        } else {
            editor.putString(activeProofFileKeyKey(artifact), proof.fileKey)
        }
        check(editor.commit()) { "The active AI proof could not be persisted." }
    }

    private fun clearSelfTested(artifact: AiModelArtifact) {
        val editor = preferences.edit()
            .remove(selfTestedKey(artifact))
            .remove(activeProofSizeKey(artifact))
            .remove(activeProofModifiedKey(artifact))
            .remove(activeProofFileKeyKey(artifact))
            .remove(activeProofFingerprintKey(artifact))
        if (artifact.family == AiModelFamily.GEMMA) {
            editor.remove(
                GemmaAdaptiveQualificationRecords.qualificationKey(
                    artifact,
                    BackendKind.LITERT_CPU,
                ),
            ).remove(
                GemmaAdaptiveQualificationRecords.qualificationKey(
                    artifact,
                    BackendKind.LITERT_GPU,
                ),
            )
        }
        check(editor.commit()) { "The AI self-test proof could not be cleared." }
    }

    private fun isSelfTested(artifact: AiModelArtifact): Boolean =
        preferences.getString(selfTestedKey(artifact), null) == artifact.sha256

    private fun selfTestedKey(artifact: AiModelArtifact): String =
        "${KEY_SELF_TESTED_PREFIX}_${artifact.tier.name.lowercase(Locale.US)}"

    private fun activeProofMatches(artifact: AiModelArtifact): Boolean {
        if (!preferences.contains(activeProofFingerprintKey(artifact))) return false
        val current = installer.activeProof(artifact) ?: return false
        return current.sizeBytes == preferences.getLong(activeProofSizeKey(artifact), -1L) &&
            current.modifiedMillis == preferences.getLong(activeProofModifiedKey(artifact), -1L) &&
            current.fileKey == preferences.getString(activeProofFileKeyKey(artifact), null) &&
            current.fingerprint == preferences.getString(activeProofFingerprintKey(artifact), null)
    }

    private fun activeProofSizeKey(artifact: AiModelArtifact): String =
        "${selfTestedKey(artifact)}_active_size"

    private fun activeProofModifiedKey(artifact: AiModelArtifact): String =
        "${selfTestedKey(artifact)}_active_modified"

    private fun activeProofFileKeyKey(artifact: AiModelArtifact): String =
        "${selfTestedKey(artifact)}_active_file_key"

    private fun activeProofFingerprintKey(artifact: AiModelArtifact): String =
        "${selfTestedKey(artifact)}_active_fingerprint"

    private fun Long?.orZero(): Long = this ?: 0L

    companion object {
        private const val PREFERENCES = "manuscript_ai"
        private const val MODEL_DIRECTORY = "ai_models"
        private const val KEY_USER_MODE = "user_mode"
        private const val KEY_DOWNLOAD_CONSENT = "download_consent"
        private const val KEY_DOWNLOAD_NETWORK_POLICY = "download_network_policy"
        private const val KEY_DOWNLOAD_PROMPT_SEEN = "download_prompt_seen"
        private const val KEY_SELF_TESTED_PREFIX = "self_tested_sha256"
        private const val KEY_FORCE_LEGACY = "force_legacy_backend_debug"
        private const val KEY_RUNTIME_LOCK_SHA256 = "gemma_runtime_lock_sha256"
        private const val KEY_RUNTIME_LOCK_BACKEND = "gemma_runtime_lock_backend"
        private const val DOWNLOAD_HEADROOM_BYTES = 536_870_912L

        @Volatile private var instance: AiModelManager? = null

        fun get(context: Context): AiModelManager = instance ?: synchronized(this) {
            instance ?: AiModelManager(context).also { instance = it }
        }
    }
}
