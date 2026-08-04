# GPT-5.6 Five-Page All-Tone Quality Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and execute a resumable S23 quality campaign that requires every Manuscript tone to earn an isolated GPT-5.6 Sol grade of exactly 100/100 on the same sealed five-page document.

**Architecture:** A PowerShell campaign controller validates sealed inputs, runs one Android instrumentation tone at a time, pulls private device evidence, freezes the raw output, and creates a grader-only input package. A fresh GPT-5.6 Sol context grades each frozen output against a presealed per-tone rubric, while an append-only ledger tracks rounds, invalidation, and the active 23-tone pass matrix.

**Tech Stack:** Kotlin, JUnit 4, Android instrumentation, Gradle, PowerShell, ADB, JSON, SHA-256, on-device Gemma through LiteRT, isolated GPT-5.6 Sol grading.

## Global Constraints

- Use document ID `northstar-five-page-all-tones-sealed-v2`.
- Use the same frozen 1,677-word source for all 23 tones.
- Use `QualityMode.THOROUGH` on the Samsung S23.
- Require every planted error to be corrected.
- Allow zero introduced objective errors.
- Preserve every protected fact, actor, permission, prohibition, obligation, sequence, and deadline.
- Preserve required Markdown headings, list structure, page order, URLs, email addresses, and quotation text.
- Allow zero Unicode em dashes.
- Require every measurable assigned-tone criterion to pass.
- A tone passes only at exactly 100/100.
- Overall success requires 23 current, non-invalidated passes out of 23.
- Never repair, normalize, or rewrite a frozen Manuscript output before grading.
- Never weaken or edit the sealed corpus, protected facts, objective inventory, or rubrics after the first device output is observed.
- A shared prompt, model, sampling, validator, chunker, cleanup, or pipeline change invalidates every dependent earlier pass.
- Preserve every failed and passing device output, grade, log, hash, and invalidation record.

---

## File Structure

### New files

- `quality/gpt56/gpt56-five-page-tone-rubrics-v2.json`
  - Immutable measurable rubrics for all 23 tones.
- `quality/gpt56/sealed-campaign-v2.json`
  - Document ID, required tone order, and SHA-256 values for the corpus, source, reference, and rubric.
- `quality/gpt56/grader-system-v2.md`
  - Isolation and strict-grading instructions for a fresh GPT-5.6 Sol grader.
- `quality/gpt56/grader-result-v2.schema.json`
  - Required structure for one isolated tone grade.
- `tools/Gpt56QualityLoop.psm1`
  - Pure host functions for hashing, sealing, pre-grade checks, grader input creation, ledger writes, grade validation, and invalidation.
- `tools/run_gpt56_quality_round.ps1`
  - Builds, installs, runs one tone, pulls evidence, freezes output, and creates the grader package.
- `tools/record_gpt56_grade.ps1`
  - Validates one isolated grader result, updates the append-only ledger, and refreshes the active matrix.
- `tools/tests/Test-Gpt56QualityLoop.ps1`
  - Behavioral tests for the PowerShell module and scripts.
- `app/src/androidTest/java/com/echolink/mobile/DeviceTestEvidenceWriterTest.kt`
  - Real Android test for private evidence writing and filename safety.

### Modified files

- `app/src/androidTest/java/com/echolink/mobile/DeviceTestEvidenceWriter.kt`
  - Add a private, `run-as` readable evidence path for automated host collection.
- `app/src/androidTest/java/com/echolink/mobile/AllToneFullPageDeviceTest.kt`
  - Require one tone and round ID, emit schema v2, and write one deterministic private result file.
- `app/src/test/java/com/echolink/mobile/Gpt56FivePageCorpusContractTest.kt`
  - Verify rubric coverage, sealed hashes, tone order, and the real Unicode em dash rule.
- `.gitignore`
  - Ignore generated round directories while retaining campaign documentation.
- `test-evidence/gpt56-five-page-all-tones-v2/README.md`
  - Explain evidence layout, immutability, and how to reconcile the final matrix.

---

### Task 1: Seal the 23-Tone Rubric and Campaign Contract

**Files:**
- Create: `quality/gpt56/gpt56-five-page-tone-rubrics-v2.json`
- Create: `quality/gpt56/sealed-campaign-v2.json`
- Modify: `app/src/test/java/com/echolink/mobile/Gpt56FivePageCorpusContractTest.kt`

**Interfaces:**
- Consumes: `WritingTone.entries`, `gpt56-five-page-all-tones-v2.json`, the sealed source, and proofread reference.
- Produces: a committed rubric file with one `objective_correctness_gate` and three measurable `acceptance_criteria` entries per tone; a committed sealing manifest used by `Assert-Gpt56SealedCampaign`.

- [ ] **Step 1: Extend the corpus contract test before creating the rubric**

Add this test:

