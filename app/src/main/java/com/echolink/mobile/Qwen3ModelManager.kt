package com.echolink.mobile

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
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

enum class Qwen3DownloadNetworkPolicy(val requiredNetworkType: NetworkType) {
    WIFI_ONLY(NetworkType.UNMETERED),
    ALLOW_MOBILE_DATA(NetworkType.CONNECTED),
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
    private val modelDirectory = File(appContext.noBackupFilesDir, MODEL_DIRECTORY)
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
        tier.sizeBytes + DOWNLOAD_HEADROOM_BYTES

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
        return downloadedModel(tier)
    }

    internal fun downloadedModel(tier: Qwen3Tier): File? {
        val file = File(modelDirectory, tier.fileName)
        return file.takeIf {
            it.isFile &&
                it.length() == tier.sizeBytes &&
                verifiedSha(tier) == tier.sha256
        }
    }

    internal fun candidateModel(tier: Qwen3Tier): File? {
        val file = File(modelDirectory, "${tier.fileName}.candidate")
        return file.takeIf {
            it.isFile &&
                it.length() == tier.sizeBytes &&
                preferences.getString(candidateShaKey(tier), null) == tier.sha256
        }
    }

    fun ensureModelAsync() {
        val tier = selectedTier()
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
            val passed = Qwen3ModelSelfTester.verify(appContext, model)
            state = if (passed) {
                Qwen3ModelState.Ready(tier, model)
            } else {
                preferences.edit().remove(selfTestedShaKey(tier)).apply()
                Qwen3ModelState.Failed(tier, "AI readiness check failed.")
            }
            startupReadinessRunning.set(false)
            android.os.Handler(appContext.mainLooper).post(onFinished)
        }, "Manuscript-Qwen3-readiness").start()
    }

    fun removeAllModels() {
        if (modelDirectory.isDirectory) {
            modelDirectory.listFiles()?.forEach { file ->
                if (file.parentFile == modelDirectory && file.isFile) file.delete()
            }
        }
        preferences.edit().apply {
            Qwen3Tier.entries.forEach { remove(verifiedShaKey(it)) }
            Qwen3Tier.entries.forEach { remove(selfTestedShaKey(it)) }
            Qwen3Tier.entries.forEach { remove(candidateShaKey(it)) }
            remove(KEY_LEGACY_VERIFIED_SHA)
            remove(KEY_BENCHMARK_TIER)
            remove(KEY_BENCHMARK_DURATION)
            remove(KEY_BENCHMARK_SUCCEEDED)
            remove(KEY_ENQUEUED_TIER)
            remove(KEY_ENQUEUED_NETWORK_POLICY)
            remove(KEY_DOWNLOAD_CONSENT)
            remove(KEY_DOWNLOAD_NETWORK_POLICY)
        }.apply()
        WorkManager.getInstance(appContext).cancelUniqueWork(MODEL_DOWNLOAD_WORK)
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
        state = Qwen3ModelState.Downloading(tier, 0L, tier.sizeBytes)
        return installModel(tier, stopped, progress)
    }

    internal fun onDownloadWaiting(tier: Qwen3Tier) {
        state = Qwen3ModelState.WaitingForWifi(tier)
    }

    internal fun onDownloadFailed(tier: Qwen3Tier, error: Throwable) {
        Log.e(LOG_TAG, "Qwen3 model installation failed", error)
        state = Qwen3ModelState.Failed(
            tier,
            error.message ?: "AI installation failed.",
        )
    }

    internal fun onDownloadReady(tier: Qwen3Tier, file: File) {
        clearDownloadRegistration(tier)
        if (tier == Qwen3Tier.BALANCED) {
            File(modelDirectory, LEGACY_BALANCED_FILE).takeIf {
                it.name != file.name && it.isFile
            }?.delete()
        }
        state = if (tier == selectedTier()) {
            Qwen3ModelState.Ready(tier, file)
        } else {
            Qwen3ModelState.NotInstalled(selectedTier())
        }
    }

    internal fun markSelfTested(tier: Qwen3Tier) {
        preferences.edit().putString(selfTestedShaKey(tier), tier.sha256).apply()
    }

    internal fun clearDownloadRegistration(tier: Qwen3Tier) {
        if (preferences.getString(KEY_ENQUEUED_TIER, null) == tier.name) {
            preferences.edit()
                .remove(KEY_ENQUEUED_TIER)
                .remove(KEY_ENQUEUED_NETWORK_POLICY)
                .apply()
        }
    }

    internal fun activateCandidate(tier: Qwen3Tier, candidate: File): File {
        require(candidate.parentFile == modelDirectory && candidate.name == "${tier.fileName}.candidate") {
            "Invalid AI candidate path."
        }
        val finalFile = File(modelDirectory, tier.fileName)
        val previousFile = File(modelDirectory, "${tier.fileName}.previous")
        if (previousFile.exists()) previousFile.delete()
        val hadPrevious = finalFile.exists()
        if (hadPrevious) {
            require(finalFile.renameTo(previousFile)) {
                "Cannot preserve the previous AI."
            }
        }
        if (!candidate.renameTo(finalFile)) {
            if (hadPrevious) previousFile.renameTo(finalFile)
            throw IOException("Cannot activate the tested AI.")
        }
        preferences.edit()
            .putString(verifiedShaKey(tier), tier.sha256)
            .remove(candidateShaKey(tier))
            .apply()
        if (previousFile.exists()) previousFile.delete()
        return finalFile
    }

    private fun enqueueDownload(tier: Qwen3Tier) {
        val networkPolicy = downloadNetworkPolicy()
        if (
            networkPolicy == Qwen3DownloadNetworkPolicy.WIFI_ONLY &&
            !isUnmeteredNetwork()
        ) {
            state = Qwen3ModelState.WaitingForWifi(tier)
        } else {
            state = Qwen3ModelState.NotInstalled(tier)
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkPolicy.requiredNetworkType)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<Qwen3ModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(Qwen3ModelDownloadWorker.KEY_TIER to tier.name))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(MODEL_DOWNLOAD_TAG)
            .build()
        val alreadyEnqueued =
            preferences.getString(KEY_ENQUEUED_TIER, null) == tier.name &&
                preferences.getString(KEY_ENQUEUED_NETWORK_POLICY, null) == networkPolicy.name
        if (!alreadyEnqueued) {
            preferences.edit()
                .putString(KEY_ENQUEUED_TIER, tier.name)
                .putString(KEY_ENQUEUED_NETWORK_POLICY, networkPolicy.name)
                .apply()
        }
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            MODEL_DOWNLOAD_WORK,
            if (alreadyEnqueued) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun installModel(
        tier: Qwen3Tier,
        stopped: () -> Boolean,
        progress: (Long, Long) -> Unit,
    ): File {
        require(modelDirectory.exists() || modelDirectory.mkdirs()) {
            "Cannot create the local AI folder."
        }
        candidateModel(tier)?.let { return it }
        val candidateFile = File(modelDirectory, "${tier.fileName}.candidate")
        val partialFile = File(modelDirectory, "${tier.fileName}.part")
        if (partialFile.length() > tier.sizeBytes) partialFile.delete()
        val bytesRemaining = (tier.sizeBytes - partialFile.length()).coerceAtLeast(0L)
        if (modelDirectory.usableSpace < bytesRemaining + DOWNLOAD_HEADROOM_BYTES) {
            state = Qwen3ModelState.InsufficientStorage(tier)
            throw IOException("Not enough free storage for ${tier.displayName}.")
        }

        if (partialFile.length() < tier.sizeBytes) {
            val connection = (URL(tier.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Manuscript-Android-Qwen3")
                if (partialFile.length() > 0L) {
                    setRequestProperty("Range", "bytes=${partialFile.length()}-")
                }
            }
            try {
                val response = connection.responseCode
                if (response != HttpURLConnection.HTTP_OK && response != HTTP_PARTIAL) {
                    throw IOException("AI download failed with HTTP $response.")
                }
                val append = response == HTTP_PARTIAL && partialFile.length() > 0L
                val startingBytes = if (append) partialFile.length() else 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partialFile, append).use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        var downloaded = startingBytes
                        while (true) {
                            if (stopped()) throw InterruptedIOException("AI download stopped.")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            state = Qwen3ModelState.Downloading(tier, downloaded, tier.sizeBytes)
                            progress(downloaded, tier.sizeBytes)
                        }
                        output.fd.sync()
                    }
                }
            } finally {
                connection.disconnect()
            }
        }

        if (partialFile.length() != tier.sizeBytes) {
            throw IOException("AI download was incomplete.")
        }
        if (sha256(partialFile, stopped) != tier.sha256) {
            partialFile.delete()
            throw IOException("AI download failed its security check.")
        }
        if (candidateFile.exists()) candidateFile.delete()
        if (!partialFile.renameTo(candidateFile)) {
            throw IOException("Cannot stage the downloaded AI.")
        }
        preferences.edit().putString(candidateShaKey(tier), tier.sha256).apply()
        return candidateFile
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

    private fun isUnmeteredNetwork(): Boolean {
        val manager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun fallbackOrder(selected: Qwen3Tier): List<Qwen3Tier> = when (selected) {
        Qwen3Tier.ADVANCED -> listOf(Qwen3Tier.BALANCED, Qwen3Tier.BASIC)
        Qwen3Tier.BALANCED -> listOf(Qwen3Tier.BASIC, Qwen3Tier.ADVANCED)
        Qwen3Tier.BASIC -> listOf(Qwen3Tier.BALANCED, Qwen3Tier.ADVANCED)
    }

    private fun sha256(file: File, stopped: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                if (stopped()) throw InterruptedIOException("AI verification stopped.")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
        private const val KEY_ENQUEUED_TIER = "enqueued_tier"
        private const val KEY_ENQUEUED_NETWORK_POLICY = "enqueued_network_policy"
        private const val MODEL_DIRECTORY = "qwen3_models"
        private const val MODEL_DOWNLOAD_WORK = "manuscript-qwen3-model-download"
        private const val MODEL_DOWNLOAD_TAG = "manuscript-qwen3"
        private const val LEGACY_BALANCED_FILE = "Qwen3-1.7B-Q8_0.gguf"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val DOWNLOAD_BUFFER_BYTES = 256 * 1024
        private const val DOWNLOAD_HEADROOM_BYTES = 536_870_912L
        private const val HTTP_PARTIAL = 206
        private const val LOG_TAG = "ManuscriptQwen3"

        @Volatile private var instance: Qwen3ModelManager? = null

        fun get(context: Context): Qwen3ModelManager = instance ?: synchronized(this) {
            instance ?: Qwen3ModelManager(context).also { instance = it }
        }
    }
}
