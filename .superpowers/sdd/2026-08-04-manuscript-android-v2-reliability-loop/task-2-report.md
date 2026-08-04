# Task 2 report

## Result

Task 2 is implemented and locally verified against source commit
`94e416888a1704c9eedebfdace0c89cd3ceacd07`.

## RED evidence

- The first focused Pester run failed three tests because the copied project
  still used the Version 1 project name and identity, had no V2 contract, and
  required a nested llama Git repository plus build-time patching.
- The untouched baseline `testReleaseUnitTest` run failed at
  `:llama-runtime:prepareManuscriptLlamaRuntime` because nested Git data was
  intentionally excluded from the standalone copy.
- Independent review found a wrong instrumentation package, active Version 1
  Play upload paths, stale generated brand assets, missing archive proof, and
  source-only vendor validation coverage.
- The first full debug gate exposed an outdated dependency lock for the new
  debug test variant.
- The next 388-test run exposed one stale test that still opened deleted
  Version 1 Play copy.
- A later active-file assertion matched its own forbidden test literal. It was
  replaced with per-file reporting and one narrow allowance for the explicit
  forbidden-value test.

## Implemented

- Release package `com.remote.Manuscript.v2`.
- Debug package `com.remote.Manuscript.v2.debug`.
- Instrumentation package `com.remote.Manuscript.v2.debug.test`, targeting the
  debug package.
- Kotlin namespace remains `com.echolink.mobile`.
- Version `2.0.0-internal`, code `1`.
- In-app name `Manuscript Android Version 2` and launcher name
  `Manuscript V2`.
- Login and subscription enforcement disabled in both production build types.
- Local `shared/contract/v1` path only.
- V2 intent keys, installer, SBOM, manifest policy, quality runners, and
  product-contract scripts.
- V2 contract and copied canonical brand hashes.
- Deterministic brand vectors and tracked generation provenance.
- Standalone llama validation using five committed-source SHA-256 sentinels.
- Real tamper regression that copies the sentinels to a temporary fixture,
  proves the actual Gradle validator passes, changes one file, and proves it
  fails closed.
- Version 1 Play bundle task, signing guard, credentials path, upload docs,
  tester link, listing drafts, and stale screenshots removed. V2 remains
  USB-only.
- Debug test dependency lock refreshed and normalized to LF.

## GREEN commands

The committed-source Gradle gate passed in 102.7 seconds:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

Additional gates passed:

```powershell
Invoke-Pester -Script tools\tests\v2_identity.Tests.ps1 -PassThru
python tools\generate_android_brand_assets.py --check
.\tools\security_release_gate.ps1
git diff --check
```

Results:

- Unit tests: 388 passed, 0 failed, 0 errors, 0 skipped.
- Identity tests: 5 passed, including the real vendor tamper failure.
- Debug lint: 99 advisory issues, 0 errors, 0 fatal issues.
- Release lint: 99 advisory issues, 0 errors, 0 fatal issues.
- Debug APK, unsigned release APK, and debug instrumentation APK assembled.
- Brand generator check passed.
- Local security gate passed.

## Artifact proof

All artifacts embed source commit `94e416888a17`.

| Artifact | Package | Version | SHA-256 |
|---|---|---|---|
| Debug APK | `com.remote.Manuscript.v2.debug` | `2.0.0-internal-debug` code 1 | `19B89122C3922665CC84971A06197A8D979109B347FA8A56FD028B20F046F26F` |
| Unsigned release APK | `com.remote.Manuscript.v2` | `2.0.0-internal` code 1 | `4C98D7EB07FC62BCF92DDD7D4C709EAE071BF37455F48475427BB739911796C6` |
| Debug instrumentation APK | `com.remote.Manuscript.v2.debug.test` | targets `com.remote.Manuscript.v2.debug` | `D049607CBB5E4D87EB88FC462420FFE9FFBAD2B407067C9FC41C6FF5C1576753` |

The debug and instrumentation APKs use the Android debug certificate with
SHA-256 digest
`8BD69027DA4681EBA4DA0C860313708A8B16139D921127B8A4C6650EE89B6EDF`.
The local release APK is intentionally unsigned because V2 has no Play or
upload-signing path during this loop.

Both production APK archives contain zero DOCX files, ZIP files, animal essay
files, judge material, correction outputs, or quality-evidence paths.
Generated BuildConfig output proves `SUBSCRIPTION_ENFORCED=false` for debug,
release, and instrumentation.

## Commits

- `5448c2e685bc29a357d101aa614b3233f6dbcd59`: initial V2 identity.
- `dc0ac5b1971f4986140b4b324974d1400a1f1aa4`: first evidence report.
- `ff0dda9ce06eb251030e66d32a2ccaeac4040761`: review cleanup and Play removal.
- `32e05ca`: artifact and tamper regression fixes.
- `94e416888a1704c9eedebfdace0c89cd3ceacd07`: normalized locked dependency state used by the verified artifacts.

## Remaining scope

Task 2 proves independent identity and buildability. Rendered brand comparison,
review-surface reliability, persistent download telemetry, emulator behavior,
physical S23 acceptance, and the twenty-essay quality campaign remain later
tasks and are not claimed here.
