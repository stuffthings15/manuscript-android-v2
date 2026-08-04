# Google Play Internal upload readiness

Do not upload until every required item is verified against the new artifact.

## Current blockers

- [ ] Fill local `play/release-contact.properties` with the verified public
  developer name, support email, and HTTPS privacy-policy URL.
- [ ] Publish the reviewed privacy policy at that stable HTTPS URL.
- [ ] Back up the upload key in a separate user-controlled location.
- [x] Use a version code greater than 28.

## Privacy and security

- [x] Package is `com.remote.Manuscript`.
- [x] No microphone or application-overlay permission exists.
- [x] Internet access is limited to user-approved AI model downloads.
- [x] Model downloads disclose Hugging Face network request handling.
- [x] Writing, prompts, corrections, and embeddings stay on the device.
- [x] No ads, analytics, trackers, crash-reporting, remote-configuration, or
  cloud-writing SDK exists.
- [x] Accessibility is optional, ignores password fields, avoids retaining full
  text before the user taps the icon, and requires **Accept** before replacing.
- [x] External `PROCESS_TEXT` input is bounded and malformed input is rejected.
- [x] Review windows use secure-screen protection.
- [x] Accessibility tree traversal has depth and node limits.
- [x] Android backup and device transfer are disabled.
- [x] Optional vocabulary and snippets use encrypted local storage.

## Supply chain and signing

- [x] Gradle wrapper distribution is pinned by SHA-256.
- [x] Dependency lockfiles exist for the root, app, and native runtime.
- [x] Gradle dependency verification metadata pins downloaded artifacts.
- [x] Release tooling generates a CycloneDX dependency SBOM.
- [x] Release tooling fails closed on shipped runtime and release-tool OSV
  advisories, while recording build-only and test-only findings.
- [x] llama.cpp source is pinned to a reviewed commit.
- [x] Release packaging fails without upload signing credentials.
- [x] Release build script checks the approved upload-certificate fingerprint.
- [x] Raw device captures and APK evidence are ignored by Git.
- [ ] Run `tools/security_release_gate.ps1 -PlayUpload`.
- [ ] Run `tools/build_play_bundle.ps1`.
- [ ] Record fresh AAB and APK hashes and certificate fingerprints.

## Quality and device gates

- [x] All configured release unit tests pass with zero failures.
- [x] Release lint has zero errors.
- [ ] Signed AAB passes Bundletool validation.
- [ ] Signed APK passes `apksigner`.
- [ ] Clean install and upgrade pass on the connected Samsung device.
- [ ] Across-app icon, immediate panel expansion, tone regeneration, Accept,
  Dismiss, replacement counter, close, keyboard restore, and navigation pass.
- [ ] Model download over Wi-Fi and optional mobile data pass.
- [ ] Airplane mode, interrupted download, low storage, process death, and
  model self-test failure recover safely.
- [ ] Network observation confirms no correction traffic.

## Play Console

- [ ] Store listing and graphics match the final build.
- [ ] Accessibility declaration matches actual behavior.
- [ ] App-content answers match `DATA_SAFETY.md`.
- [ ] Upload only to Internal testing.
- [ ] Verify **Available to internal testers**.
- [ ] Install the Play-delivered build with the invited tester account.
- [ ] Record the tester installation link and physical-device result.
