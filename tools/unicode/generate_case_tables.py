#!/usr/bin/env python3
"""Writes kitejs-rhino/.../CaseTables.kt, the case mappings that String.prototype.toLowerCase and
toUpperCase use, from the Unicode Character Database (#136).

ECMAScript 2015, 21.1.3.22 asks for the full, locale-insensitive case mappings of the UCD,
SpecialCasing.txt included. They come from the platform otherwise, and the targets disagree, so
the engine carries its own. Pinned to Unicode 16.0.0:

    python3 tools/unicode/generate_case_tables.py <folder with UnicodeData.txt, SpecialCasing.txt
        and DerivedCoreProperties.txt of 16.0.0>
"""
import pathlib
import sys

VERSION = "16.0.0"
ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
OUT = ROOT / "kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/CaseTables.kt"


def main(ucd: pathlib.Path) -> None:
    lower, upper = {}, {}
    for line in (ucd / "UnicodeData.txt").read_text(encoding="utf-8").splitlines():
        f = line.split(";")
        cp = int(f[0], 16)
        if f[12]:
            upper[cp] = [int(f[12], 16)]
        if f[13]:
            lower[cp] = [int(f[13], 16)]
    # Ranges of UnicodeData (First, Last) map nothing, so the plain rows are all there is.
    for line in (ucd / "SpecialCasing.txt").read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        f = [x.strip() for x in line.split(";")]
        conditions = f[4] if len(f) > 4 else ""
        if conditions:
            continue  # Final_Sigma is in code; the others need a locale (21.1.3.22)
        cp = int(f[0], 16)
        lo = [int(x, 16) for x in f[1].split()]
        up = [int(x, 16) for x in f[3].split()]
        lower[cp] = lo
        upper[cp] = up

    def table(m):
        rows = []
        for cp in sorted(m):
            if m[cp] == [cp]:
                continue
            rows.append(format(cp, "x") + ":" + ".".join(format(x, "x") for x in m[cp]))
        return rows

    def ranges(prop):
        cps = set()
        for line in (ucd / "DerivedCoreProperties.txt").read_text(encoding="utf-8").splitlines():
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            f = [x.strip() for x in line.split(";")]
            if f[1] != prop:
                continue
            if ".." in f[0]:
                a, b = (int(x, 16) for x in f[0].split(".."))
            else:
                a = b = int(f[0], 16)
            cps.update(range(a, b + 1))
        out = []
        start = prev = None
        for cp in sorted(cps):
            if start is None:
                start = prev = cp
            elif cp == prev + 1:
                prev = cp
            else:
                out.append((start, prev))
                start = prev = cp
        if start is not None:
            out.append((start, prev))
        return ",".join(f"{a:x}-{b:x}" for a, b in out)

    def chunks(rows, width=100):
        lines, cur = [], ""
        for r in rows:
            piece = r + ";"
            if len(cur) + len(piece) > width:
                lines.append(cur)
                cur = ""
            cur += piece
        if cur:
            lines.append(cur)
        return " +\n".join(f'        "{l}"' for l in lines)

    def chunk_string(s, width=100):
        parts = [s[i:i + width] for i in range(0, len(s), width)]
        return " +\n".join(f'        "{p}"' for p in parts)

    lo, up = table(lower), table(upper)
    text = f'''/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

// GENERATED FILE. Do not edit by hand.
// Written by tools/unicode/generate_case_tables.py from the Unicode Character Database {VERSION}.

/**
 * The full, locale-insensitive case mappings of the Unicode Character Database {VERSION}, its
 * SpecialCasing.txt included, and the Cased and Case_Ignorable properties that the Final_Sigma
 * condition reads (ECMAScript 2015, 21.1.3.22; Unicode, 3.13). Each row is a code point and its
 * mapping, in hexadecimal; a code point with no row maps to itself (#136).
 */
internal object CaseTables {{

    /** {len(lo)} rows. */
    const val LOWER: String =
{chunks(lo)}

    /** {len(up)} rows. */
    const val UPPER: String =
{chunks(up)}

    /** Inclusive ranges of the Cased property. */
    const val CASED: String =
{chunk_string(ranges("Cased"))}

    /** Inclusive ranges of the Case_Ignorable property. */
    const val CASE_IGNORABLE: String =
{chunk_string(ranges("Case_Ignorable"))}
}}
'''
    OUT.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]))
