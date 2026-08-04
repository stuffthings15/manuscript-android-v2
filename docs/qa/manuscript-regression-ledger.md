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