```kotlin
@Test
fun sealedCampaignDefinesEveryToneAndRejectsAnyEmDash() {
    val root = File(requireNotNull(System.getProperty("echoLinkAndroidDir")))
    val sealed = Json.parseToJsonElement(
        File(root, "quality/gpt56/sealed-campaign-v2.json").readText(),
    ).jsonObject
    val rubrics = Json.parseToJsonElement(
        File(root, "quality/gpt56/gpt56-five-page-tone-rubrics-v2.json").readText(),
    ).jsonObject
    val requiredTones = sealed.getValue("tone_order").jsonArray
        .map { it.jsonPrimitive.content }

    assertEquals(WritingTone.entries.map(WritingTone::name), requiredTones)
    assertEquals(requiredTones.toSet(), rubrics.keys)
    rubrics.forEach { (_, element) ->
        val rubric = element.jsonObject
        assertTrue(rubric.getValue("objective_correctness_gate").jsonPrimitive.content.isNotBlank())
        assertEquals(3, rubric.getValue("acceptance_criteria").jsonArray.size)
    }
    assertFalse(File(root, sealed.getValue("source_path").jsonPrimitive.content).readText().contains('\u2014'))
    assertFalse(File(root, sealed.getValue("reference_path").jsonPrimitive.content).readText().contains('\u2014'))
}
```

Also replace any mojibake em dash character assertion with `contains('\u2014')`.

