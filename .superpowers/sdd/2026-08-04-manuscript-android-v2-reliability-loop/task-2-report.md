# Task 2 report

## Result

The Task 2 implementation candidate is locally verified against source commit
`14eeb8f45cb51bf3b6b47240d5081a4c847f234c`. Independent fix-round review is
the remaining Task 2 gate.

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
- A second independent review found missing release-variant unit proof, a
  security-gate path bug, an impossible release-installer path, and the wrong
  contract field in the release-manifest creator.
- Focused regression work then exposed Git ERE and .NET regex differences.
  The security gate now allows only the exact known dummy key in its exact
  vendor fixture and rejects signing-password and RSA private-key fixtures.

## Implemented

- Release package `com.remote.Manuscript.v2`.
- Debug package `com.remote.Manuscript.v2.debug`.
- Instrumentation package `com.remote.Manuscript.v2.debug.test`, targeting the
  debug package.
- Kotlin namespace remains `com.echolink.mobile`.
- Version `2.0.0-internal`, code `1`.
- In-app name `Manuscript Android Version 2` and launcher name
  `Manuscript V2`.
- Login and subscription enforcement disabled in debug and release.
- Local `shared/contract/v1` path only.
- V2 intent keys, debug-only installer, SBOM, manifest policy, quality runners,
  and product-contract scripts.
- V2 contract and copied canonical brand hashes.
- Deterministic brand vectors and tracked generation provenance.
- Standalone llama validation using five committed-source SHA-256 sentinels.
- Real tamper regression that copies the sentinels to a temporary fixture,
  proves the actual Gradle validator passes, changes one file, and proves it
  fails closed.
- Version 1 Play bundle task, signing guard, credentials path, upload docs,
  tester link, listing drafts, and stale screenshots removed. V2 remains
  USB-only.
- Debug and release unit components explicitly enabled and verified.
- Debug test dependency lock refreshed and normalized to LF.
- Release-manifest creation reads `contractVersion`, while manifest verification
  checks actual files, hashes, signer, package, devices, and three S23 loops.

## GREEN commands

The forced clean-input Gradle gate passed in 2 minutes 24 seconds with all 230
tasks executed:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 --rerun-tasks :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

The combined focused suite passed 30 of 30 tests:

```powershell
Invoke-Pester -Script @(
  'tools/tests/v2_identity.Tests.ps1',
  'tools/tests/import_snapshot.Tests.ps1',
  'tools/tests/install_s23_manuscript.Tests.ps1',
  'tools/tests/release_manifest.Tests.ps1'
) -PassThru
```

Additional gates passed:

```powershell
python tools/generate_android_brand_assets.py --check
.\tools\security_release_gate.ps1
git diff --check
```

Results:

- Debug unit tests: 388 passed, 0 failed, 0 errors, 0 skipped.
- Release unit tests: 388 passed, 0 failed, 0 errors, 0 skipped.
- Focused Pester tests: 30 passed, 0 failed.
- Debug lint: 99 advisory warnings, 0 errors, 0 fatal issues.
- Release lint: 99 advisory warnings, 0 errors, 0 fatal issues.
- Debug APK, unsigned release APK, and debug instrumentation APK assembled.
- Brand generator check passed.
- Local security gate passed.

## Artifact proof

All artifacts embed source commit `14eeb8f45cb5`, use minimum SDK 26 and target
SDK 36, and were rebuilt by the forced gate above.

| Artifact | Package | Version | SHA-256 |
|---|---|---|---|
| Debug APK | `com.remote.Manuscript.v2.debug` | `2.0.0-internal-debug` code 1 | `2D0DB88D11CF2E7C99645B7ED47EB6912E7204EFB0DA991E4A213730AE3044F1` |
| Unsigned release APK | `com.remote.Manuscript.v2` | `2.0.0-internal` code 1 | `2D42BF03670CF85A6C95E2DB01B8E6687ED0A99711F6EE8E7837F66FF0BF7994` |
| Debug instrumentation APK | `com.remote.Manuscript.v2.debug.test` | targets `com.remote.Manuscript.v2.debug` | `EBDE20756760ED703E8245FFB4820F2DA2984BA1934787FCE5A69F17F9C0A70D` |

The debug and instrumentation APKs use the Android debug certificate with
SHA-256 digest
`8BD69027DA4681EBA4DA0C860313708A8B16139D921127B8A4C6650EE89B6EDF`.
The local release APK is intentionally unsigned because V2 has no Play or
upload-signing path during this loop.

