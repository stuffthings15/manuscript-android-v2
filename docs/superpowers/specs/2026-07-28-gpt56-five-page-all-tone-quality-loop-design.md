# GPT-5.6 Five-Page All-Tone Quality Loop

## Purpose

Build a repeatable acceptance loop that sends one sealed, error-heavy five-page document through Manuscript on the Samsung S23 for every supported tone. Each raw output is frozen and graded by a fresh isolated GPT-5.6 Sol grader. A tone passes only at exactly 100 percent.

The loop may improve prompts, model selection, sampling, validators, chunking, deterministic cleanup, or other system components. It may never weaken or rewrite the sealed corpus, rubric, protected facts, or acceptance thresholds after outputs have been observed.

## Scope

The campaign covers all 23 `WritingTone` values:

1. `PROOFREAD`
2. `IMPROVE`
3. `REPHRASE`
4. `SHORTEN`
5. `FRIENDLY`
6. `FORMAL`
7. `PROFESSIONAL`
8. `ACADEMIC`
9. `CONVERSATIONAL`
10. `CONCISE`
11. `DETAILED`
12. `PERSUASIVE`
13. `CONFIDENT`
14. `DIPLOMATIC`
15. `EMPATHETIC`
16. `CASUAL`
17. `TECHNICAL`
18. `SIMPLIFIED`
19. `HIGH_SCHOOL`
20. `COLLEGE`
21. `COOPERATIVE`
22. `ASSERTIVE`
23. `CAVEMAN`

The campaign uses the same frozen document for every tone. Short excerpts may be used for diagnosis, but only a complete five-page S23 run can produce an acceptance candidate.

## Sealed Inputs

Document ID: `northstar-five-page-all-tones-sealed-v2`

| Artifact | SHA-256 |
| --- | --- |
| `app/src/androidTest/assets/device_tests/gpt56-five-page-all-tones-v2.json` | `a2aca4386415a30df8f97320051529002011a17cc5f1110d168a0191f8756e78` |
| `app/src/androidTest/assets/device_tests/gpt56-five-page-errors-v2.md` | `4358a8521631c2d962de3ffb7a55f7ea04afdc1b95c2eedbffb0ea641c656dd9` |
| `app/src/androidTest/assets/device_tests/gpt56-five-page-proofread-reference-v2.md` | `7a82fec7bf4637ad80494152c8ec30cc09047d26238f03b768296941b8557fda` |

The source contains 1,677 whitespace-delimited words across five marked pages. It deliberately includes grammar, spelling, vocabulary, sentence structure, punctuation, capitalization, agreement, formatting, and factual-permission defects.

These files are immutable during the campaign. A hash mismatch blocks execution and invalidates the affected round.

## Architecture

### 1. Campaign controller

The controller runs one tone at a time. It records the current tone, round number, app build, model identity, prompt revision, device identity, start time, progress, result, output hash, grader result, and invalidation state.

The controller resumes after a device disconnect or host interruption without discarding completed artifacts. It never treats partial text as a gradeable output.

### 2. S23 device runner

The existing `AllToneFullPageDeviceTest` remains the real-model boundary. Each acceptance run uses:

- Samsung S23 device serial selected by the controller
- Installed Manuscript debug build under test
- Installed on-device AI model
- `QualityMode.THOROUGH`
- One explicitly selected tone
- The full sealed five-page source
- Per-paragraph progress and evidence
- A per-tone timeout

The runner captures:

- Raw final output
- Original, first-pass, pre-review, and final paragraph text
- Review and fallback flags
- Safety risks and failures
- Duration
- App version and version code
- Device model and Android API
- AI file, tier, backend, and sampling configuration
- Source, reference, and output hashes

### 3. Host evidence collector

The host copies the device result into an append-only campaign directory. It validates JSON structure and hashes before marking the output frozen.

A pre-grade gate rejects:

- Missing, empty, truncated, or duplicated output
- Test crash, timeout, or missing callback
- Source or rubric hash mismatch
- Protected-token corruption
- Lost headings, list structure, or page order
- Any Unicode em dash

The collector never repairs, normalizes, or rewrites the output.

### 4. Isolated GPT-5.6 Sol grader

Every frozen output is graded in a fresh isolated context. The grader receives only:

- The sealed corpus manifest
- The sealed source and proofread reference
- The rubric for the assigned tone
- The frozen Manuscript output
- Hash and identity metadata

The grader does not receive Manuscript source code, prompts, prior failures, repair ideas, or intermediate output.

The grader returns structured JSON containing:

