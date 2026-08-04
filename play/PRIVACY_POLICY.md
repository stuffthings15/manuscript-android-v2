# Manuscript Privacy Policy

**Effective date:** July 28, 2026
**Status:** Release draft. Publication is blocked until the verified publisher
name, public support email, and HTTPS policy URL are supplied through
`release-contact.properties`.

Manuscript is a local writing-correction app. This policy explains how the
release offered on Google Play handles data.

## Data Manuscript handles

Manuscript can process text you enter in its editor. In compatible external
apps, Android can send text you explicitly select through the standard
**Process Text** action.

Manuscript also offers an optional across-app writing helper. It is off by
default and works only after you read the disclosure and enable Manuscript in
Android Accessibility settings. Android treats this as sensitive access because
an accessibility service can inspect interactive window content. Manuscript is
designed to use that access to locate the supported normal text field you are
actively editing and check whether it contains text so the Manuscript icon can
appear. It reads and temporarily retains the field text only after you tap that
icon, handles the text for local review, shows a bounded panel in the keyboard
area, and replaces text only after you tap **Accept**. Password fields are
ignored. You can disable the helper at any time.

The external review panel shows the suggestion without duplicating the original
field text. When the panel closes, Manuscript returns focus to the field so the
selected system keyboard can return. Dismissing makes no replacement.

Writing corrections run on the device. Manuscript does not send text to the
developer, an advertising company, an analytics service, or a Manuscript
server. It does not sell or share personal data.

The app can keep optional vocabulary, snippets, and a tone preference on the
device. Vocabulary and snippets use encrypted local storage. Android backup and
device transfer are disabled. Temporary text used to make a correction is not
kept as a developer-accessible history.

## Voice input

Manuscript does not request microphone access, record audio, receive audio, or
include a speech-recognition engine. You may use Android voice typing supplied
by a keyboard such as Gboard. That keyboard is separate from Manuscript. Its
audio and text handling are controlled by its provider and your keyboard
settings. Review the keyboard provider's privacy policy before using voice
typing.

## Account and subscription

Production builds require a Manuscript account and active subscription. During registration and sign-in, the app sends the email and password over HTTPS to the hosted identity service. Manuscript does not store the password. The app stores the stable account ID, email, access token, and refresh token in Android Keystore-encrypted local storage. The entitlement service receives the account ID and token, then returns subscription status. PayPal handles payment details under its own policy. Manuscript does not receive full payment-card numbers.

Account and subscription traffic never includes writing, prompts, corrections, vocabulary, snippets, or recordings.

## Network and AI downloads

Manuscript requests Internet and network-state permissions only to install the
public AI selected for the device. An AI download starts only after
you approve it. You can require Wi-Fi or allow mobile data.

AI files are downloaded over HTTPS from Hugging Face. The AI request does
not contain your writing, prompts, corrections, vocabulary, snippets, or
embeddings. Like other Internet downloads, the AI host receives standard
connection and request information such as the IP address, requested file,
session time, and device or client details. Hugging Face controls that
information under its own privacy policy:
<https://huggingface.co/privacy>.

Manuscript contains no advertising, analytics, tracking, crash-reporting,
remote-configuration, or cloud-writing SDK. Writing correction continues
locally after AI installation.

## Control and deletion

You can delete saved vocabulary and snippets inside the app. You can remove all
local Manuscript data through Android Settings or by uninstalling the app.
Account and subscription records are stored by the hosted identity and payment
services. Use the linked account-deletion page to reauthenticate and delete the account. Cancel PayPal
billing separately before deleting the account.

## Security

Manuscript limits external-app replacement to an explicit user action, ignores
password fields, avoids the system clipboard for replacement, and stores
optional personal tools in encrypted local storage. No software can guarantee
absolute security.

## Children

Manuscript is a general productivity tool. It is not directed to children under
13 and does not knowingly collect personal data from children.

## Changes

Material policy changes will be posted at the same public policy URL with a new
effective date.

## Contact

Before this policy is published, the release owner must insert the verified
publisher name and public support contact used by the Google Play listing.
