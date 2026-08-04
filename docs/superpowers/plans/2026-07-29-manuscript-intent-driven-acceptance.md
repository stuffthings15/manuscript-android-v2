# Manuscript Intent-Driven Recovery and Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Manuscript work reliably on Curtis's Galaxy S23 Plus, in its editor and normal editable fields across apps, with Windows-matched branding, visible triggers, fast validated AI corrections, complete tone coverage, safe updates, and release proof tied to one exact artifact.

**Architecture:** Treat Curtis's repeated requests as the product contract. Keep brand, accessibility, review flow, AI quality, and release as separate lanes. Each lane gets runtime tests, not source-string tests. The lanes join only in a frozen S23 acceptance run whose APK hash, tests, screenshots, AI results, and final AAB are bound in one manifest.

**Tech Stack:** Kotlin, Android SDK 36, Android AccessibilityService, LiteRT Gemma, llama.cpp fallback, JUnit 4, Android instrumentation, UI Automator, PowerShell, ADB, Python Pillow, GPT Sol 5.6 blind grading

## Global Constraints

- Windows Manuscript remains the visual master.
- Release package is `com.remote.Manuscript`.
- USB debug package is `com.remote.Manuscript.debug`.
- Test package is `com.remote.Manuscript.debug.test`.
- `com.echolink.manuscript` is forbidden.
- Normal updates never uninstall or clear app data.
- USB updates force-stop all Manuscript processes, then use only `adb install -r -t`.
- Samsung accessibility approval is manual. Tests may inspect it but never bypass it.
- Manuscript must never become the selected keyboard.
- Password fields never show a trigger or expose text.
- Empty fields never show a trigger.
- Normal focused editable fields with text show the trigger.
- The in-app and across-app triggers use the same component and gentle pulse.
- Reduced motion leaves both triggers visible without animation.
- Every correction ends as validated AI text, Retry, or Model Required.
- Deterministic cleanup may protect and validate text. It may not masquerade as the accepted AI correction.
- Original text stays unchanged on failure.
- No blank result and no endless loading.
- Every tone in `WritingTone.entries` is tested. Do not hard-code an old tone count.
- No em dash may appear in generated output, source, tests, evidence, or user-facing copy.
- GPT scores are capped at 100 percent. Passing means 100 percent of locked objective checks plus a passing tone judgment.
- No Play upload until the exact frozen AAB matches the verified release manifest.
- Parked GPT campaign files remain unstaged until the product contract gate is green.
- No unrelated dirty files are staged.

---

## Intent Recovered From History

| Curtis's repeated intent | Required proof |
| --- | --- |
| Make the Android app work on the S23 Plus | A live correction succeeds on the exact installed APK |
| Writing across all apps must work | Samsung Notes, Messages draft, Gmail draft, and Chrome generated-text checks |
| The accessibility setting must be usable | Full Samsung path, manual enable, status refresh, reconnect, and enabled service proof |
| Android must match Windows | Rendered launcher and both trigger screenshots compared with approved goldens |
| Both triggers should gently flash | One shared pulse controller, both surfaces observed, reduced-motion behavior verified |
| AI should be applied to every correction | Runtime provenance on every successful correction, with a nonempty model ID and AI backend |
| Correction must be reliable and reasonably fast | Fixed latency budgets, terminal watchdog, forced failure, Retry, and no silent fallback |
| User should move through suggestions quickly | Prefetch next block and measure next-card readiness after Accept or Dismiss |
| Bad grammar must be fixed without changing meaning | Locked grammar, meaning, facts, formatting, and no-em-dash checks |
| Every tone and style must be correct | Every current `WritingTone.entries` value runs against the same five-page source |
| Use GPT Sol 5.6 to author and re-grade the test | Sealed author prompt, exact S23 output capture, blind judge prompt, and stored scorecard |
| Stop fixing the same bugs repeatedly | Three unchanged S23 passes and artifact-bound release gates |

## Confirmed Current Gaps

