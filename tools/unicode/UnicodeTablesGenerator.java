/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;

/**
 * Writes commonMain/.../regexp/UnicodeTables.kt from this JDK's own java.lang.Character.
 *
 * Rhino's regexp engine asks java.lang.Character a fixed set of questions, so the tables are read
 * from the same source the oracle compares against. Run it with JDK 21, whose Unicode version is
 * 15.0:
 *
 *   javac -d /tmp/ugen tools/unicode/UnicodeTablesGenerator.java
 *   java --add-opens java.base/java.lang=ALL-UNNAMED -cp /tmp/ugen UnicodeTablesGenerator .
 *
 * UnicodeTablesOracleTest walks every code point and fails if the committed file ever drifts.
 */
public final class UnicodeTablesGenerator {

    private static final int MAX = 0x10FFFF;
    private static final int CHUNK = 40000;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path out = root.resolve("kitejs/src/commonMain/kotlin/io/github/yuroyami/kitejs/UnicodeTables.kt");

        StringBuilder b = new StringBuilder();
        b.append("/* This Source Code Form is subject to the terms of the Mozilla Public\n")
            .append(" * License, v. 2.0. If a copy of the MPL was not distributed with this\n")
            .append(" * file, You can obtain one at http://mozilla.org/MPL/2.0/. */\n\n")
            .append("package io.github.yuroyami.kitejs\n\n")
            .append("// GENERATED FILE. Do not edit by hand.\n")
            .append("// Written by tools/unicode/UnicodeTablesGenerator.java from JDK ")
            .append(System.getProperty("java.specification.version"))
            .append(", whose Unicode version is 15.0.\n")
            .append("// UnicodeTablesOracleTest compares every code point against java.lang.Character.\n\n")
            .append("/**\n")
            .append(" * The Unicode data the lexer and the regexp engine need, as sorted range tables.\n")
            .append(" *\n")
            .append(" * Every platform reads the same numbers, so `\\\\p{...}`, case-insensitive matching and\n")
            .append(" * identifier classification give one answer everywhere instead of asking the host.\n")
            .append(" */\n")
            .append("internal object UnicodeTables {\n\n");

        // Range tables: flat [start, end, start, end, ...], inclusive, sorted.
        ranges(b, "ALPHABETIC", Character::isAlphabetic);
        ranges(b, "LOWERCASE", Character::isLowerCase);
        ranges(b, "UPPERCASE", Character::isUpperCase);
        ranges(b, "WHITE_SPACE", cp -> Character.isSpaceChar(cp) || Character.isWhitespace(cp));
        ranges(b, "ID_START", Character::isUnicodeIdentifierStart);
        ranges(b, "ID_CONTINUE", Character::isUnicodeIdentifierPart);
        ranges(b, "HEX_DIGIT", cp -> Character.digit(cp, 16) != -1);
        ranges(b, "JAVA_IDENTIFIER_START", Character::isJavaIdentifierStart);
        ranges(b, "JAVA_IDENTIFIER_PART", Character::isJavaIdentifierPart);

        // General category, run-length encoded.
        runLength(b, "CATEGORY", Character::getType);

        // Script, run-length encoded over the enum ordinals.
        @SuppressWarnings("EnumOrdinal")
        java.util.function.IntUnaryOperator scriptOf = cp -> Character.UnicodeScript.of(cp).ordinal();
        runLength(b, "SCRIPT", scriptOf);

        // Simple case mapping, as key and value lists.
        caseMap(b, "UPPER", true);
        caseMap(b, "LOWER", false);

        scriptNames(b);
        decoder(b);