- [ ] **Step 2: Run the test and verify the contract is missing**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
.\gradlew.bat --no-daemon --max-workers=1 :app:testReleaseUnitTest --tests com.echolink.mobile.Gpt56FivePageCorpusContractTest
```

Expected: FAIL because both new `quality/gpt56` JSON files are absent.

- [ ] **Step 3: Create the exact tone rubric**

Create JSON with these exact measurable requirements:

| Tone | Acceptance criteria |
| --- | --- |
| PROOFREAD | Meaning and paragraph order unchanged; word count within 5 percent of reference; no optional tone shift or added claim |
| IMPROVE | At least 12 vague or awkward constructions clarified; transitions in at least 4 page openings; 1,500 to 1,850 words |
| REPHRASE | At least 70 percent of non-heading sentences recast; no paragraph copies more than 12 consecutive non-protected words; all headings and list items retained |
| SHORTEN | 30 to 45 percent shorter than reference; every protected fact retained; no sentence longer than 28 words |
| FRIENDLY | At least 6 warm collaborative phrases; 5 to 12 suitable contractions; commands and privacy rules remain unambiguous |
| FORMAL | Zero contractions, slang, or fragments; complete institutional titles and precise modal verbs; all headings and list items use formal register |
| PROFESSIONAL | Every page opens with an action-oriented topic sentence; assignments use consistent role and deadline phrasing; 1,400 to 1,800 words |
| ACADEMIC | At least 8 explicit logical connectors; no invented citations, findings, or evidence; third-person institutional language except protected text |
| CONVERSATIONAL | Second person in 5 to 10 sentences; at least 6 natural contractions; every prohibition and deadline remains explicit |
| CONCISE | 20 to 35 percent shorter than reference; average sentence length at most 18 words; all protected facts and list structure retained |
| DETAILED | Procedural clarification added to at least 8 existing actions; 1,850 to 2,250 words; no new names, dates, quantities, permissions, or claims |
| PERSUASIVE | Clear readiness case in opening and closing; at least 5 fact-based benefit or risk links; no privacy or approval rule softened |
| CONFIDENT | At least 85 percent of finite clauses in active voice; no `maybe`, `perhaps`, or `probably`; insurance remains explicitly unverified |
| DIPLOMATIC | Check-in disagreement described without blame; at least 5 respectful consensus phrases; mandatory privacy and deadline language stays firm |
| EMPATHETIC | Participant concerns, volunteer workload, and staff uncertainty each acknowledged; at least 6 supportive sentences; no penalty implied for refusing consent |
| CASUAL | 8 to 16 contractions and plain vocabulary; no duty-obscuring slang; headings, list, quotation, URLs, and addresses intact |
| TECHNICAL | Precise encryption, locking, offline check-in, access-control, and accessibility terms; at least 8 actor-action-verification requirements; no invented specification |
| SIMPLIFIED | Average sentence length at most 15 words; at least 90 percent of sentences at most 22 words; all protected rules remain explicit |
| HIGH_SCHOOL | Mostly common vocabulary with unavoidable technical terms defined; average sentence length 12 to 20 words; complete meaning and formatting retained |
| COLLEGE | Varied sentence structures and precise transitions on every page; analytic readable register with no invented citations; average sentence length 16 to 24 words |
| COOPERATIVE | `we` or `our` in 8 to 16 suitable sentences; each assignment includes a collaboration or handoff cue; ownership and deadlines remain exact |
| ASSERTIVE | Every obligation uses `must`, `will`, or a direct imperative; zero hedges and zero passive assignments; prohibitions and deadlines remain prominent |
| CAVEMAN | At least 80 percent of eligible sentences contain 3 to 8 words; no eligible sentence exceeds 12 words; every protected fact and formatting element remains explicit |

Every tone uses this exact objective gate:

```text
Correct every planted objective error, introduce zero objective errors, preserve every protected fact and operational meaning, preserve required formatting, and use zero em dashes before tone is evaluated.
```

- [ ] **Step 4: Create and seal the campaign manifest**

Use these exact paths and tone order:

```powershell
$rubricHash=(Get-FileHash -Algorithm SHA256 quality\gpt56\gpt56-five-page-tone-rubrics-v2.json).Hash.ToLowerInvariant()
$manifest=[ordered]@{
    schema_version=2
    document_id='northstar-five-page-all-tones-sealed-v2'
    corpus_path='app/src/androidTest/assets/device_tests/gpt56-five-page-all-tones-v2.json'
    source_path='app/src/androidTest/assets/device_tests/gpt56-five-page-errors-v2.md'
    reference_path='app/src/androidTest/assets/device_tests/gpt56-five-page-proofread-reference-v2.md'
    rubric_path='quality/gpt56/gpt56-five-page-tone-rubrics-v2.json'
    required_score_percent=100
    allowed_introduced_errors=0
    allowed_em_dashes=0
    tone_order=@(
        'PROOFREAD','IMPROVE','REPHRASE','SHORTEN','CONCISE',
        'SIMPLIFIED','HIGH_SCHOOL','CAVEMAN',
        'FORMAL','PROFESSIONAL','ACADEMIC','COLLEGE','TECHNICAL','DETAILED',
        'FRIENDLY','CONVERSATIONAL','CASUAL','COOPERATIVE','EMPATHETIC','DIPLOMATIC',
        'PERSUASIVE','CONFIDENT','ASSERTIVE'
    )
    sha256=[ordered]@{
        corpus='a2aca4386415a30df8f97320051529002011a17cc5f1110d168a0191f8756e78'
        source='4358a8521631c2d962de3ffb7a55f7ea04afdc1b95c2eedbffb0ea641c656dd9'
        reference='7a82fec7bf4637ad80494152c8ec30cc09047d26238f03b768296941b8557fda'
        rubric=$rubricHash
    }
}
$json=$manifest | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText(
    'quality\gpt56\sealed-campaign-v2.json',
    $json+"`n",
    [System.Text.UTF8Encoding]::new($false)
)
```

This computes the rubric hash and writes the manifest before any new device output is run.

- [ ] **Step 5: Run the contract test and verify it passes**

Run the Step 2 command.

Expected: PASS with both contract tests green.

- [ ] **Step 6: Commit the sealed grading contract**

```powershell
git add quality/gpt56/gpt56-five-page-tone-rubrics-v2.json quality/gpt56/sealed-campaign-v2.json app/src/test/java/com/echolink/mobile/Gpt56FivePageCorpusContractTest.kt
git diff --cached --check
git commit -m "test: seal GPT 5.6 all-tone grading contract"
```

---

### Task 2: Write Private, Deterministic Device Evidence

**Files:**
- Modify: `app/src/androidTest/java/com/echolink/mobile/DeviceTestEvidenceWriter.kt`
- Modify: `app/src/androidTest/java/com/echolink/mobile/AllToneFullPageDeviceTest.kt`
- Create: `app/src/androidTest/java/com/echolink/mobile/DeviceTestEvidenceWriterTest.kt`

**Interfaces:**
- Consumes: instrumentation args `tone` and `roundId`.
- Produces: `DeviceTestEvidenceWriter.writePrivate(context, fileName, content): String`; private result file `files/test-evidence/gpt56-<tone>-<roundId>.json`; schema-v2 report with exactly one tone.

- [ ] **Step 1: Write the failing Android evidence test**

```kotlin
@RunWith(AndroidJUnit4::class)
class DeviceTestEvidenceWriterTest {
    @Test
    fun privateEvidenceIsReadableAndRejectsUnsafeNames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val location = DeviceTestEvidenceWriter.writePrivate(
            context,
            "round-proofread-001.json",
            """{"success":true}""",
        )

