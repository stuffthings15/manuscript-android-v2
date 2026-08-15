# Scores a corpus that has no per-error answer key, such as the
# 500%-more-errors set.
#
# There is no per-error answer key for these, only a manifest counting how many
# error operations were injected per essay. So the score is built the one way
# that stays honest without a key: run the SAME detector over the writer's text
# and over the shipped text, and report what it caught in each.
#
#     fixed = (errors in source) - (errors in shipped)
#     rate  = fixed / errors in source
#
# Both sides are measured identically, so the number means something even
# though the detector is not omniscient. What it cannot see is excluded from
# the numerator AND the denominator, which is why this reports its coverage
# against the manifest: if the manifest says 56 spelling operations and the
# detector finds 54, it is seeing essentially all of that class. Where coverage
# is poor, the honest move is to read the text, and score_new.py prints the
# residual errors so a person can do exactly that.
#
# An error the app INTRODUCED counts double, the same way it does in score.py.
# Damage is worse than an omission: the writer can see an uncorrected mistake
# and will never notice a quietly rewritten sentence.
#
#   python score_new.py            # every essay, worst first
#   python score_new.py 01         # one essay, with what is still wrong
#
# MANUSCRIPT_CORPUS_SRC names the corpus and MANUSCRIPT_CORPUS_OUT the round
# just pulled; run_essay_corpus.ps1 sets both. Version 1 staged this set as
# essays 31..50 so it could not collide with the original twenty in a shared
# inbox. Version 2's runner clears the inbox per corpus instead, so the essays
# keep their own numbering and the manifest is read by its own number.
import glob
import io
import os
import re
import sys
from collections import Counter

BASE = os.path.dirname(os.path.abspath(__file__))
# Every essay must reach this on its own, the same way score_all.py treats the
# keyed corpus. An average lets a broken essay hide behind good ones.
FLOOR = 95.0
SOURCES = os.environ.get("MANUSCRIPT_CORPUS_SRC") or os.path.join(
    BASE, "..", "..", "..", "20_error_riddled_essays_500_percent_more_errors"
)
OUTPUTS = os.environ.get("MANUSCRIPT_CORPUS_OUT") or os.path.join(BASE, "work", "out")
DICT = os.path.join(
    BASE, "..", "..", "app", "src", "main", "assets",
    "symspell", "frequency_dictionary_en.txt",
)
# The manifest travels with the corpus. Reading it from beside the essays keeps
# the two in step if the set is ever moved or a second keyless set arrives.
MANIFEST = os.path.join(SOURCES, "manifest.csv")


def load_dictionary():
    words = set()
    for line in io.open(DICT, encoding="utf-8-sig", errors="replace"):
        parts = line.split()
        if parts:
            words.add(parts[0].lower())
    return words


KNOWN = load_dictionary()

# Words the essays use that a general frequency list does not hold. Counting
# these as misspellings would report defects against the app that are really
# gaps in the word list, which is the mistake score.py made with
# "brachiosaurus" before it was fixed.
ALLOWED = {
    "microgrid", "microgrids", "biodiversity", "pollinator", "pollinators",
    "forager", "foragers", "apiary", "apiaries", "varroa", "genomics",
    "bioacoustics", "resilience", "resilient", "wastewater", "greywater",
    "aquifer", "aquifers", "desalination", "evapotranspiration", "xeriscaping",
    "microplastics", "microfibers", "polyester", "viscose", "lyocell",
    "geospatial", "lidar", "seismicity", "tiltmeter", "tiltmeters",
    "fumarole", "fumaroles", "tephra", "lahars", "lahar", "caldera",
    "bathymetric", "bathymetry", "submersible", "submersibles", "benthic",
    "chemosynthesis", "hydrothermal", "bioluminescence", "bioluminescent",
    "cybersecurity", "phishing", "ransomware", "multifactor", "credential",
    "credentials", "endpoint", "endpoints", "sandboxing", "analytics",
    "orbital", "constellations", "deorbit", "deorbiting", "conjunctions",
    "propellant", "hypervelocity", "remediation", "provenance", "stratigraphy",
    "archaeobotany", "revitalization", "revitalisation", "orthography",
    "immersion", "intergenerational", "telepresence", "exoskeleton",
    "exoskeletons", "gerontology", "polyculture", "agroecology", "composting",
    "gleaning", "anaerobic", "digesters", "digester", "photovoltaic",
    "photovoltaics", "inverters", "inverter", "microfinance", "syncopation",
    "improvisation", "improvisational", "bebop", "polyrhythm", "polyrhythms",
    "discography", "reef", "reefs", "coralline", "symbiont", "symbionts",
    "zooxanthellae", "outplanting", "fragmenting", "nursery", "nurseries",
    "connectivity", "walkability", "ridership", "headways", "farebox",
    "transit", "multimodal", "gentrification", "zoning", "setbacks",
}

