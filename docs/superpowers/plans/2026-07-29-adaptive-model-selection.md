# Adaptive Model Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Manuscript audit each Android device locally, choose the highest-quality safe AI that has proved it can correct the first paragraph within the 10-second Auto target, and always finish with a validated AI correction or an explicit safe failure.

**Architecture:** Add a pure device-audit snapshot, a pure adaptive selector, and a private benchmark store. `AiModelManager` owns audit, selection, qualification, download coordination, and failure strikes. `DocumentCorrectionPipeline` owns the 8-second primary paragraph watchdog and compact AI fallback. The three correction surfaces consume one shared result contract and show the same terminal failure state.

**Tech Stack:** Kotlin, Android SDK, coroutines, WorkManager, SharedPreferences, JUnit 4, Gradle 9, LiteRT Gemma, llama.cpp Qwen3

## Global Constraints

- Use AI for every accepted correction.
- Deterministic code may validate, protect formatting, and prepare an AI retry. It may not create the accepted correction.
- Auto targets the first corrected paragraph within 10,000 ms.
- The primary paragraph watchdog is 8,000 ms so fallback has time to answer.
- Faster and Best Quality remain manual overrides.
- Keep Qwen3 0.6B Q8 installed as the compact recovery model.
- Reuse the existing Wi-Fi-only or mobile-data consent. Do not create a second prompt.
- Store device facts, benchmark measurements, and failure counts locally. Never store prompt or generated text in benchmark records.
- A missing audit signal, expired benchmark, severe thermal state, or two primary strikes must cause a conservative Auto choice.
- Every callback completes once with validated nonblank AI text or the original text plus `No safe correction available`.
- Do not show internal model family or repository names in user-facing text.
- Do not stage unrelated files from the dirty parent worktree. Every commit command must name its files explicitly.
- Do not use an em dash in source, tests, documentation, or user-facing text.

---

## Task 1: Add a Complete Local Device Audit

**Files:**

- Create: `app/src/main/java/com/echolink/mobile/DeviceAudit.kt`
- Create: `app/src/test/java/com/echolink/mobile/DeviceAuditTest.kt`
- Modify: `app/src/main/java/com/echolink/mobile/AiModelCatalog.kt`
- Modify: `app/src/test/java/com/echolink/mobile/LocalAiRuntimeArchitectureTest.kt`

### Step 1: Write failing profile and fingerprint tests

- [ ] Add `DeviceAuditTest.kt` with constructed profiles for Galaxy S20, Galaxy S23, ARM64 Chromebook, x86_64 Chromebook, and a constrained phone.
- [ ] Verify the stable fingerprint changes when total RAM, processor count, ABI, Vulkan support, low-memory classification, or device family changes.
- [ ] Verify the stable fingerprint does not change when available RAM, free storage, thermal state, battery, charging, or power saving changes. Those dynamic values are checked on every selection instead of invalidating the stored hardware benchmark.
- [ ] Verify unknown battery, charging, or power-saving signals set `signalsComplete` to `false`.
- [ ] Verify no account ID, Android ID, serial number, phone number, or user text is part of the fingerprint input.

Use these exact public types in the tests:

```kotlin
data class DeviceAuditSnapshot(
    val profile: AiDeviceProfile,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val powerSaveMode: Boolean?,
    val osFingerprint: String,
    val deviceFingerprint: String,
    val auditedAtEpochMs: Long,
) {
    val signalsComplete: Boolean
        get() = batteryPercent != null && charging != null && powerSaveMode != null
}

fun interface DeviceAudit {
    fun read(): DeviceAuditSnapshot
}
```

### Step 2: Verify the new tests fail

- [ ] Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:ANDROID_HOME='C:\Users\stuff\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.DeviceAuditTest
```

- [ ] Confirm failure is caused by missing `DeviceAuditSnapshot`, `DeviceAudit`, and `DeviceFingerprint`, not by the test environment.

### Step 3: Implement the pure snapshot and fingerprint

- [ ] Add `batteryPercent`, `charging`, and `powerSaveMode` to `AiDeviceProfile` as nullable values with defaults so existing constructors continue to compile:

```kotlin
data class AiDeviceProfile(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val usableStorageBytes: Long,
    val availableProcessors: Int,
    val is64Bit: Boolean,
    val supportedAbis: Set<String>,
    val androidApi: Int,
    val hasVulkanHardware: Boolean,
    val severeThermalState: Boolean,
    val lowMemoryDevice: Boolean,
    val deviceFamily: AiDeviceFamily,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val powerSaveMode: Boolean? = null,
)
```

- [ ] Implement `DeviceFingerprint.from(snapshotFields)` with SHA-256 over a versioned, sorted, ASCII payload:

```kotlin
object DeviceFingerprint {
    private const val FORMAT_VERSION = 1

    fun from(profile: AiDeviceProfile): String {
        val payload = listOf(
            FORMAT_VERSION,
            profile.totalRamBytes,
            profile.availableProcessors,
            profile.is64Bit,
            profile.supportedAbis
                .map { it.lowercase(Locale.ROOT) }
                .sorted()
                .joinToString(","),
            profile.hasVulkanHardware,
            profile.lowMemoryDevice,
            profile.deviceFamily.name,
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
            }
    }
}
```

- [ ] Implement `AndroidDeviceAudit(context, nowEpochMs)` by moving the current RAM, storage, CPU, ABI, API, Vulkan, thermal, low-memory, and family reads out of `AiModelManager.readDeviceProfile()`.
- [ ] Add battery percentage and charging state from `ACTION_BATTERY_CHANGED`.
- [ ] Add power-saving state from `PowerManager.isPowerSaveMode`.
- [ ] Use `Build.FINGERPRINT.orEmpty()` only as the OS invalidation value. Do not hash device identifiers.
- [ ] When a service or signal cannot be read, use `null` for the new nullable signals and the safest existing value for non-nullable fields.
- [ ] Build `deviceFingerprint` from the final `AiDeviceProfile`.

### Step 4: Route current hardware selection through the audit

- [ ] Replace `AiModelManager.readDeviceProfile()` with:

```kotlin
private val deviceAudit: DeviceAudit = AndroidDeviceAudit(appContext)