        b.append("}\n");
        Files.createDirectories(out.getParent());
        Files.writeString(out, b.toString());
        System.out.println("wrote " + out + " (" + b.length() + " chars)");
    }

    private static void ranges(StringBuilder b, String name, IntPredicate p) {
        List<Integer> flat = new ArrayList<>();
        int start = -1;
        for (int cp = 0; cp <= MAX; cp++) {
            boolean v = p.test(cp);
            if (v && start < 0) start = cp;
            if (!v && start >= 0) { flat.add(start); flat.add(cp - 1); start = -1; }
        }
        if (start >= 0) { flat.add(start); flat.add(MAX); }
        emit(b, name, toArray(flat), true, "Inclusive ranges, " + (flat.size() / 2) + " of them.");
    }

    private static void runLength(StringBuilder b, String name, java.util.function.IntUnaryOperator f) {
        List<Integer> starts = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        int prev = Integer.MIN_VALUE;
        for (int cp = 0; cp <= MAX; cp++) {
            int v = f.applyAsInt(cp);
            if (v != prev) { starts.add(cp); values.add(v); prev = v; }
        }
        emit(b, name + "_STARTS", toArray(starts), true, "Run starts, " + starts.size() + " runs.");
        emit(b, name + "_VALUES", toArray(values), false, "The value each run holds.");
    }

    private static void caseMap(StringBuilder b, String name, boolean upper) {
        List<Integer> keys = new ArrayList<>();
        List<Integer> deltas = new ArrayList<>();
        for (int cp = 0; cp <= MAX; cp++) {
            int m = upper ? Character.toUpperCase(cp) : Character.toLowerCase(cp);
            if (m != cp) { keys.add(cp); deltas.add(zigzag(m - cp)); }
        }
        emit(b, name + "_KEYS", toArray(keys), true, "Code points this mapping changes, " + keys.size() + " of them.");
        emit(b, name + "_DELTAS", toArray(deltas), false, "Zigzagged offset from the key to its mapping.");
    }

    private static void scriptNames(StringBuilder b) throws Exception {
        Map<String, Integer> byName = new TreeMap<>();
        Character.UnicodeScript[] all = Character.UnicodeScript.values();
        for (int i = 0; i < all.length; i++) byName.put(all[i].name(), i);

        Field f = Character.UnicodeScript.class.getDeclaredField("aliases");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Character.UnicodeScript> aliases = (Map<String, Character.UnicodeScript>) f.get(null);
        for (Map.Entry<String, Character.UnicodeScript> e : aliases.entrySet()) {
            byName.put(e.getKey(), e.getValue().ordinal());
        }

        b.append("    /** Script name to ordinal, upper case with separators removed, aliases included. */\n");
        b.append("    val SCRIPT_NAMES: Map<String, Int> = mapOf(\n");
        for (Map.Entry<String, Integer> e : byName.entrySet()) {
            b.append("        \"").append(e.getKey()).append("\" to ").append(e.getValue()).append(",\n");
        }
        b.append("    )\n\n");
    }

    private static void emit(StringBuilder b, String name, int[] values, boolean delta, String doc) {
        int[] encoded = values.clone();
        if (delta) {
            for (int i = values.length - 1; i > 0; i--) encoded[i] = values[i] - values[i - 1];
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < encoded.length; i++) {
            if (i > 0) text.append(',');
            text.append(Integer.toString(encoded[i], 36));
        }
        b.append("    /** ").append(doc).append(" */\n");
        b.append("    val ").append(name).append(": IntArray = decode(\n");
        String s = text.toString();
        for (int i = 0; i < s.length(); i += CHUNK) {
            int end = Math.min(s.length(), i + CHUNK);
            // Never split a number across two literals.
            while (end < s.length() && s.charAt(end) != ',') end++;
            b.append("        \"").append(s, i, end).append("\",\n");
            i = end - CHUNK;
        }
        b.append("        cumulative = ").append(delta).append(",\n");
        b.append("    )\n\n");
    }

    private static void decoder(StringBuilder b) {
        b.append("""
    /**
     * Reads the base-36 numbers back. [cumulative] means each number is an offset from the one
     * before it, which is what keeps the sorted tables small.
     */
    private fun decode(vararg parts: String, cumulative: Boolean): IntArray {
        var count = 0
        for (part in parts) {
            if (part.isEmpty()) continue
            count++
            for (c in part) if (c == ',') count++
        }
        val out = IntArray(count)
        var at = 0
        var running = 0
        for (part in parts) {
            var value = 0
            var negative = false
            var empty = true
            for (c in part) {
                if (c == ',') {
                    running = if (cumulative && at > 0) running + (if (negative) -value else value) else (if (negative) -value else value)
                    out[at++] = running
                    value = 0
                    negative = false
                    empty = true
                } else if (c == '-') {
                    negative = true
                    empty = false
                } else {
                    value = value * 36 + (if (c <= '9') c - '0' else c - 'a' + 10)
                    empty = false
                }
            }
            if (!empty) {
                running = if (cumulative && at > 0) running + (if (negative) -value else value) else (if (negative) -value else value)
                out[at++] = running
            }
        }
        return out
    }

    /** True when [cp] falls in one of the inclusive ranges in [table]. */
    fun inRanges(table: IntArray, cp: Int): Boolean {
        var lo = 0
        var hi = table.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < table[mid * 2] -> hi = mid - 1
                cp > table[mid * 2 + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /** The value of the run [cp] falls in. */
    fun runValue(starts: IntArray, values: IntArray, cp: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= cp) lo = mid else hi = mid - 1
        }
        return values[lo]
    }

    /** [cp] mapped through a case table, or [cp] itself when the table does not change it. */
    fun mapCase(keys: IntArray, deltas: IntArray, cp: Int): Int {
        var lo = 0
        var hi = keys.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < keys[mid] -> hi = mid - 1
                cp > keys[mid] -> lo = mid + 1
                else -> {
                    val z = deltas[mid]
                    return cp + ((z ushr 1) xor -(z and 1))
                }
            }
        }
        return cp
    }
""");
    }

    private static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static int[] toArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }
}
