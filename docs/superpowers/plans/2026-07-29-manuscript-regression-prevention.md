# Manuscript Regression Prevention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lock Curtis's repeated Manuscript requirements into code, tests, device proof, install rules, and release gates so icon, accessibility, trigger, and correction fixes do not silently break again.

**Architecture:** Add one versioned product contract and one complaint-to-test ledger. Generate Android brand assets from the Windows master. Route the in-app and across-app triggers through one shared presentation and state policy. Put Samsung accessibility routing behind one probed route object. Install only through one S23-safe script. A release manifest binds the package, signer, commit, product contract, tests, and physical-device evidence to the exact artifact.

**Tech Stack:** Kotlin, Android SDK 36, Android AccessibilityService, JUnit 4, Android instrumentation, PowerShell, ADB, Python Pillow for image comparison, Git

## Global Constraints

- Windows `Manuscript\Windows\resources\manuscript.svg` and `manuscript.png` are the visual brand masters.
- Android must use the Windows near-black, parchment, antique-gold, cream, and brown palette. Teal, cyan, green, and lime are forbidden.
- Lock and test four separate surfaces:
  1. Android launcher icon.
  2. Manuscript in-app writing trigger.
  3. Across-app accessibility overlay trigger.
  4. Accessibility Settings guidance and row highlighting.
- The in-app and across-app trigger must have the same artwork, background, size policy, content description policy, and visible attention behavior.
- A supported non-password editable field with text must show the trigger.
- Empty fields and password fields must not show the trigger.
- The selected system keyboard stays active. Manuscript must not become an input method.
- Opening review hides or covers the keyboard area without stealing the target permanently. Closing review restores the target and keyboard.
- Every correction request must terminate. Success returns validated nonblank AI text. Failure returns an explicit visible error with Retry and keeps the original text unchanged. No spinner may run forever. No blank suggestion is allowed.
- `com.remote.Manuscript` is the permanent release package.
- `com.remote.Manuscript.debug` is the USB debug package.
- `com.remote.Manuscript.debug.test` is the instrumentation package.
- `com.echolink.manuscript` is legacy and must never be used for new builds, installs, tests, or Play uploads.
- Normal USB updates use the same package and signer with `adb install -r -t`. They must not uninstall the app or erase local AI files and accessibility authorization.
- Before every USB update, force-stop release, debug, and test packages and prove all three PIDs are gone.
- Never publish an artifact that differs from the artifact tested on the S23 Plus.
- Do not stage unrelated files from the dirty parent worktree. Every commit command must name its files.
- Do not resume the adaptive-model plan or the five-page GPT quality loop until Tasks 1 through 9 of this plan are green.
- Do not use an em dash in source, tests, documentation, evidence, or user-facing text.

---

## Evidence and Root Cause Findings

These findings are the baseline. They are not guesses.

| Problem | Current evidence | Root cause to remove |
| --- | --- | --- |
| Launcher icon looks different from Windows | The Windows PNG has a near-black rounded frame with a gold border. The S23 app drawer screenshot shows a cropped parchment-and-quill icon with the frame missing. | Android puts the full 1024 by 1024 artwork directly in an adaptive foreground. Samsung masks and scales the foreground. The existing test checks XML strings, not rendered pixels. |
| Icon color was declared fixed but Curtis still sees the wrong result | `WindowsIconParityTest` only checks path strings and hex values. | Source equality was treated as visual equality. There is no rendered golden or S23 screenshot gate. |
| Accessibility menu no longer flashes or highlights correctly | The S23 `SecSettings.apk` declares the Samsung Accessibility home row key as `accessibility_installed_services`. Current uncommitted code changed the key to `installed_service_key`. | OEM Settings keys and actions were changed without a physical-device contract test. |
| Samsung route keeps changing | `com.samsung.accessibility.ACCESSIBILITY_SETTINGS` resolves to the normal Accessibility homepage on this S23. `com.samsung.accessibility.installed_service` resolves to no activity. | Route selection was based on action resolution, but action resolution does not prove the destination screen or row highlight. |
| Writing triggers do not flash or pulse | Current `MainActivity` and `ManuscriptAccessibilityService` contain no animator or pulse controller for either trigger. | The attention behavior is not implemented or tested as a shared contract. |
| The same trigger behavior can break in one place while working in another | `MainActivity` and `ManuscriptAccessibilityService` each create and style their own `ImageButton`. | Duplicated construction, colors, padding, visibility, and lifecycle logic have drifted. |
| Across-app behavior can disappear after install work | The S23 currently has `com.remote.Manuscript.debug/com.echolink.mobile.ManuscriptAccessibilityService` enabled, but uninstalling or switching package identity resets Android accessibility authorization. | Earlier install loops mixed uninstall, reinstall, debug, release, and legacy package identities. |
| A passing build is not tied to the installed artifact | There is no single manifest binding commit, package, signer, artifact hash, contract version, S23 result, and screenshots. | Build success, source tests, device install, and public release can refer to different artifacts. |
| Fixes are hard to reproduce | The Android tree has many tracked modifications and untracked evidence files at once. | Work has crossed brand, accessibility, AI, test harness, and release lanes without a frozen baseline or complaint ledger. |

---

## Locked Product Contract

The implementation must encode these requirements in `docs/product-contract/manuscript-android-v1.json`.