internal fun currentDeviceAudit(): DeviceAuditSnapshot = deviceAudit.read()
```

- [ ] Pass `currentDeviceAudit().profile` into the current static policy until Task 3 replaces the selector.

### Step 5: Verify the focused tests pass

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.DeviceAuditTest --tests com.echolink.mobile.LocalAiRuntimeArchitectureTest
```

### Step 6: Commit only Task 1 files

- [ ] Run:

```powershell
git add -- app/src/main/java/com/echolink/mobile/DeviceAudit.kt app/src/main/java/com/echolink/mobile/AiModelCatalog.kt app/src/main/java/com/echolink/mobile/AiModelManager.kt app/src/test/java/com/echolink/mobile/DeviceAuditTest.kt app/src/test/java/com/echolink/mobile/LocalAiRuntimeArchitectureTest.kt
git commit -m "feat(android): add local AI device audit" --only -- app/src/main/java/com/echolink/mobile/DeviceAudit.kt app/src/main/java/com/echolink/mobile/AiModelCatalog.kt app/src/main/java/com/echolink/mobile/AiModelManager.kt app/src/test/java/com/echolink/mobile/DeviceAuditTest.kt app/src/test/java/com/echolink/mobile/LocalAiRuntimeArchitectureTest.kt
```

---

## Task 2: Build the Pure Adaptive Selector

**Files:**

- Create: `app/src/main/java/com/echolink/mobile/AdaptiveModelSelection.kt`
- Create: `app/src/test/java/com/echolink/mobile/AdaptiveModelSelectionTest.kt`
- Modify: `app/src/main/java/com/echolink/mobile/AiModelCatalog.kt`

### Step 1: Write the failing selection matrix

- [ ] Add tests proving:

  - Auto picks thorough when its exact validated record is at or below 10,000 ms.
  - Auto picks fast when thorough is slower than 10,000 ms and fast passes.
  - Auto picks compact when larger candidates have no exact benchmark.
  - Auto ignores records with a different artifact hash, backend, app version, Android API, OS fingerprint, device fingerprint, or policy version.
  - Auto skips a candidate with two strikes.
  - Faster picks compact.
  - Best Quality picks the highest-ranked hard-safe candidate even when its record is slow.
  - severe thermal state, low memory, incomplete signals, or insufficient storage excludes larger models.
  - an S20 cannot select the thorough model.
  - an S23 can select the thorough model after qualification.
  - ARM64 and x86_64 Chromebook profiles remain on CPU backends.

Use deterministic helpers in the test:

```kotlin
private fun record(
    selection: AiRuntimeSelection,
    context: BenchmarkContext,
    durationMs: Long,
    validated: Boolean = true,
) = ModelBenchmarkRecord(
    key = ModelBenchmarkKey.from(selection, context),
    durationMs = durationMs,
    completed = true,
    validated = validated,
    recordedAtEpochMs = 1_000L,
)
```

### Step 2: Verify the selector tests fail

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.AdaptiveModelSelectionTest
```

### Step 3: Implement exact benchmark and decision types

- [ ] Add:

```kotlin
data class BenchmarkContext(
    val appVersion: Long,
    val androidApi: Int,
    val osFingerprint: String,
    val deviceFingerprint: String,
    val policyVersion: Int = AdaptiveModelSelector.POLICY_VERSION,
)

data class ModelBenchmarkKey(
    val artifactSha256: String,
    val backend: BackendKind,
    val appVersion: Long,
    val androidApi: Int,
    val osFingerprint: String,
    val deviceFingerprint: String,
    val policyVersion: Int,
) {
    companion object {
        fun from(
            selection: AiRuntimeSelection,
            context: BenchmarkContext,
        ) = ModelBenchmarkKey(
            artifactSha256 = selection.artifact.sha256,
            backend = selection.backend,
            appVersion = context.appVersion,
            androidApi = context.androidApi,
            osFingerprint = context.osFingerprint,
            deviceFingerprint = context.deviceFingerprint,
            policyVersion = context.policyVersion,
        )
    }
}

data class ModelBenchmarkRecord(
    val key: ModelBenchmarkKey,
    val durationMs: Long,
    val completed: Boolean,
    val validated: Boolean,
    val recordedAtEpochMs: Long,
)

data class ModelRuntimeKey(
    val artifactSha256: String,
    val backend: BackendKind,
) {
    companion object {
        fun from(selection: AiRuntimeSelection) =
            ModelRuntimeKey(selection.artifact.sha256, selection.backend)
    }
}

