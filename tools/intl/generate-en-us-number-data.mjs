/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/*
 * Writes the en-US strings `toLocaleString` formats numbers with, and the cases that check the
 * formatter against ICU, from the Intl.NumberFormat of the Node that runs it (full ICU):
 *
 *   node tools/intl/generate-en-us-number-data.mjs .
 *
 * kitejs-api/.../format/EnUsNumberData.kt     currency symbols, digits and names, unit patterns
 * kitejs-api/.../format/EnUsNumberOracle.kt   values, options and what ICU prints for them
 *
 * Only what differs from the default is kept: a currency ICU knows nothing about prints its code,
 * with two fraction digits, and a compound unit is its numerator's pattern with the
 * denominator's "per" suffix unless ICU has a pattern of its own for it.
 */

import { writeFileSync } from "node:fs";
import { join } from "node:path";

const root = process.argv[2] ?? ".";
const pkg = "kitejs-api/src/commonMain/kotlin/io/github/yuroyami/kitejs/api/format";
const testPkg = "kitejs-api/src/commonTest/kotlin/io/github/yuroyami/kitejs/api/format";

const nf = (o) => new Intl.NumberFormat("en-US", o);
const HEADER = `/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
`;

/** A Kotlin string literal, with everything outside printable ASCII escaped. */
function kt(s) {
    let out = '"';
    for (const ch of s) {
        const c = ch.codePointAt(0);
        if (ch === '"' || ch === "\\" || ch === "$") out += "\\" + ch;
        else if (c >= 0x20 && c < 0x7f) out += ch;
        else if (c <= 0xffff) out += "\\u" + c.toString(16).padStart(4, "0");
        else {
            const s16 = String.fromCodePoint(c);
            out += "\\u" + s16.charCodeAt(0).toString(16) + "\\u" + s16.charCodeAt(1).toString(16);
        }
    }
    return out + '"';
}

/** Splits [lines] into string literals small enough for the JVM's constant pool. */
function chunks(lines) {
    const out = [];
    let cur = [];
    let size = 0;
    for (const l of lines) {
        if (size + l.length > 12000 && cur.length) {
            out.push(cur);
            cur = [];
            size = 0;
        }
        cur.push(l);
        size += l.length + 1;
    }
    if (cur.length) out.push(cur);
    return out.map((c) => "        " + kt(c.join("\n") + "\n")).join(",\n");
}

function check(cond, what) {
    if (!cond) throw new Error(what);
}

// ---- Currencies ---------------------------------------------------------------------------------

const currencies = [];
const A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
for (const a of A) for (const b of A) for (const c of A) {
    const code = a + b + c;
    const digits = nf({ style: "currency", currency: code }).resolvedOptions().maximumFractionDigits;
    const one = nf({ minimumFractionDigits: digits, maximumFractionDigits: digits }).format(1);
    const prefix = (display) => {
        const s = nf({ style: "currency", currency: code, currencyDisplay: display }).format(1);
        check(s.endsWith(one), `${code} ${display} is not a prefix: ${s}`);
        return s.slice(0, s.length - one.length);
    };
    const symbol = prefix("symbol");
    const narrow = prefix("narrowSymbol");
    check(prefix("code") === code + " ", `${code} code display`);
    const nameOne = nf({ style: "currency", currency: code, currencyDisplay: "name", maximumFractionDigits: 0 })
        .format(1).replace(/^1 /, "");
    const nameOther = nf({ style: "currency", currency: code, currencyDisplay: "name" })
        .format(2).replace(/^2(\.0+)? /, "");
    const dflt = code + " ";
    if (symbol !== dflt || narrow !== dflt || digits !== 2 || nameOne !== code || nameOther !== code) {
        const row = [code, symbol === dflt ? "" : symbol, narrow === symbol ? "=" : narrow === dflt ? "" : narrow,
            digits === 2 ? "" : String(digits), nameOne === code ? "" : nameOne, nameOther === nameOne ? "" : nameOther];
        for (const f of row) check(!f.includes("|") && !f.includes("\n"), `separator in ${code}`);
        currencies.push(row.join("|"));
    }
}

// ---- Units --------------------------------------------------------------------------------------

const UNITS = ("acre bit byte celsius centimeter day degree fahrenheit fluid-ounce foot gallon gigabit " +
    "gigabyte gram hectare hour inch kilobit kilobyte kilogram kilometer liter megabit megabyte meter " +
    "microsecond mile mile-scandinavian milliliter millimeter millisecond minute month nanosecond ounce " +
    "percent petabyte pound second stone terabit terabyte week yard year").split(" ");
