# Internal-testing release notes

## Google Play text

Manuscript now gives short, fully readable accessibility setup instructions.
Samsung devices open Accessibility and visibly highlight Installed apps.
Android devices that already show Manuscript directly receive the correct
instruction to tap Manuscript and turn it on.

## Internal build record

- Track: Internal testing
- Version name: `0.21.0-internal`
- Version code: `21`
- Package ID: `com.remote.Manuscript`
- AAB SHA-256: `65AFC0799F07BDAD3FBB8F199A8C77D98F1F0ACEDC0EED919224F79DD2508523`
- Upload certificate SHA-256: `45:21:79:3E:05:16:5F:4C:77:45:B1:42:85:95:95:94:EB:08:B7:A9:1B:B9:37:AD:26:44:92:62:1F:85:2F:8B`
- Direct release APK SHA-256: `CB8B3CBBC13FA78D7715F941887175FD31852FF7E36EEE47C9F57DCD37BE500C`
- Uploaded: `Jul 25, 2026 12:32 AM PDT — available to internal testers`
- Tester group: `Dream Sync private testers` (1 tester)
- Internal opt-in URL: `https://play.google.com/apps/internaltest/4701445098147154341`
- Local AI: adaptive on-device tiers, Apache-2.0
- Known limits: English grammar only. Voice typing still comes from the selected
  Android keyboard.
<en-US>
Manuscript 0.21.0 keeps accessibility setup instructions readable, highlights Installed apps on Samsung, and gives direct guidance on other Android devices.
</en-US>
# 0.22.0-internal

- Adds distinct local prompts and behavior for all 23 Manuscript writing modes.
- Fixes the first-launch AI install button so the download disclosure always opens.
- Rejects bad AI grammar such as singular counted nouns, malformed verb phrases, run-ons, lowercase sentence starts, and missing terminal punctuation.
- Uses the fast deterministic layer for safe spelling, agreement, punctuation, capitalization, and sentence-boundary repairs.
- Keeps names, numbers, dates, URLs, email addresses, quotations, formatting, negation, permissions, deadlines, and obligations protected.

# 0.28.0-internal

<en-US>
Adds a clear choice to download AI using Wi-Fi only or mobile data. The prompt shows the exact download size and possible mobile-data use before downloading.
</en-US>

# 0.29.0-internal

<en-US>
Fixes a grammar-validation gap that could leave "peoples" where "people" was required. Also repairs misplaced commas such as "and, they" while preserving valid uses such as "Indigenous peoples."

Requires AI installation before any spelling, punctuation, grammar, or tone correction begins. The main editor, across-app helper, and selected-text action now open AI installation when AI is missing.

Runs AI before deterministic validation rules and protects valid short words such as "wag" from speculative spelling changes.

Never returns a rule-only correction. If AI is missing, fails, or produces an unsafe result, Manuscript applies nothing and asks the user to retry.

Returns accepted AI wording exactly. Deterministic rules can reject unsafe output, but they can no longer rewrite the AI result.

Restores Android's native long-press text selection toolbar in the main editor, including Cut, Copy, Paste, selection handles, and Undo.

Keeps Accept and Dismiss visible above long correction content in both the main app and across-app review panel.
</en-US>