data class AdaptiveModelDecision(
    val selection: AiRuntimeSelection,
    val reason: String,
    val measuredDurationMs: Long?,
)
```

### Step 4: Implement hard safety and ranking

- [ ] Add these selector constants:

```kotlin
object AdaptiveModelSelector {
    const val POLICY_VERSION = 1
    const val AUTO_TARGET_MS = 10_000L
    const val PRIMARY_WORKING_LIMIT_MS = 8_000L
    const val MAX_AUTO_STRIKES = 2
}
```

- [ ] Implement `DeviceModelSafety.allows(selection, audit)` with these exact rules:

  - compact is allowed on any 64-bit device with enough space for its artifact and 512 MiB headroom.
  - any missing dynamic signal allows compact only.
  - low-memory, non-64-bit, less than 6 GiB total RAM, less than 1.5 GiB available RAM, severe thermal state, or power saving allows compact only.
  - fast requires its artifact size plus 1 GiB headroom, Android 31 or newer, and ARM64.
  - thorough requires Galaxy S23, 8 GiB total RAM, 3 GiB available RAM, 8 processors, Android 31 or newer, no power saving, battery at least 20 percent unless charging, and artifact size plus 1 GiB headroom.
  - GPU requires Galaxy S23 plus Vulkan. Chromebook candidates use CPU.

- [ ] Add a candidate factory:

```kotlin
object AiRuntimeCandidates {
    fun forAudit(audit: DeviceAuditSnapshot): List<AiRuntimeSelection> {
        val values = mutableListOf(
            AiRuntimeSelection(
                GemmaModelCatalog.legacyCompact,
                BackendKind.LEGACY_LLAMA_CPU,
            ),
            AiRuntimeSelection(GemmaModelCatalog.fast, BackendKind.LITERT_CPU),
            AiRuntimeSelection(GemmaModelCatalog.thorough, BackendKind.LITERT_CPU),
        )
        if (
            audit.profile.deviceFamily == AiDeviceFamily.GALAXY_S23 &&
            audit.profile.hasVulkanHardware
        ) {
            values += AiRuntimeSelection(
                GemmaModelCatalog.fast,
                BackendKind.LITERT_GPU,
            )
            values += AiRuntimeSelection(
                GemmaModelCatalog.thorough,
                BackendKind.LITERT_GPU,
            )
        }
        return values.filter { DeviceModelSafety.allows(it, audit) }
    }
}
```

- [ ] Give model tiers the quality order `LEGACY_COMPACT = 0`, `FAST = 1`, `THOROUGH = 2`.
- [ ] Prefer GPU over CPU only when both have an exact passing record and GPU is faster.

### Step 5: Implement selection without Android dependencies

- [ ] Implement:

```kotlin
fun select(
    candidates: List<AiRuntimeSelection>,
    audit: DeviceAuditSnapshot,
    records: List<ModelBenchmarkRecord>,
    strikes: Map<ModelRuntimeKey, Int>,
    context: BenchmarkContext,
    mode: AiUserMode,
): AdaptiveModelDecision
```

- [ ] Filter candidates through `DeviceModelSafety`.
- [ ] Match records by the full `ModelBenchmarkKey`.
- [ ] In Auto, exclude records that are incomplete, invalid, slower than 10,000 ms, or have two strikes.
- [ ] In Auto, treat compact as the safe baseline even before its first recorded benchmark.
- [ ] In Faster, select compact and do not mutate the saved mode.
- [ ] In Best Quality, select the highest hard-safe tier and use a passing record only to choose CPU versus GPU.
- [ ] Return a stable reason code from this closed set:

```kotlin
const val REASON_AUTO_BENCHMARK = "auto_benchmark"
const val REASON_AUTO_COMPACT_PENDING = "auto_compact_pending"
const val REASON_AUTO_STRIKES = "auto_strikes"
const val REASON_DEVICE_CONSTRAINED = "device_constrained"
const val REASON_FASTER_OVERRIDE = "faster_override"
const val REASON_BEST_QUALITY_OVERRIDE = "best_quality_override"
```

### Step 6: Verify and commit Task 2

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.AdaptiveModelSelectionTest --tests com.echolink.mobile.LocalAiRuntimeArchitectureTest
```

- [ ] Commit only:

```powershell
git add -- app/src/main/java/com/echolink/mobile/AdaptiveModelSelection.kt app/src/main/java/com/echolink/mobile/AiModelCatalog.kt app/src/test/java/com/echolink/mobile/AdaptiveModelSelectionTest.kt
git commit -m "feat(android): select AI from local benchmarks" --only -- app/src/main/java/com/echolink/mobile/AdaptiveModelSelection.kt app/src/main/java/com/echolink/mobile/AiModelCatalog.kt app/src/test/java/com/echolink/mobile/AdaptiveModelSelectionTest.kt
```

---

## Task 3: Persist Benchmarks, Invalidation, and Failure Strikes

**Files:**

- Create: `app/src/main/java/com/echolink/mobile/AdaptiveModelBenchmarkStore.kt`
- Create: `app/src/test/java/com/echolink/mobile/AdaptiveModelBenchmarkStoreTest.kt`

### Step 1: Write failing store tests

- [ ] Test round-trip storage for all benchmark key fields.
- [ ] Test replacement of an existing record with the same full key.
- [ ] Test that `validRecords(context)` rejects any context mismatch.
- [ ] Test one strike retains a candidate and two strikes excludes it from Auto.
- [ ] Test `clearStrikes` after a successful audit.
- [ ] Test `clearRecords(context)` removes only records for that exact context.
- [ ] Test `clear` removes records and strikes.
- [ ] Test the serialized preferences contain no fixed benchmark paragraph and no generated correction.
- [ ] Use an in-memory implementation of the preference port so these remain local JVM tests.

### Step 2: Verify red

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.AdaptiveModelBenchmarkStoreTest
```

### Step 3: Implement a testable preference port

- [ ] Add:

```kotlin
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class SharedPreferencesKeyValueStore(
    private val preferences: SharedPreferences,
) : KeyValueStore {
    override fun getString(key: String): String? =
        preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}
```

### Step 4: Implement exact benchmark-store behavior

- [ ] Add:

```kotlin
interface AdaptiveModelBenchmarkStore {
    fun records(): List<ModelBenchmarkRecord>
    fun validRecords(context: BenchmarkContext): List<ModelBenchmarkRecord>
    fun put(record: ModelBenchmarkRecord)
    fun clearRecords(context: BenchmarkContext)
    fun strikeCounts(): Map<ModelRuntimeKey, Int>
    fun recordStrike(selection: AiRuntimeSelection): Int
    fun clearStrikes(selection: AiRuntimeSelection)
    fun clear()
}
```

- [ ] Implement `PreferencesAdaptiveModelBenchmarkStore` with `org.json.JSONArray` and `org.json.JSONObject`.
- [ ] Sort records by their stable composite key before writing so serialization is deterministic.
- [ ] Store only enum names, hashes, numeric context values, durations, booleans, and timestamps.
- [ ] Clamp strike counts to `AdaptiveModelSelector.MAX_AUTO_STRIKES`.
- [ ] Handle malformed JSON by returning an empty list or map and replacing it on the next write.
- [ ] `validRecords(context)` must compare app version, Android API, OS fingerprint, device fingerprint, and policy version exactly.

### Step 5: Verify and commit Task 3

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.AdaptiveModelBenchmarkStoreTest --tests com.echolink.mobile.AdaptiveModelSelectionTest
```

- [ ] Commit only:

```powershell
git add -- app/src/main/java/com/echolink/mobile/AdaptiveModelBenchmarkStore.kt app/src/test/java/com/echolink/mobile/AdaptiveModelBenchmarkStoreTest.kt
git commit -m "feat(android): persist local AI benchmarks" --only -- app/src/main/java/com/echolink/mobile/AdaptiveModelBenchmarkStore.kt app/src/test/java/com/echolink/mobile/AdaptiveModelBenchmarkStoreTest.kt
```

---

## Task 4: Measure Real Qualification and Coordinate Both Downloads

**Files:**

- Modify: `app/src/main/java/com/echolink/mobile/GemmaModelSelfTester.kt`
- Modify: `app/src/main/java/com/echolink/mobile/AiModelManager.kt`
- Modify: `app/src/main/java/com/echolink/mobile/AiModelDownloadWorker.kt`
- Modify: `app/src/main/java/com/echolink/mobile/Qwen3ModelManager.kt`
- Modify: `app/src/main/java/com/echolink/mobile/Qwen3ModelDownloadWorker.kt`
- Modify: `app/src/test/java/com/echolink/mobile/LocalAiRuntimeArchitectureTest.kt`
- Create: `app/src/test/java/com/echolink/mobile/AiModelManagerSourceTest.kt`

### Step 1: Write failing qualification and coordination tests

- [ ] Add tests that require:

  - a completed, nonblank, validated correction to qualify a model.
  - qualification duration to include initialization and generation.
  - the benchmark key to use the exact artifact hash and backend.
  - existing Wi-Fi-only consent to propagate to the compact manager.
  - existing mobile-data consent to propagate to the compact manager.
  - `ensureCompactModelAsync()` to request `Qwen3Tier.BASIC` without changing the user's saved Qwen mode.
  - compact lookup to require the exact `Qwen3Tier.BASIC` artifact rather than any installed Qwen tier.
  - Gemma download enqueue not to cancel compact download work.
  - Auto to download and qualify the highest-quality hard-safe unknown candidate while continuing to use compact AI.
  - Auto to qualify fast next when thorough is measured above the target.
  - Auto to stop qualification when every hard-safe candidate has an exact record.
  - Remove AI to clear Gemma, compact AI, benchmark records, and strikes.

- [ ] Add a source guard that fails if `AiModelManager.enqueueDownload` contains:

```kotlin
cancelUniqueWork(LEGACY_MODEL_DOWNLOAD_WORK)
```

### Step 2: Verify red

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.LocalAiRuntimeArchitectureTest --tests com.echolink.mobile.AiModelManagerSourceTest
```

### Step 3: Return a measured qualification result

- [ ] Replace the Boolean-only internal path with:

```kotlin
data class ModelQualificationResult(
    val completed: Boolean,
    val validated: Boolean,
    val durationMs: Long,
) {
    val passed: Boolean
        get() = completed && validated
}
```

- [ ] Add an injectable monotonic clock for JVM testing:

```kotlin
fun interface MonotonicClock {
    fun elapsedRealtime(): Long
}
```

- [ ] Implement `GemmaModelSelfTester.qualify(...)` with the fixed built-in source:

```text
Yesterday the project team meet early, but the notes was incomplete and nobody know which draft were final.
```

- [ ] Send it through `WritingOperation.CORRECT_ONLY`, then require:

  - `LocalGenerationResult.completed`
  - nonblank output
  - `AiSafetyValidator.validate(...).safe`
  - `LocalCorrectionRouter.acceptsGeneratedGrammar(output)`

- [ ] Preserve:

```kotlin
fun verify(
    context: Context,
    model: InstalledAiModel,
    backendKind: BackendKind,
): Boolean = qualify(context, model, backendKind).passed
```

### Step 4: Make `AiModelManager` own audit, selector, and benchmark context

- [ ] Add lazy fields for `DeviceAudit` and `PreferencesAdaptiveModelBenchmarkStore`.
- [ ] Build `BenchmarkContext` from:

```kotlin
BenchmarkContext(
    appVersion = BuildConfig.VERSION_CODE.toLong(),
    androidApi = audit.profile.androidApi,
    osFingerprint = audit.osFingerprint,
    deviceFingerprint = audit.deviceFingerprint,
)
```

- [ ] Replace `selectedRuntime()` with a call to `AdaptiveModelSelector.select(...)`.
- [ ] Add:

```kotlin
fun currentDecision(): AdaptiveModelDecision
fun recordPrimaryOutcome(
    selection: AiRuntimeSelection,
    durationMs: Long,
    completed: Boolean,
    validated: Boolean,
)
fun markPrimaryReliable(selection: AiRuntimeSelection)
fun runAuditAgain(onFinished: (Result<AdaptiveModelDecision>) -> Unit)
```

- [ ] `recordPrimaryOutcome` writes an exact benchmark only for completed and validated output. It records one strike for timeout, crash, blank, or validation failure.
- [ ] `markPrimaryReliable` clears strikes only after a completed and validated correction.
- [ ] `runAuditAgain` clears stale strikes for the model only after measured qualification passes.
- [ ] Add `nextAutoQualificationCandidate()` with this deterministic order:

  1. highest-quality hard-safe candidate without an exact record
  2. GPU before CPU only on Galaxy S23 with Vulkan
  3. thorough before fast
  4. no candidate after every hard-safe candidate has an exact record

- [ ] When Auto currently selects compact because larger records are unknown, continue serving corrections with compact AI and enqueue `nextAutoQualificationCandidate()` in the background.
- [ ] When a thorough record is valid but slower than 10,000 ms, enqueue fast qualification next.
- [ ] `runAuditAgain` deletes records for the current benchmark context, keeps installed files, then starts the same finite qualification sequence.

### Step 5: Keep compact recovery installed

- [ ] Add `Qwen3ModelManager.ensureCompactModelAsync()` that directly enqueues `Qwen3Tier.BASIC`.
- [ ] Add `Qwen3ModelManager.installedCompactModel()` that returns only `installedModel(Qwen3Tier.BASIC)`.
- [ ] Add a network-policy conversion:

```kotlin
private fun AiDownloadNetworkPolicy.toQwenPolicy() =
    when (this) {
        AiDownloadNetworkPolicy.WIFI_ONLY ->
            Qwen3DownloadNetworkPolicy.WIFI_ONLY
        AiDownloadNetworkPolicy.ALLOW_MOBILE_DATA ->
            Qwen3DownloadNetworkPolicy.ALLOW_MOBILE_DATA
    }