- `S23ProductContractDeviceTest` checks empty, password, normal, multiline, transient clearing, panel open, panel close, and keyboard identity.
- It does not apply a correction.
- It does not prove AI generated the result.
- It does not test Retry, Model Required, rotation, screen off, app switching, or service reconnect.
- It does not compare rendered icons or triggers.
- It does not test Samsung Notes, Messages, Gmail, or Chrome.
- `CorrectionTerminalSurfaceSourceTest` checks source text, not runtime behavior.
- `run_s23_product_contract.ps1` repeats one narrow instrumentation test three times.
- The stored five-page device result covers only Proofread and records validator failure.
- Live S23 state currently shows Manuscript accessibility disabled after the latest debug replacement install.
- No frozen release APK and AAB have passed the complete acceptance matrix.

---

### Task 1: Lock the User-Intent Traceability Gate

**Files:**

- Create: `docs/qa/MANUSCRIPT_USER_INTENT_TRACEABILITY.md`
- Create: `app/src/test/java/com/echolink/mobile/UserIntentContractTest.kt`
- Modify: `docs/product-contract/manuscript-android-v1.json`
- Modify: `docs/qa/manuscript-regression-ledger.md`

**Interfaces:**

- Consumes: `WritingTone.entries`, product contract version 1, current package constants.
- Produces: one machine-readable list of required behaviors used by later release gates.

- [ ] **Step 1: Write the failing contract test**

```kotlin
class UserIntentContractTest {
    @Test
    fun contractLocksEveryCurrentToneAndRequiredSurface() {
        val contract = loadProductContract()
        assertEquals(
            WritingTone.entries.map { it.name }.toSet(),
            contract.requiredTones.toSet(),
        )
        assertEquals(
            setOf("editor", "accessibility_overlay", "process_text"),
            contract.correctionSurfaces.toSet(),
        )
        assertEquals(
            setOf("samsung_notes", "messages_draft", "gmail_draft", "chrome"),
            contract.externalAppSmoke.toSet(),
        )
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.UserIntentContractTest
```

Expected failure: missing `requiredTones`, `correctionSurfaces`, or `externalAppSmoke`.

- [ ] **Step 3: Extend the product contract**

Add:

```json
{
  "correctionSurfaces": ["editor", "accessibility_overlay", "process_text"],
  "externalAppSmoke": ["samsung_notes", "messages_draft", "gmail_draft", "chrome"],
  "requiredTones": [],
  "aiRequiredForAcceptedCorrection": true,
  "nextSuggestionReadyTargetMs": 500
}
```

Populate `requiredTones` from the exact current enum during the same commit. The test prevents drift.

- [ ] **Step 4: Build the traceability table**

For every row, record:

- Curtis request.
- Product requirement.
- Automated test.
- Physical-device test.
- Evidence path.
- First good commit.
- Last verified artifact hash.
- Current state: Missing, Failing, Passing, or Blocked.