```json
{
  "contractVersion": 1,
  "canonicalReleasePackage": "com.remote.Manuscript",
  "canonicalDebugPackage": "com.remote.Manuscript.debug",
  "legacyPackagesForbidden": ["com.echolink.manuscript"],
  "brand": {
    "windowsSvg": "../Windows/resources/manuscript.svg",
    "windowsPng": "../Windows/resources/manuscript.png",
    "palette": [
      "#17120D",
      "#D6A85F",
      "#F2D6A2",
      "#F7E7C6",
      "#E2B86F",
      "#8B5B2B",
      "#3B291C"
    ],
    "launcherMustPreserveOuterGoldFrame": true,
    "themedMonochromeIconAllowed": false
  },
  "trigger": {
    "touchTargetDp": 48,
    "visualSizeDp": 32,
    "requiresFocusedEditableText": true,
    "passwordFieldsAllowed": false,
    "emptyFieldsAllowed": false,
    "attentionAnimation": {
      "kind": "alpha_scale_pulse",
      "alphaFrom": 1.0,
      "alphaTo": 0.62,
      "scaleFrom": 1.0,
      "scaleTo": 1.08,
      "halfCycleMs": 700,
      "repeat": true,
      "respectRemoveAnimationsSetting": true
    }
  },
  "accessibilitySettings": {
    "samsungHomeRowKey": "accessibility_installed_services",
    "aospHomeRowKey": "user_installed_services_category",
    "serviceComponentClass": "com.echolink.mobile.ManuscriptAccessibilityService"
  },
  "correction": {
    "blankSuggestionAllowed": false,
    "infiniteLoadingAllowed": false,
    "originalTextChangesOnFailure": false,
    "terminalStates": ["success", "retryable_failure", "model_required"]
  }
}
```

The numeric pulse values are the first locked proposal. Curtis must approve the live S23 appearance in Task 5. After approval, future changes require a product-contract version increase and a new approved golden.

---

## Task 1: Freeze the Current Truth and Create the Regression Ledger

**Files:**

- Create: `docs/product-contract/manuscript-android-v1.json`
- Create: `docs/qa/manuscript-regression-ledger.md`
- Create: `docs/qa/manuscript-current-state-2026-07-29.json`
- Create: `tools/capture_manuscript_baseline.ps1`
- Create: `app/src/test/java/com/echolink/mobile/ManuscriptProductContractTest.kt`

### Step 1: Record the exact dirty baseline

- [ ] Make `capture_manuscript_baseline.ps1` read only from Git and ADB.
- [ ] Record:
  - branch and HEAD commit;
  - tracked modified files;
  - untracked Android source, test, evidence, and generated files in separate arrays;
  - installed Manuscript package names;
  - installed version code and version name;
  - signer digest;
  - S23 serial and model;
  - `accessibility_enabled`;
  - `enabled_accessibility_services`;
  - current Windows SVG and PNG SHA256 values;
  - current APK SHA256 when present.
- [ ] The script must refuse to write outside `docs/qa`.
- [ ] The script must not install, uninstall, enable, disable, launch, or stop anything.

Use this record shape:

```powershell
$record = [ordered]@{
    capturedAtUtc = [DateTime]::UtcNow.ToString('o')
    git = [ordered]@{
        branch = (git branch --show-current).Trim()
        head = (git rev-parse HEAD).Trim()
        modified = @(git diff --name-only)
        untracked = @(git ls-files --others --exclude-standard)
    }
    device = [ordered]@{
        serial = 'R5CW20R46WD'
        model = (& $adb -s 'R5CW20R46WD' shell getprop ro.product.model).Trim()
    }
}
```

### Step 2: Create the complaint-to-test ledger

- [ ] Add one row for every repeated user complaint.
- [ ] Each row must contain:
  - exact plain-language intent;
  - owning code path;
  - automated test;
  - S23 test;
  - evidence file;
  - first good commit;
  - latest verified artifact;
  - status.
- [ ] Add these initial rows:
  - Android launcher icon matches Windows color and style.
  - In-app trigger appears after real text.
  - Across-app trigger appears after real text.
  - Both triggers use the same artwork and pulse.
  - Password fields never show a trigger.
  - Across all apps can be enabled on S23.
  - Samsung Accessibility home highlights Installed apps.
  - Samsung Installed apps shows Manuscript and opens its service page.
  - Keyboard remains Gboard or Samsung Keyboard.
  - Review covers the keyboard area and restores the keyboard.
  - Every correction reaches a terminal answer.
  - USB update preserves app data and accessibility authorization.

### Step 3: Write a failing product-contract test

- [ ] Make `ManuscriptProductContractTest` parse the JSON with `kotlinx.serialization.json`.
- [ ] Assert the exact packages, palette, four surfaces, Samsung key, trigger sizes, terminal states, and no-em-dash rule.
- [ ] Assert the current Gradle `applicationId` matches the contract.
- [ ] Assert the legacy package does not appear in manifest, Gradle, install scripts, or Play upload scripts.

### Step 4: Verify red, then green

