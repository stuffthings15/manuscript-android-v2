# Task 3C report: review lifecycle integration fix round

## Result

The review surface lifecycle now owns normal close, invalidation, restoration,
and delayed replacement verification. MainActivity no longer steals focus after
the controller closes a review. The accessibility service uses stable,
content-free field identity, revalidates reconnects, tracks every replacement
verification callback, and reacquires the focused editor once per restoration
flow.

This report covers four independent fix rounds after source commit
`2709cd8f00668b30410110cf454ab101a62866ef`. The latest fix-round commit is
this commit.

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
- Fix round 2 first failed compilation because semantic drawing-order identity,
  `FocusedReviewTargetPolicy`, `ReviewKeyboardRestore`, and its scheduler did
  not exist.
- The first fix-round 2 GREEN attempt exposed one stale reconnect expectation.
- A separate rotation RED test proved window-bound identity changed after
  recreation.
- The first full fix-round 2 gate passed 428 tests and failed one stale source
  assertion that still required the removed target-comparison expression.
- Fix round 3 first failed compilation because the hierarchy locator, package
  change policy, reconnect eligibility resolver, and updated identity API did
  not exist.
- The architecture audit RED run then failed compilation because nonblank API
  33 `uniqueId`, explicit `AdoptCurrent` and `RestoreExact` modes, verified
  application-window routing, and their contracts did not exist.
- The first architecture GREEN attempt passed 44 focused tests and failed one
  pending exact-restoration reconnect test. The controller still suppressed
  reconnect while `RestoringFocus` instead of cancelling exact restoration.
- Fix round 4 first failed compilation because verified event-window package
  routing, nullable proven locators, root-based traversal, and fail-closed
  identity did not exist.
- The round 4 fixture also failed compilation because the resolver still
  accepted one node instead of a verified application root plus focused leaf.
- Audit found that the first duplicate-ID fixture passed two unfocused nodes at
  once. The corrected compiled test now taps and proves each field separately.

## Implemented

- MainActivity treats focus loss and navigation as invalidation. Normal close
  delegates focus and keyboard restoration to the shared controller only.
- Deferred target clearing dispatches `TargetLost` through the executor before
  clearing retained target state.
- Service reconnect always enters the controller's validation and reacquisition
  path.
- Every identity first requires a fresh, focused, editable leaf and a complete
  path from exactly one verified `TYPE_APPLICATION` window root. On API 33 and
  newer, a nonblank `AccessibilityNodeInfo.uniqueId` is then the identity key
  within the package. On older devices, or when `uniqueId` is blank, the
  fallback combines package, accessibility window ID, complete child-index
  path, parent classes, leaf class, and view ID when present. Repeated-row
  duplicate view IDs therefore remain distinct.
- The fallback excludes user text and bounds. It stays stable when keyboard or
  layout changes preserve the same window and hierarchy path. A recreated
  window or changed hierarchy strictly invalidates the fallback. Rotation is
  not claimed stable because it may recreate the accessibility window or tree.
- `AccessibilityNodeStructuralLocator` proves actual parent links, unique child
  indices, the top root, and a fresh root-to-leaf replay. It fails closed on
  missing links, cycles, tree changes, package or window mismatch, depth above
  32, or more than 512 inspections. Caller-owned root and focus nodes are not
  recycled. Copied nodes are released where platform recycling is required.
- Drawing order is not captured by the locator, so layout-only reordering
  cannot alter identity when the root-to-leaf child-index path stays fixed.
- `FieldStructuralLocator` has a private constructor and private-consistent
  copy. Its internal proven factory has one enforced production caller: the
  accessibility resolver.
- Reconnect validation while `TriggerVisible` now keeps the same eligible
  target, adopts a new eligible target, and hides for missing, password, or
  ineligible results. Completed repeated reconnects revalidate again.
- Reconnect uses explicit `AdoptCurrent` and may adopt a focused eligible field
  in another package. Close and keyboard restoration use `RestoreExact` and
  reject any different field or package. Reconnect while an attached or
  restoring review exists invalidates that surface before any later adoption.