```

- [ ] In `AiModelManager.grantDownloadConsent`, grant the same policy to `Qwen3ModelManager` and call `ensureCompactModelAsync()`.
- [ ] In `AiModelManager.ensureModelAsync`, ensure compact recovery first, then enqueue the selected primary.
- [ ] If the selected primary is compact, enqueue `nextAutoQualificationCandidate()` instead of treating compact as the end of the audit.
- [ ] Change both legacy selection lookup and `installedLegacyFallbackModel()` to require `installedCompactModel()`. Never substitute balanced or advanced Qwen for the recovery artifact.
- [ ] Remove the line that cancels `LEGACY_MODEL_DOWNLOAD_WORK` during Gemma enqueue.
- [ ] Keep explicit cancellation of both workers only inside `removeAllModels()`.

### Step 6: Persist the qualification record

- [ ] In `qualifyAndActivate`, capture `ModelQualificationResult` from the candidate before activation.
- [ ] If activation succeeds, write:

```kotlin
benchmarkStore.put(
    ModelBenchmarkRecord(
        key = ModelBenchmarkKey.from(selection, currentBenchmarkContext()),
        durationMs = qualification.durationMs,
        completed = qualification.completed,
        validated = qualification.validated,
        recordedAtEpochMs = System.currentTimeMillis(),
    ),
)
```

- [ ] Never write the built-in paragraph or model output to preferences or logs.
- [ ] After `AiModelDownloadWorker` records a qualification, call `ensureModelAsync()` once. This either selects the new passing candidate, queues the next unknown hard-safe candidate, or terminates when the finite queue is complete.

### Step 7: Verify and commit Task 4

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.LocalAiRuntimeArchitectureTest --tests com.echolink.mobile.AiModelManagerSourceTest --tests com.echolink.mobile.AdaptiveModelSelectionTest --tests com.echolink.mobile.AdaptiveModelBenchmarkStoreTest
```

- [ ] Commit only:

```powershell
git add -- app/src/main/java/com/echolink/mobile/GemmaModelSelfTester.kt app/src/main/java/com/echolink/mobile/AiModelManager.kt app/src/main/java/com/echolink/mobile/AiModelDownloadWorker.kt app/src/main/java/com/echolink/mobile/Qwen3ModelManager.kt app/src/main/java/com/echolink/mobile/Qwen3ModelDownloadWorker.kt app/src/test/java/com/echolink/mobile/LocalAiRuntimeArchitectureTest.kt app/src/test/java/com/echolink/mobile/AiModelManagerSourceTest.kt
git commit -m "feat(android): qualify and retain recovery AI" --only -- app/src/main/java/com/echolink/mobile/GemmaModelSelfTester.kt app/src/main/java/com/echolink/mobile/AiModelManager.kt app/src/main/java/com/echolink/mobile/AiModelDownloadWorker.kt app/src/main/java/com/echolink/mobile/Qwen3ModelManager.kt app/src/main/java/com/echolink/mobile/Qwen3ModelDownloadWorker.kt app/src/test/java/com/echolink/mobile/LocalAiRuntimeArchitectureTest.kt app/src/test/java/com/echolink/mobile/AiModelManagerSourceTest.kt
```

---

## Task 5: Enforce the Primary Deadline and Compact AI Fallback

**Files:**

- Modify: `app/src/main/java/com/echolink/mobile/DocumentCorrectionPipeline.kt`
- Modify: `app/src/main/java/com/echolink/mobile/OnDeviceGrammarEngine.kt`
- Modify: `app/src/main/java/com/echolink/mobile/LiteRtGemmaBackend.kt`
- Modify: `app/src/test/java/com/echolink/mobile/DocumentCorrectionPipelineTest.kt`
- Modify: `app/src/test/java/com/echolink/mobile/OnDeviceGrammarEngineTest.kt`

### Step 1: Write failing timeout and recovery tests

- [ ] Add coroutine tests proving:

  - all primary passes for one paragraph share one 8,000 ms budget.
  - timeout cancels the active primary generation job.
  - fallback starts after the primary timeout is caught.
  - fallback is not inside the expired primary timeout.
  - invalid and blank primary output use compact AI.
  - a valid fallback produces `usedFallback = true`.
  - a failed fallback throws `ParagraphCorrectionException`.
  - callback is invoked exactly once after success, primary timeout, runtime crash, fallback failure, and user cancellation.
  - deterministic router analysis is never used as a correction result.

- [ ] Use a controllable backend:

```kotlin
private class BlockingBackend : LocalRewriteBackend {
    override val backendId = "blocking"
    var cancelledJobId: String? = null

    override suspend fun initialize(
        model: InstalledAiModel,
        backend: BackendKind,
    ) = Unit

    override suspend fun generate(
        request: LocalGenerationRequest,
    ): LocalGenerationResult {
        awaitCancellation()
    }

    override suspend fun cancel(jobId: String) {
        cancelledJobId = jobId
    }

    override fun releaseResources() = Unit
}
```

### Step 2: Verify red

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.DocumentCorrectionPipelineTest --tests com.echolink.mobile.OnDeviceGrammarEngineTest
```

### Step 3: Put one watchdog around the full primary paragraph flow

- [ ] Add constructor values:

```kotlin
class DocumentCorrectionPipeline(
    private val primaryBackend: LocalRewriteBackend,
    private val fallbackBackend: LocalRewriteBackend? = null,
    private val primaryWorkingLimitMs: Long =
        AdaptiveModelSelector.PRIMARY_WORKING_LIMIT_MS,
    private val elapsedRealtimeMs: () -> Long =
        { System.nanoTime() / 1_000_000L },
    private val onPrimaryOutcome: (PrimaryCorrectionOutcome) -> Unit = {},
    private val acceptsGeneratedGrammar: (String) -> Boolean = { true },
    private val acceptsStrictlyReviewedGrammar: (String) -> Boolean =
        acceptsGeneratedGrammar,
    private val prepareGrammarRetryCandidate: (String) -> String = { it },
)

