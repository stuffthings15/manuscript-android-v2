# Manuscript Android Version 2 Reliability Loop

## Global constraints

- Version 1 remains untouched and installed.
- Version 2 installs beside Version 1 as `com.remote.Manuscript.v2`.
- Debug installs as `com.remote.Manuscript.v2.debug`.
- User content and correction text remain local and private.
- Every nonblank section gets a live AI attempt.
- Every correction ends with valid text, Retry, or AI Required.
- Manuscript output never contains an em dash.
- Real taps and swipes are required for acceptance. `performClick` cannot rescue missed touch input.
- Version 2 is not uploaded to Google Play during this loop.

## Task 1: Frozen independent repository

Create the standalone Version 2 repository from the approved current Version 1 working state. Record source and vendor commits, dirty-diff hashes, copied-file hashes, corpus hashes, and exclusions. Preserve the canonical ZIP and all 20 DOCX files unchanged.

## Task 2: Isolated Version 2 identity

Set display name `Manuscript Android Version 2`, launcher name `Manuscript V2`, package `com.remote.Manuscript.v2`, debug package `com.remote.Manuscript.v2.debug`, version `2.0.0-internal`, code `1`, and login enforcement off. Keep Kotlin namespace `com.echolink.mobile` and use the local shared contract.

## Task 3: Reliability mechanisms

Build the shared official correction header, rendered brand checks, `ReviewSurfaceState`, idempotent close and focus restoration, immediate first-frame upward expansion, persistent `DownloadTelemetry`, and one terminal correction result across all entry points. Use test-driven development for every production behavior change.

## Task 4: Automated and emulator gates

Run unit tests, lint, debug build, instrumentation build, rendered icon checks, API 26 and modern emulator flows, lifecycle, accessibility, low-memory, offline, network interruption, model, correction, and real gesture tests. Fix and reopen every failure.

## Task 5: Physical S23 acceptance

Force-stop Version 2, preserve data, install with `adb install -r -t`, disable Version 1 accessibility during testing, and exercise the complete user paths on `R5CW20R46WD`. Enforce the approved timing gates and run 20 open, close, and reopen cycles.

## Task 6: Twenty-essay and all-tone quality loop

Run all 200 nonblank sections through the real MainActivity Proofread UI. Require live AI provenance for every section. Run every actual tone on the assigned essays and through the across-app panel. Judge source and output twice with GPT-5.6 Sol and the locked 100-point rubric. Any score below 100 or any hard-gate failure triggers a generic regression and rerun.

## Task 7: Frozen final evidence

Run three unchanged-APK S23 loops. Bind commit, packages, signer, model, corpus, tests, judges, device, screenshots, APK, AAB, and failures in one release manifest. Complete independent review and commit without Play upload.
