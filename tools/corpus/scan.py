# Scans one shipped essay for words the app produced that are not words.
#
# score_all.py asks whether the app FIXED the writer's errors. This asks the
# opposite question: whether the app DAMAGED anything. Both matter. An app that
# changes nothing scores perfect here and zero there.
#
# A green run status proves neither. Three separate corruptions were found
# inside runs reporting a perfect accepted=10 untouched=0: "does't" for every
# third-person negation, "thaey" left untouched, and the model stuttering
# "entertainment" into "enentertainment".
#
#   python scan.py 07_Hamsters_in_Quebec_City_Canada.out.txt
import difflib
import io
import os
import re
import sys
from collections import Counter

BASE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(BASE, "work")
# Same override the scorer takes, so both read the round the runner just pulled
# rather than whatever was left in work/ by an earlier one.
OUTPUTS = os.environ.get("MANUSCRIPT_CORPUS_OUT") or os.path.join(WORK, "out")
SUBS = os.path.join(WORK, "subs")
# The fixtures are the writer's text, hash-pinned by
# quality/animal-pet-essays/fixture-manifest.json. A second corpus that is not
# pinned in the tree passes its own directory instead, because the damage scan
# is worth having for any document, not only the pinned twenty.
SOURCES = os.environ.get("MANUSCRIPT_CORPUS_SRC") or os.path.join(
    BASE, "..", "..", "quality", "animal-pet-essays", "fixtures"
)
DICT = os.path.join(
    BASE, "..", "..", "app", "src", "main", "assets",
    "symspell", "frequency_dictionary_en.txt",
)

dic = set()
for line in io.open(DICT, encoding="utf-8-sig", errors="replace"):
    parts = line.split()
    if parts:
        dic.add(parts[0].lower())

name = sys.argv[1]
out_path = os.path.join(OUTPUTS, name)
if not os.path.exists(out_path):
    sys.exit("scan.py: no shipped output at " + out_path)
out = io.open(out_path, encoding="utf-8", errors="replace").read()

# Split the harness report off on the first blank line rather than a fixed line
# count. Version 1's report was eight lines and Version 2's is ten, so a
# hardcoded skip ate real paragraphs and reported them as app-produced defects.
lines = out.split("\n")
head = []
body = out
for index, line in enumerate(lines):
    if not line.strip():
        head = lines[:index]
        body = "\n".join(lines[index + 1:])
        break

src_path = os.path.join(SOURCES, name.replace(".out.txt", ".txt"))
# A missing source is fatal, not a blank. Without it every unknown word is
# classified "APP PRODUCED IT" because nothing matches the empty string, and the
# substitution review below silently produces nothing at all. On 2026-08-11 a
# cleanup deleted the source copy and the next run reported a correctly repaired
# place name as an app-produced defect, while the review that would have caught
# a real rename was skipped without a word.
if not os.path.exists(src_path):
    sys.exit("scan.py: no source essay at " + src_path)
src = io.open(src_path, encoding="utf-8", errors="replace").read()

CONTRACTIONS = {"ain't", "y'all", "o'clock", "'s", "'re", "'ve", "'ll", "'d", "'t", "'m"}
# The shipped word list leans British, so correct American spellings are absent
# from it. SymSpellWordPolicy whitelists these in the app for the same reason;
# the scanner must not report them as defects.
CONTRACTIONS |= {
    "neighborhood", "neighborhoods", "neighbor", "neighbors", "colorful", "cannot",
}
# Real words absent from the shipped list. The app is verified to leave these
# alone; the scanner must not report that correct behaviour as a defect.
CONTRACTIONS |= {"axolotl", "axolotls"}


def american_of(word):
    """British spellings in the shipped list that yield this American word."""
    forms = []
    if word.endswith("or"):
        forms.append(word[:-2] + "our")
    if word.endswith("ors"):
        forms.append(word[:-3] + "ours")
    if word.endswith("er"):
        forms.append(word[:-2] + "re")
    if word.endswith("ers"):
        forms.append(word[:-3] + "res")
    if "iz" in word:
        forms.append(word.replace("iz", "is"))
    if "yz" in word:
        forms.append(word.replace("yz", "ys"))
    if "e" in word:
        forms += [word.replace("e", "ae", 1), word.replace("e", "oe", 1)]
    return forms


def known(raw):
    word = raw.lower().strip("'")
    if word in dic or word in CONTRACTIONS:
        return True
    # A token carrying an accented letter is a loanword or a name, not a
    # misspelling. An ASCII-only tokenizer used to clip "cafe" out of the
    # accented spelling and report the stub as a defect.
    if any(ord(character) > 127 for character in word):
        return True
    # The shipped word list leans British, so a correct American spelling can be
    # absent from it. If a British form of this word is in the list, the word is
    # real and the app was right to leave it alone.
    if any(form in dic for form in american_of(word)):
        return True
    # possessive: owner's -> owner ; dogs' -> dogs
    if word.endswith("'s") and word[:-2] in dic:
        return True
    if word.endswith("s'") and word[:-1] in dic:
        return True
    # ordinary contraction: don't -> the apostrophe form is checked whole first
    if "'" in word:
        stem = word.split("'")[0]
        if stem in dic and len(word) - len(stem) <= 3:
            return True
    return False


bad = Counter(
    word for word in re.findall(r"[^\W\d_][\w']{2,}", body, re.UNICODE)
    if not known(word)
)
print(" | ".join(line for line in head if line.strip()))
if not bad:
    print("SCAN: clean")
else:
    for word, count in bad.most_common():
        origin = (
            "in source"
            if re.search(r"\b" + re.escape(word) + r"\b", src, re.I)
            else "APP PRODUCED IT"
        )
        print("SCAN: %-16s x%-3d %s" % (word, count, origin))

# --- Substitution review -----------------------------------------------------
# A dictionary scan cannot see the worst corruptions, because their result is a
# real word: "cair" shipped as "Car", "aint" as "ait", "wasnt" as "wash't".
# Every short word-for-word substitution is written out so a reader can judge the
# ones a word list never will.


def words_in(text):
    return re.findall(r"[A-Za-z']+", text)


source_words, output_words = words_in(src), words_in(body)
if source_words and output_words:
    matcher = difflib.SequenceMatcher(
        None,
        [word.lower() for word in source_words],
        [word.lower() for word in output_words],
    )
    subs, seen = [], set()
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag != "replace" or (i2 - i1) > 2 or (j2 - j1) > 2:
            continue
        before = " ".join(source_words[i1:i2])
        after = " ".join(output_words[j1:j2])
        key = (before.lower(), after.lower())
        if key in seen:
            continue
        seen.add(key)
        subs.append((before, after))
    # A substitution whose result is NOT in the word list already shows up in the
    # scan above. These are the invisible ones: the app replaced a word with a
    # different real word.
    real_word = [
        (before, after) for before, after in subs
        if all(part.lower().strip("'") in dic for part in after.split())
        and not all(part.lower().strip("'") in dic for part in before.split())
    ]
    os.makedirs(SUBS, exist_ok=True)
    subs_path = os.path.join(SUBS, name.replace(".out.txt", ".subs.txt"))
    with io.open(subs_path, "w", encoding="utf-8") as handle:
        for before, after in subs:
            handle.write("%s -> %s\n" % (before, after))
    print(
        "SUBS: %d substitutions, %d replaced a broken word with a real one"
        % (len(subs), len(real_word))
    )