        assertEquals("""{"success":true}""", File(location).readText())
        assertTrue(location.replace('\\', '/').contains("/files/test-evidence/"))
        assertThrows(IllegalArgumentException::class.java) {
            DeviceTestEvidenceWriter.writePrivate(context, "../escape.json", "{}")
        }
    }
}
```

- [ ] **Step 2: Build and run the test to verify RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
.\gradlew.bat --no-daemon --max-workers=1 '-Pmanuscript.testBuildType=debug' :app:assembleDebugAndroidTest
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s R5CW20R46WD install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s R5CW20R46WD install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adb -s R5CW20R46WD shell am instrument -w -r -e class com.echolink.mobile.DeviceTestEvidenceWriterTest com.remote.Manuscript.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: compilation FAIL because `writePrivate` does not exist.

- [ ] **Step 3: Implement private evidence writing**

Add:

```kotlin
fun writePrivate(context: Context, fileName: String, content: String): String {
    require(Regex("[A-Za-z0-9._-]+").matches(fileName)) {
        "Evidence filename contains unsupported characters."
    }
    val directory = File(context.filesDir, "test-evidence").apply { mkdirs() }
    return File(directory, fileName).apply {
        writeText(content, Charsets.UTF_8)
    }.absolutePath
}
```

Keep the existing public `write` method unchanged for other device tests.

- [ ] **Step 4: Make the all-tone test deterministic per round**

Require:

```kotlin
val arguments = InstrumentationRegistry.getArguments()
val tone = requireNotNull(arguments.getString("tone")?.let(WritingTone::valueOf)) {
    "The tone instrumentation argument is required."
}
val roundId = requireNotNull(arguments.getString("roundId")) {
    "The roundId instrumentation argument is required."
}
require(Regex("[a-z0-9-]{3,64}").matches(roundId)) {
    "roundId must contain lowercase letters, digits, and hyphens only."
}
val tones = listOf(tone)
```

Set report schema to `2`, add top-level `round_id` and `tone`, and write:

```kotlin
val resultFileName = "gpt56-${tone.name.lowercase()}-$roundId.json"
val reportLocation = DeviceTestEvidenceWriter.writePrivate(
    targetContext,
    resultFileName,
    report.toString(2),
)
```

Keep the 30-minute per-tone timeout and paragraph evidence.

- [ ] **Step 5: Verify GREEN**

Rebuild, reinstall, and rerun the command from Step 2.

Expected: `OK (1 test)`.

- [ ] **Step 6: Verify argument failures are explicit**

Run:

```powershell
& $adb -s R5CW20R46WD shell am instrument -w -r -e class com.echolink.mobile.AllToneFullPageDeviceTest com.remote.Manuscript.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: FAIL with `The tone instrumentation argument is required.`

- [ ] **Step 7: Commit**

```powershell
git add app/src/androidTest/java/com/echolink/mobile/DeviceTestEvidenceWriter.kt app/src/androidTest/java/com/echolink/mobile/AllToneFullPageDeviceTest.kt app/src/androidTest/java/com/echolink/mobile/DeviceTestEvidenceWriterTest.kt
git diff --cached --check
git commit -m "test: capture private per-round device evidence"
```

---

### Task 3: Build the Host Contract Module

**Files:**
- Create: `tools/Gpt56QualityLoop.psm1`
- Create: `tools/tests/Test-Gpt56QualityLoop.ps1`

**Interfaces:**
- Produces:
  - `Get-Gpt56Sha256([string]$Path): string`
  - `Get-Gpt56SealedCampaign([string]$ProjectRoot): pscustomobject`
  - `Assert-Gpt56SealedCampaign([string]$ProjectRoot): void`
  - `Test-Gpt56PreGradeGate([pscustomobject]$Report, [string]$Tone): pscustomobject`
  - `New-Gpt56GraderInput([string]$ProjectRoot, [pscustomobject]$Report, [string]$OutputPath): pscustomobject`
  - `Get-Gpt56InvalidatedTones([string]$Scope, [string]$Tone, [string[]]$PassedTones): string[]`
  - `Add-Gpt56LedgerRecord([string]$LedgerPath, [pscustomobject]$Record): void`

- [ ] **Step 1: Write behavioral tests using temporary fixtures**

The test script must:

1. Copy the four sealed artifacts into a temporary project tree.
2. Verify `Assert-Gpt56SealedCampaign` passes.
3. Change one source character and verify the function throws `Sealed hash mismatch for source`.
4. Verify a report with one successful requested tone and no em dash passes.
5. Verify empty output, failed result, wrong tone, and `\u2014` each fail.
6. Verify grader input contains only `document_id`, `tone`, `source`, `reference`, `rubric`, `protected_facts`, `objective_error_inventory`, `output`, and `hashes`.
7. Verify shared changes invalidate all passed tones.
8. Verify a tone-only prompt change invalidates `PROOFREAD`, the selected tone, and no unrelated tone.
9. Verify two ledger writes produce two valid JSONL records without rewriting the first.

Use this assertion helper:

```powershell
function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\tests\Test-Gpt56QualityLoop.ps1
```

Expected: FAIL because `tools/Gpt56QualityLoop.psm1` is missing.

- [ ] **Step 3: Implement the hash and seal functions**

Use `.NET` SHA-256 through `Get-FileHash`, lowercase every hash, resolve every path under `$ProjectRoot`, and compare every entry in `sealed-campaign-v2.json`.

The mismatch must throw:

```text
Sealed hash mismatch for <artifact>: expected <expected>, got <actual>.
```

- [ ] **Step 4: Implement the pre-grade gate**

Return:

```powershell
@{
    Pass = $true
    Tone = $Tone
    OutputSha256 = <lowercase hash of UTF-8 output>
    EmDashCount = 0
}
```

Reject unless:

- report schema is `2`;
- report tone equals requested tone;
- completed tone count is `1`;
- failure count is `0`;
- exactly one result exists;
- result success is true;
- output is nonempty;
- output contains zero `[char]0x2014`;
- output contains exactly one `# ` heading and five `## Page ` headings;
- output contains the required three-item assignment list;
- result output hash equals a fresh host hash.

- [ ] **Step 5: Implement isolated grader input creation**

Build a new object from sealed files and the frozen output. Do not pass through report diagnostics, prompts, app source, prior grades, rejected candidates, or paragraph evidence.

- [ ] **Step 6: Implement invalidation**

Accepted scopes:

```text
tone_prompt
shared_prompt
model
sampling
validator
chunker
cleanup
pipeline
evidence_only
```

Rules:

- `evidence_only`: invalidate none.
- `tone_prompt`: invalidate selected tone and `PROOFREAD`.
- Every other scope: invalidate every passed tone.

- [ ] **Step 7: Implement append-only JSONL writes**

Open with `FileMode.Append`, UTF-8 without BOM, one compact JSON object plus one newline. Never call `Set-Content` on an existing ledger.

- [ ] **Step 8: Run tests and verify GREEN**

Run Step 2.

Expected: `PASS: Gpt56QualityLoop`.

- [ ] **Step 9: Commit**

```powershell
git add tools/Gpt56QualityLoop.psm1 tools/tests/Test-Gpt56QualityLoop.ps1
git diff --cached --check
git commit -m "feat: add GPT 5.6 campaign contract module"
```

---

### Task 4: Build One-Tone Device Round Orchestration

**Files:**
- Create: `tools/run_gpt56_quality_round.ps1`
- Modify: `.gitignore`
- Create: `test-evidence/gpt56-five-page-all-tones-v2/README.md`
- Modify: `tools/tests/Test-Gpt56QualityLoop.ps1`

**Interfaces:**
- Consumes parameters:
  - `-Serial <string>`
  - `-Tone <one of 23 enum names>`
  - `-RoundId <lowercase slug>`
  - `-CampaignRoot <path>`
  - optional `-AdbPath <path>` for deterministic fake ADB tests
  - optional `-SkipBuild`
- Produces:
  - `<CampaignRoot>/<Tone>/<RoundId>/device-stdout.txt`
  - `<CampaignRoot>/<Tone>/<RoundId>/device-stderr.txt`
  - `<CampaignRoot>/<Tone>/<RoundId>/device-report.json`
  - `<CampaignRoot>/<Tone>/<RoundId>/output.md`
  - `<CampaignRoot>/<Tone>/<RoundId>/round-manifest.json`
  - `<CampaignRoot>/<Tone>/<RoundId>/grader-input.json`

- [ ] **Step 1: Add controller contract tests**

Test invalid tone, unsafe round ID, missing device report, em dash output, and a successful fake report. The fake ADB executable must record arguments and return controlled JSON for `exec-out run-as`.

Expected successful instrumentation arguments:

```text
-e class com.echolink.mobile.AllToneFullPageDeviceTest
-e tone PROOFREAD
-e roundId proofread-r001
com.remote.Manuscript.debug.test/androidx.test.runner.AndroidJUnitRunner
```

- [ ] **Step 2: Run host tests and verify RED**

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\tests\Test-Gpt56QualityLoop.ps1
```

Expected: FAIL because the controller is missing.

- [ ] **Step 3: Implement parameter and environment validation**

The script must:

- import `tools/Gpt56QualityLoop.psm1`;
- call `Assert-Gpt56SealedCampaign`;
- reject tones outside sealed `tone_order`;
- reject round IDs outside `[a-z0-9-]{3,64}`;
- require ADB, Java 21, Gradle wrapper, target APK, and test APK;
- require the selected serial in `adb devices -l`;
- refuse to overwrite an existing round directory.

- [ ] **Step 4: Implement build and install**

Unless `-SkipBuild`:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testReleaseUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat --no-daemon --max-workers=1 '-Pmanuscript.testBuildType=debug' :app:assembleDebugAndroidTest
```

Then install with `adb install -r` and `adb install -r -t`.

- [ ] **Step 5: Run one instrumentation tone and capture logs**

Use `System.Diagnostics.Process` so stdout and stderr are captured exactly. A nonzero instrumentation exit preserves logs and writes a failed round manifest.

- [ ] **Step 6: Pull private JSON without MediaStore**

Read:

```text
run-as com.remote.Manuscript.debug cat files/test-evidence/gpt56-<lowercase-tone>-<roundId>.json
```

Write the exact UTF-8 text to `device-report.json`.

- [ ] **Step 7: Freeze output and build manifests**

After `Test-Gpt56PreGradeGate` passes:

- write result output verbatim to `output.md`;
- create `round-manifest.json` with source hashes, APK hashes, Git commit, prompt source-file hashes, model metadata, device metadata, output hash, and timestamps;
- create `grader-input.json` through `New-Gpt56GraderInput`.

- [ ] **Step 8: Verify GREEN**

Run host tests.