- [ ] Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:ANDROID_HOME='C:\Users\stuff\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.ManuscriptProductContractTest
```

- [ ] First failure must be the missing contract.
- [ ] Add the locked contract.
- [ ] Rerun and require a clean pass.

### Step 5: Reconcile current WIP before changing shared files

- [ ] Classify every dirty Android source file as:
  - current intended implementation;
  - generated artifact;
  - parked GPT campaign work;
  - unrelated user work;
  - obsolete duplicate.
- [ ] Keep `gpt56-full-page-unseen-v3.txt` and `LiveFullPageReviewDeviceTest.kt` parked and unstaged.
- [ ] Do not modify or stage `ManuscriptProductSourceTest.kt` until its preexisting changes are reviewed.
- [ ] Do not begin Tasks 2 through 10 until the source files that overlap `MainActivity.kt` and `ManuscriptAccessibilityService.kt` have a named baseline commit or an explicit preserved patch record.

### Step 6: Commit only contract and baseline files

- [ ] Run:

```powershell
git add -- docs/product-contract/manuscript-android-v1.json docs/qa/manuscript-regression-ledger.md docs/qa/manuscript-current-state-2026-07-29.json tools/capture_manuscript_baseline.ps1 app/src/test/java/com/echolink/mobile/ManuscriptProductContractTest.kt
git commit -m "docs(android): lock Manuscript product contract" --only -- docs/product-contract/manuscript-android-v1.json docs/qa/manuscript-regression-ledger.md docs/qa/manuscript-current-state-2026-07-29.json tools/capture_manuscript_baseline.ps1 app/src/test/java/com/echolink/mobile/ManuscriptProductContractTest.kt
```

---

## Task 2: Generate Android Brand Assets From the Windows Master

**Files:**

- Create: `tools/generate_android_brand_assets.py`
- Create: `app/src/main/res/drawable/ic_manuscript_launcher_foreground.xml`
- Create: `app/src/main/res/mipmap-mdpi/ic_launcher.png`
- Create: `app/src/main/res/mipmap-hdpi/ic_launcher.png`
- Create: `app/src/main/res/mipmap-xhdpi/ic_launcher.png`
- Create: `app/src/main/res/mipmap-xxhdpi/ic_launcher.png`
- Create: `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`
- Modify: `app/src/main/res/drawable/ic_manuscript.xml`
- Modify: `app/src/main/res/drawable/ic_manuscript_assist.xml`
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: `app/src/main/res/values/colors.xml`
- Replace: `app/src/test/java/com/echolink/mobile/WindowsIconParityTest.kt`
- Create: `evidence/brand/generated-brand-assets.json`

### Step 1: Write failing asset-provenance tests

- [ ] Replace XML substring assertions with:
  - exact Windows master hashes stored in the contract;
  - generated-file header containing the master SHA256;
  - exact allowed palette;
  - no tint, monochrome, theme attribute, or color outside the contract;
  - an adaptive foreground safe-zone transform;
  - non-adaptive PNGs for pre-API 26 launchers.
- [ ] Assert `ic_launcher.xml` uses `ic_manuscript_launcher_foreground`, not the edge-to-edge in-app vector.
- [ ] Assert the launcher foreground fits inside the center 66 by 66 portion of the 108 by 108 adaptive canvas.

Use this transform in the generated launcher vector:

```xml
<group
    android:scaleX="0.64"
    android:scaleY="0.64"
    android:translateX="184.32"
    android:translateY="184.32">
    <!-- Canonical 1024 by 1024 Windows paths -->
</group>
```

### Step 2: Verify the focused test fails

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.WindowsIconParityTest
```

- [ ] Confirm it fails because the current adaptive foreground has no safe-zone transform and the tests do not validate rendered assets.

### Step 3: Implement one deterministic generator

- [ ] Read `..\Windows\resources\manuscript.svg` and `manuscript.png`.
- [ ] Reject generation if:
  - either file is missing;
  - the SVG and PNG dimensions are not 1024 by 1024;
  - the SVG contains a color outside the contract palette;
  - the canonical hash differs from the contract without a contract-version change.
- [ ] Generate:
  - full-detail in-app vector;
  - full-detail assist vector;
  - safe-zone launcher foreground;
  - density-specific legacy PNGs;
  - a JSON provenance record under `evidence/brand/generated-brand-assets.json`.
- [ ] Make output byte-stable. A second run must produce zero Git diff.

### Step 4: Wire the adaptive icon correctly

- [ ] Use near-black for the adaptive background:

```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/manuscript_icon_background" />
    <foreground android:drawable="@drawable/ic_manuscript_launcher_foreground" />
</adaptive-icon>
```

- [ ] Do not add `<monochrome>`.
- [ ] Do not use `ic_manuscript_assist` as a launcher foreground.

### Step 5: Verify generation and source gates

- [ ] Run:

```powershell
python tools\generate_android_brand_assets.py --check
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.WindowsIconParityTest
.\gradlew.bat --no-daemon :app:lintRelease
git diff --check
```

### Step 6: Commit only brand files

- [ ] Stage and commit with explicit paths:

```powershell
git add -- tools/generate_android_brand_assets.py app/src/main/res/drawable/ic_manuscript.xml app/src/main/res/drawable/ic_manuscript_assist.xml app/src/main/res/drawable/ic_manuscript_launcher_foreground.xml app/src/main/res/mipmap-mdpi/ic_launcher.png app/src/main/res/mipmap-hdpi/ic_launcher.png app/src/main/res/mipmap-xhdpi/ic_launcher.png app/src/main/res/mipmap-xxhdpi/ic_launcher.png app/src/main/res/mipmap-xxxhdpi/ic_launcher.png app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml app/src/main/res/values/colors.xml app/src/test/java/com/echolink/mobile/WindowsIconParityTest.kt evidence/brand/generated-brand-assets.json
git commit -m "fix(android): derive brand assets from Windows master" --only -- tools/generate_android_brand_assets.py app/src/main/res/drawable/ic_manuscript.xml app/src/main/res/drawable/ic_manuscript_assist.xml app/src/main/res/drawable/ic_manuscript_launcher_foreground.xml app/src/main/res/mipmap-mdpi/ic_launcher.png app/src/main/res/mipmap-hdpi/ic_launcher.png app/src/main/res/mipmap-xhdpi/ic_launcher.png app/src/main/res/mipmap-xxhdpi/ic_launcher.png app/src/main/res/mipmap-xxxhdpi/ic_launcher.png app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml app/src/main/res/values/colors.xml app/src/test/java/com/echolink/mobile/WindowsIconParityTest.kt evidence/brand/generated-brand-assets.json
```

