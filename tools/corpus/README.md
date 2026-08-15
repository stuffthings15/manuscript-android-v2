# Corpus scoring

Answers the one question none of Version 2's other gates ask: **did the app fix
the writer's errors?**

`EssayUiBatchDeviceTest`, `EssayQualityEvidenceBuilder` and the campaign module
in `tools/EssayQualityCampaign.psm1` all check that the app changed the text and
did not damage it. An app that changed one comma per section passes every one of
them. This measures the other half.

The target is **every essay at or above 97%**, not an average. An average hides a
broken essay behind good ones, and the worst essay is the one a reader meets.

## Files

- `score_all.py` - per-instance scoring against the hand-built keys. Prints worst
  first and exits non-zero if any essay is below the floor.
- `scan.py` - the opposite question: words the app produced that are not words,
  plus a substitution list a dictionary can never judge.
- `derive.py` - regenerates `truth/NN.json` by aligning the corpus against the
  milder version of the same twenty essays at
  `Documents\Manuscript\20_Animal_Pet_Error_Essays`. Provenance only; the keys
  are already derived and reviewed.
- `truth/01.json` .. `truth/20.json` - the keys, one per essay.

## How scoring works

Per **error instance**, not per error type. A rule that fixes `dont` once and
misses it nine times has not fixed `dont`.

Each key entry is matched against the fixture to count instances, then against
the shipped text to count survivors. On top of the per-essay keys sits a shared
pattern library inside `score_all.py`, because the generator injected some errors
into both versions of every essay (`Dogs Makes Great Pets`, `they usually is
speaking`) and an alignment between the two cannot see those. They are also the
class the app was weakest at, so leaving them out would flatter the score.

Damage is weighted double and subtracted: the writer can see an error that was
left, but will never notice a name quietly rewritten.

## Run it

The runner sets `MANUSCRIPT_CORPUS_OUT` to the directory it just pulled, so a
stale round cannot be scored as though it were the current one. Without it, both
scripts read `work/out`.

```bash
python tools/corpus/score_all.py          # all twenty, worst first
python tools/corpus/score_all.py 17       # one essay, and what it still gets wrong
python tools/corpus/scan.py 17_Donkeys_in_Almeria_Spain.out.txt
```

## What the scanner reports that is not a defect

Each was mistaken for an app defect at least once before being understood, and
each is whitelisted in `scan.py` for the same reason the app whitelists it:

- Possessives and contractions: `owner's`, `ain't`.
- Correct American spellings absent from the British-leaning shipped word list:
  `neighborhood`, `harbor`, `colorful`.
- Accented loanwords. An ASCII-only tokenizer clips `cafe` out of the accented
  spelling and reports the stub.
- Real words absent from the list: `axolotl`.

## Differences from the Version 1 copy

- Sources come from `quality/animal-pet-essays/fixtures`, which are hash-pinned
  by `fixture-manifest.json` and validated by `Get-ManuscriptEssayCorpus`, rather
  than a scratch copy pulled off the phone. A deleted scratch copy once made a
  correctly repaired place name read as an app-produced defect.
- The harness report is split off at the first blank line instead of a fixed line
  count. Version 1's report was eight lines and Version 2's is ten, so a
  hardcoded skip would eat real paragraphs and score them as failures.
- The floor is 97, and `score_all.py` exits non-zero below it. A broken essay
  that cannot be scored at all also fails, because a silently dropped essay used
  to read as a pass.
- `score.py` was not ported. It carries a hand-written key for essay 21, which is
  not part of this twenty-essay corpus.

## Faithfulness of the port

Run against the same shipped outputs, this scorer reproduces the Version 1
scorer's `errors`, `fixed`, `rate` and `score` for all twenty essays to the
decimal. Because it read its sources from the Version 2 fixtures while Version 1
read its own corpus copy, that match also proves all twenty fixtures are
byte-identical between the trees.

Those numbers describe the Version 1 engine. They are not a Version 2 baseline
and must not be quoted as one.
