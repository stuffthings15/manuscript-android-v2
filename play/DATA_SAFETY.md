# Google Play Data Safety working record

This record must be reviewed against the exact signed AAB and current Google
Play definitions before any closed, open, or production release.

Google currently exempts apps that are active only on Internal testing from the
published Data Safety section. That exemption does not permit inaccurate
privacy copy.

## Confirmed app behavior

- Writing, prompts, corrections, tone choices, vocabulary, snippets, and
  embeddings remain on the device.
- The optional accessibility helper is off by default. It checks whether the
  active normal field contains text so it can offer the icon. Full field text
  is read only after the user taps the icon.
- Password fields are ignored.
- Replacement requires an explicit **Accept** action.
- The app uses an account email, stable account ID, authentication tokens, and
  subscription status for access control. It has no ads, analytics, crash
  reporting, tracking, remote configuration, or cloud-writing service.
- In-app dictation uses `RECORD_AUDIO` exclusively with Android's strict
  on-device speech recognizer (`createOnDeviceSpeechRecognizer`, Android 12+).
  Voice audio is processed only on the device, is never recorded to storage,
  and is never transmitted. Declared in Data safety as microphone data
  collected: NO (ephemeral on-device processing only).
- Optional vocabulary and snippets use encrypted local storage. Android backup
  and device transfer are disabled.
- `android.permission.INTERNET` and network-state access are present only for
  user-approved AI downloads.
- AI files are downloaded over HTTPS from Hugging Face. No writing is placed
  in the request.
- Hugging Face receives normal network request data, including the IP address,
  requested file, session time, and device or client details, under its own
  privacy policy.

## Account data declarations

Before a non-Internal release, declare account email, user ID, authentication information, and subscription status as collected for account management and app functionality. Data is encrypted in transit. Session tokens are encrypted at rest with Android Keystore. The identity and payment providers process data under their own policies. Confirm the exact provider settings and final Play definitions before submission.

The app and public website both link to authenticated self-service account deletion, with a support fallback when sign-in is unavailable. The user must cancel PayPal billing separately before deletion. Deletion removes the identity account and support text, then removes the account link from payment records retained for disputes, taxes, fraud prevention, or another legal duty.

## Conservative form review

Do not automatically answer that no data leaves the device. The AI download
transfers connection metadata to a third party.

Before a non-Internal release, the release owner must confirm Hugging Face's
current retention and use, then complete the form conservatively. The likely
review points are:

- Optional approximate location if the recipient derives location from IP.
- App functionality as the purpose.
- Encrypted in transit.
- Optional because users can keep using deterministic local tools without
  approving an AI download.
- Whether the transfer qualifies for Google's user-initiated-action sharing
  exception.

Google states that the developer remains responsible for the exact declaration.
Record the final answers and the evidence used in the release report.

## App-content forms

- App access: production features require a Manuscript account and active subscription. Provide reviewer credentials through Play Console App access instructions.
- Ads: no.
- Content rating: productivity utility with no built-in social feed.
- Target audience: choose the real intended age groups.
- News app: no.
- Health app: no.
- Financial features: no.
- Government app: no.

## Across-app disclosure

Use the disclosure bundled in `strings.xml`. It must explain:

- Accessibility detects the active normal field and checks whether it contains
  text before showing the icon.
- Full field text is read only after the user taps the Manuscript icon.
- Text correction runs locally.
- Password fields are ignored.
- Replacement happens only after **Accept**.
- Access can be disabled at any time.

## Release checks

- Merged manifest contains Internet and network-state permissions only for
  AI installation.
- Merged manifest contains `RECORD_AUDIO` for on-device dictation only, with
  `android.hardware.microphone` marked not required; it contains no
  application-overlay permission. Speech code uses only the strict on-device
  recognizer factory.
- Dependency review finds no ads, analytics, tracking, crash-reporting, cloud
  writing, or remote-configuration SDK.
- Network monitoring shows AI-host traffic only during an approved AI
  download and no traffic during correction.
- `PROCESS_TEXT` remains available as a bounded fallback.
- Saved personal tools remain encrypted and excluded from backup.
- Public privacy policy, Play form, signed AAB, and in-app disclosures agree.
