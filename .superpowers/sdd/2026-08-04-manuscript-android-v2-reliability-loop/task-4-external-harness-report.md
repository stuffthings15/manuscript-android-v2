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

### Round 2 fixture readiness and cleanup

Added stable descriptions for every generated editor; each fixture block now
waits for its application root and expected editor. Multiline acceptance
physically taps that editor before requiring an IME window and focused field.
Cleanup now closes panels by real tap, finishes the fixture, uses Back for a
remaining V2 overlay, and fails if any V2 overlay remains. Round 2 RED first
showed 6/6 failures, then 2/6 passed after cleanup; the passing product path
exercised IME/panel behavior. The latest full run was 6 run, 1 passed, 5
failed; remaining failures are settings timing, detach Close persistence,
structural focus, and product trigger behavior. Lifecycle controls were moved
above IME-obscured editor rows; the fixture/test APKs rebuilt successfully.

### Exact commit d8c52e19 verification

Verified `d8c52e19cdf91ab3ceccbb9944f6f026307774e2` unchanged. Both fixture APKs
built successfully (63 Gradle tasks up-to-date) and installed with `adb install
-r -t` before all six tests ran.

Result: 6 run, 0 passed, 6 failed, 0 skipped.

- Both Settings tests failed the 300 ms disappearance assertion.
- Structural, lifecycle, and product-contract tests failed their start guard:
  `Test started with a stale V2 overlay.`

Post-run `dumpsys accessibility` reported Bound Manuscript and `Crashed
services:{}`. `dumpsys activity services` contained the exact V2 ServiceRecord
with `hasBound=true`. No code change or commit was made; the unrelated
`progress.md` modification remains preserved.

### Round 3 stable-overlay gate

The fixture now requires 750 ms continuously free of a V2 accessibility overlay
before each fixture start. After real Close/Back cleanup, a persistent overlay
causes `STALE_V2_OVERLAY` JUnit assumption skips rather than contaminated
failures. Build passed. Exact rerun: 6 run, 0 passed, 0 failed, 6 skipped.
Every skip reported the 750 ms stale-V2 message. Post-run Manuscript remained
Bound and `Crashed services:{}`.

### One-time clean-service precondition rerun

On commit `b76cfb5ce72dccd2374f7e19bf4038aee641d51c`, the V2 process alone was
force-stopped, accessibility enabled services were toggled then reapplied to
the exact V2 component, and global accessibility was set to 1. Before the
suite, the exact V2 Activity ServiceRecord reported `hasBound=true`, Bound
services contained Manuscript, and Crashed services was empty. No data or model
was cleared.

Ordered suite result: 6 run, 1 passed, 3 failed, 2 skipped.

1. `triggerVisibleSettingsSwitchMeetsLifecycleTimings` — failed 300 ms
   disappearance assertion.
2. `panelOpenSettingsSwitchMeetsLifecycleTimings` — failed 300 ms
   disappearance assertion.
3. `duplicateViewIdRowsHaveDistinctStableStructuralLocators` — failed because
   final fixture cleanup left a V2 overlay.
4. `everyFixtureModeObservesActualManuscriptTriggerAndPanelBehavior` — skipped
   at the 750 ms stale-V2 gate after test 3.
5. `detachedAndTargetLossModesRemoveTheActualManuscriptSurface` — skipped at
   the same stale-V2 gate.
6. `generatedFieldsFollowTheLockedTriggerAndKeyboardContract` — passed.

Post-run Bound and empty Crashed services were again captured. No code or Git
write was made for this evidence rerun.

### Task 4A focused cleanup and timing harness fix

RED on `148c1229972330f83eff310def9a43160ce6851b`: the isolated trigger-visible
Settings test satisfied its 300 ms disappearance and 500 ms return body, then
failed in `FixtureDeviceSupport.withFixture` because cleanup sampled overlay
absence immediately after Activity finish. Runtime logs showed
`ApplicationSwitched TriggerVisible -> Hidden` at finish; the product is
allowed 300 ms to remove the surface.

Cleanup now waits up to the product's 300 ms removal gate and then requires
750 ms continuous absence. If the real Back fallback is needed, the same
300 ms plus 750 ms proof is repeated. Focused JVM contracts cover removal at
the boundary, removal after the gate, and reappearance during stable absence:
3 tests, 0 failures, 0 errors, 0 skipped. Fixture APK, test APK, and unit-test
build completed successfully (70 tasks; 4 executed, 66 up-to-date). Only the
two fixture packages were installed with `adb install -r -t`.

Timing diagnosis found three harness issues without weakening a product gate:

- `CLEAR_TOP | SINGLE_TOP` made API 36 Settings reuse
  `SettingsHomepageActivity.onNewIntent`, which crashed. The fixture retains
  the proven no-flags `ACTION_SETTINGS` launch.