WORD = re.compile(r"[A-Za-z][A-Za-z'’-]*")


def spelling_errors(text):
    """Words no dictionary and no allowance knows."""
    found = []
    for match in WORD.finditer(text):
        word = match.group(0)
        bare = word.strip("'’-").lower()
        if len(bare) < 3:
            continue
        if bare in KNOWN or bare in ALLOWED:
            continue
        # A hyphenated or possessive form is fine when both halves are known.
        parts = [p for p in re.split(r"[-'’]", bare) if p]
        if parts and all(p in KNOWN or p in ALLOWED or len(p) < 3 for p in parts):
            continue
        found.append(word)
    return found


MECHANICAL = [
    ("double punctuation", re.compile(r"[.,;:!?]{2,}(?!\.\.)")),
    ("space before punctuation", re.compile(r"[ \t]+[.,;:!?]")),
    ("missing space after punctuation", re.compile(r"[,;:][A-Za-z]")),
    ("run of spaces", re.compile(r"[ ]{2,}")),
    ("tab inside a line", re.compile(r"[^\n]\t")),
    ("repeated word", re.compile(r"(?i)(?<![\w'])([A-Za-z']{3,})[ \t]+\1(?![\w'])")),
    ("space before apostrophe", re.compile(r"[A-Za-z][ \t]+['’](?:s|t|re|ve|ll|d|m)\b")),
    ("lowercase after a full stop", re.compile(r"[.!?][ \t]+[a-z]")),
]

# Confusion sets, only in the shapes that are decidable from two words either
# side. Anything needing more context is left to a person reading the text.
CONFUSION = [
    ("then/than", re.compile(r"(?i)\b(?:better|worse|more|less|rather|other|larger|smaller|greater|higher|lower|faster|slower|longer|shorter|earlier|later)\s+then\b")),
    ("to/too", re.compile(r"(?i)\b(?:too)\s+(?:judge|see|make|know|be|do|find|use|say|go|take|give|keep|work)\b")),
    ("there/their", re.compile(r"(?i)\bthere\s+(?:own|role|roles|work|goals|hives|results|members|staff|data|budget|budgets|efforts)\b")),
    ("its/it's", re.compile(r"(?i)\bits\s+(?:a|an|the|not|often|also|important|clear|possible|likely)\b")),
    ("affect/effect", re.compile(r"(?i)\bthe\s+affect\b|\beffects?\s+change\b")),
    ("could of", re.compile(r"(?i)\b(?:could|would|should|might|must)\s+of\b")),
    ("a/an", re.compile(r"(?i)\ba\s+[aeiou]\w+|\ban\s+[bcdfgjklmnpqrstvwxyz]\w+")),
]


def detect(text):
    """Every error this can see, as (category, evidence) pairs."""
    found = []
    for word in spelling_errors(text):
        found.append(("spelling", word))
    for label, pattern in MECHANICAL:
        for match in pattern.finditer(text):
            found.append((label, match.group(0)[:40]))
    for label, pattern in CONFUSION:
        for match in pattern.finditer(text):
            found.append((label, match.group(0)[:40]))
    return found


def manifest_totals():
    totals = {}
    if not os.path.exists(MANIFEST):
        return totals
    lines = io.open(MANIFEST, encoding="utf-8-sig").read().splitlines()
    header = lines[0].split(",")
    for line in lines[1:]:
        if not line.strip():
            continue
        cells = line.split(",")
        row = dict(zip(header, cells))
        try:
            # The four named categories, not total_error_operations. The total
            # counts something broader - it runs about 40% higher than the
            # categories sum to - and measuring coverage against it would
            # understate the detector for no reason.
            totals[int(row["number"])] = sum(
                int(row[key]) for key in (
                    "spelling_operations",
                    "grammar_operations",
                    "punctuation_operations",
                    "formatting_spacing_operations",
                )
            )
        except (KeyError, ValueError):
            pass
    return totals


def body(path):
    """
    The shipped file carries a report header before the writer's text.

    Split on the first blank line, not a fixed count. Version 1's report was
    eight lines and Version 2's is ten, so skipping eight here would leave two
    header lines inside the text and count them as the writer's errors.
    """
    text = io.open(path, encoding="utf-8", errors="replace").read()
    lines = text.split("\n")
    if not lines or not lines[0].startswith("STATUS:"):
        return text
    for index, line in enumerate(lines):
        if not line.strip():
            return "\n".join(lines[index + 1:])
    return text


