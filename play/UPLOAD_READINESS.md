# Internal testing upload: what is ready and what needs Curtis

Target: a **new** Google Play app, package **`com.remote.Manuscript.v2`**,
published to **Internal testing** only. Decided by Curtis on 2026-08-16.

## Ready now

| Item | State |
|---|---|
| Release signing config | In `app/build.gradle`, reads four `MANUSCRIPT_UPLOAD_*` environment variables, never committed |
| `bundleRelease` | Builds. Output `app/build/outputs/bundle/release/app-release.aab` |
| Bundle size | 107,722,350 bytes, down from 191,933,740 after dropping emulator ABIs |
| ABIs shipped | `arm64-v8a`, `armeabi-v7a` only. Play serves per-device splits |
| Unit tests | 959, zero failures, plus `lintDebug` clean |
| Device suite | `MainToneMenuDeviceTest` 4/4, `ReviewSurfaceLifecycleDeviceTest` 5/5 on the S20 |
| Version | `versionCode 1`, `versionName 2.0.0-internal`. Correct for a NEW listing, since it starts its own sequence |

## Blocked on Curtis, in order

### 1. The upload key: DONE

Version 2 has its own upload key, created by `tools/create_upload_key.ps1`:
4096-bit RSA, valid 10,000 days, alias `manuscript-v2-upload`, password from
the cryptographic RNG and stored only in a DPAPI credential that no other
Windows account can read.

    Certificate SHA256  20278C05A2E9DBE3F7F7067C1EA3BEA27E6D1EC8880F20D0B37062F8AAC5DD05

Reusing V1's key was tried first and then dropped on purpose: a brand new Play
app has no upload key until its first upload, so there is nothing to inherit,
and separate keys mean losing or rotating one cannot affect the other app.

**Back this key up.** If it is lost after Play has seen it, replacing it needs
Google's key reset process. The two files are in `%LOCALAPPDATA%\Manuscript`
and are useless without each other:

    manuscript-v2-upload.jks
    manuscript-v2-upload-credential.xml

The credential is DPAPI-protected for this Windows account, so copying it to
another machine will NOT work. A backup means the keystore plus the password
recorded somewhere safe, not a copy of the .xml.

Rebuild the signed bundle any time with `.	oolsuild_play_bundle.ps1`.

### 2. Create the app in Play Console

Requires a browser signed into the **Remote67** developer account. The
2026-08-01 attempt failed because Chrome was signed into
`willittorch15@gmail.com`, which has no developer account. Check the signed-in
account first.

Create the app, then set package `com.remote.Manuscript.v2`. **Play package IDs
cannot be changed after creation**, so this is the one field worth reading
twice.

### 3. Declarations that are Curtis's to make

These are legal statements by the developer account and cannot be delegated:
developer agreement, content rating questionnaire, target audience, and the
Data safety form.

Facts for the Data safety form, true of this build:

- Writing, corrections, tone choices, vocabulary and snippets stay on the
  device. There is no analytics, no crash reporting, no telemetry and no cloud
  correction service.
- `RECORD_AUDIO` is used only with Android's on-device recognizer. Audio is
  never stored and never transmitted.
- `INTERNET` is used to download AI weight files from Hugging Face over HTTPS,
  and for account entitlement checks. **No writing is placed in any request.**
  Hugging Face receives ordinary request metadata under its own policy.
- The accessibility service is **off by default** and is enabled by the user in
  Android settings. It reads the focused field only to offer corrections, and
  ignores password fields.
- **`SYSTEM_ALERT_WINDOW` is declared**, and this is new in Version 2. It is
  used only to draw the floating assistant so it can be dragged and stay where
  the writer leaves it. It is optional: without the grant the assistant falls
  back to the accessibility overlay. Play asks about overlay use, so declare it
  rather than hoping the question is not asked.

### 4. Tester list

V1 uses a one-user list named `Dream Sync private testers`. Do **not** reuse
V1's tester opt-in URL; the new app issues its own.

## After upload

Confirm the release shows **Active** and available to internal testers, and
record the tester URL and the signed AAB's SHA-256 in
`AI Brain/Echo-Link/memory.md`. A build that was uploaded but never confirmed
available is not a finished upload.