- Settings activation and Back navigation are recorded as OS navigation
  latency. The 300 ms removal deadline starts at verified Settings application
  activation; the 500 ms return deadline starts at verified fixture
  application activation.
- Zero-timeout surface checks are now true single samples, and deadline waits
  reject observations completed after the absolute deadline. Settings removal
  uses direct V2 accessibility-overlay presence rather than two expensive tree
  traversals. Physical panel Close now independently requires trigger return
  within 500 ms from the tap.

Final isolated six-method run against the then-installed product:
6 run, 2 passed, 4 failed, 0 skipped.

1. Broad lifecycle modes: failed only the new strict Close-to-trigger return,
   504 ms versus 500 ms.
2. Detached and target-loss: passed, 1/1, 11.094 s.
3. Trigger-visible Settings: passed, 1/1, 5.572 s. Recorded timings were
   Settings activation 1033 ms, overlay removal 1 ms, Back navigation 512 ms,
   and V2 trigger return 339 ms after verified fixture activation.
4. Panel-open Settings: failed V2 overlay removal at 505 ms versus 300 ms.
5. Duplicate structural locator: test body reached cleanup, then cleanup
   failed because the V2 overlay missed the 300 ms removal plus 750 ms stable
   absence contract.
6. Locked product contract: failed the existing assertion at
   `S23ProductContractDeviceTest.kt:40`.

Post-run state: exact enabled component
`com.remote.Manuscript.v2.debug/com.echolink.mobile.ManuscriptAccessibilityService`,
`accessibility_enabled=1`, Bound services contained Manuscript, Crashed
services was empty, and the exact Activity ServiceRecord reported
`hasBound=true`.

API 36 operational note: one earlier V2 `am force-stop` attempt cleared
`enabled_accessibility_services` to `null` and unbound the service even without
an explicit disable. Reapplying only the exact V2 component restored Bound and
`hasBound=true`; V2 was not force-stopped again. No product data or model was
cleared. A temporary Settings-only force-stop/prewarm experiment was not
encoded in the harness.

### Task 4A fix round 2: explicit transient action

The product exposed the transient trigger for about 357 ms, but the fixture's
900 ms `postDelayed` clear started at Activity creation. Instrumentation could
therefore reach the transient block after the field had already cleared. The
timer was removed. Transient mode now exposes a real button named
`Clear generated transient field`; the test first observes the V2 trigger,
physically taps that button, verifies the still-focused fixture editor is
empty, and then requires trigger removal from the resulting real text-change
event. No sleep is used for correctness and no product deadline changed.

The 501 ms Close result also included harness work before the physical tap:
the deadline timestamp was captured before locating the Close node. The node
is now located first, lookup time is reported separately, and the unchanged
500 ms deadline starts immediately before physical down-event injection.
Injection and observation overhead remain inside the strict gate; a real miss
still fails.

Focused local contracts: 6 tests, 0 failures, 0 errors, 0 skipped (3 explicit
fixture-action contracts plus the existing 3 cleanup timing contracts).
Fixture unit tests, fixture APK, and fixture test APK built successfully (70
tasks; 4 executed, 66 up-to-date). This round claims local harness/build proof
only; device acceptance remains a separate product-plus-fixture run.

### Task 4A fix round 3: snapshot-start deadline semantics

The exact product reached TriggerVisible and exposed its window before the
500 ms deadline, but the harness began a real accessibility-window traversal
before the deadline and rejected its true snapshot when traversal completed at
501-502 ms. Deadline sampling now records `sampleStartedAt` before the
condition acquires `automation.windows`. A true snapshot passes only when its
acquisition started on or before the absolute deadline. A sample starting
after the deadline fails without traversing. A false snapshot that completes
after the deadline also fails; no extra sample is accepted.

Boundary contracts prove: true start 499 ms / completion 502 ms passes; false
start 499 ms / completion 502 ms fails; true start 501 ms fails without
sampling. The 500 ms and 300 ms constants, physical input timestamp, and
fail-closed behavior are unchanged.

The transient self-proof now also waits at most 1000 ms for the exact fixture
editor to become focused and empty after physical injection. It still fails if
the action never completes, then separately requires product-trigger removal.

Focused local result: 9 tests, 0 failures, 0 errors, 0 skipped. Fixture unit
tests, fixture APK, and fixture test APK built successfully (70 tasks; 13
executed, 57 up-to-date). No device mutation was made because Task 4B retained
exclusive use of `emulator-5554`.

### Task 4A fix round 4: strict 10 ms sampling and transient proof

