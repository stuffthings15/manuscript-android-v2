# Task 3D report: pure download telemetry and presentation

## Result

Task 3D adds immutable, platform-neutral download telemetry, deterministic
rolling speed and ETA calculation, a typed restore seam for Task 3E, and one
safe presentation mapping for later in-app and notification consumers.

This task does not persist telemetry, integrate WorkManager, change network or
model policy, render UI, install an APK, access a device, or publish anything.

## RED proof

Tests were added before production code.

The final tests-only RED command was:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:ANDROID_HOME='C:\Users\stuff\AppData\Local\Android\Sdk'
& 'C:\Users\stuff\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' --no-daemon --max-workers=2 :app:testDebugUnitTest --tests "com.echolink.mobile.DownloadTelemetryCalculatorTest" --tests "com.echolink.mobile.DownloadTelemetryPresentationTest"
```

Gradle reached `compileDebugUnitTestKotlin` and failed on the missing
`DownloadTelemetryCalculator`, `DownloadTelemetry`, `DownloadSample`,
`DownloadTelemetryPresentation`, and related typed contracts. This was the
required production-missing RED, not an environment failure.

Later focused RED runs proved these review findings before their fixes:

- Singular output failed with `1 bytes/s` instead of `1 byte/s`.
- Retry tests failed compilation because `retryAllowed` did not exist.
- Restore tests failed compilation because `DownloadTelemetryRestoreFactory`
  did not exist.
- Ready completeness and internal factory-invariant tests both failed.
- The stale active-sample factory test failed before exact timestamp and byte
  matching were enforced.

Two earlier attempts did not count as RED proof. One stopped because
`JAVA_HOME` was absent. One stopped because the sandbox could not download the
Gradle distribution. The successful RED and all GREEN runs used the installed
JDK, Android SDK, and cached pinned Gradle distribution.

## Implemented contracts

- `DownloadPhase`: Preparing, Downloading, Verifying, Ready, Waiting, Paused,
  and Failed.
- Typed network, block-reason, failure-code, safe-failure, action, generation,
  observation, and sample data.
- Final telemetry and presentation values with private construction,
  defensive unmodifiable lists, structural equality, and no public copy or
  setter path.
- Strict phase invariants for block reasons, failures, verification, Retry,
  Cancel, completed known-byte Ready state, and active current samples.
- Safe failure text is a closed enum. Callers cannot provide a raw failure
  message.
- Injected time, exact rolling speed, rounded-up ETA, percent clamping, age
  eviction, count limits, generation reset, stale callback ignore, byte and
  clock rollback reset, and partial-resume baselines.
- Overflow-safe `BigInteger` arithmetic for percent, speed, and ETA.
- Internal typed restore factory with exact update time and validation for
  generation, future samples, monotonic samples, current progress, active
  sample freshness, age, and count.
- Shared presentation for every phase, including real progress, network,
  speed, ETA, `Calculating ETA`, Retry, and Cancel data.
- Grammar-safe singular and plural byte and second units.
- Privacy tests cover nested values and URL, Android URI, file URI, content
  URI, Windows path, Android data path, Android storage path, credential,
  bearer, API-key, prompt, and correction-text canaries.

## Focused GREEN proof

The focused command above passed:

- `DownloadTelemetryCalculatorTest`: 31 passed.
- `DownloadTelemetryPresentationTest`: 18 passed.
- Total: 49 passed, 0 failed, 0 errors, 0 skipped.

## Full build proof

Exact dual-variant command:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:ANDROID_HOME='C:\Users\stuff\AppData\Local\Android\Sdk'
& 'C:\Users\stuff\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' --no-daemon --max-workers=2 :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

Result:

- Command exit: 0 in 90.1 seconds.
- Debug unit suite: 497 passed, 0 failed, 0 errors, 0 skipped.
- Release unit suite: 497 passed, 0 failed, 0 errors, 0 skipped.
- Debug lint: 0 errors, 101 advisory warnings.
- Release lint: 0 errors, 101 advisory warnings.
- Debug, unsigned release, and debug instrumentation APKs assembled.
- Instrumentation compiled only. No device test ran in Task 3D.

Artifact SHA-256 values:

```text
app-debug.apk
1E6805E3D5CECE66C1DF636F59AA2E251A38FD1EC16EC3EF605A0340C07D18D2

app-release-unsigned.apk
9D982B80F4AFBAABEEAD3787F549C54986CAEF016210614A6D70DB4D9F5463FB

app-debug-androidTest.apk
4CE967257851F470F1A3D239D4A59C8867E0B8CE1D52A40AEF25A433FCB2FAC0
```

## Additional gates

- Explicit model and product-contract tests: 53 passed, 0 failed.
- Identity, import, installer, and release-manifest Pester tests: 30 passed,
  0 failed after the required elevated rerun. The first sandboxed run had two
  permission-only fixture failures and is not counted as a pass.
- Android brand generation check: passed.
- Local security gate: passed.
- Task 3D no-em-dash scan: passed.
- `git diff --check`: passed.
- Public telemetry API exposes no Android type.

## Files

```text
A app/src/main/java/com/echolink/mobile/DownloadTelemetry.kt
A app/src/main/java/com/echolink/mobile/DownloadTelemetryPresentation.kt
A app/src/test/java/com/echolink/mobile/DownloadTelemetryCalculatorTest.kt
A app/src/test/java/com/echolink/mobile/DownloadTelemetryPresentationTest.kt
A .superpowers/sdd/2026-08-04-manuscript-android-v2-reliability-loop/task-3d-report.md
```

## Deferred to Task 3E and later

Task 3E must add persistence encoding and decoding, WorkManager integration,
worker progress updates, rotation and restart recovery, interrupted transfer
resume, network-loss handling, and notification state. It must use
`DownloadTelemetryRestoreFactory` for restored typed data and reject impossible
persisted values before reconstruction.

Task 3F must bind the shared presentation to the real app and notification UI.
Neither task may accept raw URLs, paths, credentials, prompts, or user writing
as telemetry.