Expected: `PASS: Gpt56QualityLoop`.

- [ ] **Step 9: Commit**

```powershell
git add tools/run_gpt56_quality_round.ps1 tools/tests/Test-Gpt56QualityLoop.ps1 .gitignore test-evidence/gpt56-five-page-all-tones-v2/README.md
git diff --cached --check
git commit -m "feat: orchestrate frozen GPT 5.6 device rounds"
```

---

### Task 5: Define and Validate the Isolated Grader Result

**Files:**
- Create: `quality/gpt56/grader-system-v2.md`
- Create: `quality/gpt56/grader-result-v2.schema.json`
- Create: `tools/record_gpt56_grade.ps1`
- Modify: `tools/Gpt56QualityLoop.psm1`
- Modify: `tools/tests/Test-Gpt56QualityLoop.ps1`

**Interfaces:**
- Produces:
  - `Test-Gpt56Grade([pscustomobject]$Grade, [pscustomobject]$GraderInput): pscustomobject`
  - `record_gpt56_grade.ps1 -RoundDirectory <path> -GradeFile <path> -ChangeScope <scope>`
  - `campaign-ledger.jsonl`
  - `active-matrix.json`

- [ ] **Step 1: Write grade validation tests**

Test:

- valid 100/100 grade accepted;
- missing objective inventory item rejected;
- introduced error count above zero rejected as a pass;
- missing protected fact result rejected;
- em dash count above zero rejected;
- failed tone criterion rejected as a pass;
- output hash mismatch rejected;
- a grader result that claims 100 while any gate fails rejected.

- [ ] **Step 2: Run host tests and verify RED**

Expected: FAIL because grade validation is absent.

- [ ] **Step 3: Write the grader system instruction**

The grader instruction must state:

```text
You are an isolated GPT-5.6 Sol acceptance grader.
Read only the supplied grader-input JSON.
Do not inspect Manuscript source code, prompts, logs, prior rounds, or rejected candidates.
Do not repair, normalize, or rewrite the candidate.
Grade every objective-error inventory item and every protected fact.
Find introduced errors across the complete output.
Measure all three assigned-tone criteria.
Count Unicode em dashes.
Return JSON only, matching grader-result-v2.schema.json.
Set score_percent to 100 only when every gate passes. Otherwise set pass to false and score_percent below 100.
```

- [ ] **Step 4: Create the exact result schema**

Require:

```json
{
  "schema_version": 2,
  "document_id": "string",
  "tone": "string",
  "output_sha256": "64 lowercase hex",
  "pass": "boolean",
  "score_percent": "number from 0 through 100",
  "submission_complete": "boolean",
  "objective_results": "array with one result per inventory item",
  "introduced_errors": "array",
  "protected_fact_results": "array with one result per fact",
  "meaning_integrity": {"pass": "boolean", "findings": "array"},
  "formatting": {"pass": "boolean", "heading_count": "integer", "page_heading_count": "integer", "list_item_count": "integer", "findings": "array"},
  "tone_criteria": "array of exactly three measured results",
  "em_dash_count": "integer",
  "failure_summary": "array"
}
```

Each inventory, fact, and tone criterion result includes `index`, `pass`, `evidence`, and `reason`.

- [ ] **Step 5: Implement strict grade validation**

`pass=true` requires:

- `score_percent=100`;
- complete submission;
- every objective result passes;
- zero introduced errors;
- every protected fact passes;
- meaning passes;
- formatting passes with one H1, five page H2 headings, and three assignment list items;
- all three tone criteria pass;
- zero em dashes;
- output hash, document ID, and tone match grader input.

Any mismatch throws and does not update the ledger.

- [ ] **Step 6: Implement grade recording**

Write one append-only ledger record with:

- tone and round ID;
- grade hash;
- output hash;
- pass and score;
- change scope;
- invalidated tone list;
- commit and app/model identities from the round manifest;
- UTC timestamp.

Then reconstruct `active-matrix.json` from the ledger. Never edit ledger history.

- [ ] **Step 7: Verify GREEN**

Run host tests.

Expected: `PASS: Gpt56QualityLoop`.

- [ ] **Step 8: Commit**

```powershell
git add quality/gpt56/grader-system-v2.md quality/gpt56/grader-result-v2.schema.json tools/record_gpt56_grade.ps1 tools/Gpt56QualityLoop.psm1 tools/tests/Test-Gpt56QualityLoop.ps1
git diff --cached --check
git commit -m "feat: validate isolated GPT 5.6 grades"
```

---

### Task 6: Verify the Complete Harness Before Expensive Runs

**Files:**
- Verify only.

**Interfaces:**
- Consumes all Tasks 1 through 5.
- Produces a locally verified and S23-verified campaign harness.

- [ ] **Step 1: Run host behavior tests**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\tests\Test-Gpt56QualityLoop.ps1
```

Expected: `PASS: Gpt56QualityLoop`.

- [ ] **Step 2: Run Android local gates**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
.\gradlew.bat --no-daemon --max-workers=1 :app:testReleaseUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat --no-daemon --max-workers=1 '-Pmanuscript.testBuildType=debug' :app:assembleDebugAndroidTest
```

