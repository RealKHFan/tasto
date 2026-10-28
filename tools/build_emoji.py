#!/usr/bin/env python3
"""Build Tasto's emoji asset from Unicode's emoji-test.txt.

Keeps fully-qualified emoji up to a maximum emoji version, without skin-tone
variants. Output: a "#<group>" line per category followed by one emoji per line.

Usage: build_emoji.py <emoji-test.txt> <output.txt> [max_version]
"""
import re
import sys

SKIN = {chr(c) for c in range(0x1F3FB, 0x1F400)}
GROUPS = {
    "Smileys & Emotion": "smileys",
    "People & Body": "people",
    "Animals & Nature": "nature",
    "Food & Drink": "food",
    "Activities": "activities",
    "Travel & Places": "travel",
    "Objects": "objects",
    "Symbols": "symbols",
    "Flags": "flags",
}
LINE = re.compile(r"^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#\s*(\S+)\s+E(\d+\.\d+)")


def main():
    src, dst = sys.argv[1], sys.argv[2]
    max_version = float(sys.argv[3]) if len(sys.argv) > 3 else 15.1
    out = {}
    order = []
    group = None
    with open(src, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("# group:"):
                name = line.split(":", 1)[1].strip()
                group = GROUPS.get(name)
                if group and group not in out:
                    out[group] = []
                    order.append(group)
                continue
            if group is None:
                continue
            m = LINE.match(line)
            if not m:
                continue
            codes = [int(c, 16) for c in m.group(1).split()]
            emoji = "".join(chr(c) for c in codes)
            if float(m.group(3)) > max_version or any(ch in SKIN for ch in emoji):
                continue
            out[group].append(emoji)
    # Smileys and People share one tab, like most keyboards.
    out["smileys"] = out.pop("smileys") + out.pop("people")
    order.remove("people")
    with open(dst, "w", encoding="utf-8") as fh:
        for g in order:
            fh.write(f"#{g}\n")
            for e in out[g]:
                fh.write(e + "\n")
    print({g: len(out[g]) for g in order}, file=sys.stderr)


if __name__ == "__main__":
    main()
