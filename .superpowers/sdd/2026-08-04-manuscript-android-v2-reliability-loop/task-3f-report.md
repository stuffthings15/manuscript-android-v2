# Task 3F gap inventory

## Existing guarantees

- Durable telemetry covers every approved phase, exact target, generation, work UUID,
  downloaded and total bytes, network, rolling samples, checksum state, Retry, and Cancel.
- Rolling speed and ETA are computed from bounded real samples. Before enough samples,
  the shared presentation says `Calculating ETA`.
- MainActivity reads the exact durable target and renders the shared presentation.
- Cancel persists Paused before exact work cancellation. Retry reserves and schedules a
  fresh generation and work identity.
- MainActivity, the accessibility panel, and Process Text all enter the correction engine
  through `polishDocumentTerminal` or `polishTerminal`.
- The correction watchdog provides once-only terminal delivery, timeout, blank-output
  rejection, known-error unchanged-output rejection, Retry, and Model Required.

## Concrete gaps at base 4d5e0b0

1. Both foreground workers build notifications from raw percent and a policy label.
   They do not use `DownloadTelemetryPresentation`, so phase, exact bytes, rolling speed,
   truthful ETA, failure, Paused, Ready, and Retry can disagree with MainActivity.
2. Worker notifications always expose Cancel and never derive actions from the shared
   presentation. Failed and Paused cannot expose a real exact-attempt Retry action.
3. Verifying notifications retain the last download percent text and cannot prove that
   speed and ETA are hidden.
4. No test inspects a built notification. Current notification coverage is source text.
5. `polishDocumentTerminal` constructs the watchdog, then calls `polishDocument` without
   catching a synchronous start exception. That exception can escape before any terminal
   callback reaches any correction surface.
6. `CorrectionTerminalResult.Success` accepts corrected text containing an em dash.
   The global product contract forbids that output, so terminal validation is incomplete.
7. `CorrectionTerminalSurfaceSourceTest` proves symbol presence only. It does not prove
   the three surfaces terminate on synchronous start failure or render real Retry and AI
   Required behavior.

## Required TDD order

1. Built notification consumes the shared presentation and exact actions.
2. Synchronous correction start failures become one RetryableFailure.
3. Generic terminal success rejects em dash output.
4. Focused tests, related regressions, then full debug and release tests, lint, APKs, and
   instrumentation compilation under JDK 21.

## Baseline execution note

The first baseline command could not start because the wrapper requested network. The
cached Gradle binary then could not open the repository project cache due host ACL denial.
No baseline test pass is claimed from those two attempts.

## Implemented local contract

- Both model workers now build foreground notifications from the exact durable
  `DownloadUiSnapshot` and shared `DownloadTelemetryPresentation`.
- Generation, target, and actionable work identity are fenced before notification
  actions are exposed.
- Persisted phase changes and successful Ready commits refresh the shared foreground
  notification after persistence.
- Failed notifications expose only exact-attempt Retry. Active downloads expose only
  exact-attempt Cancel.
- The Retry receiver is explicit, non-exported, immutable, generation-bound,
  target-bound, work-bound, and schedules through `AndroidDownloadServices.retry`.
- All three correction surfaces use the shared guarded terminal entry point.
  Synchronous backend start failure becomes one Retry result.
- Blank text, known-error unchanged text, timeout, cancellation, thrown failure, and
  em-dash output cannot become terminal Success. A missing model becomes AI Required.

## TDD evidence

- Target fencing: RED `3 tests completed, 1 failed`; GREEN `4/4`.
- Actionable-attempt fencing: RED `4 tests completed, 1 failed`; GREEN in the same
  focused `4/4` gate.
- Ready refresh: RED `2 tests completed, 1 failed`; GREEN in the worker aggregate.
- Worker notification wiring: RED `3 tests completed, 1 failed`; GREEN `11/11` across
  notification wiring, fencing, notifier, and typed-input groups.
- Related aggregate command:
  `:app:testDebugUnitTest --tests '*Download*' --tests
  com.echolink.mobile.CorrectionRequestWatchdogTest --tests
  com.echolink.mobile.CorrectionTerminalSurfaceSourceTest`
  passed `284/284` after the exact worker contract was restored.

## Fresh full local gate

JDK 21 command:

` :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease
:app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest `

- Exit: `0`, `BUILD SUCCESSFUL in 1m 13s`.
- Debug unit tests: `850`, failures `0`, errors `0`, skipped `0`.
- Release unit tests: `850`, failures `0`, errors `0`, skipped `0`.
- Debug lint: passed.
- Release lint: passed.
- Debug APK: built.
- Release APK: built.
- Debug instrumentation APK: built.
- `git diff --check`: passed.

## Device boundary

- Earlier emulator-only built-notification proof passed `1/1` and preserved all three
  model file sizes. The later Retry built-notification case has only compiled locally.
- No physical device was touched in Task 3F.
- Task 3F is locally green, not device-complete and not independently reviewed.

## Independent review fix

Review found that a backend `cancel()` exception could escape before the watchdog
delivered Retry. A strict test captured RED: `15 tests completed, 1 failed`.

The shared watchdog now always attempts terminal cancellation after backend cancellation.
If both fail, the backend failure remains primary and the terminal observer failure is
suppressed. The terminal value is stored and delivered exactly once for all three shared
correction surfaces.

- Focused watchdog and three-surface group: `15/15`.
- Updated related aggregate: `285/285`.
- Fresh full JDK 21 gate: `BUILD SUCCESSFUL in 1m 10s`, exit `0`.
- Debug unit tests: `851`, failures `0`, errors `0`, skipped `0`.
- Release unit tests: `851`, failures `0`, errors `0`, skipped `0`.
- Debug and release lint: passed.
- Debug, release, and debug instrumentation APKs: built.
- `git diff --check`: passed.
- Device boundary unchanged. No device action occurred for this review fix.

## Exact emulator gate and restart fix

The first exact emulator run used product APK
`F6C6A7BD739C936EF5556C3A9577D4DC39CA88FB1CE1140BB9ECA435B457BD6E`
and test APK
`41FB698B94D5725C48F58A72A859FA8F0DE88127A22002178B95AE6D7B3017D0`.
Both installed with `adb install -r -t`; no uninstall or application-data clear occurred.
The gate was RED `3/4`: Cancel could not find a re-enqueued exact durable Downloading
attempt after MainActivity restart.

Root cause: MainActivity read the durable snapshot but never resumed an active durable
attempt. Strict local RED was captured before the fix. Commit `a80a35c` adds active-only
resume through the lifecycle coordinator and services, called once during MainActivity
creation. It preserves the exact target, generation, and work identity and never reserves
a replacement generation.

- Focused aggregate after the fix: passed.
- Full local gate: `852` debug and `852` release tests, zero failures; debug and release
  lint passed; debug, release, and test APKs built.
- Exact committed product APK:
  `55D83269419D39F0E99A643482B4AD98BFACA024A2FF8425DEA022A112296254`,
  `211493834` bytes.
- Exact committed test APK:
  `42B7855A277A2CC032B708542439291AB2105580E8F259ABB5C4657715E9F84B`,
  `842922` bytes.
- Final approved classes on `emulator-5554`: `OK (4 tests)` in `6.104s`.
- Model sizes before and after were identical: `639446688`, `1282439584`,
  `2497280256` bytes.
- Exact V2 accessibility service was enabled and present in `Bound services`.
- Accessibility `Crashed services:{}` and filtered `AndroidRuntime:E` output was empty.
- Physical device `RFCRA0A6CFH` was listed only and never targeted.
