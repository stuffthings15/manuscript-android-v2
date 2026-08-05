# Manuscript Android Regression Ledger

This ledger is the durable product checklist. A row is Verified only when the automated test, physical S23 evidence, exact artifact hash, and first-good commit are recorded.

| User intent | Owning path | Automated gate | S23 gate | Evidence | First good commit | Artifact | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Launcher icon matches Windows color and style | Brand generator and launcher resources | `WindowsIconParityTest` | Launcher screenshot comparison | `evidence/brand/s23` | Pending | Pending | Open |
| In-app trigger appears after real text | Shared trigger state | `ManuscriptTriggerStateTest` | In-app editor fixture | `evidence/device/s23` | Pending | Pending | Open |
| Across-app trigger appears after real text | Accessibility service and shared trigger state | `ManuscriptTriggerStateTest` | External editor fixture | `evidence/device/s23` | Pending | Pending | Open |
| Both triggers use the same artwork and pulse | Trigger view factory | `ManuscriptTriggerPresentationTest` | Trigger parity capture | `evidence/brand/s23` | Pending | Pending | Open |
| Empty fields show no trigger | Shared trigger state | `ManuscriptTriggerStateTest` | Empty field fixture | `evidence/device/s23` | Pending | Pending | Open |
| Password fields never show a trigger | External writing policy | `ManuscriptTriggerStateTest` | Password fixture | `evidence/device/s23` | Pending | Pending | Open |
| Across all apps can be enabled on S23 | Accessibility Settings route | `AccessibilitySettingsRouteTest` | Settings flow | `evidence/device/s23/settings` | Pending | Pending | Open |
| Accessibility home highlights Installed apps | Accessibility Settings route | `AccessibilitySettingsRouteTest` | Samsung home capture | `evidence/device/s23/settings` | Pending | Pending | Open |
| Installed apps shows Manuscript and opens service details | Accessibility Settings guide | `AccessibilitySettingsRouteTest` | Installed apps capture | `evidence/device/s23/settings` | Pending | Pending | Open |
| Gboard or Samsung Keyboard remains active | Accessibility overlay handoff | Existing overlay handoff tests | Pre and post keyboard receipt | `evidence/device/s23` | Pending | Pending | Open |
| Review covers keyboard area and restores keyboard | Review panel handoff | Existing panel tests | Panel bounds and close test | `evidence/device/s23` | Pending | Pending | Open |
| Every correction reaches a terminal answer | Shared correction watchdog | `CorrectionRequestWatchdogTest` | Success, timeout, and Retry | `evidence/device/s23` | Pending | Pending | Open |
| USB update preserves data and accessibility authorization | Safe installer | Pester installer tests | Pre and post install receipt | `evidence/device/s23/install-receipt.json` | Pending | Pending | Open |
| Tested APK and uploaded AAB are bound to one release | Release manifest | Release manifest tests | Three pass receipts | `evidence/release` | Pending | Pending | Open |

## Lane Rules

- Lane A owns brand and launcher work.
- Lane B owns accessibility, trigger, panel, keyboard, and installation work.
- Lane C owns AI model, speed, correctness, and tone work.
- One lane changes per milestone commit.
- The product contract reruns before a lane can close.
- Parked GPT campaign files remain unstaged until Lane A and Lane B are green.

## 2026-08-05 emulator checkpoint

The API 36 and API 26 lifecycle, external-editor, brand, download-control, local-AI, unit, lint, and build gates passed against product commit `20dab2325844d1f02d5b58eb146c2494e1102f25` and product APK SHA-256 `285F68E4C4311F24A180C4A1831D23D7E8D372B498D40020E3E0FDD49828986C`. Harness commits `4b2eb57` and `4e2cbfe` produced product test APK SHA-256 `C762B4F605EDA7B1A0387776B242DE9EFAF5D62134ABC29DA9EC516872D96D2F`. The matrix includes the regression that removed unsafe background priority from long native llama calls and the two-frame Close-bounds gate that removed a transient API 26 host-tap coordinate.

Detailed evidence: `docs/qa/emulator-lifecycle-verification-2026-08-05.md`.

This checkpoint does not change any row to Verified. The required physical S23 Plus evidence, release signer, and frozen release artifacts remain pending.