---

## Task 3: Add Rendered Icon Proof

**Files:**

- Create: `app/src/androidTest/java/com/echolink/mobile/RenderedBrandDeviceTest.kt`
- Create: `tools/compare_manuscript_icons.py`
- Create after approval: `app/src/androidTest/assets/golden/s23_launcher_approved.png`
- Create after approval: `app/src/androidTest/assets/golden/in_app_trigger_approved.png`
- Create after approval: `app/src/androidTest/assets/golden/across_app_trigger_approved.png`
- Modify: `docs/qa/manuscript-regression-ledger.md`

### Step 1: Write a failing rendered-device test

- [ ] Render `ic_manuscript`, `ic_manuscript_assist`, and the adaptive application icon to fixed-size PNG files.
- [ ] Record actual ARGB values, bounds, density, Android version, One UI version, and package version.
- [ ] Fail when:
  - outer gold frame is missing;
  - any forbidden brand color is present;
  - more than 2 percent of non-antialiased pixels fall outside the approved palette;
  - the icon content crosses the adaptive safe-zone boundary.

### Step 2: Add deterministic image comparison

- [ ] `compare_manuscript_icons.py` must compute:
  - palette distance in CIE Lab;
  - nontransparent content bounds;
  - edge-frame continuity;
  - structural similarity;
  - per-image SHA256.
- [ ] The script must fail on missing evidence. It may not silently create a golden.
- [ ] A new golden requires `--approve-golden` plus the contract version and current Git commit.

### Step 3: Capture live S23 screenshots

- [ ] Use the same installed APK that passed the source tests.
- [ ] Capture:
  - app drawer launcher icon;
  - Manuscript in-app trigger beside text;
  - overlay trigger in Samsung Notes;
  - overlay trigger in Google Messages.
- [ ] Curtis visually approves the launcher and both trigger surfaces once.
- [ ] Store only cropped UI evidence that contains no personal text.

### Step 4: Lock the approved S23 goldens

- [ ] Add the approved images and hashes to the contract.
- [ ] Future image changes require:
  - failing old golden;
  - contract version increase;
  - Curtis approval;
  - new golden and ledger entry.

### Step 5: Verify and commit

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.echolink.mobile.RenderedBrandDeviceTest
python tools\compare_manuscript_icons.py --contract docs\product-contract\manuscript-android-v1.json --evidence evidence\brand\s23
```

- [ ] Commit only the test, tool, approved goldens, contract hash update, and ledger update.

---

## Task 4: Build One Shared Trigger Presentation

**Files:**

- Create: `app/src/main/java/com/echolink/mobile/ManuscriptTriggerPresentation.kt`
- Create: `app/src/main/java/com/echolink/mobile/ManuscriptTriggerViewFactory.kt`
- Create: `app/src/test/java/com/echolink/mobile/ManuscriptTriggerPresentationTest.kt`
- Modify: `app/src/main/java/com/echolink/mobile/MainActivity.kt`
- Modify: `app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt`
- Modify: `app/src/test/java/com/echolink/mobile/AndroidUsabilitySourceTest.kt`
- Modify: `app/src/test/java/com/echolink/mobile/AcrossAppWritingSourceTest.kt`

### Step 1: Write failing shared-presentation tests

- [ ] Assert both callers request the same `ManuscriptTriggerPresentation`.
- [ ] Assert there is one drawable, one visual size, one touch target, one background, one padding value, and one pulse policy.
- [ ] Assert neither `MainActivity` nor `ManuscriptAccessibilityService` directly calls `setImageResource` for the writing trigger.

Use this public contract:

```kotlin
data class ManuscriptTriggerPresentation(
    val iconRes: Int = R.drawable.ic_manuscript_assist,
    val touchTargetDp: Int = 48,
    val visualSizeDp: Int = 32,
    val paddingDp: Int = 8,
    val backgroundColor: Int,
    val borderColor: Int,
    val pulse: TriggerPulseSpec,
)

class ManuscriptTriggerViewFactory(
    private val presentation: ManuscriptTriggerPresentation,
) {
    fun create(
        context: Context,
        contentDescription: String,
        onClick: () -> Unit,
    ): ImageButton
}
```

### Step 2: Verify red

- [ ] Run:

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.ManuscriptTriggerPresentationTest --tests com.echolink.mobile.AndroidUsabilitySourceTest --tests com.echolink.mobile.AcrossAppWritingSourceTest
```

- [ ] Confirm failure proves duplicate trigger construction.

### Step 3: Implement and route both surfaces

- [ ] Replace the in-app `ImageButton` construction with the factory.
- [ ] Replace the overlay `ImageButton` construction with the factory.
- [ ] Keep surface-specific content descriptions.
- [ ] Keep the 48 dp touch target and 32 dp visual icon.
- [ ] Keep the trigger non-focus-stealing.

### Step 4: Verify and commit

- [ ] Run focused tests, release tests, lint, and `git diff --check`.
- [ ] Commit the factory, presentation, both callers, and tests only.

---

## Task 5: Lock Trigger Visibility and Attention Behavior

**Files:**

