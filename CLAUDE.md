# Manuscript Android Version 2 - read this before building here

This tree had no `CLAUDE.md` at all until 2026-08-14, and that absence is half
of a defect that cost about three weeks of repeated work.

## This tree is canonical (settled 2026-08-15)

Curtis decided it: `Manuscript/Android Version 2` is the canonical Manuscript
Android tree and all work happens here. `Manuscript/Android` is the archive.

It was disputed for about three weeks, and this file not existing was half of
the reason. A session working in `Manuscript/Android` read a `CLAUDE.md`
saying THAT tree was canonical; a session working here read nothing and fell
back on the Claude memory file saying the opposite. Both followed the only
guidance they could see. On 2026-08-14 that produced commit `6bee2c2` here,
porting `score_all.py`, `scan.py`, `derive.py` and twenty `truth/NN.json` keys
into this tree, while another session was extending those same tools there.

The archive still holds work this tree does not, and Curtis chose to port what
is missing deliberately rather than let it be rediscovered piece by piece. So
`Manuscript/Android` is worth READING while that port is outstanding. It is
not a place to make changes.

## Where each tree stands, as at 2026-08-14

- `Manuscript/Android` (V1): working device loop on the Samsung S20
  `RFCRA0A6CFH`, 621 unit tests, the corpus harness plus `score.py`,
  `score_all.py`, `silent.py`, and essay work that took a hand-scored corpus
  from 66% to 97% of injected errors fixed with zero damage.
- This tree (V2): Java 21 toolchain pinned, S23-guarded installer, the freshly
  ported scorer - but no AI weights on the phone, so it has never scored an
  essay. The corpus gate and installer are verified; the measurement is not.

Neither list is an argument for one over the other. They are here so the answer
can be given on evidence rather than on which file a session happened to open.

## Facts about this tree specifically

- Its own git repo, own `.git`, invisible to the outer `Echo-Link` repo.
- Build: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`. The Java 21
  toolchain is pinned in `app/build.gradle`, so the JDK in the shell no longer
  decides whether the suite passes.
- `tools\install_s23_manuscript.ps1` verifies a Galaxy S23 Plus before it will
  touch a device. The phone usually attached to this machine is an S20, which
  that script refuses by design.
- Distribution is local and USB-only. Do not reuse V1 package IDs, signing
  keys, or tester links.
- Commit named files only. Never `git add -A`.

## Everything else

`AI Brain/DECISIVE.md` holds the facts that must survive a session switch, and
`AI Brain/Echo-Link/memory.md` holds the detailed record. Read them before
deciding anything here. A session that opens at `Documents` loads neither
unless it asks, which is the hole all of this exists to close.
