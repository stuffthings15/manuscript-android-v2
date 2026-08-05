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
import java.util.concurrent.atomic.AtomicBoolean

data class Qwen3DeviceCapability(
    val totalRamBytes: Long,
    val availableProcessors: Int,
    val usableStorageBytes: Long,
    val is64Bit: Boolean,
    val availableRamBytes: Long = totalRamBytes,
    val isLowRamDevice: Boolean = false,
    val androidApi: Int = Build.VERSION.SDK_INT,
    val performanceClass: Int = if (Build.VERSION.SDK_INT >= 31) {
        Build.VERSION.MEDIA_PERFORMANCE_CLASS
    } else {
        0
    },
    val isPowerSaveMode: Boolean = false,
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    val supportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty(),
    val hasVulkanHardware: Boolean = false,
    val isCharging: Boolean = false,
    val batteryPercent: Int = 100,
)

data class Qwen3RuntimeProfile(
    val tier: Qwen3Tier,
    val backend: LocalBackendType,
    val contextSize: Int,
    val maximumSourceCharacters: Int,
    val maximumOutputTokens: Int,
    val threadCount: Int,
    val conserveResources: Boolean,
)

object Qwen3RuntimeProfilePolicy {
    fun create(
        tier: Qwen3Tier,
        constrained: Boolean,
        availableProcessors: Int,
    ): Qwen3RuntimeProfile {
        val compactModel = tier == Qwen3Tier.BASIC
        return Qwen3RuntimeProfile(
            tier = tier,
            backend = LocalBackendType.LLAMA_CPP_CPU,
            contextSize = if (compactModel) 512 else 1_024,
            maximumSourceCharacters = if (compactModel) 480 else 600,
            maximumOutputTokens = if (compactModel) 192 else 384,
            threadCount = if (constrained) {
                2
            } else {
                availableProcessors.coerceIn(2, 4)
            },
            conserveResources = constrained,
        )
    }
}

enum class Qwen3UserMode(val displayName: String) {
    AUTO("Auto"),
    FASTER("Faster"),
    BEST_QUALITY("Best Quality"),
}

enum class Qwen3DownloadNetworkPolicy {
    WIFI_ONLY,
    ALLOW_MOBILE_DATA,
}

data class Qwen3InferenceBenchmark(
    val tier: Qwen3Tier,
    val durationMs: Long,
    val succeeded: Boolean,
) {
    val allowsAdvancedTier: Boolean
        get() = succeeded && tier == Qwen3Tier.BALANCED && durationMs <= ADVANCED_GATE_MS
    val requiresCompactTier: Boolean
        get() = tier == Qwen3Tier.BALANCED &&
            succeeded &&
            durationMs > BALANCED_SPEED_LIMIT_MS

    companion object {
        private const val ADVANCED_GATE_MS = 3_000L
        private const val BALANCED_SPEED_LIMIT_MS = 15_000L
    }
}

enum class Qwen3Tier(
    val displayName: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    BASIC(
        displayName = "AI",
        repository = "Qwen/Qwen3-0.6B-GGUF",
        revision = "23749fefcc72300e3a2ad315e1317431b06b590a",
        fileName = "Qwen3-0.6B-Q8_0.gguf",
        sizeBytes = 639_446_688L,
        sha256 = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031",
    ),
    BALANCED(
        displayName = "AI",
        repository = "bartowski/Qwen_Qwen3-1.7B-GGUF",
        revision = "822702a8257cf1b05a27d73f775d8606cf2e33a4",
        fileName = "Qwen_Qwen3-1.7B-Q4_K_M.gguf",
        sizeBytes = 1_282_439_584L,
        sha256 = "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb",
    ),
    ADVANCED(
        displayName = "AI",
        repository = "Qwen/Qwen3-4B-GGUF",
        revision = "bc640142c66e1fdd12af0bd68f40445458f3869b",
        fileName = "Qwen3-4B-Q4_K_M.gguf",
        sizeBytes = 2_497_280_256L,
        sha256 = "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5",
    );

    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/$revision/$fileName"
}

object Qwen3CapabilitySelector {
    private const val GIB = 1_073_741_824L