- Create: `app/src/main/java/com/echolink/mobile/ManuscriptTriggerState.kt`
- Create: `app/src/main/java/com/echolink/mobile/TriggerPulseController.kt`
- Create: `app/src/test/java/com/echolink/mobile/ManuscriptTriggerStateTest.kt`
- Create: `app/src/test/java/com/echolink/mobile/TriggerPulseControllerTest.kt`
- Create: `app/src/androidTest/java/com/echolink/mobile/TriggerParityDeviceTest.kt`
- Modify: `app/src/main/java/com/echolink/mobile/WritingAssistUiPolicy.kt`
- Modify: `app/src/main/java/com/echolink/mobile/MainActivity.kt`
- Modify: `app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt`

### Step 1: Write the failing state matrix

- [ ] Cover these states:

```kotlin
enum class ManuscriptTriggerState {
    HIDDEN_NO_TEXT,
    HIDDEN_PASSWORD,
    HIDDEN_REVIEW_OPEN,
    VISIBLE_PULSING,
    VISIBLE_STATIC_REDUCED_MOTION,
}
```

- [ ] Test both surfaces against the same cases:
  - focused normal field plus text;
  - focused empty field;
  - password field;
  - focus lost;
  - correction panel open;
  - transient self-generated accessibility event;
  - window change;
  - service reconnect;
  - animator scale set to zero.
- [ ] Preserve the existing 300 ms target-clear grace for non-password transient events.
- [ ] Password fields must clear immediately.

### Step 2: Write failing pulse tests

- [ ] Assert:
  - alpha cycles from 1.0 to 0.62 and back;
  - scale cycles from 1.0 to 1.08 and back;
  - half-cycle is 700 ms;
  - repeat continues while visible;
  - hide, detach, panel-open, service destroy, and reduced-motion cancel animation;
  - showing an already-visible trigger does not create a second animator.

### Step 3: Implement pure state and one controller

- [ ] `MainActivity` converts editor facts into `ManuscriptTriggerState`.
- [ ] `ManuscriptAccessibilityService` converts node facts into the same state.
- [ ] Both pass state changes to `TriggerPulseController`.
- [ ] Never use flashing color changes. Use the locked gentle alpha and scale pulse.
- [ ] Respect Android's Remove animations or zero animator duration scale by showing a static trigger.

### Step 4: Add device parity proof

- [ ] In-app:
  - type one character;
  - wait for two alpha samples;
  - prove trigger stays attached and clickable.
- [ ] Across-app:
  - type one character in the device test editor;
  - wait for two alpha samples;
  - prove the same bounds, colors, pulse range, and click result.
- [ ] Stress 100 accessibility events and require one stable overlay window token.
- [ ] Prove password and empty fields show no trigger.

### Step 5: Get live appearance approval and lock it

- [ ] Show Curtis the in-app and external pulse on S23.
- [ ] If the pulse is too strong or weak, change only the contract values, rerun all focused tests, and recapture goldens.
- [ ] After approval, record the contract version and screenshot hashes in the ledger.

### Step 6: Commit only trigger-state files

- [ ] Use message:

```text
fix(android): lock shared trigger visibility and pulse
```

---

## Task 6: Make Samsung Accessibility Guidance Evidence Based

**Files:**

- Create: `app/src/main/java/com/echolink/mobile/AccessibilitySettingsRoute.kt`
- Create: `app/src/test/java/com/echolink/mobile/AccessibilitySettingsRouteTest.kt`
- Create: `app/src/androidTest/java/com/echolink/mobile/AccessibilitySettingsRouteDeviceTest.kt`
- Create: `tools/probe_samsung_accessibility.ps1`
- Modify: `app/src/main/java/com/echolink/mobile/AccessibilitySettingsGuide.kt`
- Modify: `app/src/main/java/com/echolink/mobile/MainActivity.kt`
- Modify: `app/src/test/java/com/echolink/mobile/AccessibilitySettingsGuideTest.kt`
- Modify: `docs/qa/manuscript-regression-ledger.md`

### Step 1: Lock facts from the physical S23

- [ ] The probe must record:
  - manufacturer, model, Android version, One UI version;
  - resolved activity for `android.settings.ACCESSIBILITY_SETTINGS`;
  - resolved activity for `com.samsung.accessibility.ACCESSIBILITY_SETTINGS`;
  - whether `android.settings.ACCESSIBILITY_DETAILS_SETTINGS` is launchable by Manuscript;
  - preference keys extracted from the installed `SecSettings.apk`;
  - UI hierarchy after each route;
  - whether the intended row is highlighted.
- [ ] On the current S23, treat these facts as expected:
  - Samsung home action resolves to `Settings$AccessibilitySettingsActivity`;
  - old `com.samsung.accessibility.installed_service` action does not resolve;
  - Samsung Accessibility home uses `accessibility_installed_services`;
  - AOSP category uses `user_installed_services_category`.

### Step 2: Write a failing route matrix

- [ ] Use:

```kotlin
data class AccessibilitySettingsRoute(
    val action: String,
    val highlightKey: String,
    val expectedDestination: Destination,
)

enum class Destination {
    ACCESSIBILITY_HOME,
    INSTALLED_APPS,
    MANUSCRIPT_SERVICE,
}
```

- [ ] Assert Samsung never uses `installed_service_key`.
- [ ] Assert action resolution alone is not considered destination proof.
- [ ] Assert the fallback still uses the correct row key.
- [ ] Assert the service component is exact and includes the active package.

### Step 3: Implement a two-stage guide