- Confirmed application switches are accepted only from verified
  `TYPE_WINDOW_STATE_CHANGED` application event window and its matching root
  package. A stale active root cannot override the event window. The production
  path cancels the transient grace period and dispatches hiding before focused
  node lookup. IME and overlay noise cannot use this path. Runtime timing is not
  claimed in Task 3C.
- One immutable verified `ReviewTargetId` is created during focused-tree
  acquisition and stored in `Target`. Retained nodes never recompute identity.
- Retained accessibility nodes are accepted only while input-focused. A moved
  focus cannot restore or replace text in the old attached editor.
- MainActivity keyboard restoration uses a tracked named runnable. Invalidation
  and destroy cancel it; execution requires the matching visible target and
  current eligibility.
- Restoration performs one focused-node reacquisition. Later restore effects
  validate that same current node instead of reacquiring repeatedly.
- `TrackedDelayedWork` assigns generation tokens, records every delayed
  replacement verification callback, cancels previous generations, and makes
  late callbacks inert.
- Close, invalidation, failure, success, and destroy cancel tracked replacement
  work. Destroy also cancels keyboard, trigger-press, deferred-clear, and
  retained-input work.
- Compiled MainActivity instrumentation sends real pointer input to an actual
  review card and is prepared to measure view disappearance and trigger return.
- Compiled external fixture instrumentation polls the complete empty and
  password observation windows, opens the actual panel before detached or
  target-loss invalidation, counts trigger descriptions after Settings return,
  requires zero Manuscript triggers throughout its Settings observation window,
  pointer-taps two duplicate-ID fields under different parents, derives each
  locator from fresh root and focused nodes, retaps the first field to prove
  stability, and
  records an explicit prerequisite receipt if the accessibility service is
  unavailable.
- The fixture records `onStop` and `onResume`. Separate trigger-visible and
  panel-open package-switch tests use fixed deadlines started before the
  Settings tap and Back gesture, require ordered disappearance and return, and
  require exactly one stable trigger after returning.
- No Task 3C instrumentation test uses `performClick` or an accessibility click
  fallback.

## GREEN evidence

