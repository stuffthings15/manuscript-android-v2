# Task 4 external accessibility harness report

Date: 2026-08-04

## Scope and preserved RED evidence

The prior same-target suite instrumented `com.remote.Manuscript.v2.debug`.
Android force-stopped that target before instrumentation, killing the process
which hosts `ManuscriptAccessibilityService`. The recorded RED result was 0/5
lifecycle tests and 0/1 product-contract test: the component remained listed
in `enabled_accessibility_services`, but `accessibility_enabled=0`, Bound
services was empty, Crashed services contained Manuscript, and no
`ManuscriptReview` events appeared. Re-enabling during that same run did not
bind the service. Those results made the original structural-locator failures
non-diagnostic.

## Implementation

- Added `:external-fixture`, application ID `com.remote.Manuscript.v2.fixture`,
  compile/target SDK 36, min SDK 26, Java 17, AndroidX runner 1.7.0, JUnit
  extension 1.3.0, and AndroidX Orchestrator 1.6.1.
- Moved `ExternalEditorLifecycleFixtureDeviceTest`,
  `S23ProductContractDeviceTest`, and `S23ExternalEditorFixtureActivity` out
  of `:app`; invalid same-target copies no longer exist.
- The fixture owns the Activity. It closes Activities through a real physical
  tap on a Finish control; it never force-stops the instrumentation target.
- The test support verifies at each behavior test and after each test that the
  target is the fixture (not Manuscript), the exact V2 component is enabled,
  global accessibility is on, Manuscript is bound, and it is absent from
  Crashed services.
- User-visible strings are resolved from the installed Manuscript package by
  resource name. The product contract requires a real installed
  `no_backup/qwen3_models/*.gguf` path and requires the correction panel; Model
  Setup cannot pass.
- The fixture requests interactive windows, uses its own TYPE_APPLICATION root
  for structural proof, keeps coordinate gestures, and retains lifecycle,
  duplicate-ID, transient, empty, password, normal, multiline, target-loss,
  detach, package-switch, 300 ms disappearance, 500 ms return, and IME checks.

## Build and install verification

Passed:

```text
:external-fixture:assembleDebug
:external-fixture:assembleDebugAndroidTest
:app:assembleDebugAndroidTest
BUILD SUCCESSFUL (142 actionable tasks; 34 executed, 108 up-to-date)
```

Installed with `adb -s emulator-5554 install -r -t`:

```text
external-fixture-debug.apk
external-fixture-debug-androidTest.apk
```

No app was uninstalled, no data was cleared, and no Play operation occurred.

## Device prerequisite and post-run proof

Immediately before each final run, only this component was enabled:

```text
com.remote.Manuscript.v2.debug/com.echolink.mobile.ManuscriptAccessibilityService
accessibility_enabled=1
```

Before and after the final external instrumentation run, `dumpsys accessibility`
reported Manuscript in Bound services and `Crashed services:{}`. Logcat also
contained `ManuscriptReview` external-trigger down, release, click, and
`review_block=1/1;tone=PROOFREAD` records during the external run. The original
same-target service crash did not recur.

## Final external instrumentation result

Command:

```text
adb -s emulator-5554 shell am instrument -w -r com.remote.Manuscript.v2.fixture.test/androidx.test.runner.AndroidJUnitRunner
```

Exact total: 6 run, 0 passed, 6 failed, 0 skipped.

1. `triggerVisibleSettingsSwitchMeetsLifecycleTimings` — trigger did not meet
   the required pre-switch visibility assertion.
2. `panelOpenSettingsSwitchMeetsLifecycleTimings` — trigger did not meet the
   required pre-switch visibility assertion.
3. `duplicateViewIdRowsHaveDistinctStableStructuralLocators` — generated first
   structural field was unavailable while the product First-launch check window
   was active.
4. `everyFixtureModeObservesActualManuscriptTriggerAndPanelBehavior` — normal
   trigger visibility assertion failed.
5. `detachedAndTargetLossModesRemoveTheActualManuscriptSurface` — Close
   remained visible after a lifecycle action.
6. `generatedFieldsFollowTheLockedTriggerAndKeyboardContract` — trigger touch
   did not open the actual correction panel.

## Concern and next gate