- [ ] Stage 1 opens Accessibility and highlights Installed apps.
- [ ] Stage 2 gives a persistent plain instruction: `Tap Installed apps, then Manuscript across-app writing helper.`
- [ ] If a direct public service-details action is available and launchable without privileged permission, use it.
- [ ] If it is not available, do not guess hidden Samsung actions. Keep the tested two-tap path.
- [ ] When the user returns, recheck the exact service component and update the in-app status immediately.
- [ ] If disabled, keep the Enable across all apps action visible.
- [ ] If enabled, show `Across all apps: On` and a `Settings` action.

### Step 4: Prove both Settings menu areas

- [ ] Capture a clean S23 flow:
  1. Manuscript Across all apps card.
  2. Accessibility home with Installed apps highlighted.
  3. Installed apps list with Manuscript visible.
  4. Manuscript service detail screen.
  5. Return to Manuscript with status On.
- [ ] Record UI hierarchy and screenshot for each step.
- [ ] Do not declare this fixed from an intent unit test.

### Step 5: Verify and commit

- [ ] Run focused unit tests, the route device test, full release unit tests, and lint.
- [ ] Commit only route, guide, caller, tests, probe, and ledger files.

---

## Task 7: Make USB Installation Preserve State

**Files:**

- Create: `tools/install_s23_manuscript.ps1`
- Create: `tools/tests/install_s23_manuscript.Tests.ps1`
- Modify: `tools/run_persistent_device_test.ps1`
- Modify: `README.md`
- Modify: `docs/qa/manuscript-regression-ledger.md`

### Step 1: Write failing Pester tests

- [ ] Test:
  - zero devices;
  - two devices;
  - wrong serial;
  - wrong model;
  - missing APK;
  - package mismatch;
  - signer mismatch;
  - release package installed while debug was requested;
  - PID still alive after force-stop;
  - accessibility disabled before install;
  - accessibility enabled before install but missing after install;
  - successful replacement install.
- [ ] Assert the script contains no `adb uninstall`, `pm uninstall`, or `pm clear`.

### Step 2: Implement the safe installer

- [ ] Require:

```powershell
param(
    [Parameter(Mandatory)]
    [ValidateSet('debug', 'release')]
    [string]$Variant,
    [string]$Serial = 'R5CW20R46WD',
    [string]$ExpectedModel = 'SM-S916U1'
)
```

- [ ] Resolve exactly one authorized device.
- [ ] Inspect APK package, version, and certificate before touching the phone.
- [ ] Capture preinstall:
  - enabled accessibility services;
  - selected keyboard;
  - package data directory existence;
  - installed signer and version;
  - local AI state without reading model content.
- [ ] Force-stop:
  - `com.remote.Manuscript`;
  - `com.remote.Manuscript.debug`;
  - `com.remote.Manuscript.debug.test`.
- [ ] Require empty `pidof` output for all three.
- [ ] Use only `adb install -r -t`.
- [ ] Reject `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and stop. Do not uninstall automatically.
- [ ] Verify:
  - package;
  - version;
  - signer;
  - update time;
  - process starts;
  - accessibility authorization is unchanged;
  - selected keyboard is unchanged.

### Step 3: Produce an install receipt

- [ ] Write `evidence/device/s23/install-receipt.json` with no private text.
- [ ] Include APK SHA256, commit, package, signer, version, pre/post accessibility state, pre/post keyboard, and timestamps.

### Step 4: Replace all manual install instructions

- [ ] `run_persistent_device_test.ps1` must call the safe installer.
- [ ] README and plan examples must stop recommending raw install commands for S23 acceptance.

### Step 5: Verify and commit

- [ ] Run Pester tests and one real debug update.
- [ ] Confirm accessibility stays enabled on the S23.
- [ ] Commit only installer, tests, runner, README, and ledger.

---

## Task 8: Guarantee a Terminal Correction Answer

**Files:**

- Create: `app/src/main/java/com/echolink/mobile/CorrectionTerminalResult.kt`
- Create: `app/src/main/java/com/echolink/mobile/CorrectionRequestWatchdog.kt`
- Create: `app/src/test/java/com/echolink/mobile/CorrectionRequestWatchdogTest.kt`
- Modify: `app/src/main/java/com/echolink/mobile/LocalCorrectionPipeline.kt`
- Modify: `app/src/main/java/com/echolink/mobile/OnDeviceGrammarEngine.kt`
- Modify: `app/src/main/java/com/echolink/mobile/MainActivity.kt`
- Modify: `app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt`
- Modify: `app/src/main/java/com/echolink/mobile/ProcessTextActivity.kt`
- Modify: `docs/superpowers/plans/2026-07-29-adaptive-model-selection.md`

### Step 1: Write the terminal-result matrix

- [ ] Use one sealed result:

```kotlin
sealed interface CorrectionTerminalResult {
    data class Success(
        val correctedText: String,
        val modelId: String,
        val elapsedMs: Long,
    ) : CorrectionTerminalResult

    data class RetryableFailure(
        val userMessage: String,
        val elapsedMs: Long,
    ) : CorrectionTerminalResult

