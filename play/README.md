# Version 2 distribution

Curtis decided on 2026-08-16: **Version 2 gets its own Google Play app under
the package `com.remote.Manuscript.v2`, published to Internal testing.**

This reverses what this file said before, which was that the V2 workspace was
local and USB-only and must never be used for a Play upload. That rule existed
to stop V2 colliding with V1's listing, and it is replaced rather than
forgotten: the collision is avoided by V2 having its own package and its own
app, not by V2 staying off Play.

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
