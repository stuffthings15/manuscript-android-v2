# Derives each essay's ground truth from the two versions of it that exist.
#
# score.py needs to know what the writer's errors WERE. Essay 21's list is 187
# patterns written by hand, and twenty more of those is the whole cost of
# scoring the corpus - so it is worth noticing that the cost does not have to
# be paid.
#
# The essays were generated twice: the corpus uses
# 20_Animal_Pet_Essays_800_Percent_More_Errors, and beside it sits
# 20_Animal_Pet_Error_Essays, the same twenty essays with far fewer errors
# injected. Neither is clean. But where the two differ, the milder one is
# nearly always right, so aligning them word by word hands back the injected
# errors: "Grate" against "Great", "cud" against "could", "gardin" against
# "guarding".
#
# What this CANNOT see is an error the generator put in both copies. The milder
# essay 01 still reads "Dogs Makes Great Pets" and "they usually is speaking",
# and those are the agreement errors - the class the app was weakest at. They
# come from score.py's shared pattern library instead, which is why that
# library is not optional.
#
# A derived pair is a candidate, not a verdict. The milder version can be
# wrong-to-less-wrong rather than wrong-to-right, so the output is meant to be
# read before it is trusted.
#
#   python derive.py            # all twenty, writes truth/NN.json
#   python derive.py 01         # one, and prints what it found
import glob
import html
import io
import json
import os
import re
import sys
import zipfile
from difflib import SequenceMatcher

BASE = os.path.dirname(os.path.abspath(__file__))
DICT = os.path.join(
    BASE, "..", "..", "app", "src", "main", "assets",
    "symspell", "frequency_dictionary_en.txt",
)


def load_known():
    words = set()
    if not os.path.exists(DICT):
        return words
    for line in io.open(DICT, encoding="utf-8-sig", errors="replace"):
        parts = line.split()
        if len(parts) >= 2:
            words.add(parts[0])
    return words


KNOWN = None
MANUSCRIPT = os.path.abspath(os.path.join(BASE, "..", "..", ".."))
HARD = os.path.join(MANUSCRIPT, "20_Animal_Pet_Essays_800_Percent_More_Errors")
MILD = os.path.join(MANUSCRIPT, "20_Animal_Pet_Error_Essays")
TRUTH = os.path.join(BASE, "truth")

# Punctuation the alignment reports as a difference when nothing was corrected.
EDGE = " \t\r\n.,;:!?\"'()[]"


def read_docx(path):
    with zipfile.ZipFile(path) as archive:
        xml = archive.read("word/document.xml").decode("utf-8")
    xml = re.sub(r"</w:p>", "\n", xml)
    xml = re.sub(r"<[^>]+>", "", xml)
    return html.unescape(xml).strip()


def pairs_for(hard_text, mild_text):
    """Every word the harder version has and the milder one corrects."""
    hard = hard_text.split()
    mild = mild_text.split()
    matcher = SequenceMatcher(
        None, [w.lower() for w in mild], [w.lower() for w in hard]
    )
    found = []
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag != "replace" or (i2 - i1) != (j2 - j1):
            continue
        for step in range(i2 - i1):
            wrong = hard[j1 + step].strip(EDGE)
            right = mild[i1 + step].strip(EDGE)
            if not wrong or not right:
                continue
            # Same word, different punctuation or case only. Not an error the
            # app is being asked to fix, and counting it would flatter or
            # punish the score for no reason.
            if wrong.lower() == right.lower():
                continue
            # A number or a bare digit run is the generator's numbering, not a
            # spelling.
            if any(character.isdigit() for character in wrong + right):
                continue
            if is_noise(wrong, right):
                continue
            found.append({"wrong": wrong, "right": right})
    return found


def edit_distance(left, right):
    previous = list(range(len(right) + 1))
    for i, a in enumerate(left, 1):
        current = [i]
        for j, b in enumerate(right, 1):
            current.append(
                min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (a != b))
            )
        previous = current
    return previous[-1]