def score(name):
    source_path = os.path.join(SOURCES, name + ".txt")
    out_path = os.path.join(OUTPUTS, name + ".out.txt")
    if not os.path.exists(out_path):
        return None
    source = io.open(source_path, encoding="utf-8", errors="replace").read()
    # A run that timed out or failed writes the writer's own text back out. That
    # is not a correction and must never be scored as one. score_all.py already
    # carried this guard; the keyless scorer did not, and on the one real
    # TIMEOUT file in the archive that omission read as 10.6% fixed when the app
    # had fixed nothing at all. The 10.6% was the fixed-count header skip
    # deleting the title and the first two paragraphs, errors and all.
    status = io.open(out_path, encoding="utf-8", errors="replace") \
        .read().split("\n", 1)[0].strip()
    if not status.startswith("STATUS: OK"):
        return {"name": name, "broken": status}
    shipped = body(out_path)

    before = detect(source)
    after = detect(shipped)
    before_counts = Counter(c for c, _ in before)
    after_counts = Counter(c for c, _ in after)

    # An error present in the shipped text but NOT in the writer's is one the
    # app introduced. Counted separately, and against the app twice.
    before_evidence = Counter(before)
    introduced = []
    after_evidence = Counter(after)
    for item, count in after_evidence.items():
        extra = count - before_evidence.get(item, 0)
        if extra > 0:
            introduced.extend([item] * extra)

    found = len(before)
    left = len(after)
    fixed = max(0, found - left)
    rate = 100.0 * fixed / found if found else 100.0
    penalty = 2.0 * len(introduced)
    return {
        "name": name,
        "found": found,
        "left": left,
        "fixed": fixed,
        "rate": rate,
        "score": max(0.0, rate - penalty),
        "introduced": introduced,
        "before_counts": before_counts,
        "after_counts": after_counts,
        "residual": after,
    }


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    names = sorted(
        os.path.basename(p)[:-4] for p in glob.glob(os.path.join(SOURCES, "*.txt"))
    )
    if only:
        names = [n for n in names if n.startswith(only)]
    manifest = manifest_totals()

    results = [r for r in (score(n) for n in names) if r]
    if not results:
        sys.exit("score_new.py: no shipped outputs yet in " + OUTPUTS)
    broken = [r for r in results if r.get("broken")]
    results = [r for r in results if not r.get("broken")]
    results.sort(key=lambda r: r["score"])

    print("%-4s %-46s %6s %6s %7s %8s" % (
        "no", "essay", "seen", "left", "rate", "score"))
    for r in results:
        print("%-4s %-46s %6d %6d %6.1f%% %7.1f%%" % (
            r["name"][:2], r["name"][3:49], r["found"], r["left"],
            r["rate"], r["score"]))
    for r in broken:
        print("%-4s %-46s   %s" % (r["name"][:2], r["name"][3:49], r["broken"]))
    print()
    below = [r for r in results if r["score"] < FLOOR]
    print("%d of %d at or above %g%%." % (len(results) - len(below), len(results), FLOOR))
    if below:
        print("Below the bar: " + ", ".join(r["name"][:2] for r in below))
    if broken:
        print("Not scored, the run did not finish: "
              + ", ".join(r["name"][:2] for r in broken))

    # Coverage: how much of the injected damage this detector can even see.
    if manifest:
        seen = sum(r["found"] for r in results)
        injected = sum(manifest.get(int(r["name"][:2]), 0) for r in results)
        if injected:
            print()
            print("Detector coverage: %d of %d injected operations are visible "
                  "to it (%.0f%%)." % (seen, injected, 100.0 * seen / injected))
            print("The rest need reading. Errors it cannot see are excluded "
                  "from both sides of the rate.")

    if only and results:
        r = results[0]
        print()
        if r["introduced"]:
            print("  INTRODUCED by the app (counts double):")
            for category, evidence in Counter(r["introduced"]).most_common(15):
                print("    %-32s %s" % (category[0], repr(category[1])[:60]))
        print("  still wrong, by category:")
        for category, count in r["after_counts"].most_common():
            print("    %-32s %d left of %d" % (
                category, count, r["before_counts"].get(category, 0)))
        print()
        print("  residual detail (first 40):")
        for category, evidence in r["residual"][:40]:
            print("    [%-28s] %s" % (category, repr(evidence)[:60]))

    # Exit non-zero below the floor so a regression fails the runner instead of
    # printing a table nobody reads, matching score_all.py. A run that could not
    # be scored is also a failure: an essay silently dropped from the table used
    # to read as a pass.
    return 1 if below or broken else 0


sys.exit(main())
