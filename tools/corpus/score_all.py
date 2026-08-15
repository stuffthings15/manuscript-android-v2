# Scores every essay: how many of the writer's errors the app actually fixed.
#
# scan.py asks whether the app DAMAGED anything, and 20 of 20 come back clean.
# STATUS: OK says every paragraph was corrected. Neither asks whether anything
# was FIXED, and an app that changed nothing would pass both perfectly. This
# asks the other question.
#
# Ground truth per essay comes from derive.py, which recovers the injected
# errors by aligning the corpus essays against the milder version of the same
# twenty. On top of that sit the shared grammar patterns, because the generator
# put some errors in BOTH versions - "Dogs Makes Great Pets", "they usually is
# speaking" - and a diff between them cannot see those. They are also the class
# the app was weakest at, so leaving them out would flatter the score.
#
# Counted per instance, not per type: a rule that fixes "dont" once and misses
# it nine times has not fixed "dont".
#
#   python score_all.py           # every essay, worst first
#   python score_all.py 07        # one essay, with what it still gets wrong
import glob
import io
import json
import os
import re
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
# Every essay must reach this on its own. An average hides a broken essay behind
# good ones, and the worst essay is the one a reader would actually meet.
FLOOR = 97.0
TRUTH = os.path.join(BASE, "truth")
# Version 2 keeps the corpus in the tree under a hash-pinned manifest instead of
# a scratch copy pulled off the phone, so the source of truth for "what the
# writer typed" is the fixture, not whatever happens to be in work/.
SOURCES = os.path.join(BASE, "..", "..", "quality", "animal-pet-essays", "fixtures")
# The runner pulls a round into its own directory and points the scorer at it,
# so a stale round cannot be scored as though it were the current one. Three
# times in Version 1 a harness reported success for work that did not happen,
# once by scanning the previous run's file.
OUTPUTS = os.environ.get("MANUSCRIPT_CORPUS_OUT") or os.path.join(BASE, "work", "out")

# Errors the generator baked into both versions of every essay, so derive.py
# is blind to them. Same shape as score.py's table: a pattern, and what it
# should have become.
SHARED = [
    ("agreement", r"\b(dogs|cats|birds|horses|animals|people|peoples|kids|owners|"
                  r"parrots|rabbits|goats|ducks|ferrets|geckos|turtles|hamsters|"
                  r"pigeons|rats|crabs|alpacas|donkeys|chickens|fish|pigs)\s+"
                  r"(is|was|has|does|makes|takes|needs|gets|comes|goes|says|likes|"
                  r"wants|knows|thinks|bites|barks|eats|sleeps|runs|plays|helps)\b",
     "plural subject, plural verb"),
    ("agreement", r"\bthey\s+(is|was|has|does|makes|takes|needs|gets|comes|says)\b",
     "they + plural verb"),
    ("agreement", r"\byou\s+(is|was|gets|comes|says|makes|takes|needs|goes)\b",
     "you + plural verb"),
    ("agreement", r"\bwe\s+(is|was|gets|comes|says|makes|takes|needs|goes)\b",
     "we + plural verb"),
    ("apostrophe", r"\b(dont|cant|wont|isnt|arent|wasnt|werent|doesnt|didnt|"
                   r"hasnt|havent|couldnt|wouldnt|shouldnt|thats|theres|its\s+a\s+"
                   r"shame|youre|theyre|were\s+not)\b", "missing apostrophe"),
    ("wrong word", r"\bthere\s+(big|small|really|just|going|not|already)\b",
     "they're"),
    ("wrong word", r"\b(better|worse|more|bigger|smaller|easier|harder)\s+then\b",
     "than"),
    ("wrong word", r"\byour\s+(not|sad|going|welcome|right|wrong)\b", "you're"),
    ("grammar", r"\balot\b", "a lot"),
    ("grammar", r"\bpeoples\b", "people"),
    ("grammar", r"\bcould\s+of\b|\bwould\s+of\b|\bshould\s+of\b", "could have"),
    ("grammar", r"\bnobody\s+hardly\b|\bnot\s+hardly\b", "double negative"),
    ("grammar", r"\bmost\s+greatest\b|\bmore\s+better\b", "double comparative"),
]