def is_scattered(wrong, count, hard_words):
    """
    True when the pair looks like a word that simply appears everywhere.

    Aligning two texts pairs a word against whatever sits opposite it, so a
    common word can be matched once by accident. "it" against "is" was
    observed a single time in essay 17 while "it" appears seventeen times, and
    "but" against "It" once against sixteen. A real injected error is not
    scattered through the essay like that: it was typed wrong where it was
    typed.
    """
    if wrong.lower() not in KNOWN:
        return False
    appearances = sum(1 for w in hard_words if w.strip(EDGE).lower() == wrong.lower())
    return appearances >= 8 * max(1, count)


def is_noise(wrong, right):
    """
    True when the alignment paired two words that have nothing to do with
    each other.

    Aligning two texts of different lengths puts unrelated words opposite one
    another wherever a sentence was rewritten rather than a word corrected:
    "pay" against "attention", "and" against "The", "the" against "pet".
    Scoring the app against those would mark it down for errors that were
    never in the essay.

    Both real words and more than two edits apart is the test. A genuine
    confusion is close - "cud" for "could", "wether" for "weather", "framer"
    for "farmer" - because that is what makes it invisible in the first place.
    """
    if not KNOWN:
        return False
    if wrong.lower() not in KNOWN or right.lower() not in KNOWN:
        return False
    if edit_distance(wrong.lower(), right.lower()) > 2:
        return True
    # British against American is a difference the speller refuses to touch on
    # purpose, so counting it as an error would score the app down for
    # behaving as designed.
    return {wrong.lower(), right.lower()} in ({"fibre", "fiber"}, {"colour", "color"})


def main():
    global KNOWN
    KNOWN = load_known()
    only = sys.argv[1] if len(sys.argv) > 1 else None
    if not os.path.isdir(HARD) or not os.path.isdir(MILD):
        sys.exit(
            "derive.py: need both essay sets beside Manuscript/:\n"
            "  %s\n  %s" % (HARD, MILD)
        )
    if not os.path.isdir(TRUTH):
        os.makedirs(TRUTH)

    total = 0
    for hard_path in sorted(glob.glob(os.path.join(HARD, "*.docx"))):
        name = os.path.basename(hard_path)
        number = name[:2]
        if only and number != only:
            continue
        mild_path = os.path.join(MILD, name)
        if not os.path.exists(mild_path):
            print("%s: no milder version, skipped" % number)
            continue
        found = pairs_for(read_docx(hard_path), read_docx(mild_path))
        # One entry per distinct pair, carrying how many times the alignment
        # actually saw it.
        #
        # The count is not decoration. Without it "it" paired once against
        # "is" by a misalignment made every one of the seventeen "it"s in
        # essay 17 count as an error, and the essay scored 71% for mistakes
        # nobody made. A pair may be scored at most as often as it was
        # observed.
        unique = []
        seen = {}
        for pair in found:
            key = (pair["wrong"].lower(), pair["right"].lower())
            if key in seen:
                seen[key]["count"] += 1
                continue
            pair["count"] = 1
            seen[key] = pair
            unique.append(pair)
        hard_words = read_docx(hard_path).split()
        unique = [
            p for p in unique
            if not is_scattered(p["wrong"], p["count"], hard_words)
        ]
        out = os.path.join(TRUTH, number + ".json")
        io.open(out, "w", encoding="utf-8").write(
            json.dumps({"essay": name, "pairs": unique}, indent=2, ensure_ascii=False)
        )
        total += len(unique)
        print("%s  %3d distinct pairs (%d instances)" % (number, len(unique), len(found)))
        if only:
            for pair in unique:
                print("    %-18s -> %s" % (pair["wrong"], pair["right"]))
    print()
    print("%d pairs written to truth/. Read them before trusting them:" % total)
    print("the milder version is not clean either, so a pair can be")
    print("wrong-to-less-wrong rather than wrong-to-right.")


main()