- Submission completeness
- Every planted-error result
- Every introduced-error finding with excerpt and rule
- Every protected-fact result
- Meaning and instruction integrity
- Heading and list preservation
- Measured tone criteria
- Em dash count
- Final score and pass or fail verdict

No averaging, curve, tolerance, best-of selection, or manual override can create a pass.

### 5. Failure analyzer

Failed results are classified using evidence before changes are proposed:

- Prompt or tone instruction
- Model capability or model selection
- Sampling or output budget
- Protected-fact validator
- Paragraph chunking or context
- Deterministic cleanup
- Runtime, timeout, or device failure
- Grader or evidence failure

The analyzer identifies one smallest proven cause for the next repair attempt. A grader failure is retried with a fresh grader and does not count as a Manuscript score.

## Repair Loop

For each failed tone:

1. Preserve the frozen output and grade.
2. Reproduce the defect with the smallest useful test.
3. Trace the failure to one system boundary.
4. Add a regression that fails for the proven cause.
5. Change one layer only.
6. Run the focused regression.
7. Run the full local unit, lint, and build gates.
8. Install the verified build on the S23.
9. Rerun the complete five-page tone.
10. Freeze and hash the raw output.
11. Grade it in a fresh isolated GPT-5.6 Sol context.
12. Repeat until the tone scores 100 percent.

If three attempted fixes for the same cause fail, stop adding patches and review the architecture before another production change.

The source, reference, manifest, protected facts, objective-error inventory, and tone rubrics remain unchanged throughout the loop.

## Tone Order

The campaign follows dependency order:

1. Correctness foundation: `PROOFREAD`
2. Core rewriting: `IMPROVE`, `REPHRASE`, `SHORTEN`, `CONCISE`
3. Accessibility and compression: `SIMPLIFIED`, `HIGH_SCHOOL`, `CAVEMAN`
4. Register and depth: `FORMAL`, `PROFESSIONAL`, `ACADEMIC`, `COLLEGE`, `TECHNICAL`, `DETAILED`
5. Interpersonal style: `FRIENDLY`, `CONVERSATIONAL`, `CASUAL`, `COOPERATIVE`, `EMPATHETIC`, `DIPLOMATIC`
6. Influence and firmness: `PERSUASIVE`, `CONFIDENT`, `ASSERTIVE`

Proofread is first because every other tone requires the full correctness gate before style is evaluated.

## Invalidation Rules

A shared prompt, model, sampling, validator, chunker, cleanup, or pipeline change invalidates every earlier pass that depends on it. Those tones must run and grade again.

A tone-only prompt change invalidates:

- The changed tone
- `PROOFREAD`
- Any previously passed tone that shares the changed instruction fragment

Changing the sealed corpus or rubric is prohibited. If an intentional new campaign needs a different corpus or rubric, it receives a new document ID, hashes, evidence directory, and baseline.

## Acceptance Criteria

A tone passes only when all conditions are true:

- Complete five-page output
- All planted objective errors corrected
- Zero introduced objective errors
- Every protected fact preserved
- Meaning, actor, permission, prohibition, obligation, sequence, and deadline integrity preserved
- All headings, list items, hierarchy, paragraph order, URLs, email addresses, quotation, and formatting preserved as required
- Every measurable assigned-tone criterion passes
- Zero em dashes
- Final grader score is exactly 100/100

The campaign passes only when:

- All 23 tones have current, non-invalidated passing artifacts
- The final matrix is 23/23
- The overall result says `100 percent result: YES`
- Every output and grade hash reconciles with the campaign ledger
- The final app build passes unit tests, lint, build, focused device tests, and installed-version verification

## Error Handling

- Device disconnect: keep current evidence, mark the attempt interrupted, reconnect, and rerun the same tone.
- Timeout: preserve paragraph evidence, mark the attempt failed, diagnose before changing code or timeout.
- App or model crash: preserve logs and runtime metadata, then reproduce before repair.
- Missing or corrupt evidence: block grading and rerun collection.
- Grader parse failure: preserve the response, start a fresh isolated grading context, and do not score the failed grader call.
- Hash mismatch: stop the campaign until the sealed artifact is restored or a new campaign is explicitly approved.

## Audit Package

Each round stores:

- Sealed input hashes
- Build and device metadata
- Raw device logs
- Paragraph evidence
- Frozen raw output
- Frozen output hash
- Structured grader input manifest
- Structured grader result
- Human-readable defect report
- Regression and verification evidence
- Invalidation decisions

The final package includes the complete 23-tone matrix, all active hashes, all final grades, the app and model identity, and the explicit 100 percent result.