- [ ] **Step 5: Run, inspect, and commit**

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.UserIntentContractTest
git diff --check
git add -- docs/qa/MANUSCRIPT_USER_INTENT_TRACEABILITY.md docs/product-contract/manuscript-android-v1.json docs/qa/manuscript-regression-ledger.md app/src/test/java/com/echolink/mobile/UserIntentContractTest.kt
git commit -m "test(android): lock Curtis acceptance contract" --only -- docs/qa/MANUSCRIPT_USER_INTENT_TRACEABILITY.md docs/product-contract/manuscript-android-v1.json docs/qa/manuscript-regression-ledger.md app/src/test/java/com/echolink/mobile/UserIntentContractTest.kt
```

---

### Task 2: Prove Samsung Accessibility Onboarding and Recovery

**Files:**

- Create: `app/src/androidTest/java/com/echolink/mobile/S23AccessibilityRecoveryDeviceTest.kt`
- Create: `tools/capture_s23_accessibility_path.ps1`
- Modify: `app/src/main/java/com/echolink/mobile/AccessibilitySettingsGuide.kt`
- Modify: `app/src/main/java/com/echolink/mobile/MainActivity.kt`
- Modify: `tools/install_s23_manuscript.ps1`
- Modify: `docs/qa/S23_ACCEPTANCE_MATRIX.md`

**Interfaces:**

- Consumes: `AccessibilitySettingsCheckpoint`, service component, safe installer receipt.
- Produces: a manual-approval checkpoint and verified enabled service before cross-app tests.

- [ ] **Step 1: Write the failing recovery test**

```kotlin
@Test
fun returningFromSamsungSettingsRefreshesAcrossAppStatus() {
    launchMainActivity()
    assertTextVisible("Across all apps: Off")
    openAccessibilityGuide()
    assertSamsungInstalledAppsRowKey("accessibility_installed_services")
    waitForManualServiceEnable()
    returnToManuscript()
    assertTextVisible("Across all apps: On")
    assertServiceConnectedWithin(5_000L)
}
```

- [ ] **Step 2: Add installer state tests**

Pester must cover both Samsung outcomes:

```powershell
It 'preserves enabled service when Samsung keeps approval' { }
It 'fails with a manual re-enable checkpoint when Samsung disables approval' { }
It 'never writes enabled_accessibility_services' { }
It 'never runs uninstall or pm clear' { }
```

The installer must not claim approval was preserved when the live secure setting says otherwise.

- [ ] **Step 3: Implement honest recovery**

- If service remains enabled, continue.
- If Samsung disables it, stop acceptance and open only the normal Accessibility home.
- Show exact instructions: Installed apps, Manuscript across-app writing helper, top switch, approve.
- Poll read-only service state.
- Resume only after the exact component is enabled and bound.
- Never write secure settings.

- [ ] **Step 4: Capture the complete path**

Capture generated screenshots and UI XML for:

1. Manuscript Across all apps card.
2. Samsung Accessibility home with Installed apps highlighted.
3. Installed apps list with Manuscript visible.
4. Manuscript service detail screen.
5. Samsung warning approval.
6. Enabled service detail.
7. Manuscript status On.

- [ ] **Step 5: Verify and commit**

```powershell
Invoke-Pester tools/tests/install_s23_manuscript.Tests.ps1
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.AccessibilitySettingsGuideTest
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.echolink.mobile.S23AccessibilityRecoveryDeviceTest
```

Commit only the files named in this task.

---

### Task 3: Replace Visual Assumptions With Rendered Proof

**Files:**

- Create: `app/src/androidTest/java/com/echolink/mobile/S23BrandCaptureDeviceTest.kt`
- Create: `tools/compare_manuscript_surfaces.py`
- Create: `tools/tests/test_compare_manuscript_surfaces.py`
- Create after Curtis approval: `app/src/androidTest/assets/goldens/s23/launcher.png`
- Create after Curtis approval: `app/src/androidTest/assets/goldens/s23/in_app_trigger.png`
- Create after Curtis approval: `app/src/androidTest/assets/goldens/s23/across_app_trigger.png`
- Modify: `tools/generate_android_brand_assets.py`
- Modify: `docs/qa/S23_ACCEPTANCE_MATRIX.md`

**Interfaces:**

- Consumes: Windows `manuscript.svg`, shared trigger factory, S23 screenshots.
- Produces: approved rendered goldens and numeric image comparison results.

- [ ] **Step 1: Write failing comparison tests**

```python
def test_outer_gold_frame_survives_samsung_mask():
    result = compare_surface("launcher")
    assert result.gold_frame_recall >= 0.95
    assert result.mean_palette_distance <= 8.0

def test_both_triggers_match_each_other():
    result = compare_pair("in_app_trigger", "across_app_trigger")
    assert result.alpha_mask_iou >= 0.98
    assert result.mean_palette_distance <= 4.0