This is now a valid external harness: fixture instrumentation did not crash or
unbind Manuscript. The six failures are product/runtime findings or the
existing First-launch check window interfering with application-root
observation; they are no longer attributable to same-target instrumentation.
Do not clear data or dismiss/change that user-state dialog automatically. The
next task should capture bounded event-to-identity diagnostics with this live
fixture and decide the production structural/overlay behavior separately.

## Round 1 harness review fixes

### Review fixes applied

- Exact V2 health proof now requires the enabled setting to contain only
  `com.remote.Manuscript.v2.debug/com.echolink.mobile.ManuscriptAccessibilityService`,
  exactly one Bound accessibility service, `Crashed services:{}`, and an exact
  `dumpsys activity services` record for that component with `hasBound=true`.
- Trigger, Close, resize-handle, and panel assertions now search only a
  `TYPE_ACCESSIBILITY_OVERLAY` whose root and matching node package are
  `com.remote.Manuscript.v2.debug`. Fixture controls separately search only the
  fixture `TYPE_APPLICATION` root.
- `productSurfaceMeetsExpectation` now returns whether the requested visibility
  expectation was met. The transient contract correctly requires false after
  the clear delay.
- Product acceptance now injects real `ACTION_DOWN`, `ACTION_MOVE`, and
  `ACTION_UP` coordinates on the V2 `resize_review_panel` handle. It captures
  the exact V2 overlay height and requires the first upward gesture to enlarge
  it.
- Product acceptance also requires an actual `TYPE_INPUT_METHOD` window and an
  editable focused fixture field before opening the panel and after closing it.

### TDD and build evidence

The first Round 1 device run was RED because the stricter empty crash-section
parser treated `Crashed services:{}` as `:{}`. The parser was corrected to
normalize that colon. The final build passed:

```text
:external-fixture:assembleDebug
:external-fixture:assembleDebugAndroidTest
BUILD SUCCESSFUL (63 actionable tasks; 4 executed, 59 up-to-date)
```

Both fixture APKs were installed with `adb install -r -t` on `emulator-5554`.
Only the V2 component was enabled before the final run.

### Final Round 1 device result

Exact total: 6 run, 0 passed, 6 failed, 0 skipped.

The final post-run evidence had one Bound Manuscript service, an empty Crashed
services set, and the exact V2 Activity service record with `hasBound=true`.
The V2 overlay identity check is live: the empty/password contract failures
reported an unexpected V2 `Open Manuscript for this text field` surface rather
than accepting an ambiguous copied string.

1. `triggerVisibleSettingsSwitchMeetsLifecycleTimings` — fixture Switch action
   was unavailable after V2 trigger visibility.
2. `panelOpenSettingsSwitchMeetsLifecycleTimings` — V2 Close did not satisfy
   the panel-open assertion.
3. `duplicateViewIdRowsHaveDistinctStableStructuralLocators` — first fixture
   structural field was unavailable.
4. `everyFixtureModeObservesActualManuscriptTriggerAndPanelBehavior` — V2
   trigger was present for the empty-field absence contract.
5. `detachedAndTargetLossModesRemoveTheActualManuscriptSurface` — V2 Close did
   not appear after triggering the panel.
6. `generatedFieldsFollowTheLockedTriggerAndKeyboardContract` — V2 trigger was
   present for the empty-field absence contract before the panel/IME path.

The expanded-panel swipe and IME-before/after assertions did not execute in
this device state because the exact V2 empty-field failure stops the product
contract first. Their source-level contract is present; live panel/IME proof
remains blocked by the listed V2 runtime failures and is not claimed.

### Commit review disposition

Review Critical 4 is resolved by later explicit user authority: the user
command `commit` authorized Task 4 commit `1a1f104`. This Round 1 change is a
separate fix commit; the authorized Task 4 commit was not reverted.

### Resize-gesture correction

The resize handle is only 24dp high. The gesture now starts at its exact center
and moves upward by `min(300px, available screen space)`, then still requires
the exact V2 overlay height to increase. The rebuilt test APK passed assembly.
The follow-up device suite remained 6 run, 0 passed, 6 failed, 0 skipped; it
still reaches the earlier exact V2 empty-field failures before the resize path.
Post-run accessibility state remained Bound with `Crashed services:{}`.