Both production APK archives contain zero DOCX files, ZIP files, animal essay
files, judge material, correction outputs, or quality-evidence paths.
Generated BuildConfig output proves `SUBSCRIPTION_ENFORCED=false` for debug,
release, and instrumentation.

## Exact Task 2 file set

This is the name-status diff from the approved Task 1 checkpoint
`c21bc2aafc64a9cadb31b629ee31bf21ddc2c764` through the verified Task 2 source
commit:

```text
M .gitattributes
A .superpowers/sdd/2026-08-04-manuscript-android-v2-reliability-loop/task-2-report.md
M README.md
M app/build.gradle
M app/gradle.lockfile
M app/src/main/AndroidManifest.xml
M app/src/main/java/com/echolink/mobile/AccountActivity.kt
M app/src/main/java/com/echolink/mobile/MainActivity.kt
M app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt
M app/src/main/java/com/echolink/mobile/ProcessTextActivity.kt
M app/src/main/res/drawable/ic_manuscript.xml
M app/src/main/res/drawable/ic_manuscript_assist.xml
M app/src/main/res/drawable/ic_manuscript_launcher_foreground.xml
M app/src/main/res/values/strings.xml
M app/src/test/java/com/echolink/mobile/BuildIdentityTest.kt
M app/src/test/java/com/echolink/mobile/ExternalWritingPolicyTest.kt
M app/src/test/java/com/echolink/mobile/ManuscriptProductContractTest.kt
M app/src/test/java/com/echolink/mobile/ManuscriptProductSourceTest.kt
M app/src/test/java/com/echolink/mobile/UserVisibleAiTerminologyTest.kt
M app/src/test/java/com/echolink/mobile/WindowsIconParityTest.kt
M build.gradle
R docs/product-contract/manuscript-android-v1.json -> docs/product-contract/manuscript-android-v2.json
M docs/qa/S23_ACCEPTANCE_MATRIX.md
A evidence/brand/generated-brand-assets.json
M llama-runtime/build.gradle
D play/DATA_SAFETY.md
D play/INTERNAL_TESTING.md
D play/PRIVACY_POLICY.md
M play/README.md
D play/RELEASE_NOTES.md
D play/STORE_LISTING.md
D play/UPLOAD_READINESS.md
D play/assets/app-icon-512.png
D play/assets/feature-graphic-1024x500.png
D play/assets/screenshots/01-editor-icon.png
D play/assets/screenshots/02-writing-card.png
D play/assets/screenshots/03-counter-advance.png
D play/assets/screenshots/04-selected-text-action.png
D play/release-contact.properties.example
M settings.gradle
M tools/ManuscriptReleaseManifestPolicy.psm1
D tools/build_play_bundle.ps1
M tools/create_manuscript_release_manifest.ps1
M tools/generate_android_brand_assets.py
M tools/generate_dependency_sbom.ps1
M tools/install_s23_manuscript.ps1
M tools/run_gpt56_quality_round.ps1
M tools/run_persistent_device_test.ps1
M tools/run_s23_product_contract.ps1
M tools/security_release_gate.ps1
M tools/tests/Test-Gpt56QualityLoop.ps1
M tools/tests/import_snapshot.Tests.ps1
M tools/tests/install_s23_manuscript.Tests.ps1
M tools/tests/release_manifest.Tests.ps1
A tools/tests/v2_identity.Tests.ps1
M tools/verify_manuscript_release_manifest.ps1
M validate_port.py
```

## Commits

- `5448c2e685bc29a357d101aa614b3233f6dbcd59`: initial V2 identity.
- `dc0ac5b1971f4986140b4b324974d1400a1f1aa4`: first evidence report.
- `ff0dda9ce06eb251030e66d32a2ccaeac4040761`: review cleanup and Play removal.
- `32e05ca`: artifact and tamper regression fixes.
- `94e416888a1704c9eedebfdace0c89cd3ceacd07`: normalized dependency lock.
- `64fc2ff`: first Task 2 review report.
- `642b74d9e1397b158a2304bdf31a4b84a8c806ef`: second review fixes.
- `14eeb8f45cb51bf3b6b47240d5081a4c847f234c`: security and manifest regressions used by the verified artifacts.

## Remaining scope

Task 2 proves independent identity and buildability. Rendered brand comparison,
review-surface reliability, persistent download telemetry, emulator behavior,
physical S23 acceptance, and the twenty-essay quality campaign remain later
tasks and are not claimed here.
