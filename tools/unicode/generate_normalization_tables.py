#!/usr/bin/env python3
"""Writes kitejs-rhino/.../NormalizationTables.kt, the data of String.prototype.normalize (#135).

ECMAScript 2015, 21.1.3.12: the result is the normalization form of Unicode Standard Annex 15.
Common Kotlin has no normalizer, so the engine carries the one-level decompositions, the
canonical combining classes and the full composition exclusions of the UCD. Pinned to 16.0.0,
like the case tables:

    python3 tools/unicode/generate_normalization_tables.py <folder with UnicodeData.txt and
        DerivedNormalizationProps.txt of 16.0.0>
"""
import pathlib
import sys

VERSION = "16.0.0"
ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
OUT = ROOT / "kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/NormalizationTables.kt"
PART = 40000


def main(ucd: pathlib.Path) -> None:
    decomp, ccc = [], []
    for line in (ucd / "UnicodeData.txt").read_text(encoding="utf-8").splitlines():
        f = line.split(";")
        cp = int(f[0], 16)
        if f[3] != "0":
            ccc.append((cp, int(f[3])))
        d = f[5]
        if d:
            compat = d.startswith("<")
            if compat:
                d = d.split(">", 1)[1].strip()
            parts = ".".join(format(int(x, 16), "x") for x in d.split())
            decomp.append(format(cp, "x") + (":k" if compat else ":") + parts)
    excl = set()
    for line in (ucd / "DerivedNormalizationProps.txt").read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        f = [x.strip() for x in line.split(";")]
        if f[1] != "Full_Composition_Exclusion":
            continue
        if ".." in f[0]:
            a, b = (int(x, 16) for x in f[0].split(".."))
        else:
            a = b = int(f[0], 16)
        excl.update(range(a, b + 1))

    def runs(pairs):
        # ccc in runs: start, end, class
        out = []
        for cp, c in sorted(pairs):
            if out and out[-1][1] == cp - 1 and out[-1][2] == c:
                out[-1][1] = cp
            else:
                out.append([cp, cp, c])
        return ";".join(f"{a:x}-{b:x}:{c}" for a, b, c in out)

    def ranges(cps):
        out = []
        for cp in sorted(cps):
            if out and out[-1][1] == cp - 1:
                out[-1][1] = cp
            else:
                out.append([cp, cp])
        return ",".join(f"{a:x}-{b:x}" for a, b in out)

    def parts(text, name):
        chunks = [text[i:i + PART] for i in range(0, len(text), PART)]
        consts = []
        for k, c in enumerate(chunks):
            lines = [c[i:i + 100] for i in range(0, len(c), 100)]
            body = " +\n".join(f'        "{l}"' for l in lines)
            consts.append(f"    private const val {name}_{k}: String =\n{body}\n")
        joined = " + ".join(f"{name}_{k}" for k in range(len(chunks)))
        return "".join(consts), joined

    d_consts, d_join = parts(";".join(decomp), "DECOMPOSITIONS")
    c_consts, c_join = parts(runs(ccc), "COMBINING_CLASSES")
    e_consts, e_join = parts(ranges(excl), "COMPOSITION_EXCLUSIONS")
    text = f'''/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

// GENERATED FILE. Do not edit by hand.
// Written by tools/unicode/generate_normalization_tables.py from the Unicode Character Database {VERSION}.

/**
 * The normalization data of the Unicode Character Database {VERSION}: each code point's one-level
 * decomposition, `k` marking a compatibility one; the canonical combining classes, as runs of
 * code points with one class; and the code points of Full_Composition_Exclusion (UAX #15; #135).
 * The values are hexadecimal. Each table is split in parts, as a class file holds no string
 * constant over 64 KB.
 */
internal object NormalizationTables {{

    val DECOMPOSITIONS: String get() = {d_join}

    val COMBINING_CLASSES: String get() = {c_join}

    val COMPOSITION_EXCLUSIONS: String get() = {e_join}

{d_consts}
{c_consts}
{e_consts}}}
'''
    OUT.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]))