Expected: both commands exit `0`.

- [ ] **Step 3: Run focused S23 evidence tests**

Install both APKs and run:

```powershell
& $adb -s R5CW20R46WD shell am instrument -w -r -e class com.echolink.mobile.DeviceTestEvidenceWriterTest com.remote.Manuscript.debug.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s R5CW20R46WD shell am instrument -w -r -e class com.echolink.mobile.LocalAiSmokeDeviceTest com.remote.Manuscript.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (1 test)` twice.

- [ ] **Step 4: Run a short controlled controller probe**

Use a test-only small corpus class, not the sealed acceptance output, to verify that the controller creates all six expected files and the grader input omits diagnostics.

Expected: no sealed five-page result has been observed yet, so rubric sealing remains valid.

- [ ] **Step 5: Commit any harness-only corrections through TDD**

If a harness defect appears, fix only that defect, rerun Steps 1 through 4, and commit:

```powershell
git commit -m "fix: harden GPT 5.6 campaign harness"
```

---

### Task 7: Run and Grade PROOFREAD to 100/100

**Files:**
- Generated: `test-evidence/gpt56-five-page-all-tones-v2/PROOFREAD/<roundId>/`
- Modify only after a proven failure: the smallest relevant production file and its focused regression test.

**Interfaces:**
- Consumes: the sealed campaign, S23 controller, grader prompt, and schema.
- Produces: one current, non-invalidated 100/100 PROOFREAD round.

- [ ] **Step 1: Run the first full five-page round**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\run_gpt56_quality_round.ps1 `
  -Serial R5CW20R46WD `
  -Tone PROOFREAD `
  -RoundId proofread-r001 `
  -CampaignRoot test-evidence\gpt56-five-page-all-tones-v2
```

Expected: a frozen `output.md`, report, logs, manifest, and isolated `grader-input.json`.

- [ ] **Step 2: Launch a fresh isolated GPT-5.6 Sol grader**

Use a fresh agent context with no forked conversation history. Give it only:

- `quality/gpt56/grader-system-v2.md`
- the round's `grader-input.json`
- `quality/gpt56/grader-result-v2.schema.json`
- the exact output path for `grade.json`

Explicitly prohibit reading any other workspace file.

- [ ] **Step 3: Validate and record the grade**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\record_gpt56_grade.ps1 `
  -RoundDirectory test-evidence\gpt56-five-page-all-tones-v2\PROOFREAD\proofread-r001 `
  -GradeFile test-evidence\gpt56-five-page-all-tones-v2\PROOFREAD\proofread-r001\grade.json `
  -ChangeScope evidence_only
```

- [ ] **Step 4: If the grade fails, perform one evidence-led repair**

For each failed round:

1. Preserve the round unchanged.
2. Select the earliest objective failure or most severe introduced semantic error.
3. Reproduce it in a focused unit test using the smallest source sentence or paragraph.
4. Run the test and confirm RED for the observed reason.
5. Trace prompt, model, sampling, validator, chunker, cleanup, or runtime data flow.
6. Change one layer only.
7. Run the focused test to GREEN.
8. Run host tests, full unit tests, lint, build, and Local AI smoke.
9. Record invalidation scope.
10. Commit the verified repair.
11. Run `proofread-r002`, then `proofread-r003`, increasing only the round number.

If three fixes for the same cause fail, stop and write an architecture decision before another change.

- [ ] **Step 5: Stop only at a validated current pass**

Expected matrix entry:

```json
{
  "tone": "PROOFREAD",
  "pass": true,
  "score_percent": 100,
  "invalidated": false
}
```

---

### Task 8: Run the Remaining 22 Tones Sequentially

**Files:**
- Generated: one append-only round directory per attempt.
- Modify only after a proven grade failure: the smallest relevant production file and focused regression.

**Interfaces:**
- Consumes: current PROOFREAD pass.
- Produces: a current 23/23 active matrix.

- [ ] **Step 1: Run each tone in this exact order**

For each tone, run this exact command after setting the two values from the table:

```powershell
$tone='IMPROVE'
$roundId='improve-r001'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\run_gpt56_quality_round.ps1 `
  -Serial R5CW20R46WD `
  -Tone $tone `
  -RoundId $roundId `
  -CampaignRoot test-evidence\gpt56-five-page-all-tones-v2