```

- [ ] **Step 2: Capture rendered surfaces**

Capture the launcher from Samsung Home, the in-app trigger over generated text, and the overlay trigger over the fixture app. Crop from UI bounds, not hard-coded screen coordinates.

- [ ] **Step 3: Prove pulse and reduced motion**

The device test captures three frames over 1.5 seconds:

```kotlin
assertTrue(alphaChanged || scaleChanged)
enableRemoveAnimationsForTest()
assertTrue(triggerRemainsVisible())
assertFalse(alphaChanged)
assertFalse(scaleChanged)
```

Restore the device animation setting in `finally`.

- [ ] **Step 4: Obtain Curtis approval**

Show Windows master beside all three S23 captures. Goldens are created only from Curtis-approved captures.

- [ ] **Step 5: Verify and commit**

```powershell
python -m unittest tools.tests.test_compare_manuscript_surfaces -v
python tools/generate_android_brand_assets.py --check
```

Commit generated source assets, comparison code, tests, and approved goldens only.

---

### Task 4: Prove Every Successful Correction Used AI

**Files:**

- Create: `app/src/main/java/com/echolink/mobile/CorrectionProvenance.kt`
- Create: `app/src/test/java/com/echolink/mobile/CorrectionProvenanceTest.kt`
- Create: `app/src/androidTest/java/com/echolink/mobile/CorrectionSurfaceRuntimeDeviceTest.kt`
- Modify: `app/src/main/java/com/echolink/mobile/CorrectionTerminalResult.kt`
- Modify: `app/src/main/java/com/echolink/mobile/DocumentCorrectionPipeline.kt`
- Modify: `app/src/main/java/com/echolink/mobile/MainActivity.kt`
- Modify: `app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt`
- Modify: `app/src/main/java/com/echolink/mobile/ProcessTextActivity.kt`

**Interfaces:**

- Consumes: backend ID, model artifact hash, selected tone, elapsed time.
- Produces: a content-free provenance record attached to every `Success`.

- [ ] **Step 1: Write failing provenance tests**

```kotlin
data class CorrectionProvenance(
    val requestId: String,
    val backendId: String,
    val modelSha256: String,
    val tone: WritingTone,
    val aiGenerated: Boolean,
)

@Test
fun deterministicOnlyTextCannotBecomeSuccess() {
    val result = terminalResult(
        candidate = "Rules-only text.",
        provenance = provenance(aiGenerated = false),
    )
    assertTrue(result is CorrectionTerminalResult.RetryableFailure)
}
```

- [ ] **Step 2: Extend `Success`**

```kotlin
data class Success(
    val correctedText: String,
    val modelId: String,
    val elapsedMs: Long,
    val provenance: CorrectionProvenance,
) : CorrectionTerminalResult
```

Require `aiGenerated == true`, nonblank backend ID, and a valid model SHA256 before delivery.

- [ ] **Step 3: Replace source-string surface proof**

Runtime device tests must execute:

- Main editor success.
- Accessibility overlay success.
- Process Text success.
- Blank model output.
- Model exception.
- Missing callback timeout.
- Duplicate callback.
- Cancel.
- Model Required.
- Retry after failure.

Each surface must end once, show a next action, and keep original text on failure.

- [ ] **Step 4: Keep evidence private**

Persist only request ID, hashes, model ID, tone, duration, result type, and timestamps. Do not persist user text.

- [ ] **Step 5: Verify and commit**

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest --tests com.echolink.mobile.CorrectionProvenanceTest --tests com.echolink.mobile.CorrectionRequestWatchdogTest
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.echolink.mobile.CorrectionSurfaceRuntimeDeviceTest
```

---

### Task 5: Make Review Fast and Stable

**Files:**

- Create: `app/src/main/java/com/echolink/mobile/ReviewProgressController.kt`
- Create: `app/src/test/java/com/echolink/mobile/ReviewProgressControllerTest.kt`
- Create: `app/src/androidTest/java/com/echolink/mobile/S23ReviewProgressDeviceTest.kt`
- Modify: `app/src/main/java/com/echolink/mobile/MainActivity.kt`
- Modify: `app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt`
- Modify: `app/src/main/java/com/echolink/mobile/PersistentDocumentCorrectionRunner.kt`

**Interfaces:**

- Consumes: ordered correction blocks and terminal results.
- Produces: one shared current index, prefetch state, and instant next-card transition.