    data object ModelRequired : CorrectionTerminalResult
}
```

- [ ] Test:
  - success;
  - blank model output;
  - unchanged output when errors remain;
  - model throws;
  - model callback never returns;
  - cancellation;
  - activity recreation;
  - service recreation;
  - two callbacks from a backend;
  - fallback success;
  - all backends fail.
- [ ] Exactly one terminal result is delivered.
- [ ] Failure keeps the original text and shows Retry.

### Step 2: Add a bounded watchdog

- [ ] The watchdog owns the timeout and once-only completion.
- [ ] No surface owns a separate timer.
- [ ] Loading must show elapsed time and a Cancel action.
- [ ] Timeout must end in a visible terminal state.
- [ ] The user must never see a blank correction or a disabled screen with no next action.

### Step 3: Route all three surfaces through the result

- [ ] Main editor, accessibility overlay, and Process Text use the same result renderer.
- [ ] Remove surface-specific interpretations of blank, error, and timeout.
- [ ] Accepted text must come from `Success.correctedText`.
- [ ] Deterministic cleanup may validate and protect text. It may not be presented as the AI correction.

### Step 4: Sequence the adaptive-model plan

- [ ] Update the adaptive-model plan to depend on this terminal contract.
- [ ] Do not implement hardware selection until this task is green.
- [ ] Keep the compact local fallback requirement.

### Step 5: Verify and commit

- [ ] Run focused failure tests, full unit tests, lint, and an S23 forced-timeout device test.
- [ ] Commit terminal result, watchdog, three surface integrations, tests, and the plan dependency only.

---

## Task 9: Build the Physical S23 Acceptance Matrix

**Files:**

- Create: `app/src/androidTest/java/com/echolink/mobile/S23ProductContractDeviceTest.kt`
- Create: `app/src/androidTest/java/com/echolink/mobile/S23ExternalEditorFixtureActivity.kt`
- Create: `tools/run_s23_product_contract.ps1`
- Create: `docs/qa/S23_ACCEPTANCE_MATRIX.md`
- Create: `evidence/device/s23/manifest.json`
- Create: `app/src/androidTest/AndroidManifest.xml`
- Modify: `docs/qa/manuscript-regression-ledger.md`

### Step 1: Add controlled fixture fields

- [ ] Fixture screen contains:
  - empty normal field;
  - normal field with text;
  - multiline field with one page;
  - password field;
  - field with duplicate view ID;
  - field that emits transient window events;
  - field near top and bottom screen edges.

### Step 2: Automate the contract matrix

- [ ] Require:
  - in-app trigger appears;
  - external trigger appears;
  - both triggers match the approved visual golden;
  - both pulse or stay static under reduced motion;
  - empty and password fields never show a trigger;
  - 100 transient events retain one overlay;
  - clicking trigger opens one panel;
  - panel covers keyboard area but leaves navigation free;
  - target text stays visible;
  - Dismiss changes nothing;
  - Accept changes only the intended text;
  - system keyboard returns;
  - correction reaches a terminal result;
  - rotation, screen off/on, app switching, and service reconnect recover.

### Step 3: Add real-app smoke coverage

- [ ] Run privacy-safe smoke tests in:
  - Samsung Notes;
  - Google Messages draft;
  - Gmail draft;
  - Chrome text area.
- [ ] Use generated test text only.
- [ ] Never send, post, or save a message.
- [ ] Record supported and unsupported behavior by app and version.

### Step 4: Require three consecutive clean loops

- [ ] Each loop:
  1. Safe replacement install.
  2. Verify accessibility still enabled.
  3. Run controlled fixture.
  4. Run real-app smoke.
  5. Capture launcher and both trigger screenshots.
  6. Run one successful correction.
  7. Run one forced failure and Retry.
  8. Verify keyboard and service health.
- [ ] A single failure resets the count to zero after the root cause is fixed.
- [ ] Do not change code between the three passing loops.

### Step 5: Commit the matrix and evidence manifest

- [ ] Commit tests, runner, matrix, sanitized evidence manifest, and ledger only.
- [ ] Do not commit screenshots containing user content.

---

## Task 10: Bind Tests to One Release Artifact

**Files:**

- Create: `tools/create_manuscript_release_manifest.ps1`
- Create: `tools/verify_manuscript_release_manifest.ps1`
- Create: `tools/tests/release_manifest.Tests.ps1`
- Create: `app/src/main/java/com/echolink/mobile/BuildIdentity.kt`
- Create: `app/src/test/java/com/echolink/mobile/BuildIdentityTest.kt`
- Modify: `app/build.gradle`
- Modify: `tools/build_play_bundle.ps1`
- Modify: `tools/security_release_gate.ps1`
- Modify: `README.md`

### Step 1: Define the release manifest

- [ ] Include:

```json
{
  "gitCommit": "",
  "gitTreeCleanForReleaseFiles": true,
  "contractVersion": 1,
  "contractSha256": "",
  "applicationId": "com.remote.Manuscript",
  "versionCode": 0,
  "versionName": "",
  "signerSha256": "",
  "apkSha256": "",
  "aabSha256": "",
  "windowsBrandSvgSha256": "",
  "generatedBrandProvenanceSha256": "",
  "unitTestReceiptSha256": "",
  "lintReceiptSha256": "",
  "s23EvidenceManifestSha256": "",
  "s23PassLoops": 3
}
```

### Step 2: Expose build identity in the app

- [ ] The About or diagnostics view shows:
  - version name and code;
  - package;
  - short commit;
  - contract version;
  - signer fingerprint suffix;
  - local AI readiness;
  - accessibility status.
- [ ] No secret or personal identifier is shown.

### Step 3: Make release gates verify identity

- [ ] `security_release_gate.ps1` must fail when:
  - product contract missing or changed after tests;
  - brand provenance missing;
  - source tests or lint receipt missing;
  - S23 evidence refers to another APK;
  - package or signer differs;
  - fewer than three clean S23 loops;
  - legacy package appears;
  - release files are dirty;
  - an em dash exists in changed product files.
- [ ] `build_play_bundle.ps1` must produce the AAB once and freeze it.
- [ ] Play upload must use the frozen AAB hash from the manifest.
- [ ] No rebuild is allowed between S23 acceptance and Play upload.

### Step 4: Verify and commit

- [ ] Run Pester tests, unit tests, lint, frozen APK install, S23 contract, AAB build, signature checks, and manifest verification.
- [ ] Commit release-manifest tools, tests, build identity, Gradle wiring, release gates, and README.

---

## Task 11: Reconcile AI Brain and Git After the Product Gate Passes

**Files:**

- Modify only after verification: `C:\Users\stuff\Documents\AI Brain\Echo-Link\context.md`
- Append only after verification: `C:\Users\stuff\Documents\AI Brain\Echo-Link\memory.md`
- Modify: `docs/qa/manuscript-regression-ledger.md`

### Step 1: Correct stale durable facts

- [ ] Update AI Brain only after the release manifest is green.
- [ ] Replace stale Android version facts with the verified package, version, commit, signer, artifact hashes, and contract version.
- [ ] Record that Windows is the visual brand master.
- [ ] Record the tested Samsung row key and the two-stage Settings route.
- [ ] Record the safe USB update rule.
- [ ] Record that uninstall resets accessibility authorization and local AI data.

### Step 2: Append the verified session result

- [ ] Add one dated event with:
  - what was broken;
  - evidence-backed causes;
  - commits;
  - exact tested artifact;
  - S23 loop count;
  - remaining open items.
- [ ] Do not claim Play, public, or device proof that did not occur.

### Step 3: Close ledger rows

- [ ] A row becomes Verified only when its automated test, S23 evidence, exact artifact hash, and first-good commit are filled.
- [ ] A later regression reopens the same row. It does not create an unrelated new fix with no history.

---

## Task 12: Resume AI Quality Work Without Reopening Brand and Accessibility

**Files:**

- Modify: `docs/superpowers/plans/2026-07-29-adaptive-model-selection.md`
- Modify: `docs/superpowers/plans/2026-07-28-gpt56-five-page-all-tone-quality-loop.md`
- Modify: `docs/qa/manuscript-regression-ledger.md`

### Step 1: Add prerequisite gates

- [ ] Both plans must require:
  - contract test green;
  - brand rendered test green;
  - shared trigger parity green;
  - Samsung route proof green;
  - safe install receipt green;
  - terminal correction result green;
  - exact artifact identity.

### Step 2: Keep work in separate release lanes

- [ ] Lane A: brand and launcher.
- [ ] Lane B: accessibility, trigger, panel, and keyboard.
- [ ] Lane C: AI model selection, speed, correctness, and tone.
- [ ] One lane changes per milestone commit.
- [ ] Before merging a lane, rerun the product-contract gate so it cannot break the other lanes.

### Step 3: Resume the five-page loop

- [ ] Reinstall local AI only after Tasks 1 through 10 are green.
- [ ] Use one frozen GPT-authored error page per round.
- [ ] Capture Manuscript output from the live S23.
- [ ] Grade exactly the captured output.
- [ ] Define success as 100 percent of objective locked checks plus the approved tone rubric. Do not invent a score above 100 percent.
- [ ] If a tone fails, change only the AI lane and rerun the full product-contract gate before the next device build.

---

## Final Verification Gate

No task is complete until all commands below pass against the same commit and artifact.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:ANDROID_HOME='C:\Users\stuff\AppData\Local\Android\Sdk'

python tools\generate_android_brand_assets.py --check
.\gradlew.bat --no-daemon :app:testReleaseUnitTest
.\gradlew.bat --no-daemon :app:lintRelease
Invoke-Pester tools\tests
.\tools\install_s23_manuscript.ps1 -Variant debug -Serial R5CW20R46WD
.\tools\run_s23_product_contract.ps1 -Serial R5CW20R46WD -RequiredPassLoops 3
python tools\compare_manuscript_icons.py --contract docs\product-contract\manuscript-android-v1.json --evidence evidence\brand\s23
.\tools\security_release_gate.ps1
.\tools\verify_manuscript_release_manifest.ps1
git diff --check
```

