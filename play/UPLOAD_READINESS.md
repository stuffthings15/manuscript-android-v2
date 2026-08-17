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

### 1. The upload key

Nothing signable exists without it. Set all four, then rebuild:

```bash
MANUSCRIPT_UPLOAD_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD
```

Reusing V1's key is acceptable; a fresh key for V2 is cleaner. Either way the
key stays outside this repository. Confirm afterwards that the AAB actually
carries a signature rather than assuming it: an unsigned bundle looks identical
in every other respect, which is how it goes unnoticed.

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
