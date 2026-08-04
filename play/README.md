# Manuscript Google Play package

Use these files for Google Play Internal testing:

- `STORE_LISTING.md`: listing copy and required public fields.
- `RELEASE_NOTES.md`: artifact-specific release record.
- `PRIVACY_POLICY.md`: policy source.
- `DATA_SAFETY.md`: current behavior and form-review notes.
- `INTERNAL_TESTING.md`: tester workflow.
- `UPLOAD_READINESS.md`: release gate.
- `release-contact.properties.example`: required public contact template.
- `assets/`: listing graphics and sanitized screenshots.

Current Android identity:

- Package: `com.remote.Manuscript`
- Minimum Android API: 26
- Target Android API: 36

The app requests Internet access only for user-approved AI model downloads.
Writing correction remains local. The app has no microphone, application
overlay, ads, analytics, or cloud-writing SDK.

Do not reuse old artifact names or hashes. Run
`tools/security_release_gate.ps1 -PlayUpload`, then
`tools/build_play_bundle.ps1`. Record fresh version, hashes, certificate,
Bundletool result, Play Console state, tester link, and physical-device result
in `RELEASE_NOTES.md`.
