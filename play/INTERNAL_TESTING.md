# Internal tester instructions

## Install

1. Open the private Google Play opt-in link with the invited Google account.
2. Join the test and install Manuscript from Google Play.
3. Open Manuscript. Confirm the title is **Manuscript** and the slogan is
   **Flawless writing powered locally.**
4. Sign in with the supplied Manuscript reviewer account. Confirm an active subscription unlocks the app and an inactive account does not. No microphone or cloud-writing setup should appear.

## Enable the across-app helper

1. In Manuscript, tap **Enable across all apps** and then **Continue**.
2. Confirm Android highlights or flashes **Installed apps**.
3. Open **Installed apps**, choose **Manuscript across-app writing helper**, and
   turn it on after reading the disclosure.
4. Confirm the helper remains off until the user completes these steps.

## Test Gboard voice input

1. Keep Gboard selected and open a normal writing field in Chrome, Messages,
   email, or another supported app.
2. Tap Gboard's microphone and dictate three malformed sentences.
3. Confirm Android or Gboard handles the microphone request. Manuscript must not
   request microphone access.
4. Confirm the Manuscript icon appears after text exists.
5. Tap the icon. The review panel must replace the keyboard area without
   covering Android's navigation controls.
6. Confirm the panel shows the suggestion but does not repeat the original text.
7. Tap the top-right close button. Confirm the source field regains focus and
   Gboard or the selected system keyboard returns.

Gboard voice typing may need a downloaded offline language pack. Its behavior
is controlled by Google and the user's Gboard settings.

## Test writing correction

1. Enter a full page with misspelling, grammar, punctuation, and sentence errors.
2. Tap the small icon. Verify it appears only for the active field and does not flash.
3. Review one to three complete sentences. Do not accept line fragments.
4. Tap **Replace Text**. Verify the exact source text changes and the counter
   advances.
5. Continue through every block. The card must stay open while the field stays
   active.
6. Tap **Dismiss**. Verify it advances without changing the text.
7. Tap outside the field. Verify the card and any target mark disappear.
8. Verify the top-left Manuscript icon opens the app settings.
9. Verify the top-right close button closes the panel and restores the keyboard.
10. Verify there is no jump to the middle of the page, no blocked Back/Home/Recents
    or gesture navigation, and no unwanted window or app resize.

## Test every tone

Test Proofread, Improve, Rephrase, Shorten, Friendly, Formal, Professional,
Diplomatic, Empathetic, Cooperative, Assertive, and Caveman.

For each tone:

1. Select the tone.
2. Tap **Regenerate** beside the tone list.
3. Confirm the suggestion is rewritten in that tone.
4. Replace the text.
5. Move to the next block and confirm the same tone stays selected.
6. Confirm the result preserves the main meaning.

## Test selected text in another app

1. Open a compatible editor and select non-sensitive text.
2. Open Android's text-actions menu and choose **Manuscript**.
3. Confirm the suggestion, tone, Replace Text, and Dismiss controls are populated.
4. Change the tone and confirm this selected-text fallback updates the suggestion.
5. Tap **Replace Text** and confirm Android replaces the selected text.
6. Repeat and tap **Dismiss**; confirm the source app remains unchanged.

## Offline and privacy test

1. Turn on airplane mode.
2. Confirm typed-text correction and every tone still work.
3. Confirm Android lists no microphone permission for Manuscript.
4. Confirm no ad or analytics consent appears. Account and subscription checks may use the network, but correction text must remain local.
5. Clear local data. Confirm saved vocabulary, snippets, and settings are gone.

## Report format

- Device and Android version:
- Manuscript version code:
- App or editor field used:
- Exact steps:
- Expected result:
- Actual result:
- Screenshot or screen recording with private text removed:

## Account lifecycle

1. Create an account and verify the email.
2. Use Forgot password in Android and complete the website recovery flow.
3. Confirm an active subscription unlocks the release build.
4. Cancel PayPal billing, then delete the account from the linked website.
5. Confirm the deleted account cannot sign in and retained payment records no longer contain the Manuscript account ID or email.