Latest focused lifecycle and instrumentation compilation gate:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:ANDROID_HOME='C:\Users\stuff\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=2 :app:testDebugUnitTest --tests com.echolink.mobile.ExternalFieldIdentityTest --tests com.echolink.mobile.ExternalPackageChangePolicyTest --tests com.echolink.mobile.FocusedReviewTargetPolicyTest --tests com.echolink.mobile.ReviewSurfaceControllerTest --tests com.echolink.mobile.ReviewSurfaceEffectExecutorTest --tests com.echolink.mobile.ReviewSurfaceHostIntegrationSourceTest :app:assembleDebugAndroidTest
```

Latest result: 46 focused tests passed. These are 8 identity tests, 3 package
change policy tests, 3 focused-target policy tests, 15 controller tests, 13
executor tests, and 4 host integration source tests. The debug instrumentation
APK compiled. None of its instrumentation tests executed.

Full dual-variant gate:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

Results:

- Debug unit tests: 442 passed, 0 failed, 0 errors, 0 skipped.
- Release unit tests: 442 passed, 0 failed, 0 errors, 0 skipped.
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
- Full-window absence polling for empty and password fields.
- Panel-first detached and target-loss invalidation.
- Exact single-trigger counting after returning from Settings.
- Zero-trigger observation throughout Settings before returning.
- Actual hierarchy-locator comparison between two duplicate-resource-ID fields
  beneath different parents, including same-field stability after refocus.

Therefore Task 3C has no runtime proof that a transient trigger never flashed,
that an actual overlay panel closed, or that exactly one trigger returned. The
tests that require those observations compile, but their assertions have not
run on an emulator or device.

Emulator and physical S23 execution, service enablement, gesture timing, and
content-free device evidence remain Task 4. This task did not install an APK,
touch Version 1, access a device, or publish to Google Play.

## First fix-round file set

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

## Second fix-round file set

```text
M app/src/androidTest/java/com/echolink/mobile/ExternalEditorLifecycleFixtureDeviceTest.kt
M app/src/main/java/com/echolink/mobile/ExternalFieldIdentity.kt
A app/src/main/java/com/echolink/mobile/FocusedReviewTargetPolicy.kt
M app/src/main/java/com/echolink/mobile/MainActivity.kt
M app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt
A app/src/main/java/com/echolink/mobile/ReviewKeyboardRestore.kt
M app/src/main/java/com/echolink/mobile/ReviewSurfaceController.kt
M app/src/test/java/com/echolink/mobile/AcrossAppWritingSourceTest.kt
M app/src/test/java/com/echolink/mobile/ExternalFieldIdentityTest.kt
A app/src/test/java/com/echolink/mobile/FocusedReviewTargetPolicyTest.kt
A app/src/test/java/com/echolink/mobile/ReviewKeyboardRestoreTest.kt
M app/src/test/java/com/echolink/mobile/ReviewSurfaceControllerTest.kt
M app/src/test/java/com/echolink/mobile/ReviewSurfaceEffectExecutorTest.kt
M .superpowers/sdd/2026-08-04-manuscript-android-v2-reliability-loop/task-3c-report.md
```

## Third fix-round file set

```text
M app/src/androidTest/java/com/echolink/mobile/ExternalEditorLifecycleFixtureDeviceTest.kt
M app/src/androidTest/java/com/echolink/mobile/S23ExternalEditorFixtureActivity.kt
A app/src/main/java/com/echolink/mobile/AccessibilityNodeStructuralLocator.kt
M app/src/main/java/com/echolink/mobile/ExternalFieldIdentity.kt
A app/src/main/java/com/echolink/mobile/ExternalPackageChangePolicy.kt
M app/src/main/java/com/echolink/mobile/FocusedReviewTargetPolicy.kt
M app/src/main/java/com/echolink/mobile/MainActivity.kt
M app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt
M app/src/main/java/com/echolink/mobile/ReviewSurfaceController.kt
M app/src/main/java/com/echolink/mobile/ReviewSurfaceEffectExecutor.kt
M app/src/test/java/com/echolink/mobile/ExternalFieldIdentityTest.kt
A app/src/test/java/com/echolink/mobile/ExternalPackageChangePolicyTest.kt
M app/src/test/java/com/echolink/mobile/FocusedReviewTargetPolicyTest.kt
M app/src/test/java/com/echolink/mobile/ReviewSurfaceControllerTest.kt
M app/src/test/java/com/echolink/mobile/ReviewSurfaceEffectExecutorTest.kt
M app/src/test/java/com/echolink/mobile/ReviewSurfaceHostIntegrationSourceTest.kt
M .superpowers/sdd/2026-08-04-manuscript-android-v2-reliability-loop/task-3c-report.md
```

## Fourth fix-round file set

```text
M app/src/androidTest/java/com/echolink/mobile/ExternalEditorLifecycleFixtureDeviceTest.kt
M app/src/androidTest/java/com/echolink/mobile/S23ExternalEditorFixtureActivity.kt
M app/src/main/java/com/echolink/mobile/AccessibilityNodeStructuralLocator.kt
M app/src/main/java/com/echolink/mobile/ExternalFieldIdentity.kt
M app/src/main/java/com/echolink/mobile/ExternalPackageChangePolicy.kt
M app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt
M app/src/test/java/com/echolink/mobile/ExternalFieldIdentityTest.kt
M app/src/test/java/com/echolink/mobile/ExternalPackageChangePolicyTest.kt
M app/src/test/java/com/echolink/mobile/ReviewSurfaceHostIntegrationSourceTest.kt
M .superpowers/sdd/2026-08-04-manuscript-android-v2-reliability-loop/task-3c-report.md
```