```

| Tone | First round ID |
|---|---|
| IMPROVE | `improve-r001` |
| REPHRASE | `rephrase-r001` |
| SHORTEN | `shorten-r001` |
| CONCISE | `concise-r001` |
| SIMPLIFIED | `simplified-r001` |
| HIGH_SCHOOL | `high-school-r001` |
| CAVEMAN | `caveman-r001` |
| FORMAL | `formal-r001` |
| PROFESSIONAL | `professional-r001` |
| ACADEMIC | `academic-r001` |
| COLLEGE | `college-r001` |
| TECHNICAL | `technical-r001` |
| DETAILED | `detailed-r001` |
| FRIENDLY | `friendly-r001` |
| CONVERSATIONAL | `conversational-r001` |
| CASUAL | `casual-r001` |
| COOPERATIVE | `cooperative-r001` |
| EMPATHETIC | `empathetic-r001` |
| DIPLOMATIC | `diplomatic-r001` |
| PERSUASIVE | `persuasive-r001` |
| CONFIDENT | `confident-r001` |
| ASSERTIVE | `assertive-r001` |

- [ ] **Step 2: Freeze, isolate, grade, and record every round**

For each tone:

1. Run the controller.
2. Launch a fresh GPT-5.6 Sol grader with no history.
3. Validate `grade.json`.
4. Record it.
5. If score is below 100, follow Task 7 Step 4.
6. Do not start the next tone until the current tone is 100 and non-invalidated.

- [ ] **Step 3: Enforce invalidation immediately**

After a repair:

- `tone_prompt`: rerun the selected tone and PROOFREAD.
- `shared_prompt`, `model`, `sampling`, `validator`, `chunker`, `cleanup`, or `pipeline`: rerun every tone that previously passed.
- `evidence_only`: rerun only the interrupted or corrupt round.

No invalidated pass counts toward 23/23.

- [ ] **Step 4: Commit each verified repair separately**

Use one of:

```powershell
$toneSlug=$tone.ToLowerInvariant().Replace('_','-')
git commit -m "fix: improve $toneSlug five-page correctness"
git commit -m "fix: preserve facts during $toneSlug rewriting"
git commit -m "fix: enforce $toneSlug style without em dashes"
```

Do not commit frozen evidence with production code. Preserve evidence on disk under the campaign directory.

---

### Task 9: Final Regression, Reconciliation, and Handoff

**Files:**
- Generated: `test-evidence/gpt56-five-page-all-tones-v2/active-matrix.json`
- Generated: `test-evidence/gpt56-five-page-all-tones-v2/final-audit.json`
- Modify: `test-evidence/gpt56-five-page-all-tones-v2/README.md`

**Interfaces:**
- Consumes: active ledger and all 23 current passes.
- Produces: a reconciled final audit with `100 percent result: YES`.

- [ ] **Step 1: Reconstruct the matrix from the ledger**

Require:

- exactly 23 tone keys;
- every key matches sealed tone order;
- every active grade is 100;
- every pass is non-invalidated;
- every output, report, and grade file exists;
- every stored hash matches a fresh hash;
- every em dash count is zero.

- [ ] **Step 2: Run fresh host and Android gates**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\tests\Test-Gpt56QualityLoop.ps1
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
.\gradlew.bat --no-daemon --max-workers=1 :app:testReleaseUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat --no-daemon --max-workers=1 '-Pmanuscript.testBuildType=debug' :app:assembleDebugAndroidTest
```

Expected: all commands exit `0`.

- [ ] **Step 3: Run final focused S23 checks**

Run:

```powershell
& $adb -s R5CW20R46WD shell am instrument -w -r -e class com.echolink.mobile.LocalAiSmokeDeviceTest com.remote.Manuscript.debug.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s R5CW20R46WD shell am instrument -w -r -e class com.echolink.mobile.MainToneMenuDeviceTest#pastedTextKeepsEveryToneInAVisibleSwipeableMenu com.remote.Manuscript.debug.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s R5CW20R46WD shell am instrument -w -r -e class com.echolink.mobile.MainToneMenuDeviceTest#reviewCardUsesSwipeableToneMenuAndStaysSyncedWithMainMenu com.remote.Manuscript.debug.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s R5CW20R46WD shell am instrument -w -r -e class com.echolink.mobile.MainToneMenuDeviceTest#acceptStaysVisibleAboveLongCorrectionText com.remote.Manuscript.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (1 test)` four times.

- [ ] **Step 4: Verify installed package and crash state**

Confirm:

- installed version name and code match the final APK;
- app process launches;
- no `FATAL EXCEPTION`, app ANR, or process crash appears after launch;
- installed model identity matches every active round.

- [ ] **Step 5: Write final audit**

`final-audit.json` must include:

```json
{
  "document_id": "northstar-five-page-all-tones-sealed-v2",
  "outputs_received": 23,
  "outputs_passed": 23,
  "outputs_failed": 0,
  "overall_percentage": 100,
  "em_dash_count": 0,
  "hashes_reconciled": true,
  "invalidated_passes": 0,
  "100_percent_result": "YES"
}
```

- [ ] **Step 6: Update campaign README and commit only code and documentation**

```powershell
git add test-evidence/gpt56-five-page-all-tones-v2/README.md
git diff --cached --check
git commit -m "docs: record GPT 5.6 all-tone acceptance"
```

Do not claim completion unless the fresh commands and final audit prove all requirements.
