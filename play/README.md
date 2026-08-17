# Version 2 distribution

**Version 2 gets its own Google Play app under the package
`com.remote.Manuscript.v2`, published to Internal testing.**

This is not a new decision. Curtis set the standing Android workflow on
2026-07-22: every update is tested, signed, published to the Internal testing
track, and confirmed available to the testers. He reauthorised it repeatedly
across sessions and it kept being reverted, which is the thing this file now
exists to stop.

What this file said before was a temporary restriction from an August
reliability loop, written to stop Version 2 colliding with Version 1's listing.
It outlived its task. The collision is avoided by Version 2 having its own
package and its own app, not by keeping it off the store, so the restriction
was replaced rather than carried forward.

## What still holds

- **A separate Play app.** V1 is `com.remote.Manuscript`, with its own internal
  track and tester link. Play package IDs cannot be renamed, so V2 cannot join
  that listing and must not try to.
- **A separate upload key is safer, and the same key is acceptable.** Whichever
  is used, it is read from the environment and never committed. See
  `UPLOAD_READINESS.md`.
- **No V1 tester links, credentials or artifact hashes are reused.**

## What Claude did NOT do, and why

The Play app has not been created and nothing has been uploaded. Two reasons,
both real:

1. **No upload key on this machine.** `app/build.gradle` now has a release
   signing config, but it is fed from `MANUSCRIPT_UPLOAD_STORE_FILE`,
   `MANUSCRIPT_UPLOAD_STORE_PASSWORD`, `MANUSCRIPT_UPLOAD_KEY_ALIAS` and
   `MANUSCRIPT_UPLOAD_KEY_PASSWORD`. None are set here, so the bundle builds
   unsigned and Play will refuse it. An unsigned artifact is not an upload
   waiting to happen; it is nothing to upload.
2. **Creating the app means signing declarations as the developer.** Content
   rating, data safety, target audience and the developer agreement are legal
   statements made by Curtis's developer account. They are his to make, not an
   assistant's to make on his behalf, and Play Console requires his session.

`UPLOAD_READINESS.md` lists exactly what to click and what to declare, so the
part that needs him is short.