# Damage is weighted double: the writer can see an error that was left, but
# will never notice a name quietly rewritten.
def whole(word):
    return re.compile(r"(?<![A-Za-z])" + re.escape(word) + r"(?![A-Za-z])", re.I)


_KNOWN = None


def known_words():
    """The shipped frequency list, which is what the app itself knows."""
    global _KNOWN
    if _KNOWN is None:
        path = os.path.join(
            BASE, "..", "..", "app", "src", "main", "assets",
            "symspell", "frequency_dictionary_en.txt",
        )
        _KNOWN = set()
        if os.path.exists(path):
            for line in io.open(path, encoding="utf-8-sig", errors="replace"):
                parts = line.split()
                if len(parts) >= 2:
                    _KNOWN.add(parts[0])
    return _KNOWN


def body(raw):
    """
    The shipped text without the harness report at the top.

    Split on the first blank line rather than a fixed line count. Version 1's
    report was eight lines and Version 2's EssayUiBatchDeviceTest writes ten,
    so a hardcoded skip silently ate the first two paragraphs of every essay
    and scored them as errors the app failed to fix.
    """
    lines = raw.split("\n")
    for index, line in enumerate(lines):
        if not line.strip():
            return "\n".join(lines[index + 1:])
    return raw


def names_in(text):
    """
    Capitalised words and acronyms the app must hand back unchanged.

    The same rule the app's own guard uses, so the scorer and the code agree
    on what a name is: a word capitalised in the MIDDLE of a sentence, or a
    run of two capitals, minus any word the writer also types in lower case
    somewhere - because that makes it an ordinary word that happened to start
    a sentence, not a name.
    """
    candidates = set(re.findall(r"\b[A-Z]{2,}\b", text))
    for sentence in re.split(r"(?<=[.!?])\s+", text):
        words = re.findall(r"[A-Z][A-Za-z']+", sentence)
        candidates |= set(words[1:])
    return {
        name for name in candidates
        if not re.search(
            r"(?<![A-Za-z])" + re.escape(name.lower()) + r"(?![A-Za-z])", text
        )
    }


