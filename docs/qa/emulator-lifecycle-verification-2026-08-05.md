# Emulator Lifecycle Verification - 2026-08-05

This is emulator evidence only. It does not close any physical Galaxy S23 Plus gate.

## Source and artifacts

- Branch: `codex/manuscript-android-v2`
- Product commit: `20dab2325844d1f02d5b58eb146c2494e1102f25`
- Harness commits: `4b2eb57` and `4e2cbfe`
- Product APK SHA-256: `285F68E4C4311F24A180C4A1831D23D7E8D372B498D40020E3E0FDD49828986C`
- Product test APK SHA-256: `C762B4F605EDA7B1A0387776B242DE9EFAF5D62134ABC29DA9EC516872D96D2F`
- Fixture APK SHA-256: `A9035AAC2F1D2F87EBB2C78AF20D4BAE7C53572D884DBBAF9076112153017A29`
- Fixture test APK SHA-256: `9871FF014700D1EEC7FC8DC32C66B807F3E003E8696AE071CDC12A7014CC96D9`
- All four local hashes matched the installed APKs on API 36 and API 26.

## Local build gate

JDK 21 completed the final warm-cache gate with `BUILD SUCCESSFUL` in 27 seconds, 309 tasks. The preceding clean native-runtime rebuild gate also passed in 1 minute 36 seconds:

- App debug and release unit tests.
- App debug and release lint.
- App debug APK and instrumentation APK.
- Fixture unit tests and debug lint.
- Fixture debug APK and instrumentation APK.
- Vendored llama runtime sentinel verification.

The four committed artifacts were then rebuilt in 42 seconds, 161 tasks. The final harness-only app test APK was rebuilt from `4e2cbfe` in 40 seconds, 79 tasks. The product APK hash stayed unchanged.

## Close and reopen lifecycle

Physical host taps were used because in-process instrumentation can be starved by native inference before Android receives `ACTION_DOWN`.

| Device | Cold cycles | Warm cycles | Maximum `ACTION_DOWN` to Closing render |
| --- | ---: | ---: | ---: |
| API 36 emulator | 10 of 10 | 10 of 10 | 2 ms warm with the final harness |
| API 26 emulator | 10 of 10 | 10 of 10 | 207 ms cold, 5 ms warm |

Active-request host close also passed against the committed product APK. API 26 reached Closing 2 ms after Android delivered `ACTION_DOWN`.

The final harness waits until the Close target has identical global bounds in two separate pre-draw frames before it asks the host to tap. A focused source regression failed before this change and passed afterward. This removed the API 26 false tap caused by a transient first-frame coordinate.

## Native restart regression

An intermediate APK used Android background and minimum priority for the dedicated llama runtime thread. Repeated open, close, and restart stress produced a native `SIGABRT` when ART could not suspend a long `FastNative` model call. A focused source test failed before the fix. The runtime now keeps its dedicated off-UI executor at normal priority, the focused test passes, and the complete final lifecycle matrix ended with zero package crash entries.

## Across-app lifecycle

- API 36 external editor fixture: 5 consecutive passes.
- API 26 external editor fixture: 5 consecutive passes.
- API 26 correctly allowed the Close target to disappear after accepted `ACTION_DOWN` even when the platform rejected the later `ACTION_UP` for the removed window.
- During pre-final harness validation, one API 36 video-probe attempt missed the returned trigger after a 3.764 second harness lookup. Product logs recorded panel detach at 282 ms and trigger first draw at 480 ms. The final-APK five-run consecutive gate passed.

## Brand and download controls

Both emulators passed the same seven device tests:

- Three rendered brand and adaptive-mask tests.
- Two foreground download-notification tests.
- Two Retry and Cancel download-telemetry tests.

## Local AI proof

The locked sentence was corrected to:

`Dogs wag their tails because it helps people understand how they feel.`

| Device | Hardware-selected model | Final correction | Selected model SHA-256 |
| --- | --- | ---: | --- |
| API 36 emulator | Qwen3 1.7B Q4_K_M, Balanced | 6.536 s | `72C5C3CB38FA32D5256E2FE30D03E7A64C6C79E668AD84057E3BD66E250B24FB` |
| API 26 emulator | Qwen3 0.6B Q8_0, Compact | 49.432 s | `9465E63A22AD5354D9BB4B99E90117043C7124007664907259BD16D043BB031` |

The explicit Compact test also passed with correct output in 3.441 seconds on API 36 and 46.685 seconds on API 26. No model tier, context, token, or inference-thread cap was changed. The model policy files remain unchanged. Native work stays on a dedicated normal-priority executor, off the UI thread.

## Restored emulator state

- Exact enabled service: `com.remote.Manuscript.v2.debug/com.echolink.mobile.ManuscriptAccessibilityService`.
- Service bound on both emulators.
- Zero stale `DEAD` service connections.
- Zero package entries in the crash log buffer.
- API 36 reported an empty accessibility crashed-service set.
- Debug model override restored to false or absent.

## Open proof boundary

No physical S23 Plus test was run, and the attached physical device was not touched. Launcher approval, Samsung Settings guidance, keyboard behavior in Samsung apps, three identical-APK S23 loops, release signer, AAB, and Play upload remain open.