- [ ] **Step 1: Write failing navigation tests**

```kotlin
@Test
fun acceptAdvancesOnceAndPrefetchedNextCardIsReady() {
    val controller = controllerWithThreeReadyBlocks()
    controller.acceptCurrent()
    assertEquals(1, controller.currentIndex)
    assertTrue(controller.currentBlockReady)
}

@Test
fun repeatedTapCannotSkipTwoCorrections() {
    val controller = controllerWithThreeReadyBlocks()
    controller.acceptCurrent()
    controller.acceptCurrent()
    assertEquals(1, controller.currentIndex)
}
```

- [ ] **Step 2: Use one controller on both review surfaces**

The controller owns:

- `currentIndex`.
- `totalCount`.
- `currentState`.
- next-block prefetch.
- accept and dismiss lock.
- activity or service restoration.
- terminal failure and Retry.

- [ ] **Step 3: Lock speed budgets**

Use the already approved adaptive-model targets:

- Auto first corrected block within 10,000 ms.
- Primary model working limit 8,000 ms.
- Retry or compact fallback uses remaining budget.
- Next prefetched review card visible within 500 ms after Accept or Dismiss.
- Any missed limit ends visibly. It never keeps spinning.

- [ ] **Step 4: Test lifecycle stress**

On the S23 fixture:

- 100 transient accessibility events create one trigger.
- 20 rapid taps open one panel.
- 20 rapid Accept taps advance once.
- Rotation restores the same block.
- Screen off and on restores the same block.
- App switch and return restores the same block.
- Service reconnect restores or safely ends the request.
- Keyboard identity matches before and after.

- [ ] **Step 5: Verify and commit**

Run focused unit tests, runtime device tests, full release unit tests, and lint. Commit only this lane.

---

### Task 6: Expand the Controlled S23 Contract Test

**Files:**

- Modify: `app/src/androidTest/java/com/echolink/mobile/S23ExternalEditorFixtureActivity.kt`
- Modify: `app/src/androidTest/java/com/echolink/mobile/S23ProductContractDeviceTest.kt`
- Modify: `tools/run_s23_product_contract.ps1`
- Modify: `docs/qa/S23_ACCEPTANCE_MATRIX.md`

**Interfaces:**

- Consumes: shared trigger, terminal correction, progress controller, accessibility service.
- Produces: one complete controlled fixture receipt per run.

- [ ] **Step 1: Add missing fixture fields**

Add modes for:

- duplicate view ID.
- top screen edge.
- bottom screen edge.
- noneditable selectable text.
- disabled editor.
- read-only editor.
- one-page multiline text.
- service reconnect.

- [ ] **Step 2: Test full behavior**

For each applicable field:

```kotlin
assertTriggerVisibility()
assertOnePanelOpens()
assertTargetTextStillVisible()
assertDismissDoesNotChangeText()
assertSuccessfulAiCorrectionChangesOnlyTarget()
assertFailureKeepsOriginalAndShowsRetry()
assertKeyboardRestored()
```

- [ ] **Step 3: Capture step evidence**

The runner must capture screenshots and UI XML at the moment each required behavior is visible. A screenshot taken only after the test ends is insufficient.

- [ ] **Step 4: Make the runner test more than one method**

Require these classes:

- `S23AccessibilityRecoveryDeviceTest`.
- `S23BrandCaptureDeviceTest`.
- `CorrectionSurfaceRuntimeDeviceTest`.
- `S23ReviewProgressDeviceTest`.
- `S23ProductContractDeviceTest`.

The runner fails if any class is missing from the receipt.

- [ ] **Step 5: Verify and commit**

Run one diagnostic loop first. Fix failures. Then run the complete controlled loop once. Three-pass release repetition happens only in Task 10.

---

### Task 7: Run Privacy-Safe Real-App Smoke Tests

**Files:**

- Create: `tools/run_s23_external_app_smoke.ps1`
- Create: `tools/tests/run_s23_external_app_smoke.Tests.ps1`
- Create: `docs/qa/S23_EXTERNAL_APP_SMOKE.md`
- Modify: `tools/run_s23_product_contract.ps1`