    fun select(
        device: Qwen3DeviceCapability,
        benchmark: Qwen3InferenceBenchmark? = null,
        mode: Qwen3UserMode = Qwen3UserMode.AUTO,
    ): Qwen3Tier {
        if (
            !device.is64Bit ||
            device.isLowRamDevice ||
            device.totalRamBytes < 6L * GIB ||
            device.usableStorageBytes < 3L * GIB
        ) {
            return Qwen3Tier.BASIC
        }
        if (mode == Qwen3UserMode.FASTER) return Qwen3Tier.BASIC
        if (mode == Qwen3UserMode.AUTO && benchmark?.requiresCompactTier == true) {
            return Qwen3Tier.BASIC
        }
        // Android reports less RAM than the marketed capacity because firmware
        // reserves part of it. A marketed 12 GB Samsung can report about 10.3 GiB.
        val minimumAdvancedRam =
            if (mode == Qwen3UserMode.BEST_QUALITY) 10L * GIB else 12L * GIB
        val advancedHardware =
            device.totalRamBytes >= minimumAdvancedRam &&
            device.availableRamBytes >= 3L * GIB &&
            device.availableProcessors >= 8 &&
            device.usableStorageBytes >= 4L * GIB &&
            device.androidApi >= 31 &&
            (device.performanceClass >= 31 || device.hasVulkanHardware)
        return when {
            advancedHardware && mode == Qwen3UserMode.BEST_QUALITY -> Qwen3Tier.ADVANCED
            advancedHardware && benchmark?.allowsAdvancedTier == true -> Qwen3Tier.ADVANCED
            else -> Qwen3Tier.BALANCED
        }
    }
}

sealed class Qwen3ModelState {
    data class Ready(
        val tier: Qwen3Tier,
        val file: File,
        val backend: LocalBackendType = LocalBackendType.LLAMA_CPP_CPU,
    ) : Qwen3ModelState()
    data class Downloading(val tier: Qwen3Tier, val downloaded: Long, val total: Long) :
        Qwen3ModelState()
    data class Checking(val tier: Qwen3Tier) : Qwen3ModelState()
    data class ConsentRequired(val tier: Qwen3Tier) : Qwen3ModelState()
    data class WaitingForWifi(val tier: Qwen3Tier) : Qwen3ModelState()
    data class InsufficientStorage(val tier: Qwen3Tier) : Qwen3ModelState()
    data class Failed(val tier: Qwen3Tier, val message: String) : Qwen3ModelState()
    data class NotInstalled(val tier: Qwen3Tier) : Qwen3ModelState()
}

/**
 * Installs one official Qwen3 model, chosen from device RAM, CPU, and storage.
 *
 * Only public model weights are downloaded. User text never leaves the device.
 */