data class PrimaryCorrectionOutcome(
    val durationMs: Long,
    val completed: Boolean,
    val validated: Boolean,
    val timedOut: Boolean,
)
```

- [ ] Split `correctParagraph` into:

```kotlin
private suspend fun correctParagraph(
    document: DocumentCorrectionRequest,
    chunk: ParagraphChunk,
    acceptsGrammar: (String) -> Boolean,
    acceptsStrictReview: (String) -> Boolean,
    prepareGrammarRetry: (String) -> String,
): CorrectedParagraph {
    val startedAt = elapsedRealtimeMs()
    return try {
        val corrected = withTimeout(primaryWorkingLimitMs) {
            correctParagraphWithPrimary(
                document = document,
                chunk = chunk,
                acceptsGrammar = acceptsGrammar,
                acceptsStrictReview = acceptsStrictReview,
                prepareGrammarRetry = prepareGrammarRetry,
            )
        }
        onPrimaryOutcome(
            PrimaryCorrectionOutcome(
                durationMs = elapsedRealtimeMs() - startedAt,
                completed = true,
                validated = true,
                timedOut = false,
            ),
        )
        corrected
    } catch (failure: TimeoutCancellationException) {
        cancel(document.jobId)
        onPrimaryOutcome(
            PrimaryCorrectionOutcome(
                durationMs = elapsedRealtimeMs() - startedAt,
                completed = false,
                validated = false,
                timedOut = true,
            ),
        )
        correctParagraphWithFallback(document, chunk, acceptsGrammar)
    } catch (failure: ParagraphCorrectionException) {
        onPrimaryOutcome(
            PrimaryCorrectionOutcome(
                durationMs = elapsedRealtimeMs() - startedAt,
                completed = false,
                validated = false,
                timedOut = false,
            ),
        )
        correctParagraphWithFallback(document, chunk, acceptsGrammar)
    }
}
```

- [ ] Ensure `correctParagraphWithPrimary` never invokes fallback internally.
- [ ] Change primary calls to `allowFallback = false`.
- [ ] Keep exactly one fallback generation with `strictRetry = true`.
- [ ] Catch `TimeoutCancellationException` separately so it is reported as a timeout rather than a crash.
- [ ] Let every other `CancellationException` propagate immediately. User cancellation must never run fallback or add a strike.
- [ ] Call `onPrimaryOutcome` once per nonblank paragraph.
- [ ] Do not catch external user cancellation as a model failure strike.

### Step 4: Restart a failed primary runtime once

- [ ] Add `LiteRtGemmaBackend.restart(model, backend)` that releases resources and initializes the same installed artifact and backend once.
- [ ] In `OnDeviceGrammarEngine`, wrap the Gemma primary with a one-restart adapter. On the first non-cancellation runtime exception from `generate`, call `restart(installed, selection.backend)` and retry the same generation request once.
- [ ] Keep the adapter inside the pipeline's 8,000 ms timeout so restart and retry share the same primary budget.
- [ ] Do not restart on validation rejection or user cancellation.
- [ ] Convert a second runtime failure into `ParagraphCorrectionException` so compact AI handles the paragraph and the primary receives one outcome and one strike, not two.

### Step 5: Feed measured outcomes back to selection

- [ ] Capture the selected `AiRuntimeSelection` before backend initialization.
- [ ] Pass `onPrimaryOutcome` into the pipeline:

```kotlin
onPrimaryOutcome = { outcome ->
    modelManager.recordPrimaryOutcome(
        selection = selection,
        durationMs = outcome.durationMs,
        completed = outcome.completed,
        validated = outcome.validated,
    )
}
```

- [ ] Confirm a second consecutive slow or failed result causes `currentDecision()` to choose the next passing Auto candidate or compact.
- [ ] Confirm Faster and Best Quality mode values do not change when a failure is recorded.

### Step 6: Verify and commit Task 5

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.DocumentCorrectionPipelineTest --tests com.echolink.mobile.OnDeviceGrammarEngineTest --tests com.echolink.mobile.AdaptiveModelSelectionTest
```

- [ ] Commit only:

```powershell
git add -- app/src/main/java/com/echolink/mobile/DocumentCorrectionPipeline.kt app/src/main/java/com/echolink/mobile/OnDeviceGrammarEngine.kt app/src/main/java/com/echolink/mobile/LiteRtGemmaBackend.kt app/src/test/java/com/echolink/mobile/DocumentCorrectionPipelineTest.kt app/src/test/java/com/echolink/mobile/OnDeviceGrammarEngineTest.kt
git commit -m "fix(android): bound correction time and recover with AI" --only -- app/src/main/java/com/echolink/mobile/DocumentCorrectionPipeline.kt app/src/main/java/com/echolink/mobile/OnDeviceGrammarEngine.kt app/src/main/java/com/echolink/mobile/LiteRtGemmaBackend.kt app/src/test/java/com/echolink/mobile/DocumentCorrectionPipelineTest.kt app/src/test/java/com/echolink/mobile/OnDeviceGrammarEngineTest.kt
```

---

## Task 6: Show Audit Status and One Safe Terminal Failure

**Files:**

- Create: `app/src/main/java/com/echolink/mobile/AiAuditSummary.kt`
- Modify: `app/src/main/java/com/echolink/mobile/AiModelManager.kt`
- Modify: `app/src/main/java/com/echolink/mobile/MainActivity.kt`
- Modify: `app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt`
- Modify: `app/src/main/java/com/echolink/mobile/ProcessTextActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/com/echolink/mobile/AndroidUsabilitySourceTest.kt`
- Modify: `app/src/test/java/com/echolink/mobile/ManuscriptProductSourceTest.kt`
- Modify: `app/src/test/java/com/echolink/mobile/UserVisibleAiTerminologyTest.kt`

### Step 1: Write failing UI contract tests