**Interfaces:**

- Consumes: exact installed debug APK, enabled accessibility service, generated text.
- Produces: one sanitized result per supported external app.

- [ ] **Step 1: Lock generated test text**

Use:

```text
This are a generated Manuscript test sentence. It contain two grammar mistake.
```

Never read, capture, or save existing user text.

- [ ] **Step 2: Write Pester safety tests**

```powershell
It 'never taps Send, Post, Save, or Archive' { }
It 'clears only text inserted by the runner' { }
It 'refuses to run when an existing draft contains text' { }
It 'records package and version for each external app' { }
```

- [ ] **Step 3: Run app checks**

- Samsung Notes: use a disposable unsaved note, Accept, verify corrected field, discard.
- Messages: use a new blank draft, Dismiss, verify no send.
- Gmail: use a new blank draft, Dismiss, verify no send and discard.
- Chrome: use a local data URL or project-owned test page, Accept, verify corrected field.

- [ ] **Step 4: Define pass**

Each supported app must show:

- trigger appears.
- trigger opens one panel.
- original text remains visible.
- Accept or Dismiss behaves correctly.
- keyboard returns.
- no app action sends or saves content.

- [ ] **Step 5: Verify and commit**

Run the Pester safety suite before touching external apps. Store only generated-text screenshots.

---

### Task 8: Finish Adaptive Model Selection on the S23

**Files:**

- Execute and update: `docs/superpowers/plans/2026-07-29-adaptive-model-selection.md`
- Create: `app/src/androidTest/java/com/echolink/mobile/S23AdaptiveModelAcceptanceDeviceTest.kt`
- Modify: `docs/qa/MANUSCRIPT_USER_INTENT_TRACEABILITY.md`

**Interfaces:**

- Consumes: terminal result and correction provenance from Task 4.
- Produces: selected model, exact benchmark context, latency result, validation result, and fallback proof.

- [ ] **Step 1: Finish the existing adaptive plan with TDD**

Do not rewrite its approved targets:

- Auto target is 10,000 ms.
- Primary working limit is 8,000 ms.
- Every accepted correction uses AI.
- Compact recovery model remains available.

- [ ] **Step 2: Audit the actual S23**

Record content-free facts:

- model and Android API.
- total and available RAM.
- ABI and processors.
- Vulkan availability.
- thermal and power state.
- installed model hashes.
- benchmark policy version.

- [ ] **Step 3: Qualify models**

Every candidate runs the same sealed paragraph. A model qualifies only if:

- output is nonblank.
- grammar gate passes.
- meaning and protected facts pass.
- no em dash appears.
- provenance proves AI.
- measured time meets the selected mode limit.

- [ ] **Step 4: Prove fallback**

Force the primary backend to time out. The compact AI must either return validated text within the remaining budget or end with Retry. Rules-only text cannot pass as success.

- [ ] **Step 5: Verify and commit**

Run the adaptive unit matrix, one cold S23 run, and 20 warm S23 corrections. Report median and slowest time from measured results. Do not invent performance numbers before measurement.

---

### Task 9: Run the Five-Page GPT Sol 5.6 All-Tone Loop

**Files:**

- Execute and update: `docs/superpowers/plans/2026-07-28-gpt56-five-page-all-tone-quality-loop.md`
- Create: `tools/grade_five_page_tone_run.py`
- Create: `tools/tests/test_grade_five_page_tone_run.py`
- Create after product gate: `app/src/androidTest/java/com/echolink/mobile/FivePageAllToneAcceptanceDeviceTest.kt`
- Create after product gate: `test-evidence/device-v29-debug/gpt56-five-page-all-tones-v3-results.json`
- Modify: `docs/qa/MANUSCRIPT_USER_INTENT_TRACEABILITY.md`

**Interfaces:**

- Consumes: one sealed GPT Sol 5.6 error document and every `WritingTone.entries` value.
- Produces: exact S23 output, objective score, blind GPT judgment, timing, model provenance, and pass state for every tone.