class Qwen3ModelManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val downloadCommands by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ModelDownloadCommandCore(AndroidDownloadServices.get(appContext))
    }
    private val modelDirectory = File(appContext.noBackupFilesDir, MODEL_DIRECTORY)
    private val installer = AiModelInstaller(modelDirectory)
    @Volatile private var sessionTier: Qwen3Tier? = null
    private val startupReadinessRunning = AtomicBoolean(false)

    @Volatile
    var state: Qwen3ModelState = initialState()
        private set

    fun selectedTier(): Qwen3Tier {
        val override = preferences.getString(KEY_TIER_OVERRIDE, null)
            ?.let { value -> Qwen3Tier.entries.firstOrNull { it.name == value } }
        override?.let { return it }
        sessionTier?.let { return it }
        return Qwen3CapabilitySelector.select(
            readCapability(),
            storedBenchmark(),
            userMode(),
        ).also { sessionTier = it }
    }

    fun userMode(): Qwen3UserMode = preferences.getString(
        KEY_USER_MODE,
        Qwen3UserMode.AUTO.name,
    )?.let { name -> Qwen3UserMode.entries.firstOrNull { it.name == name } }
        ?: Qwen3UserMode.AUTO

    fun setUserMode(mode: Qwen3UserMode) {
        preferences.edit().putString(KEY_USER_MODE, mode.name).apply()
        preferences.edit().remove(KEY_TIER_OVERRIDE).apply()
        sessionTier = null
        refreshState()
    }

    fun setTierOverride(tier: Qwen3Tier?) {
        preferences.edit().apply {
            if (tier == null) remove(KEY_TIER_OVERRIDE) else putString(KEY_TIER_OVERRIDE, tier.name)
        }.apply()
        sessionTier = null
        refreshState()
    }

    fun runtimeProfile(tier: Qwen3Tier = selectedTier()): Qwen3RuntimeProfile {
        val capability = readCapability()
        val constrained =
            capability.isPowerSaveMode ||
                (!capability.isCharging && capability.batteryPercent < 15) ||
                capability.availableRamBytes < 1_500_000_000L ||
                capability.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        return Qwen3RuntimeProfilePolicy.create(
            tier = tier,
            constrained = constrained,
            availableProcessors = capability.availableProcessors,
        )
    }

    fun hasDownloadConsent(): Boolean = preferences.getBoolean(KEY_DOWNLOAD_CONSENT, false)

    fun hasSeenDownloadPrompt(): Boolean =
        preferences.getBoolean(KEY_DOWNLOAD_PROMPT_SEEN, false)

    fun markDownloadPromptSeen() {
        preferences.edit().putBoolean(KEY_DOWNLOAD_PROMPT_SEEN, true).apply()
    }

    fun requiredStorageBytes(tier: Qwen3Tier): Long =
        installer.remainingBytes(LegacyQwenModelCatalog.forTier(tier)) +
            DOWNLOAD_HEADROOM_BYTES

    fun downloadNetworkPolicy(): Qwen3DownloadNetworkPolicy =
        preferences.getString(KEY_DOWNLOAD_NETWORK_POLICY, null)
            ?.let { name ->
                Qwen3DownloadNetworkPolicy.entries.firstOrNull { it.name == name }
            }
            ?: Qwen3DownloadNetworkPolicy.WIFI_ONLY

    fun grantDownloadConsent(
        networkPolicy: Qwen3DownloadNetworkPolicy = Qwen3DownloadNetworkPolicy.WIFI_ONLY,
    ) {
        preferences.edit()
            .putBoolean(KEY_DOWNLOAD_CONSENT, true)
            .putString(KEY_DOWNLOAD_NETWORK_POLICY, networkPolicy.name)
            .apply()
        sessionTier = null
        val tier = selectedTier()
        val selection = AiRuntimeSelection(
            LegacyQwenModelCatalog.forTier(tier),
            BackendKind.LEGACY_LLAMA_CPU,
        )
        val target = DownloadTargetCatalog.targetFor(selection)
        if (downloadCommands.retryTerminal(target) != null) {
            state = Qwen3ModelState.NotInstalled(tier)
            return
        }
        ensureModelAsync()
    }

    fun installedModel(): File? {
        val selected = selectedTier()
        installedModel(selected)?.let { return it }
        return fallbackOrder(selected).firstNotNullOfOrNull { tier -> installedModel(tier) }
    }

    fun tierForModel(file: File): Qwen3Tier? =
        Qwen3Tier.entries.firstOrNull { it.fileName == file.name }

    fun installedModel(tier: Qwen3Tier): File? {
        if (!isSelfTested(tier)) return null
        if (verifiedSha(tier) != tier.sha256) return null
        if (!activeProofMatches(tier)) return null
        return downloadedModel(tier)
    }

    internal fun downloadedModel(tier: Qwen3Tier): File? {
        val file = File(modelDirectory, tier.fileName)
        return file.takeIf {
            it.isFile &&
                it.length() == tier.sizeBytes
        }
    }

    internal fun candidateModel(tier: Qwen3Tier): File? {
        val artifact = LegacyQwenModelCatalog.forTier(tier)
        return installer.candidateFile(artifact).takeIf {
            it.isFile && it.length() == artifact.sizeBytes
        }
    }

    fun ensureModelAsync(tier: Qwen3Tier = selectedTier()) {
        installedModel(tier)?.let {
            state = Qwen3ModelState.Ready(tier, it)
            return
        }
        if (!hasDownloadConsent()) {
            state = Qwen3ModelState.ConsentRequired(tier)
            return
        }
        enqueueDownload(tier)
    }

    fun retryModelDownload(): DownloadAttempt? {
        val tier = selectedTier()
        val selection = AiRuntimeSelection(
            LegacyQwenModelCatalog.forTier(tier),
            BackendKind.LEGACY_LLAMA_CPU,
        )
        val target = DownloadTargetCatalog.targetFor(selection)
        val retried = downloadCommands.retryTerminal(target) ?: return null
        state = Qwen3ModelState.NotInstalled(tier)
        return retried
    }

    fun verifyInstalledModelAsync(onFinished: () -> Unit) {
        val tier = selectedTier()
        val model = installedModel(tier)
        if (model == null) {
            ensureModelAsync()
            onFinished()
            return
        }
        if (!startupReadinessRunning.compareAndSet(false, true)) return
        state = Qwen3ModelState.Checking(tier)
        Thread({
            try {
                val passed = Qwen3ModelSelfTester.verify(
                    appContext,
                    model,
                    stopped = { false },
                )
                state = if (passed) {
                    Qwen3ModelState.Ready(tier, model)
                } else {
                    preferences.edit().remove(selfTestedShaKey(tier)).apply()
                    Qwen3ModelState.Failed(tier, "AI readiness check failed.")
                }
            } catch (_: Exception) {
                state = Qwen3ModelState.Failed(tier, "AI readiness check failed.")
                preferences.edit().remove(selfTestedShaKey(tier)).apply()
            } finally {
                startupReadinessRunning.set(false)
                android.os.Handler(appContext.mainLooper).post(onFinished)
            }
        }, "Manuscript-Qwen3-readiness").start()
    }

    fun removeAllModels() {
        if (!downloadCommands.cancelActiveBeforeRemoval(
                DownloadTargetCatalog.targetsFor(AiModelFamily.LEGACY_QWEN),
            )
        ) {
            return
        }
        AndroidDownloadServices.get(appContext).clearRecords(
            DownloadTargetCatalog.targetsFor(AiModelFamily.LEGACY_QWEN),
        )
        if (modelDirectory.isDirectory) {
            modelDirectory.listFiles()?.forEach { file ->
                if (file.parentFile == modelDirectory && file.isFile) file.delete()
            }
        }
        preferences.edit().apply {
            Qwen3Tier.entries.forEach { remove(verifiedShaKey(it)) }
            Qwen3Tier.entries.forEach { remove(selfTestedShaKey(it)) }
            Qwen3Tier.entries.forEach { remove(candidateShaKey(it)) }
            Qwen3Tier.entries.forEach { remove(activeProofSizeKey(it)) }
            Qwen3Tier.entries.forEach { remove(activeProofModifiedKey(it)) }
            Qwen3Tier.entries.forEach { remove(activeProofFileKeyKey(it)) }
            Qwen3Tier.entries.forEach { remove(activeProofFingerprintKey(it)) }
            remove(KEY_LEGACY_VERIFIED_SHA)
            remove(KEY_BENCHMARK_TIER)
            remove(KEY_BENCHMARK_DURATION)
            remove(KEY_BENCHMARK_SUCCEEDED)
            remove(KEY_DOWNLOAD_CONSENT)
            remove(KEY_DOWNLOAD_NETWORK_POLICY)
        }.apply()
        sessionTier = null
        state = Qwen3ModelState.ConsentRequired(selectedTier())
    }

    fun recordInference(tier: Qwen3Tier, durationMs: Long, succeeded: Boolean) {
        val current = storedBenchmark()
        val sample = Qwen3InferenceBenchmark(tier, durationMs, succeeded)
        val replaceStored =
            current == null ||
                current.tier != tier ||
                (succeeded && (!current.succeeded || durationMs < current.durationMs))
        if (replaceStored) {
            preferences.edit()
                .putString(KEY_BENCHMARK_TIER, tier.name)
                .putLong(KEY_BENCHMARK_DURATION, durationMs)
                .putBoolean(KEY_BENCHMARK_SUCCEEDED, succeeded)
                .apply()
        }
        val retained = if (replaceStored) sample else current
        if (
            userMode() == Qwen3UserMode.AUTO &&
            retained?.requiresCompactTier == true
        ) {
            sessionTier = null
            ensureModelAsync()
        }
    }

    internal fun downloadTier(
        tier: Qwen3Tier,
        stopped: () -> Boolean,
        progress: (Long, Long) -> Unit,
    ): File {
        require(hasDownloadConsent()) { "AI download consent is required." }
        require(modelDirectory.exists() || modelDirectory.mkdirs()) {
            "Cannot create the local AI folder."
        }
        val artifact = LegacyQwenModelCatalog.forTier(tier)
        val bytesRemaining = installer.remainingBytes(artifact)
        if (modelDirectory.usableSpace < bytesRemaining + DOWNLOAD_HEADROOM_BYTES) {
            state = Qwen3ModelState.InsufficientStorage(tier)
            throw AiModelStorageException("Not enough free storage for AI.")
        }
        state = Qwen3ModelState.Downloading(
            tier,
            installer.partialBytes(artifact),
            artifact.sizeBytes,
        )
        return installer.stage(artifact, stopped) { downloaded, total ->
            state = Qwen3ModelState.Downloading(tier, downloaded, total)
            progress(downloaded, total)
        }
    }

    internal fun partialBytes(tier: Qwen3Tier): Long {
        val artifact = LegacyQwenModelCatalog.forTier(tier)
        val completeLocalModel = candidateModel(tier) ?: downloadedModel(tier)
        return if (completeLocalModel != null) artifact.sizeBytes else installer.partialBytes(artifact)
    }

    internal fun onDownloadWaiting(tier: Qwen3Tier) {
        state = Qwen3ModelState.WaitingForWifi(tier)
    }

    internal fun onDownloadFailed(tier: Qwen3Tier, failure: Throwable) {
        state = Qwen3ModelState.Failed(
            tier,
            safeAiModelDownloadFailure(failure).userMessage,
        )
    }

    internal fun onDownloadFailed(tier: Qwen3Tier, failureCode: DownloadFailureCode) {
        state = Qwen3ModelState.Failed(tier, SafeDownloadFailure.from(failureCode).userMessage)
    }

    internal fun onDownloadReady(tier: Qwen3Tier, file: File) {
        state = if (tier == selectedTier()) {
            Qwen3ModelState.Ready(tier, file)
        } else {
            Qwen3ModelState.NotInstalled(selectedTier())
        }
    }

    internal fun markSelfTested(tier: Qwen3Tier, activeFile: File) {
        require(activeFile == File(modelDirectory, tier.fileName)) {
            "The active proof must use the canonical model file."
        }
        val artifact = LegacyQwenModelCatalog.forTier(tier)
        val proof = checkNotNull(installer.activeProof(artifact)) {
            "The active AI proof could not be recorded."
        }
        val editor = preferences.edit()
            .putString(verifiedShaKey(tier), tier.sha256)
            .putString(selfTestedShaKey(tier), tier.sha256)
            .remove(candidateShaKey(tier))
            .putLong(activeProofSizeKey(tier), proof.sizeBytes)
            .putLong(activeProofModifiedKey(tier), proof.modifiedMillis)
            .putString(activeProofFingerprintKey(tier), proof.fingerprint)
        if (proof.fileKey == null) {
            editor.remove(activeProofFileKeyKey(tier))
        } else {
            editor.putString(activeProofFileKeyKey(tier), proof.fileKey)
        }
        check(editor.commit()) { "The active AI proof could not be persisted." }
    }

    internal fun qualifyCandidate(
        tier: Qwen3Tier,
        candidate: File,
        stopped: () -> Boolean = { false },
    ): AiModelQualifiedCandidate? {
        val artifact = LegacyQwenModelCatalog.forTier(tier)
        val qualification = installer.qualifyCandidate(
            artifact = artifact,
            candidate = candidate,
            stopped = stopped,
            selfTest = { file -> Qwen3ModelSelfTester.verify(appContext, file, stopped) },
        )
        if (qualification == null) {
            if (stopped()) throw InterruptedIOException("AI qualification stopped.")
            installer.discardRejectedModel(artifact, candidate)
            clearTierProof(tier)
        }
        return qualification
    }

    internal fun qualifyActiveModel(
        tier: Qwen3Tier,
        active: File,
        stopped: () -> Boolean = { false },
    ): AiModelQualifiedActive? {
        val artifact = LegacyQwenModelCatalog.forTier(tier)
        val qualification = installer.qualifyActive(
            artifact = artifact,
            active = active,
            stopped = stopped,
            selfTest = { file -> Qwen3ModelSelfTester.verify(appContext, file, stopped) },
        )
        if (qualification == null) {
            if (stopped()) throw InterruptedIOException("AI qualification stopped.")
            installer.discardRejectedModel(artifact, active)
            clearTierProof(tier)
        }
        return qualification
    }

    internal fun activateQualifiedCandidate(
        tier: Qwen3Tier,
        qualification: AiModelQualifiedCandidate,
    ): File {
        val artifact = LegacyQwenModelCatalog.forTier(tier)
        require(qualification.artifact == artifact) {
            "The qualified AI does not match the selected model."
        }
        val activation = installer.activateQualifiedCandidate(qualification)
        check(activation.activated) { "The qualified AI was not activated." }
        markSelfTested(tier, activation.activeFile)
        return activation.activeFile
    }

    internal fun acceptQualifiedActive(
        tier: Qwen3Tier,
        qualification: AiModelQualifiedActive,
    ): File {
        val artifact = LegacyQwenModelCatalog.forTier(tier)
        require(qualification.artifact == artifact) {
            "The qualified AI does not match the selected model."
        }
        val active = installer.acceptQualifiedActive(qualification)
        markSelfTested(tier, active)
        return active
    }

    internal fun activateCandidate(
        tier: Qwen3Tier,
        candidate: File,
        stopped: () -> Boolean = { false },
    ): File {
        val qualification = qualifyCandidate(tier, candidate, stopped)
            ?: throw AiModelIntegrityException("AI candidate failed its local check.")
        return activateQualifiedCandidate(tier, qualification)
    }

    private fun enqueueDownload(tier: Qwen3Tier) {
        state = Qwen3ModelState.NotInstalled(tier)
        val selection = AiRuntimeSelection(
            LegacyQwenModelCatalog.forTier(tier),
            BackendKind.LEGACY_LLAMA_CPU,
        )
        val target = DownloadTargetCatalog.targetFor(selection)
        downloadCommands.ensure(target)
    }

    private fun readCapability(): Qwen3DeviceCapability {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val batteryLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val batteryScale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        return Qwen3DeviceCapability(
            totalRamBytes = memory.totalMem,
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            usableStorageBytes = modelDirectory.parentFile?.usableSpace ?: modelDirectory.usableSpace,
            is64Bit = is64Bit,
            availableRamBytes = memory.availMem,
            isLowRamDevice = activityManager.isLowRamDevice,
            isPowerSaveMode = powerManager.isPowerSaveMode,
            thermalStatus = if (Build.VERSION.SDK_INT >= 29) {
                powerManager.currentThermalStatus
            } else {
                PowerManager.THERMAL_STATUS_NONE
            },
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            hasVulkanHardware = appContext.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL,
            ),
            isCharging =
                batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                    batteryStatus == BatteryManager.BATTERY_STATUS_FULL,
            batteryPercent = if (batteryLevel >= 0 && batteryScale > 0) {
                (batteryLevel * 100 / batteryScale).coerceIn(0, 100)
            } else {
                100
            },
        )
    }

    private fun initialState(): Qwen3ModelState {
        val tier = selectedTier()
        return installedModel(tier)?.let {
            Qwen3ModelState.Ready(tier, it)
        }
            ?: if (hasDownloadConsent()) {
                Qwen3ModelState.NotInstalled(tier)
            } else {
                Qwen3ModelState.ConsentRequired(tier)
            }
    }

    private fun refreshState() {
        val tier = selectedTier()
        state = installedModel(tier)?.let {
            Qwen3ModelState.Ready(tier, it)
        }
            ?: if (hasDownloadConsent()) {
                Qwen3ModelState.NotInstalled(tier)
            } else {
                Qwen3ModelState.ConsentRequired(tier)
            }
    }

    private fun storedBenchmark(): Qwen3InferenceBenchmark? {
        val tierName = preferences.getString(KEY_BENCHMARK_TIER, null) ?: return null
        val tier = Qwen3Tier.entries.firstOrNull { it.name == tierName } ?: return null
        if (!preferences.contains(KEY_BENCHMARK_DURATION)) return null
        return Qwen3InferenceBenchmark(
            tier,
            preferences.getLong(KEY_BENCHMARK_DURATION, Long.MAX_VALUE),
            preferences.getBoolean(KEY_BENCHMARK_SUCCEEDED, false),
        )
    }

    private fun verifiedSha(tier: Qwen3Tier): String? {
        preferences.getString(verifiedShaKey(tier), null)?.let { return it }
        val legacy = preferences.getString(KEY_LEGACY_VERIFIED_SHA, null)
        if (legacy == tier.sha256) {
            preferences.edit().putString(verifiedShaKey(tier), legacy).apply()
            return legacy
        }
        return null
    }

    private fun verifiedShaKey(tier: Qwen3Tier): String =
        "${KEY_VERIFIED_SHA_PREFIX}_${tier.name.lowercase()}"

    private fun selfTestedShaKey(tier: Qwen3Tier): String =
        "${KEY_SELF_TESTED_SHA_PREFIX}_${tier.name.lowercase()}"

    private fun candidateShaKey(tier: Qwen3Tier): String =
        "${KEY_CANDIDATE_SHA_PREFIX}_${tier.name.lowercase()}"

    private fun isSelfTested(tier: Qwen3Tier): Boolean =
        preferences.getString(selfTestedShaKey(tier), null) == tier.sha256

    private fun activeProofMatches(tier: Qwen3Tier): Boolean {
        if (!preferences.contains(activeProofFingerprintKey(tier))) return false
        val artifact = LegacyQwenModelCatalog.forTier(tier)
        val current = installer.activeProof(artifact) ?: return false
        return current.sizeBytes == preferences.getLong(activeProofSizeKey(tier), -1L) &&
            current.modifiedMillis == preferences.getLong(activeProofModifiedKey(tier), -1L) &&
            current.fileKey == preferences.getString(activeProofFileKeyKey(tier), null) &&
            current.fingerprint == preferences.getString(activeProofFingerprintKey(tier), null)
    }

    private fun activeProofSizeKey(tier: Qwen3Tier): String =
        "${selfTestedShaKey(tier)}_active_size"

    private fun activeProofModifiedKey(tier: Qwen3Tier): String =
        "${selfTestedShaKey(tier)}_active_modified"

    private fun activeProofFileKeyKey(tier: Qwen3Tier): String =
        "${selfTestedShaKey(tier)}_active_file_key"

    private fun activeProofFingerprintKey(tier: Qwen3Tier): String =
        "${selfTestedShaKey(tier)}_active_fingerprint"

    private fun clearTierProof(tier: Qwen3Tier) {
        preferences.edit()
            .remove(verifiedShaKey(tier))
            .remove(selfTestedShaKey(tier))
            .remove(candidateShaKey(tier))
            .remove(activeProofSizeKey(tier))
            .remove(activeProofModifiedKey(tier))
            .remove(activeProofFileKeyKey(tier))
            .remove(activeProofFingerprintKey(tier))
            .apply()
    }

    private fun fallbackOrder(selected: Qwen3Tier): List<Qwen3Tier> = when (selected) {
        Qwen3Tier.ADVANCED -> listOf(Qwen3Tier.BALANCED, Qwen3Tier.BASIC)
        Qwen3Tier.BALANCED -> listOf(Qwen3Tier.BASIC, Qwen3Tier.ADVANCED)
        Qwen3Tier.BASIC -> listOf(Qwen3Tier.BALANCED, Qwen3Tier.ADVANCED)
    }

    companion object {
        private const val PREFERENCES = "manuscript_qwen3"
        private const val KEY_TIER_OVERRIDE = "tier_override"
        private const val KEY_USER_MODE = "user_mode"
        private const val KEY_DOWNLOAD_CONSENT = "download_consent"
        private const val KEY_DOWNLOAD_NETWORK_POLICY = "download_network_policy"
        private const val KEY_DOWNLOAD_PROMPT_SEEN = "download_prompt_seen"
        private const val KEY_VERIFIED_SHA_PREFIX = "verified_sha256"
        private const val KEY_SELF_TESTED_SHA_PREFIX = "self_tested_sha256"
        private const val KEY_CANDIDATE_SHA_PREFIX = "candidate_sha256"
        private const val KEY_LEGACY_VERIFIED_SHA = "verified_sha256"
        private const val KEY_BENCHMARK_TIER = "benchmark_tier"
        private const val KEY_BENCHMARK_DURATION = "benchmark_duration_ms"
        private const val KEY_BENCHMARK_SUCCEEDED = "benchmark_succeeded"
        private const val MODEL_DIRECTORY = "qwen3_models"
        private const val DOWNLOAD_HEADROOM_BYTES = 536_870_912L

        @Volatile private var instance: Qwen3ModelManager? = null

        fun get(context: Context): Qwen3ModelManager = instance ?: synchronized(this) {
            instance ?: Qwen3ModelManager(context).also { instance = it }
        }
    }
}