const DISPLAYS = ["short", "narrow", "long"];
const pattern = (unit, display, n) => {
    const s = nf({ style: "unit", unit, unitDisplay: display }).format(n);
    check(s.split(String(n)).length === 2, `${unit} ${display} ${s}`);
    return s.replace(String(n), "{0}");
};
const simple = {};
for (const u of UNITS) simple[u] = DISPLAYS.map((d) => [pattern(u, d, 1), pattern(u, d, 2)]);
// The suffix a denominator adds, as most compound units show it.
const suffix = {};
for (const y of UNITS) {
    suffix[y] = DISPLAYS.map((d, di) => {
        const counts = new Map();
        for (const x of UNITS) {
            for (const n of [1, 2]) {
                const out = pattern(`${x}-per-${y}`, d, n);
                const num = simple[x][di][n - 1];
                if (out.startsWith(num)) counts.set(out.slice(num.length), (counts.get(out.slice(num.length)) ?? 0) + 1);
            }
        }
        return [...counts.entries()].sort((p, q) => q[1] - p[1])[0][0];
    });
}
const unitRows = UNITS.map((u) => [u, ...simple[u].flat(), ...suffix[u]].join("|"));
const exceptionRows = [];
for (const x of UNITS) for (const y of UNITS) {
    const unit = `${x}-per-${y}`;
    DISPLAYS.forEach((d, di) => {
        const actual = [pattern(unit, d, 1), pattern(unit, d, 2)];
        const generic = [0, 1].map((i) => simple[x][di][i] + suffix[y][di]);
        if (actual[0] !== generic[0] || actual[1] !== generic[1]) exceptionRows.push([unit, di, ...actual].join("|"));
    });
}
for (const r of [...unitRows, ...exceptionRows]) check(!r.includes("\n"), "newline in a unit");

writeFileSync(join(root, pkg, "EnUsNumberData.kt"), `${HEADER}
package io.github.yuroyami.kitejs.api.format

/*
 * Generated by tools/intl/generate-en-us-number-data.mjs from ICU ${process.versions.icu}, CLDR ${process.versions.cldr}.
 * Do not edit by hand.
 */

/** The en-US strings [EnUsNumberFormat] prints currencies and units with. */
internal object EnUsNumberData {

    /** Currency code, symbol, narrow symbol (= for the symbol), digits, name, plural name. Empty is the default. */
    private val CURRENCIES = listOf(
${chunks(currencies)},
    )

    /** Unit, its patterns for one and for other in short, narrow and long, then its "per" suffix in each. */
    private val UNITS = listOf(
${chunks(unitRows)},
    )

    /** Compound unit, display, and its own patterns for one and for other. */
    private val COMPOUND_EXCEPTIONS = listOf(
${chunks(exceptionRows)},
    )

    private class Currency(val symbol: String, val narrow: String, val digits: Int, val one: String, val other: String)

    private val currencies: Map<String, Currency> by lazy {
        val map = HashMap<String, Currency>()
        for (line in CURRENCIES.joinToString("").lineSequence()) {
            if (line.isEmpty()) continue
            val f = line.split('|')
            val code = f[0]
            val symbol = f[1].ifEmpty { "$code\\u00A0" }
            val narrow = when (f[2]) {
                "=" -> symbol
                "" -> "$code\\u00A0"
                else -> f[2]
            }
            val one = f[4].ifEmpty { code }
            map[code] = Currency(symbol, narrow, f[3].ifEmpty { "2" }.toInt(), one, f[5].ifEmpty { one })
        }
        map
    }

    private val units: Map<String, List<String>> by lazy {
        UNITS.joinToString("").lineSequence().filter { it.isNotEmpty() }.associate { line ->
            val f = line.split('|')
            f[0] to f.drop(1)
        }
    }

    private val exceptions: Map<String, List<String>> by lazy {
        COMPOUND_EXCEPTIONS.joinToString("").lineSequence().filter { it.isNotEmpty() }.associate { line ->
            val f = line.split('|')
            "\${f[0]}|\${f[1]}" to f.drop(2)
        }
    }

    fun currencySymbol(code: String, narrow: Boolean): String {
        val c = currencies[code] ?: return "$code\\u00A0"
        return if (narrow) c.narrow else c.symbol
    }

    fun currencyDigits(code: String): Int = currencies[code]?.digits ?: 2

    /** The currency's name for one, and for other. */
    fun currencyNames(code: String): Pair<String, String> {
        val c = currencies[code] ?: return code to code
        return c.one to c.other
    }

    /** A sanctioned simple unit, or two joined by "-per-" (ECMA-402 IsWellFormedUnitIdentifier). */
    fun isWellFormedUnit(unit: String): Boolean {
        if (unit in units) return true
        val per = unit.indexOf("-per-")
        return per > 0 && unit.substring(0, per) in units && unit.substring(per + 5) in units
    }

    /** The pattern, with {0} for the number, of a well-formed [unit] in [display] (short, narrow, long). */
    fun unitPattern(unit: String, display: Int, one: Boolean): String {
        val plural = if (one) 0 else 1
        exceptions["$unit|$display"]?.let { return it[plural] }
        val per = unit.indexOf("-per-")
        if (per < 0) return units.getValue(unit)[display * 2 + plural]
        val numerator = units.getValue(unit.substring(0, per))
        val denominator = units.getValue(unit.substring(per + 5))
        return numerator[display * 2 + plural] + denominator[6 + display]
    }
}
`);