- [ ] **Step 1: Freeze the author artifact**

Use GPT Sol 5.6 to create one five-page document containing:

- grammar errors.
- spelling errors.
- punctuation errors.
- vocabulary errors.
- sentence-structure errors.
- agreement and tense errors.
- dangling modifiers.
- faulty parallelism.
- names, dates, money, links, lists, headings, quotations, permissions, and prohibitions that must be preserved.

Record model name, prompt, timestamp, document SHA256, and no-em-dash requirement.

- [ ] **Step 2: Run exactly the frozen document on the S23**

For every current tone:

```kotlin
for (tone in WritingTone.entries) {
    val result = correctFrozenDocument(tone)
    require(result is CorrectionTerminalResult.Success)
    saveContentFreeReceipt(tone, result.provenance, result.elapsedMs)
    saveExactGeneratedTestOutput(tone, result.correctedText)
}
```

Do not substitute emulator output, desktop output, or a different APK.

- [ ] **Step 3: Score objective checks**

Each tone must score 100 percent on:

- protected names.
- numbers.
- dates.
- money.
- links.
- quotations.
- headings.
- list markers.
- paragraph structure required by the tone.
- permission, prohibition, obligation, and uncertainty.
- no em dash.
- no prompt leakage.
- no blank or truncated section.
- AI provenance present.

Proofread also requires zero detected grammar defects and minimal unnecessary rewriting.

- [ ] **Step 4: Blind GPT Sol 5.6 judging**

Use a separate judge prompt. Give it:

- frozen source.
- exact S23 output.
- selected tone contract.
- objective scorecard.

Do not give it the app prompt, prior score, or desired verdict. Require:

- correctness out of 100.
- tone fidelity out of 5.
- meaning preservation out of 5.
- remaining errors with exact excerpts.
- pass or fail.

Pass requires objective 100 percent, correctness 100 percent, tone fidelity 5 of 5, meaning preservation 5 of 5, and no remaining error.

- [ ] **Step 5: Fix only the AI lane**

If one tone fails:

1. Add the exact failure as a test.
2. Run it red.
3. Change prompt, validator, model selection, or correction pipeline.
4. Run the focused test green.
5. Rerun full product-contract gates.
6. Rebuild and safely replace the APK.
7. Manually restore accessibility if Samsung disables it.
8. Rerun the failed tone.
9. After it passes, rerun every tone.

Do not change brand or accessibility code while fixing AI quality.

- [ ] **Step 6: Verify and commit**

One all-tone receipt must contain every current enum value exactly once. A result with `tone_count: 1` fails.

---

### Task 10: Freeze One Release Candidate and Run Three Clean Loops

**Files:**

- Modify: `tools/run_s23_product_contract.ps1`
- Modify: `tools/create_manuscript_release_manifest.ps1`
- Modify: `tools/verify_manuscript_release_manifest.ps1`
- Modify: `tools/security_release_gate.ps1`
- Create: `evidence/device/s23/three-run-manifest.json`
- Create: `evidence/release/manuscript-release-manifest.json`

**Interfaces:**

- Consumes: all prior test receipts and one exact APK.
- Produces: three unchanged physical-device pass loops and a frozen release manifest.

- [ ] **Step 1: Freeze the candidate**

Record:

- Git commit.
- scoped dirty-file list.
- contract hash.
- APK hash.
- test APK hash.
- package.
- signer.
- version.
- installed model hashes.
- Windows brand hash.

- [ ] **Step 2: Run each complete loop**

Each loop must include:

1. Safe replacement install.
2. Accessibility state check and manual restoration if required.
3. Samsung settings path.
4. Rendered launcher and both triggers.
5. Controlled fixture suite.
6. successful editor correction.
7. successful overlay correction.
8. successful Process Text correction.
9. forced failure and Retry.
10. adaptive-model check.
11. Samsung Notes, Messages, Gmail, and Chrome smoke.
12. keyboard and service health.
13. one five-page Proofread acceptance run.

- [ ] **Step 3: Enforce unchanged inputs**

