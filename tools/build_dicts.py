#!/usr/bin/env python3
"""Turn AOSP-style .combined word lists into Tasto's compact dictionary assets.

Output format (UTF-8 text, one entry per line):
    word<TAB>freq<TAB>next1|next2|next3
freq is 1..255 (log scale, from the source list). Next words are ordered
most likely first and always refer to words that are present in the same file.

Usage: build_dicts.py <input.combined> <output.txt> <max_words> [conversational.txt]

The optional conversational list ("word count" per line, most frequent first, e.g. the
OpenSubtitles lists from github.com/hermitdave/FrequencyWords) lifts words that are common
in chat but rare in news text ("hallo", "okay", "tschüss").
"""
import math
import re
import sys
import unicodedata

WORD_RE = re.compile(r"^[^\W\d_](?:[^\W\d_]|['’\-](?=[^\W\d_]))*$")


def parse(path):
    words = []  # (word, freq, [bigrams])
    cur = None
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if line.startswith(" word="):
                fields = dict(
                    kv.split("=", 1) for kv in line.strip().split(",") if "=" in kv
                )
                w = fields.get("word", "")
                try:
                    f = int(fields.get("f", "0"))
                except ValueError:
                    f = 0
                flags = fields.get("flags", "")
                bad = (
                    f <= 0
                    or "offensive" in flags
                    or fields.get("not_a_word") == "true"
                    or not WORD_RE.match(w)
                    or len(w) > 32
                )
                cur = None if bad else [w.replace("’", "'"), f, []]
                if cur is not None:
                    words.append(cur)
            elif line.startswith("  bigram=") and cur is not None:
                fields = dict(
                    kv.split("=", 1) for kv in line.strip().split(",") if "=" in kv
                )
                try:
                    bf = int(fields.get("f", "0"))
                except ValueError:
                    bf = 0
                cur[2].append((fields.get("bigram", ""), bf))
    return words


def fold(w):
    """Same idea as the app's Folding: strip accents from Latin letters only (й stays й)."""
    w = w.lower().replace("ß", "ss").replace("'", "").replace("’", "").replace("ё", "е")
    out = []
    for ch in w:
        if ord(ch) < 0x250:
            out.extend(c for c in unicodedata.normalize("NFD", ch) if not unicodedata.combining(c))
        else:
            out.append(ch)
    return "".join(out)


def boost(words, conv_path):
    # Subtitle lists contain accent-stripped spellings ("fur" for "für", "dont"); never add
    # a new word whose folded form already belongs to a real word.
    folded_main = {fold(e[0]) for e in words}
    by_lower = {}
    for e in words:
        cur = by_lower.get(e[0].lower())
        if cur is None or e[1] > cur[1]:
            by_lower[e[0].lower()] = e
    added = boosted = 0
    with open(conv_path, encoding="utf-8") as fh:
        for rank, line in enumerate(fh, start=1):
            parts = line.split()
            if len(parts) != 2:
                continue
            w = parts[0].replace("’", "'")
            if not WORD_RE.match(w) or len(w) > 32:
                continue
            f = int(round(254 - 12 * math.log2(rank)))
            if f < 40:
                break
            e = by_lower.get(w.lower())
            if e is not None:
                if f > e[1]:
                    e[1] = f
                    boosted += 1
            elif rank <= 30000 and len(w) > 1 and fold(w) not in folded_main:
                e = [w, f, []]
                words.append(e)
                by_lower[w.lower()] = e
                added += 1
    print(f"conversational boost: {boosted} boosted, {added} added", file=sys.stderr)


def main():
    src, dst, max_words = sys.argv[1], sys.argv[2], int(sys.argv[3])
    words = parse(src)
    if len(sys.argv) > 4:
        boost(words, sys.argv[4])
    # Highest frequency first; stable for equal freq (keeps source order).
    words.sort(key=lambda e: -e[1])
    seen = set()
    kept = []
    for w, f, bg in words:
        if w in seen:
            continue
        seen.add(w)
        kept.append((w, f, bg))
        if len(kept) >= max_words:
            break
    present = {w for w, _, _ in kept}
    with open(dst, "w", encoding="utf-8") as out:
        for w, f, bg in kept:
            nexts = [b for b, _ in sorted(bg, key=lambda x: -x[1]) if b in present and b != w]
            out.write(f"{w}\t{min(255, f)}\t{'|'.join(nexts[:3])}\n")
    print(f"{dst}: {len(kept)} words", file=sys.stderr)


if __name__ == "__main__":
    main()