// ---- Oracle cases -------------------------------------------------------------------------------

let seed = 0x5eed1234;
function random() {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const pick = (xs) => xs[Math.floor(random() * xs.length)];

const NUMBERS = [0, -0, 1, -1, 2, 0.5, -0.5, 1.5, 2.5, -2.5, 1.005, 1.0005, 0.125, 9.995, 99.95, 999.9995,
    1234.5, 1234.5678, -1234.5678, 12345.678, 123456.789, 999999, 999950, 1e6, 1234567, -987654.321,
    1e15, 1e21, 1.5e300, 1.7976931348623157e308, 12345678901234567, 0.000123, 0.000001234, 1.5e-7, 5e-310,
    0.07, 0.1, 0.3, 1 / 3, 2 / 3, NaN, Infinity, -Infinity, 42, 100, 1000, 10000, 0.0005, 0.995];
const BIGINTS = [0n, 1n, -1n, 123n, 999999n, 1000000n, -123456789n, 12345678901234567890123456789n];

const NOTATIONS = ["standard", "scientific", "engineering", "compact"];
const optionPool = {
    style: () => pick(["decimal", "percent", "currency", "unit"]),
    currency: () => pick(["USD", "EUR", "JPY", "GBP", "CHF", "BHD", "XOF", "CLF", "INR", "usd", "XYZ", "PEN", "CAD"]),
    currencyDisplay: () => pick(["code", "symbol", "narrowSymbol", "name"]),
    currencySign: () => pick(["standard", "accounting"]),
    unit: () => random() < 0.3 ? `${pick(UNITS)}-per-${pick(UNITS)}` : pick(UNITS),
    unitDisplay: () => pick(DISPLAYS),
    notation: () => pick(NOTATIONS),
    compactDisplay: () => pick(["short", "long"]),
    minimumIntegerDigits: () => pick([1, 2, 3, 5]),
    minimumFractionDigits: () => pick([0, 1, 2, 3, 5]),
    maximumFractionDigits: () => pick([0, 1, 2, 3, 4, 6, 20]),
    minimumSignificantDigits: () => pick([1, 2, 3, 5]),
    maximumSignificantDigits: () => pick([1, 2, 3, 5, 8, 21]),
    roundingMode: () => pick(["ceil", "floor", "expand", "trunc", "halfCeil", "halfFloor", "halfExpand", "halfTrunc", "halfEven"]),
    roundingPriority: () => pick(["auto", "morePrecision", "lessPrecision"]),
    trailingZeroDisplay: () => pick(["auto", "stripIfInteger"]),
    useGrouping: () => pick([true, false, "min2", "auto", "always", "true", "false"]),
    signDisplay: () => pick(["auto", "never", "always", "exceptZero", "negative"]),
};

const curated = [
    {}, { style: "percent" }, { style: "currency", currency: "USD" }, { style: "currency", currency: "JPY" },
    { style: "currency", currency: "EUR", currencyDisplay: "name" }, { style: "currency", currency: "EUR", currencyDisplay: "code" },
    { style: "currency", currency: "USD", currencySign: "accounting" }, { style: "unit", unit: "kilometer-per-hour" },
    { style: "unit", unit: "meter", unitDisplay: "long" }, { style: "unit", unit: "mile-per-gallon", unitDisplay: "narrow" },
    { notation: "scientific" }, { notation: "engineering" }, { notation: "compact" }, { notation: "compact", compactDisplay: "long" },
    { minimumFractionDigits: 2, maximumFractionDigits: 2 }, { maximumSignificantDigits: 3 }, { minimumSignificantDigits: 5 },
    { minimumIntegerDigits: 4 }, { useGrouping: false }, { useGrouping: "min2" }, { signDisplay: "always" },
    { signDisplay: "exceptZero" }, { signDisplay: "negative" }, { signDisplay: "never" },
    { maximumFractionDigits: 1, roundingMode: "halfEven" }, { maximumFractionDigits: 2, minimumFractionDigits: 2, roundingIncrement: 5 },
    { maximumFractionDigits: 0, minimumFractionDigits: 0, roundingIncrement: 25 }, { maximumFractionDigits: 2, minimumFractionDigits: 2, roundingIncrement: 50, roundingMode: "halfEven" },
    { maximumSignificantDigits: 2, maximumFractionDigits: 2, roundingPriority: "morePrecision" },
    { maximumSignificantDigits: 2, maximumFractionDigits: 2, roundingPriority: "lessPrecision" },
    { style: "currency", currency: "USD", trailingZeroDisplay: "stripIfInteger" }, { roundingMode: "ceil", maximumFractionDigits: 0 },
    { roundingMode: "floor", maximumFractionDigits: 0 }, { notation: "compact", maximumFractionDigits: 2 },
    { notation: "compact", style: "currency", currency: "USD" }, { notation: "scientific", style: "percent" },
    { useGrouping: true, notation: "compact" }, { useGrouping: "true", notation: "compact" }, { maximumFractionDigits: 20 },
    { maximumSignificantDigits: 21 }, { localeMatcher: "lookup", numberingSystem: "latn" },
];
const errorCases = [
    { style: "currency" }, { style: "unit" }, { currency: "US" }, { currency: "U$D" }, { unit: "parsec" },
    { unit: "meter-per-parsec" }, { style: "bogus" }, { minimumIntegerDigits: 0 }, { minimumIntegerDigits: 22 },
    { maximumFractionDigits: 101 }, { minimumFractionDigits: 3, maximumFractionDigits: 2 }, { roundingIncrement: 3 },
    { roundingIncrement: 5, maximumFractionDigits: 2 }, { roundingIncrement: 5, maximumSignificantDigits: 2 },
    { useGrouping: "sometimes" }, { numberingSystem: "x" }, { localeMatcher: "nope" }, { signDisplay: "maybe" },
    { minimumSignificantDigits: 5, maximumSignificantDigits: 3 }, { maximumSignificantDigits: NaN },
];

function randomOptions() {
    const o = {};
    for (const [k, gen] of Object.entries(optionPool)) if (random() < 0.25) o[k] = gen();
    if (o.style === "currency" && o.currency === undefined) o.currency = optionPool.currency();
    if (o.style === "unit" && o.unit === undefined) o.unit = optionPool.unit();
    return o;
}

function encodeOptions(o) {
    return Object.entries(o).map(([k, v]) => {
        const t = typeof v === "string" ? "s" : typeof v === "boolean" ? "b" : "n";
        check(typeof v !== "string" || !/[,|=\n]/.test(v), "option text");
        return `${k}=${t}${String(v)}`;
    }).join(",");
}

function run(value, o) {
    try {
        return nf(o).format(value);
    } catch (e) {
        return "!" + e.name;
    }
}

const encodeValue = (v) => typeof v === "bigint" ? "b" + v : "n" + (Object.is(v, -0) ? "-0" : String(v));
const cases = [];
// Every curated option set meets a spread of values; the random ones meet a few each.
const SPREAD = [0, -0, 1, -1, 0.5, -2.5, 1.0005, 9.995, 1234.5678, -987654.321, 999950, 1234567, 1e21, 0.000123,
    1.5e-7, NaN, -Infinity, 123n, -123456789n];
for (const o of curated) for (const v of SPREAD) cases.push([encodeValue(v), encodeOptions(o), run(v, o)]);
for (const o of errorCases) cases.push(["n1", encodeOptions(o), run(1, o)]);
let accepted = 0;
while (accepted < 300) {
    const o = randomOptions();
    if (run(1, o).startsWith("!")) continue;
    accepted++;
    for (let i = 0; i < 3; i++) {
        const v = random() < 0.15 ? pick(BIGINTS) : random() < 0.5 ? pick(NUMBERS) : Number((random() * 10 ** Math.floor(random() * 12 - 4)).toPrecision(1 + Math.floor(random() * 8)));
        cases.push([encodeValue(v), encodeOptions(o), run(v, o)]);
    }
}
const caseLines = cases.map((c) => {
    for (const f of c) check(!f.includes("|") && !f.includes("\n"), "separator in a case");
    return c.join("|");
});

writeFileSync(join(root, testPkg, "EnUsNumberOracle.kt"), `${HEADER}
package io.github.yuroyami.kitejs.api.format

/*
 * Generated by tools/intl/generate-en-us-number-data.mjs from ICU ${process.versions.icu}, CLDR ${process.versions.cldr}.
 * Do not edit by hand.
 */

/**
 * What \`new Intl.NumberFormat("en-US", options).format(value)\` printed, one case a line:
 * the value (n for a Number, b for a BigInt), the options (name=type and value, s for a string,
 * n for a number, b for a boolean), and the text, or ! and the error's name.
 */
internal val EN_US_NUMBER_ORACLE: List<String> = listOf(
${chunks(caseLines).replace(/^ {8}/gm, "    ")},
)
`);

console.log(`${currencies.length} currencies, ${unitRows.length} units, ${exceptionRows.length} compound exceptions, ${cases.length} cases`);