Required final human checks:

- [ ] Curtis approves the S23 launcher icon against the Windows icon.
- [ ] Curtis approves the in-app trigger.
- [ ] Curtis approves the across-app trigger.
- [ ] Curtis sees Installed apps highlighted from Manuscript.
- [ ] Curtis sees Manuscript in the Installed apps list.
- [ ] Curtis completes one live correction and one Retry flow.
- [ ] Curtis confirms Gboard or Samsung Keyboard remains active.

Required final evidence:

- [ ] Exact APK and AAB hashes.
- [ ] Package and signer.
- [ ] Git commit and clean scoped release tree.
- [ ] Product-contract version and hash.
- [ ] Three consecutive S23 pass receipts.
- [ ] Sanitized screenshots for launcher, both triggers, both Settings menu areas, panel, and terminal correction.
- [ ] Updated complaint ledger.
- [ ] Updated AI Brain event.

## Stop Rules

- Stop if the package or signer does not match. Do not uninstall.
- Stop if a Settings action resolves but lands on an unproved screen.
- Stop if icon source checks pass but rendered S23 proof fails.
- Stop if accessibility was enabled before an update and is disabled after it.
- Stop if any correction returns blank or loads forever.
- Stop if a fix changes more than one release lane without separate commits and contract reruns.
- Stop if evidence refers to a different artifact hash.
- Stop if unrelated dirty files would be included in a commit.
- Stop before Play upload until the exact frozen artifact passes every gate.
