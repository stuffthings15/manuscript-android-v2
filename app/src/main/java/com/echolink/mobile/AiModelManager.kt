package com.echolink.mobile

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class AiUserMode(val displayName: String, val qualityMode: QualityMode) {
    AUTO("Auto", QualityMode.AUTO),
    FASTER("Faster", QualityMode.FAST),
    BEST_QUALITY("Best Quality", QualityMode.THOROUGH),
}

enum class AiDownloadNetworkPolicy(val requiredNetworkType: NetworkType) {
    WIFI_ONLY(NetworkType.CONNECTED),
    ALLOW_MOBILE_DATA(NetworkType.CONNECTED),
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

class AiModelManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val modelDirectory = File(appContext.noBackupFilesDir, MODEL_DIRECTORY)
    private val installer = AiModelInstaller(modelDirectory)
    private val readinessRunning = AtomicBoolean(false)

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
        return hardwareSelectedRuntime()
    }

    private fun hardwareSelectedRuntime(): AiRuntimeSelection = AiHardwarePolicy.select(
        device = readDeviceProfile(),
        qualityMode = userMode().qualityMode,
        qualifications = BackendQualifications(
            gemmaGpuQualified = preferences.getBoolean(KEY_GPU_QUALIFIED, false),
            gemmaThoroughQualified = preferences.getBoolean(
                KEY_THOROUGH_QUALIFIED,
                false,
            ),
        ),
    )

    fun selectedArtifact(): AiModelArtifact = selectedRuntime().artifact

    fun installedModel(
        selection: AiRuntimeSelection = selectedRuntime(),
    ): InstalledAiModel? {
        if (selection.artifact.family == AiModelFamily.LEGACY_QWEN) {
            val manager = Qwen3ModelManager.get(appContext)
            val file = manager.installedModel() ?: return null
            val tier = manager.tierForModel(file) ?: return null
            return InstalledAiModel(LegacyQwenModelCatalog.forTier(tier), file)
        }
        if (isSelfTested(selection.artifact)) {
            val file = installer.activeFile(selection.artifact)
            installer.takeIf { it.isValid(file, selection.artifact) }
                ?.let { return InstalledAiModel(selection.artifact, file) }
        }
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
        installedModel(selection)?.let { installed ->
            state = AiModelState.Ready(selection, installed)
            // A legacy model keeps correction working, but the selected Gemma
            // artifact is still the intended runtime: keep its download moving.
            if (
                installed.artifact.family == AiModelFamily.LEGACY_QWEN &&
                selection.artifact.family == AiModelFamily.GEMMA &&
                hasDownloadConsent()
            ) {
                enqueueDownload(selection)
            }
            return
        }
        if (selection.artifact.family == AiModelFamily.LEGACY_QWEN) {
            // Consent already given in the shared dialog must carry over, or
            // the legacy manager stops at ConsentRequired and the tap looks
            // like it did nothing.
            if (hasDownloadConsent()) {
                mirrorConsentToLegacyManager(downloadNetworkPolicy())
            }
            pruneUnselectedGemmaWeights()
            Qwen3ModelManager.get(appContext).ensureModelAsync()
            state = AiModelState.NotInstalled(selection)
            return
        }
        if (!hasDownloadConsent()) {
            state = AiModelState.ConsentRequired(selection)
            return
        }
        enqueueDownload(selection)
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
            val passed = if (installed.artifact.family == AiModelFamily.GEMMA) {
                GemmaModelSelfTester.verify(appContext, installed, selection.backend)
            } else {
                Qwen3ModelSelfTester.verify(appContext, installed.file)
            }
            state = if (passed) {
                AiModelState.Ready(selection, installed)
            } else {
                clearSelfTested(installed.artifact)
                AiModelState.Failed(selection, "AI readiness check failed.")
            }
            readinessRunning.set(false)
            android.os.Handler(appContext.mainLooper).post(onFinished)
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
        val removed = modelDirectory.listFiles()
            ?.filter { it.parentFile == modelDirectory && it.isFile }
            ?.count { it.delete() }
            ?: 0
        if (removed > 0) {
            preferences.edit()
                .remove(selfTestedKey(GemmaModelCatalog.fast))
                .remove(selfTestedKey(GemmaModelCatalog.thorough))
                .apply()
        }
    }

    fun removeAllModels() {
        if (modelDirectory.isDirectory) {
            modelDirectory.listFiles()?.forEach { file ->
                if (file.parentFile == modelDirectory && file.isFile) file.delete()
            }
        }
        preferences.edit().clear().apply()
        WorkManager.getInstance(appContext).cancelUniqueWork(MODEL_DOWNLOAD_WORK)
        WorkManager.getInstance(appContext).cancelUniqueWork(LEGACY_MODEL_DOWNLOAD_WORK)
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
        val bytesNeeded =
            (artifact.sizeBytes - installer.candidateFile(artifact).length()).coerceAtLeast(0L)
        if (modelDirectory.parentFile?.usableSpace.orZero() < bytesNeeded + DOWNLOAD_HEADROOM_BYTES) {
            state = AiModelState.InsufficientStorage(selection)
            throw IOException("Not enough free storage for AI.")
        }
        state = AiModelState.Downloading(selection, 0L, artifact.sizeBytes)
        return installer.stage(artifact, stopped) { downloaded, total ->
            state = AiModelState.Downloading(selection, downloaded, total)
            progress(downloaded, total)
        }
    }

    internal fun candidateModel(selection: AiRuntimeSelection): File? =
        installer.candidateFile(selection.artifact).takeIf {
            installer.isValid(it, selection.artifact)
        }

    internal fun onDownloadWaiting(selection: AiRuntimeSelection) {
        state = AiModelState.WaitingForWifi(selection)
    }

    internal fun onDownloadFailed(selection: AiRuntimeSelection, failure: Throwable) {
        state = AiModelState.Failed(
            selection,
            failure.message ?: "AI installation failed.",
        )
    }

    internal fun qualifyAndActivate(
        selection: AiRuntimeSelection,
        candidate: File,
    ): InstalledAiModel? {
        val activation = installer.qualifyAndActivate(
            selection.artifact,
            candidate,
        ) { file ->
            GemmaModelSelfTester.verify(
                appContext,
                InstalledAiModel(selection.artifact, file),
                selection.backend,
            )
        }
        if (!activation.activated) return null
        markSelfTested(selection.artifact)
        val installed = InstalledAiModel(selection.artifact, activation.activeFile)
        if (selection.artifact.tier == AiModelTier.THOROUGH) {
            preferences.edit().putBoolean(KEY_THOROUGH_QUALIFIED, true).apply()
        }
        qualifyGpuIfSupported(installed)
        return installed
    }

    // KEY_GPU_QUALIFIED was read but never written, so LITERT_GPU was dead
    // code on every device. After a successful activation on qualifying
    // hardware, run one GPU self-test; persisting the pass lets the hardware
    // policy select the much faster GPU backend from then on. A failure
    // changes nothing - the CPU path stays active.
    private fun qualifyGpuIfSupported(installed: InstalledAiModel) {
        if (preferences.getBoolean(KEY_GPU_QUALIFIED, false)) return
        val device = readDeviceProfile()
        if (!device.hasVulkanHardware || device.severeThermalState) {
            return
        }
        val passed = runCatching {
            GemmaModelSelfTester.verify(
                appContext,
                installed,
                BackendKind.LITERT_GPU,
            )
        }.getOrDefault(false)
        if (passed) {
            preferences.edit().putBoolean(KEY_GPU_QUALIFIED, true).apply()
        }
    }

    internal fun onDownloadReady(
        selection: AiRuntimeSelection,
        installed: InstalledAiModel,
    ) {
        clearDownloadRegistration(selection)
        state = if (selection == selectedRuntime()) {
            AiModelState.Ready(selection, installed)
        } else {
            AiModelState.NotInstalled(selectedRuntime())
        }
    }

    internal fun clearDownloadRegistration(selection: AiRuntimeSelection) {
        if (
            preferences.getString(KEY_ENQUEUED_ARTIFACT, null) ==
            selection.artifact.tier.name
        ) {
            preferences.edit()
                .remove(KEY_ENQUEUED_ARTIFACT)
                .remove(KEY_ENQUEUED_NETWORK_POLICY)
                .remove(KEY_ENQUEUED_CONSTRAINT_VERSION)
                .apply()
        }
    }

    private fun enqueueDownload(selection: AiRuntimeSelection) {
        val networkPolicy = downloadNetworkPolicy()
        state = if (
            networkPolicy == AiDownloadNetworkPolicy.WIFI_ONLY &&
            !isDownloadNetworkAllowed()
        ) {
            AiModelState.WaitingForWifi(selection)
        } else {
            AiModelState.NotInstalled(selection)
        }
        val request = OneTimeWorkRequestBuilder<AiModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(networkPolicy.requiredNetworkType)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setInputData(
                workDataOf(
                    AiModelDownloadWorker.KEY_ARTIFACT_TIER to
                        selection.artifact.tier.name,
                    AiModelDownloadWorker.KEY_BACKEND to selection.backend.name,
                ),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(MODEL_DOWNLOAD_TAG)
            .build()
        preferences.edit()
            .putString(KEY_ENQUEUED_ARTIFACT, selection.artifact.tier.name)
            .putString(KEY_ENQUEUED_NETWORK_POLICY, networkPolicy.name)
            .putInt(KEY_ENQUEUED_CONSTRAINT_VERSION, DOWNLOAD_CONSTRAINT_VERSION)
            .apply()
        val workManager = WorkManager.getInstance(appContext)
        workManager.cancelUniqueWork(LEGACY_MODEL_DOWNLOAD_WORK)
        workManager.enqueueUniqueWork(
            MODEL_DOWNLOAD_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
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

    internal fun isDownloadNetworkAllowed(): Boolean {
        val manager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false
        }
        return downloadNetworkPolicy() == AiDownloadNetworkPolicy.ALLOW_MOBILE_DATA ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun markSelfTested(artifact: AiModelArtifact) {
        preferences.edit().putString(selfTestedKey(artifact), artifact.sha256).apply()
    }

    private fun clearSelfTested(artifact: AiModelArtifact) {
        preferences.edit().remove(selfTestedKey(artifact)).apply()
    }

    private fun isSelfTested(artifact: AiModelArtifact): Boolean =
        preferences.getString(selfTestedKey(artifact), null) == artifact.sha256

    private fun selfTestedKey(artifact: AiModelArtifact): String =
        "${KEY_SELF_TESTED_PREFIX}_${artifact.tier.name.lowercase(Locale.US)}"

    private fun Long?.orZero(): Long = this ?: 0L

    companion object {
        private const val PREFERENCES = "manuscript_ai"
        private const val MODEL_DIRECTORY = "ai_models"
        private const val MODEL_DOWNLOAD_WORK = "manuscript-ai-model-download-v3"
        private const val LEGACY_MODEL_DOWNLOAD_WORK = "manuscript-ai-model-download"
        private const val MODEL_DOWNLOAD_TAG = "manuscript-ai"
        private const val KEY_USER_MODE = "user_mode"
        private const val KEY_DOWNLOAD_CONSENT = "download_consent"
        private const val KEY_DOWNLOAD_NETWORK_POLICY = "download_network_policy"
        private const val KEY_DOWNLOAD_PROMPT_SEEN = "download_prompt_seen"
        private const val KEY_SELF_TESTED_PREFIX = "self_tested_sha256"
        private const val KEY_GPU_QUALIFIED = "gemma_gpu_qualified"
        private const val KEY_FORCE_LEGACY = "force_legacy_backend_debug"
        private const val KEY_THOROUGH_QUALIFIED = "gemma_thorough_qualified"
        private const val KEY_ENQUEUED_ARTIFACT = "enqueued_artifact"
        private const val KEY_ENQUEUED_NETWORK_POLICY = "enqueued_network_policy"
        private const val KEY_ENQUEUED_CONSTRAINT_VERSION = "enqueued_constraint_version"
        private const val DOWNLOAD_CONSTRAINT_VERSION = 3
        private const val DOWNLOAD_HEADROOM_BYTES = 536_870_912L

        @Volatile private var instance: AiModelManager? = null

        fun get(context: Context): AiModelManager = instance ?: synchronized(this) {
            instance ?: AiModelManager(context).also { instance = it }
        }
    }
}