Exact product logs placed the normal Close overlay event at +263 ms, but the
harness slept from about 451 ms to the deadline and rejected at 507 ms without
sampling the already-visible surface. Strict deadline sampling now uses 10 ms
resolution. Snapshot-start semantics and the exact 300/500 ms deadlines remain
unchanged. A focused contract proves a condition becoming true at 480 ms is
sampled exactly at 480 ms before the 500 ms deadline; no grace is added.

`tapFixtureDescription` now waits boundedly to acquire the exact fixture node,
then performs the same physical injection. A missing structural or transient
action still fails; only the immediate node-acquisition race was removed.

Manual transient reproduction installed only the fixture target APK with
`adb install -r -t`. Local and installed SHA-256 both equaled
`5295c0830a0a85160deeddd869400181279f47d0f32ae29891541bcb175e26fa`,
confirming the current action contract was installed. Before the real tap, the
exact editor reported text `Generated transient text.` and `focused=true`.
After tapping `Clear generated transient field`, it remained focused and
reported text `Generated test text` equal to hint `Generated test text`.
Root cause: the action cleared correctly, but Android exposed the hint through
`AccessibilityNodeInfo.text`; the old self-proof mistook it for user text. The
bounded proof now accepts empty text or `isShowingHintText` with text equal to
the hint, while still requiring the exact editor to remain focused.

Focused local result: 11 tests, 0 failures, 0 errors, 0 skipped. Fixture unit
tests, fixture APK, and fixture test APK built successfully (70 tasks; 13
executed, 57 up-to-date). No product package was installed, edited, cleared, or
force-stopped. Full product-plus-fixture acceptance remains separate.

### Task 4A fix round 5: rendered user-view timing

API 36 accessibility-window enumeration is stale for the trigger-return clock:
the exact product entered `TriggerVisible` at 59-222 ms and emitted the Close
overlay state change at 263-300 ms, while `UiAutomation.windows` did not expose
the returned overlay until 501-504 ms. The 500 ms trigger-return assertions now
use rendered pixels and retain a separate eventual exact V2 overlay package plus
content-description assertion. Neither observation alone passes.

The expected crop is computed from the freshly reacquired fixture editor using
the product's verified 48 dp placement. On the 420 dpi emulator, multiline
editor bounds `[52,141][1028,424]` produce the exact 126 px crop
`[902,434][1028,560]`. The pure detector requires the official dark circular
frame, gold ring geometry, centered parchment manuscript, and dark ink while
allowing the shipped pulse alpha/scale. Five pixel contracts cover opaque and
pulsed/scaled presence, blank absence, a near-match frame without the official
icon, and an adversarial palette checker. All pass.

Live capture cannot serve as a completion clock on this API 36 emulator:
`UiAutomation.takeScreenshot()` completed at 987 ms, shell PNG screencap plus
in-memory decode completed at 1,993 ms, and full 10,368,016-byte raw RGBA
screencap plus decode completed at 2,175 ms. Sample start alone was not accepted.

The final harness therefore starts a 3-second, 4 Mbps `screenrecord` before the
physical action, stores only generated fixture/Settings pixels under the
fixture external-files directory, and analyzes the encoded video afterward.
`MediaExtractor` supplies exact variable-frame sample timestamps and
`MediaMetadataRetriever` decodes those frames. The first material change in the
verified transition region is the user-visible transition; the official trigger
must occur within the next unchanged 500 ms. Retrospective decode duration is
irrelevant to the recorded presentation timestamps. The physical tap, 300 ms
tree removal gate, and eventual exact V2 identity checks remain unchanged.

Two isolated Close reproductions were strictly RED. The final exact-PTS run
encoded 14 frames, showed panel disappearance at 1,185 ms, and first showed the
official trigger at 2,229 ms: 1,044 ms later. An earlier generated-fixture video
also showed the crop blank through transition +500 ms and first positive later;
exact encoded PTS bracketed panel disappearance by 844 ms, trigger still absent
at 1,269 ms, and first present at 1,453 ms, a minimum 609 ms delay. The six-test
suite was not claimed after the first required test failed.

Content-free manual crops are under `C:\tmp`: `round5-close-transition.png`,
`round5-close-plus100.png`, `round5-close-plus300.png`, and
`round5-close-plus500.png` share SHA-256
`7095057c9094639ff5d305190ee243147ee081ddca86ec19e6a198a31128c8a2`;
`round5-close-first-positive.png` is
`4894a094ccea30fb6b4ef7c476bb94e4c18dc6288ed6640c8c6cdbd527a02099`.
The source generated-fixture MP4 is
`d87af053af9d3d45cd5814c07f27a644cc37770edf5016271643474ec3c65184`.

Final local result: 16 tests, 0 failures, 0 errors, 0 skipped. Fixture unit
tests, fixture APK, and fixture test APK built successfully (70 tasks; 12
executed, 58 up-to-date). No product source, package, data, model, or service
state was changed by this round.