- [ ] Require all three correction surfaces to reference `R.string.ai_safe_correction_unavailable`.
- [ ] Require the terminal failure path to preserve original text.
- [ ] Require Replace or Accept to remain disabled after terminal failure.
- [ ] Require each spinner or progress state to stop on failure.
- [ ] Require model settings to show selected AI, backend, measured time, audit date, compact recovery status, reason, and `Run audit again`.
- [ ] Require all user-visible values to use `AI`, `Fast`, `Thorough`, `CPU`, or `GPU`, never `Gemma`, `Qwen`, `LiteRT`, `llama.cpp`, repository names, or artifact filenames.

### Step 2: Verify red

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.AndroidUsabilitySourceTest --tests com.echolink.mobile.ManuscriptProductSourceTest --tests com.echolink.mobile.UserVisibleAiTerminologyTest
```

### Step 3: Add a shared audit summary

- [ ] Add:

```kotlin
data class AiAuditSummary(
    val selectedAiLabel: String,
    val backendLabel: String,
    val measuredDurationMs: Long?,
    val auditedAtEpochMs: Long?,
    val compactFallbackInstalled: Boolean,
    val selectionReason: String,
)
```

- [ ] Add `AiModelManager.auditSummary()` that maps internal values:

  - compact tier to `AI Compact`
  - fast tier to `AI Fast`
  - thorough tier to `AI Thorough`
  - `LITERT_CPU` and `LEGACY_LLAMA_CPU` to `CPU`
  - `LITERT_GPU` to `GPU`
  - reason codes to short plain explanations

### Step 4: Add strings and settings action

- [ ] Add exact user-facing strings:

```xml
<string name="ai_safe_correction_unavailable">No safe correction available</string>
<string name="ai_run_audit_again">Run audit again</string>
<string name="ai_audit_running">Testing this device locally...</string>
<string name="ai_audit_complete">AI test complete.</string>
<string name="ai_audit_failed">AI test failed. Compact AI will be used.</string>
<string name="ai_compact_ready">Compact recovery: Ready</string>
<string name="ai_compact_not_ready">Compact recovery: Installing</string>
```

- [ ] Extend `MainActivity.showModelSettings()` with the summary fields and `Run audit again`.
- [ ] Disable the action while an audit is running.
- [ ] Refresh the same dialog after completion.
- [ ] Do not show or log the fixed benchmark paragraph.

### Step 5: Normalize failure on every surface

- [ ] Add one helper per surface that:

  - restores or retains source text.
  - stops progress.
  - displays `No safe correction available`.
  - disables Replace or Accept.
  - leaves Retry or Run audit again available.

- [ ] Keep `Result.failure(...)` inside the engine. Convert it to the shared visible safe state at the UI boundary.
- [ ] Do not pass the original text to Replace as if it were a correction.

### Step 6: Verify and commit Task 6

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.AndroidUsabilitySourceTest --tests com.echolink.mobile.ManuscriptProductSourceTest --tests com.echolink.mobile.UserVisibleAiTerminologyTest --tests com.echolink.mobile.OnDeviceGrammarEngineTest
```

- [ ] Commit only:

```powershell
git add -- app/src/main/java/com/echolink/mobile/AiAuditSummary.kt app/src/main/java/com/echolink/mobile/AiModelManager.kt app/src/main/java/com/echolink/mobile/MainActivity.kt app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt app/src/main/java/com/echolink/mobile/ProcessTextActivity.kt app/src/main/res/values/strings.xml app/src/test/java/com/echolink/mobile/AndroidUsabilitySourceTest.kt app/src/test/java/com/echolink/mobile/ManuscriptProductSourceTest.kt app/src/test/java/com/echolink/mobile/UserVisibleAiTerminologyTest.kt
git commit -m "feat(android): show AI audit and safe failure" --only -- app/src/main/java/com/echolink/mobile/AiAuditSummary.kt app/src/main/java/com/echolink/mobile/AiModelManager.kt app/src/main/java/com/echolink/mobile/MainActivity.kt app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt app/src/main/java/com/echolink/mobile/ProcessTextActivity.kt app/src/main/res/values/strings.xml app/src/test/java/com/echolink/mobile/AndroidUsabilitySourceTest.kt app/src/test/java/com/echolink/mobile/ManuscriptProductSourceTest.kt app/src/test/java/com/echolink/mobile/UserVisibleAiTerminologyTest.kt
```

---

## Task 7: Add Device-Level Acceptance Tests

**Files:**

- Create: `app/src/androidTest/java/com/echolink/mobile/AdaptiveModelSelectionDeviceTest.kt`
- Modify: `app/src/androidTest/java/com/echolink/mobile/FullPageDeviceGrammarTest.kt`
- Modify: `play/INTERNAL_TESTING.md`
- Modify: `play/RELEASE_NOTES.md`

### Step 1: Enforce the USB preinstall safety gate

- [ ] Use the Android SDK's absolute ADB path because `adb` is not guaranteed to be on `PATH`:

```powershell
$adb='C:\Users\stuff\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
```

- [ ] Require exactly one authorized physical S23 before continuing.
- [ ] Force-stop both possible Manuscript packages and the instrumentation package before any USB install:

```powershell
& $adb shell am force-stop com.remote.Manuscript
& $adb shell am force-stop com.remote.Manuscript.debug
& $adb shell am force-stop com.remote.Manuscript.debug.test
```

- [ ] Verify the old processes are gone:

```powershell
$releasePid=((& $adb shell pidof com.remote.Manuscript) | Out-String).Trim()
$debugPid=((& $adb shell pidof com.remote.Manuscript.debug) | Out-String).Trim()
$testPid=((& $adb shell pidof com.remote.Manuscript.debug.test) | Out-String).Trim()
if ($releasePid -or $debugPid -or $testPid) {
    throw 'Old Manuscript process is still running.'
}
```

- [ ] Install with replacement so app data is preserved:

```powershell
& $adb install -r -t 'C:\Users\stuff\Documents\Echo-Link\Manuscript\Android\app\build\outputs\apk\debug\app-debug.apk'
if ($LASTEXITCODE -ne 0) {
    throw 'USB replacement install failed. Do not uninstall automatically.'
}
```