Between loops:

- no source change.
- no rebuild.
- no APK change.
- no test APK change.
- no model change.
- no contract change.

Any failure resets the pass count after the root cause is fixed.

- [ ] **Step 4: Obtain Curtis approval**

Curtis approves:

- launcher.
- in-app trigger.
- overlay trigger.
- gentle pulse.
- Samsung guidance.
- one live correction.
- Retry.
- keyboard restoration.

- [ ] **Step 5: Build and freeze the AAB**

Build the release AAB once from the same verified commit. Verify package, signer, version, and hash. Add it to the release manifest. Do not rebuild after this point.

---

### Task 11: Release Gate and Durable History

**Files:**

- Modify after verified success: `C:\Users\stuff\Documents\AI Brain\Echo-Link\context.md`
- Append after verified success: `C:\Users\stuff\Documents\AI Brain\Echo-Link\memory.md`
- Modify: `docs/qa/MANUSCRIPT_USER_INTENT_TRACEABILITY.md`
- Modify: `docs/qa/manuscript-regression-ledger.md`

**Interfaces:**

- Consumes: green release manifest and Curtis approval.
- Produces: honest durable state and the exact Play upload artifact.

- [ ] **Step 1: Run final local gates**

```powershell
python tools/generate_android_brand_assets.py --check
python -m unittest tools.tests.test_compare_manuscript_surfaces -v
.\gradlew.bat --no-daemon :app:testReleaseUnitTest
.\gradlew.bat --no-daemon :app:lintRelease
Invoke-Pester tools/tests
.\tools\verify_manuscript_release_manifest.ps1
git diff --check
```

- [ ] **Step 2: Block false completion**

Release verification fails when:

- accessibility is off.
- any required device class did not run.
- any external app is missing.
- any current tone is missing.
- any objective score is below 100 percent.
- any correction lacks AI provenance.
- latency limit failed without a terminal Retry.
- screenshots or receipts refer to another APK.
- AAB differs from the frozen manifest.
- legacy package appears.
- em dash appears.

- [ ] **Step 3: Update AI Brain only with verified facts**

Correct the stale dynamic state. Record current package, version, commit, signer, artifact hashes, service behavior after update, selected model, measured speed, tone result count, and remaining external gates.

- [ ] **Step 4: Upload only the frozen AAB**

Play Console upload uses the exact AAB SHA256 in the green manifest. Verify Internal testing version and tester availability before claiming release.

- [ ] **Step 5: Commit scoped documentation**

Commit only the traceability table, regression ledger, sanitized receipts, and release manifest. Do not commit personal drafts, user text, credentials, APKs, AABs, or unapproved screenshots.

---

## Final Pass Definition

Manuscript is accepted only when all are true:

- Correct Windows-derived launcher visible on S23.
- Both triggers match and gently pulse.
- Reduced motion works.
- Accessibility can be manually enabled through the proven Samsung path.
- Service reconnects and status refreshes.
- Trigger works in editor and normal external fields.
- Empty, password, read-only, and disabled fields stay hidden.
- Samsung Notes, Messages draft, Gmail draft, and Chrome pass safely.
- Every successful correction proves AI use.
- Every request ends in Success, Retry, or Model Required.
- Original text survives every failure.
- Next suggestion appears within the locked target.
- Adaptive model meets the Auto target or falls back safely.
- Every current tone passes the five-page objective and blind judge gates.
- Three complete S23 loops pass without code or artifact changes.
- Curtis approves the visible behavior.
- Frozen APK and AAB match the verified release manifest.

## Stop Rules

- Stop if package, signer, commit, APK hash, or model hash changes.
- Stop if Samsung disables accessibility. Ask Curtis to re-enable it manually.
- Stop if existing personal text appears in an external-app draft.
- Stop if a correction lacks AI provenance.
- Stop if output is blank, truncated, or still loading after the deadline.
- Stop if a tone score is below the locked gate.
- Stop if a fix crosses release lanes without a separate commit and full contract rerun.
- Stop before Play upload unless the frozen manifest is green.
