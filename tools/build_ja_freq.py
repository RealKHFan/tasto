#!/usr/bin/env python3
"""Japanese word frequencies for ranking kana→kanji conversions.

Input: OpenSubtitles 2018 frequency list by Hermit Dave (CC BY-SA 4.0), "word count" per line.
Output (UTF-8): "word<TAB>count", most common first. Only words with kanji or katakana are kept
(hiragana-only words never need converting), and only those seen at least 3 times.

Usage: build_ja_freq.py <ja_full.txt> <output.txt>
"""
import re
import sys

KANJI_OR_KATAKANA = re.compile(r"[一-鿿㐀-䶿々ァ-ヺ]")


def main():
    src, dst = sys.argv[1], sys.argv[2]
    rows = []
    with open(src, encoding="utf-8") as fh:
        for line in fh:
            parts = line.rstrip("\n").rsplit(" ", 1)
            if len(parts) != 2 or not parts[1].isdigit():
                continue
            word, count = parts[0].strip(), int(parts[1])
            if count < 3 or not word or "\t" in word or not KANJI_OR_KATAKANA.search(word):
                continue
            rows.append((word, count))
    rows.sort(key=lambda r: -r[1])
    with open(dst, "w", encoding="utf-8") as out:
        for word, count in rows:
            out.write(f"{word}\t{count}\n")
    print(f"{dst}: {len(rows)} words", file=sys.stderr)


if __name__ == "__main__":
    main()
