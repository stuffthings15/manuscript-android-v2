# Manuscript Android Version 2

**Flawless writing powered locally**

Manuscript is a local-only Android writing editor and optional across-app
writing helper. In-app dictation uses Android's strict ON-DEVICE speech
recognizer (Android 12+): audio is processed only on the device and is never
stored or uploaded. Keyboard voice typing from Gboard or the selected system
keyboard also continues to work. Manuscript bundles no speech engine,
advertising, analytics, or tracking SDK. Internet access is used only after
consent to download pinned AI weights; writing inference stays on the device.

## Included

- Automatic small writing icon after focused text becomes available.
- Compact correction card with a suggestion, counter, tone controls, and actions.
- Paragraph-safe document jobs with automatic processing and reliable counter
  progression.
- Accept Change, Reject Change, Accept Paragraph, Reject Paragraph, Accept All,
  Undo, Restore Original, exact range replacement, and repeated-text safety.
- Twenty-three distinct writing modes with swipeable tone controls.
- Changing tone regenerates the entire pending document.
- Android `PROCESS_TEXT` support for selected text in compatible external apps.
- Optional accessibility helper for supported normal editable fields. After the
  user explicitly enables it, a small Manuscript icon appears beside the active
  field. Tapping it places the local review panel over the keyboard area.
- The across-app panel shows the suggestion, not a duplicate of the original text.
  The original remains visible in the source app. Closing or finishing the panel
  returns focus to the field and restores Gboard, Samsung Keyboard, or the selected
  system keyboard.
- Guarded direct replacement only after the user taps Replace Text. Password fields
  are ignored, and unsupported editors keep the `PROCESS_TEXT` fallback.
- Encrypted local vocabulary and snippets.
- AI-first correction. Deterministic code is limited to reversible preparation,
  safety validation, formatting restoration, and meaning-neutral cleanup.
- LiteRT-LM with a pinned Gemma E2B model is the primary local runtime. A pinned
  Qwen llama.cpp path remains a hidden compatibility fallback.
- One-time AI-download disclosure with exact size, a Wi-Fi-only or explicit
  mobile-data choice, resumable WorkManager installation, SHA-256 integrity
  checking, and a local inference self-test before activation.
- Paragraph context, sequential generation, a fresh reviewer pass, protected
  spans, prompt caching, and idle or memory-pressure unloading.
- Auto, Faster, and Best Quality modes plus active AI, backend, storage, and
  Remove AI controls.
- No clipboard shortcut, `InputMethodService` keyboard, `SYSTEM_ALERT_WINDOW`
  permission, or cloud fallback. The bounded panel is an Android accessibility overlay.
- The across-app helper is off by default and requires a clear in-app disclosure plus
  user activation in Android Accessibility settings.

## Debug build

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Gradle itself can start on JDK 17 or newer. The JDK that compiles and runs the
app module is not that one: `app/build.gradle` pins a Java 21 toolchain, because
the unit tests call `List.removeFirst()`, added to `java.util.List` in Java 21,
and on a Java 17 runtime `TrackedDelayedWorkTest` fails with
`NoSuchMethodError`. The bytecode target stays at Java 17.

Gradle does not auto-detect the JDK bundled with Android Studio, so
`gradle.properties` names its path in `org.gradle.java.installations.paths`.
Change that path if the Android Studio JDK moves, or point it at any Java 21
home. No toolchain download repository is configured, so the build stays
offline.

## Safe Galaxy S23 Plus update

Use the guarded replacement installer for every USB update:

```powershell
.\tools\install_s23_manuscript.ps1 -Variant debug
```

It verifies the S23 Plus, package, and signer before changing the device. It
force-stops the release, debug, and test processes, preserves app data, installs
with replacement and test flags, then verifies accessibility access, the
selected keyboard, signer, version, and launched process. A package or signer
conflict stops the update. The script never removes an installed app or clears
its data.

## Distribution boundary

Version 2 is local and USB-only. Do not reuse Version 1 package IDs, signing
credentials, artifacts, URLs, or tester links.
