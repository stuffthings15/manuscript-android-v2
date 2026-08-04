# Galaxy S23 Plus Acceptance Matrix

Target: `SM-S916U1`
Serial: `R5CW20R46WD`
Release package: `com.remote.Manuscript`
USB package: `com.remote.Manuscript.debug`

## Frozen gates

| Gate | Required proof |
|---|---|
| Launcher | Windows-derived icon, Samsung-safe spacing, Curtis approval |
| In-app trigger | Windows palette, 48 dp target, 32 dp art, gentle pulse |
| Across-app trigger | Same component, palette, size, and pulse |
| Reduced motion | Both triggers remain visible without animation |
| Empty field | Trigger hidden |
| Normal field | Trigger visible |
| Multiline field | Trigger visible |
| Password field | Trigger hidden immediately |
| Transient empty event | Trigger removed |
| Review panel | Opens, closes, and restores the selected keyboard |
| Accessibility | Installed apps row, Manuscript row, detail, and On proof |
| Installation | Replacement only, signer checked, data preserved |
| Terminal result | Success, Retry, or Model Required, never blank or endless |
| Stability | Three consecutive runs with identical source and APK hashes |

## External app smoke

Use generated text only. Do not read, store, or capture existing personal text.

| App | Required check |
|---|---|
| Samsung Notes | Trigger, review, accept, keyboard restoration |
| Messages draft | Trigger, review, dismiss, no send |
| Gmail draft | Trigger, review, dismiss, no send |
| Chrome text field | Trigger, review, accept |

The runner is `tools/run_s23_product_contract.ps1`. It refuses to start unless
Manuscript accessibility is enabled and only the authorized S23 Plus is
attached. It runs the same product-contract test three times without rebuilding
or reinstalling.
