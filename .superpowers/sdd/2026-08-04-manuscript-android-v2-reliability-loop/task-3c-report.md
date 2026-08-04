# Task 3C report: review lifecycle integration fix round

## Result

The review surface lifecycle now owns normal close, invalidation, restoration,
and delayed replacement verification. MainActivity no longer steals focus after
the controller closes a review. The accessibility service uses stable,
content-free field identity, revalidates reconnects, tracks every replacement
verification callback, and reacquires the focused editor once per restoration
flow.

This report covers the independent fix round after source commit
`2709cd8f00668b30410110cf454ab101a62866ef`. The fix-round commit is this
commit.

## RED evidence

- New unit tests first failed to compile because `ExternalFieldIdentity`,
  `FieldBounds`, `TrackedDelayedWork`, and `DelayedWorkScheduler` did not exist.
- The actual fixture instrumentation test first failed to compile because the
  fixture exposed no `resumeCount` or `stopCount` lifecycle observations.
- The focused lifecycle suite then failed one reconnect expectation because the
  old executor silently preserved `TriggerVisible` instead of revalidating the
  focused editor.
- Source integration tests reproduced manual MainActivity focus restoration,
  the reconnect shortcut, object-hash field identity, and untracked delayed
  replacement verification.

## Implemented

- MainActivity treats focus loss and navigation as invalidation. Normal close
  delegates focus and keyboard restoration to the shared controller only.
- Deferred target clearing dispatches `TargetLost` through the executor before
  clearing retained target state.
- Service reconnect always enters the controller's validation and reacquisition
  path.
- `ExternalFieldIdentity` uses package, window, view ID, class, and bounds. It
  stores no field text and does not use `AccessibilityNodeInfo.hashCode()`.
- Restoration performs one focused-node reacquisition. Later restore effects
  validate that same current node instead of reacquiring repeatedly.
- `TrackedDelayedWork` assigns generation tokens, records every delayed
  replacement verification callback, cancels previous generations, and makes
  late callbacks inert.
- Close, invalidation, failure, success, and destroy cancel tracked replacement
  work. Destroy also cancels keyboard, trigger-press, deferred-clear, and
  retained-input work.
- MainActivity instrumentation now closes an actual review card with real
  pointer input and observes actual view disappearance and trigger restoration.
- External fixture instrumentation observes the real Manuscript trigger and
  panel, covers normal, multiline, empty, password, target loss, and package
  switching, and records an explicit prerequisite receipt if the accessibility
  service is unavailable.
- The fixture records `onStop` and `onResume`; the package-switch case requires
  exactly `com.android.settings` before returning to Manuscript.
- No Task 3C instrumentation test uses `performClick` or an accessibility click
  fallback.

## GREEN evidence

Focused lifecycle and instrumentation compilation gate:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 :app:testDebugUnitTest --tests com.echolink.mobile.ReviewSurfaceEffectExecutorTest --tests com.echolink.mobile.ReviewSurfaceHostIntegrationSourceTest --tests com.echolink.mobile.TrackedDelayedWorkTest --tests com.echolink.mobile.ExternalFieldIdentityTest :app:assembleDebugAndroidTest
```

Result: 17 focused tests passed and the debug instrumentation APK compiled.

Full dual-variant gate:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

Results:

- Debug unit tests: 420 passed, 0 failed, 0 errors, 0 skipped.
- Release unit tests: 420 passed, 0 failed, 0 errors, 0 skipped.
- Debug lint: 101 advisory issues, 0 errors.
- Release lint: 101 advisory issues, 0 errors.
- Debug APK, unsigned release APK, and debug instrumentation APK assembled.
- `git diff --check` passed.
- Forbidden node-hash identity, reconnect shortcut, and direct delayed
  replacement-verification patterns are absent.

## Compiled versus executed

Executed locally:

- Focused pure lifecycle tests.
- Complete debug and release unit suites.
- Debug and release lint.
- Debug, release, and debug instrumentation assembly.

Compiled only, not executed in Task 3C:

- Actual MainActivity close-within-300-ms and trigger-within-500-ms test.
- Actual accessibility trigger and panel observations for all external fixture
  modes.
- Actual Settings package switch with fixture stop and resume observations.
- Accessibility-service prerequisite receipt behavior.

Emulator and physical S23 execution, service enablement, gesture timing, and
content-free device evidence remain Task 4. This task did not install an APK,
touch Version 1, access a device, or publish to Google Play.

## Exact fix-round file set

```text
M app/src/androidTest/java/com/echolink/mobile/ExternalEditorLifecycleFixtureDeviceTest.kt
M app/src/androidTest/java/com/echolink/mobile/ReviewSurfaceLifecycleDeviceTest.kt
M app/src/androidTest/java/com/echolink/mobile/S23ExternalEditorFixtureActivity.kt
A app/src/main/java/com/echolink/mobile/ExternalFieldIdentity.kt
M app/src/main/java/com/echolink/mobile/MainActivity.kt
M app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt
M app/src/main/java/com/echolink/mobile/ReviewSurfaceEffectExecutor.kt
A app/src/main/java/com/echolink/mobile/TrackedDelayedWork.kt
A app/src/test/java/com/echolink/mobile/ExternalFieldIdentityTest.kt
M app/src/test/java/com/echolink/mobile/ReviewSurfaceEffectExecutorTest.kt
M app/src/test/java/com/echolink/mobile/ReviewSurfaceHostIntegrationSourceTest.kt
A app/src/test/java/com/echolink/mobile/TrackedDelayedWorkTest.kt
A .superpowers/sdd/2026-08-04-manuscript-android-v2-reliability-loop/task-3c-report.md
```