def score(number):
    truth_path = os.path.join(TRUTH, number + ".json")
    if not os.path.exists(truth_path):
        return None
    truth = json.load(io.open(truth_path, encoding="utf-8"))
    stem = truth["essay"][:-5]
    source_path = os.path.join(SOURCES, stem + ".txt")
    out_path = os.path.join(OUTPUTS, stem + ".out.txt")
    if not (os.path.exists(source_path) and os.path.exists(out_path)):
        return None
    source = io.open(source_path, encoding="utf-8", errors="replace").read()
    raw = io.open(out_path, encoding="utf-8", errors="replace").read()
    # A run that timed out writes the writer's own text back out, and scoring
    # that reports 9% as though it measured correction quality. Essay 18 did
    # exactly this once, and the number looked like a catastrophic regression
    # rather than a phone that never answered. Say what happened instead.
    status = raw.split("\n", 1)[0].strip()
    if not status.startswith("STATUS: OK"):
        return {"number": number, "stem": stem, "broken": status}
    shipped = body(raw)

    found = fixed = 0
    misses = []
    for pair in truth["pairs"]:
        pattern = whole(pair["wrong"])
        # At most as many instances as the alignment actually observed. One
        # misaligned "it" against "is" otherwise makes every "it" in the essay
        # an error: essay 17 scored 71% on thirty-three mistakes nobody made.
        in_source = min(len(pattern.findall(source)), pair.get("count", 1))
        if not in_source:
            continue
        survived = min(len(pattern.findall(shipped)), in_source)
        found += in_source
        fixed += max(0, in_source - survived)
        if survived:
            misses.append((pair["wrong"], pair["right"], survived, in_source))

    for category, pattern, expected in SHARED:
        in_source = len(re.findall(pattern, source, re.I))
        if not in_source:
            continue
        survived = len(re.findall(pattern, shipped, re.I))
        found += in_source
        fixed += max(0, in_source - survived)
        if survived:
            misses.append((category, expected, survived, in_source))

    # Damage: a name or acronym the writer used that came back fewer times.
    #
    # Two things are deliberately NOT damage, and counting them made the app
    # look worse than it is:
    #
    #  - A capitalised MISSPELLING. "Ansering" and "Englnd" disappearing is
    #    the app doing its job, and the ground truth already knows they are
    #    errors, so anything listed there is excluded.
    #  - A shouted word the dictionary knows. Calming "RAINY" to "Rainy" is
    #    intended behaviour. A shouted word it does NOT know is an acronym,
    #    and that must survive - which is the ESPN rule, so the same test
    #    decides both.
    misspellings = {p["wrong"].lower() for p in truth["pairs"]}
    corrections = {p["right"].lower() for p in truth["pairs"]}
    damage = []
    for name in names_in(source):
        if len(name) < 3 or name.lower() in misspellings:
            continue
        if name.isupper() and name.lower() in known_words():
            continue
        # A word no dictionary knows is a misspelling, not a name, unless the
        # ground truth says it is the CORRECT form of one - which is how
        # "Almeria" stays protected while "Amler", "Utercht", "Mroocco" and
        # "BEYOOND" are recognised as typos the app repaired. Counting those
        # as damage marked the app down for working.
        if (
            name.lower() not in known_words()
            and name.lower() not in corrections
        ):
            continue
        before = len(whole(name).findall(source))
        after = len(whole(name).findall(shipped))
        if after < before:
            damage.append((name, before, after))

    rate = 100.0 * fixed / found if found else 0.0
    penalty = 2.0 * sum(b - a for _, b, a in damage)
    return {
        "number": number,
        "stem": stem,
        "found": found,
        "fixed": fixed,
        "rate": rate,
        "score": max(0.0, rate - penalty),
        "damage": damage,
        "misses": misses,
    }


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    numbers = sorted(
        os.path.basename(p)[:2] for p in glob.glob(os.path.join(TRUTH, "*.json"))
    )
    results = [r for r in (score(n) for n in numbers if not only or n == only) if r]
    if not results:
        sys.exit("score_all.py: nothing to score. Run derive.py, then a device pass.")

    broken = [r for r in results if r.get("broken")]
    results = [r for r in results if not r.get("broken")]
    results.sort(key=lambda r: r["score"])
    print("%-4s %-38s %6s %6s %7s %7s" % ("", "essay", "errors", "fixed", "rate", "score"))
    for r in results:
        flag = "" if r["score"] >= FLOOR else "   <-- below %g" % FLOOR
        print("%-4s %-38s %6d %6d %6.1f%% %6.1f%%%s" % (
            r["number"], r["stem"][3:][:38], r["found"], r["fixed"],
            r["rate"], r["score"], flag))
    for r in broken:
        print("%-4s %-38s   %s" % (r["number"], r["stem"][3:][:38], r["broken"]))
    below = [r for r in results if r["score"] < FLOOR]
    print()
    print("%d of %d at or above %g%%. Worst: %s at %.1f%%" % (
        len(results) - len(below), len(results), FLOOR,
        results[0]["stem"][3:], results[0]["score"]))

    if only and results:
        r = results[0]
        if r["damage"]:
            print()
            print("  LOST:")
            for name, before, after in r["damage"]:
                print("    %-24s %d -> %d" % (name, before, after))
        print()
        print("  still wrong, most instances first:")
        for wrong, right, survived, total in sorted(
            r["misses"], key=lambda m: -m[2]
        )[:30]:
            print("    %-22s %d/%d left -> %s" % (wrong[:22], survived, total, right))

    # Exit non-zero so a regression fails a script instead of printing a table
    # nobody reads. A run that could not be scored at all is also a failure: a
    # broken essay silently dropped from the table used to read as a pass.
    if below or broken:
        return 1
    return 0


sys.exit(main())