- [ ] If replacement fails because of a signature conflict, stop and report the exact package and error. Uninstall only after Curtis explicitly accepts loss of that package's local app data.
- [ ] Repeat the force-stop and process check before every later USB install in this plan.

### Step 2: Write device tests before release changes

- [ ] Add tests that run only when the required AI artifact is installed and otherwise fail with an installation instruction.
- [ ] Record cold and warm first-paragraph duration with `SystemClock.elapsedRealtime()`.
- [ ] Assert the warm Auto result is nonblank, validated, and at or below 10,000 ms.
- [ ] Assert the selected Auto decision matches the highest-quality exact passing record.
- [ ] Inject a 1 ms primary limit and assert compact fallback produces a validated result.
- [ ] Inject invalid primary output and assert strict retry or compact fallback.
- [ ] Assert audit preferences contain no test source or generated text.
- [ ] Keep the existing five-page tone tests and add an assertion that every completed paragraph is AI generated and validated.

### Step 3: Compile the device tests

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:compileDebugAndroidTestKotlin
```

### Step 4: Run the physical S23 matrix

- [ ] Confirm the S23 is visible:

```powershell
adb devices -l
```

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.echolink.mobile.AdaptiveModelSelectionDeviceTest
```

- [ ] Manually verify one correction in the main editor.
- [ ] Manually verify one correction through the across-app accessibility panel.
- [ ] Record cold duration, warm duration, selected tier, backend, fallback result, app version, and device build fingerprint in the internal testing record.
- [ ] Do not claim S23 verification if the device suite is skipped, disconnected, or run only on an emulator.

### Step 5: Update release notes only with measured facts

- [ ] Add the measured S23 results to `play/INTERNAL_TESTING.md`.
- [ ] Add user-facing behavior changes to `play/RELEASE_NOTES.md`.
- [ ] State any untested Chromebook behavior as unit-tested compatibility, not physical-device proof.

### Step 6: Commit Task 7

- [ ] Run:

```powershell
git add -- app/src/androidTest/java/com/echolink/mobile/AdaptiveModelSelectionDeviceTest.kt app/src/androidTest/java/com/echolink/mobile/FullPageDeviceGrammarTest.kt play/INTERNAL_TESTING.md play/RELEASE_NOTES.md
git commit -m "test(android): verify adaptive AI on device" --only -- app/src/androidTest/java/com/echolink/mobile/AdaptiveModelSelectionDeviceTest.kt app/src/androidTest/java/com/echolink/mobile/FullPageDeviceGrammarTest.kt play/INTERNAL_TESTING.md play/RELEASE_NOTES.md
```

---

## Task 8: Run the Full Release Gate and Update the Private Repository

**Files:**

- Modify only if needed after a failing gate: files already named in Tasks 1 through 7
- Update remote: private repository `stuffthings15/manuscript-android`

### Step 1: Run formatting and placeholder checks

- [ ] Run:

```powershell
$placeholderPattern=@('T'+'BD','T'+'ODO','FIX'+'ME','similar'+' to','appropriate'+' error') -join '|'
rg -n $placeholderPattern app/src/main app/src/test app/src/androidTest docs/superpowers play
$longDash=[char]0x2014
Get-ChildItem app/src/main,app/src/test,app/src/androidTest,docs/superpowers,play -Recurse -File | Select-String -Pattern $longDash
git diff --check
```

- [ ] Investigate every match in files changed by this plan. Do not alter unrelated user work.

### Step 2: Run the complete local gate

- [ ] Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:ANDROID_HOME='C:\Users\stuff\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon :app:testReleaseUnitTest lintDebug
```

- [ ] Confirm at least the existing 305 tests plus all new tests pass.
- [ ] Run the existing security release gate documented in `play/UPLOAD_READINESS.md`.
- [ ] Build the signed release bundle with `tools/build_play_bundle.ps1`.
- [ ] Record the final test count, lint result, security-gate result, bundle version, and bundle SHA-256.

### Step 3: Review the exact change set

- [ ] Run:

```powershell
git status --short
git diff --stat HEAD
git log --oneline 9224f23..HEAD
```

- [ ] Confirm every adaptive-model commit contains only its named Android files.
- [ ] Confirm unrelated Windows, website, platform, and evidence changes remain unstaged.

### Step 4: Rebuild the source-only private mirror

- [ ] Copy the verified Android source state into the existing source-only staging repository:

```text
C:\tmp\manuscript-android-private-upload-20260729
```

- [ ] Preserve the mirror exclusions for secrets, signing files, local properties, models, build output, APKs, AABs, device evidence, and runtime data.
- [ ] Run the full release unit test and lint gate from the mirror before pushing.
- [ ] Commit the mirror with:

```text
feat: add adaptive on-device AI selection
```

- [ ] Push `main` only after the mirror test and lint gate pass.

### Step 5: Verify the private remote

- [ ] Verify the repository is still private.
- [ ] Verify the remote main commit matches the local mirror commit.
- [ ] Verify forbidden files are absent remotely.
- [ ] Report private GitHub sync separately from Play upload or physical-device proof.

---

## Final Specification Coverage Check

- [ ] Device audit covers RAM, storage, CPU, ABI, Android API, OS fingerprint, Vulkan, low-memory status, thermal state, battery, charging, power saving, and device family.
- [ ] The benchmark measures a real local correction and stores no writing.
- [ ] Auto uses the highest-quality exact validated benchmark at or below 10 seconds.
- [ ] The primary working limit is 8 seconds.
- [ ] Compact AI remains installed and receives the same approved network policy.
- [ ] One restart is attempted for runtime failure.
- [ ] Two slow or failed corrections downgrade Auto.
- [ ] Faster and Best Quality remain manual modes.
- [ ] All correction surfaces terminate once with validated AI text or an explicit safe failure.
- [ ] Rule-only correction cannot reach Accept or Replace.
- [ ] Settings show audit status and Run audit again.
- [ ] S20, S23, low-RAM, Chromebook, thermal, storage, battery, and power-saving cases are tested.
- [ ] S23 proof is labeled physical only after connected-device tests pass.
- [ ] Full unit, lint, security, signed-bundle, and private-repository verification are complete before release handoff.
