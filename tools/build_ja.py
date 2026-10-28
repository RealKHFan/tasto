#!/usr/bin/env python3
"""Turn the SKK-JISYO.L kana→kanji dictionary (GPL-2.0-or-later) into Tasto's Japanese asset.

Output (UTF-8): one entry per line, "reading<TAB>cand1<TAB>cand2...", most common first.
Readings are hiragana (and ー). Entries for verbs/adjectives with okurigana ("okuri-ari") end in
the romaji consonant of the first okurigana kana, as in SKK: "たべr" -> 食べ (+る).

Usage: build_ja.py <SKK-JISYO.L in EUC-JP> <output.txt> [max candidates per reading]
"""
import re
import sys

READING = re.compile(r"^[ぁ-ゖー]+[a-z]?$")


def main():
    src, dst = sys.argv[1], sys.argv[2]
    max_cands = int(sys.argv[3]) if len(sys.argv) > 3 else 12
    table = {}
    with open(src, encoding="euc_jp", errors="replace") as fh:
        for line in fh:
            if line.startswith(";") or " /" not in line:
                continue
            reading, rest = line.rstrip("\n").split(" /", 1)
            if not READING.match(reading):
                continue
            cands = []
            for c in rest.split("/"):
                c = c.split(";", 1)[0].strip()  # drop SKK annotations
                if not c or c.startswith("(") or "\t" in c or c in cands or "�" in c:
                    continue
                cands.append(c)
                if len(cands) >= max_cands:
                    break
            if cands:
                old = table.setdefault(reading, [])
                old.extend(c for c in cands if c not in old)
    # Sorted by reading, so the keyboard can binary-search it without sorting on the phone.
    with open(dst, "w", encoding="utf-8") as out:
        for reading in sorted(table):
            out.write(reading + "\t" + "\t".join(table[reading][:max_cands]) + "\n")
    print(f"{dst}: {len(table)} readings", file=sys.stderr)


if __name__ == "__main__":
    main()
