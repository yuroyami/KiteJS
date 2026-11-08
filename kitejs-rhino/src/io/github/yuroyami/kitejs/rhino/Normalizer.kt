/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * The four normalization forms of Unicode Standard Annex 15, from [NormalizationTables], for
 * `String.prototype.normalize` (ECMAScript 2015, 21.1.3.12; #135): full decomposition, canonical
 * or compatibility, then canonical ordering, then for NFC and NFKC canonical composition. Hangul
 * syllables decompose and compose by the algorithm of Unicode, 3.12.
 */
internal object Normalizer {

    enum class Form(val compatibility: Boolean, val composed: Boolean) {
        NFC(false, true), NFD(false, false), NFKC(true, true), NFKD(true, false)
    }

    private class Decomposition(val compatibility: Boolean, val parts: IntArray)

    private val decompositions: Map<Int, Decomposition> by lazy {
        val map = HashMap<Int, Decomposition>(6000)
        for (row in NormalizationTables.DECOMPOSITIONS.split(';')) {
            if (row.isEmpty()) continue
            val colon = row.indexOf(':')
            val key = row.substring(0, colon).toInt(16)
            var rest = row.substring(colon + 1)
            val compat = rest.startsWith("k")
            if (compat) rest = rest.substring(1)
            map[key] = Decomposition(compat, rest.split('.').map { it.toInt(16) }.toIntArray())
        }
        map
    }

    /** Start, end and class of each run, in order. */
    private val combiningClasses: IntArray by lazy {
        val rows = NormalizationTables.COMBINING_CLASSES.split(';')
        val out = IntArray(rows.size * 3)
        for ((k, row) in rows.withIndex()) {
            val dash = row.indexOf('-')
            val colon = row.indexOf(':')
            out[3 * k] = row.substring(0, dash).toInt(16)
            out[3 * k + 1] = row.substring(dash + 1, colon).toInt(16)
            out[3 * k + 2] = row.substring(colon + 1).toInt()
        }
        out
    }

    /** Each primary composite by the pair of code points it decomposes to (UAX #15, D114). */
    private val compositions: Map<Long, Int> by lazy {
        val excluded = HashSet<Int>()
        for (r in NormalizationTables.COMPOSITION_EXCLUSIONS.split(',')) {
            if (r.isEmpty()) continue
            val dash = r.indexOf('-')
            val a = r.substring(0, dash).toInt(16)
            val b = r.substring(dash + 1).toInt(16)
            for (cp in a..b) excluded += cp
        }
        val map = HashMap<Long, Int>(2048)
        for ((cp, d) in decompositions) {
            if (d.compatibility || d.parts.size != 2 || cp in excluded) continue
            map[pairKey(d.parts[0], d.parts[1])] = cp
        }
        map
    }

    private fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 21) or b.toLong()

    fun combiningClass(cp: Int): Int {
        val t = combiningClasses
        var lo = 0
        var hi = t.size / 3 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < t[3 * mid] -> hi = mid - 1
                cp > t[3 * mid + 1] -> lo = mid + 1
                else -> return t[3 * mid + 2]
            }
        }
        return 0
    }

    fun normalize(s: String, form: Form): String {
        // Text below U+0300 is already in NFC: nothing there decomposes canonically to anything
        // but its own composite. NFD splits its accented letters and NFKC its fractions.
        if (form == Form.NFC && s.all { it.code < 0x300 }) return s
        val cps = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val cp = codePointAt(s, i)
            i += if (cp >= 0x10000) 2 else 1
            decompose(cp, form.compatibility, cps)
        }
        reorder(cps)
        if (form.composed) compose(cps)
        val sb = StringBuilder(cps.size)
        for (cp in cps) appendCodePoint(sb, cp)
        return sb.toString()
    }

    private fun decompose(cp: Int, compatibility: Boolean, out: MutableList<Int>) {
        if (cp in S_BASE until S_BASE + S_COUNT) {
            val index = cp - S_BASE
            out += L_BASE + index / N_COUNT
            out += V_BASE + (index % N_COUNT) / T_COUNT
            val t = index % T_COUNT
            if (t != 0) out += T_BASE + t
            return
        }
        val d = decompositions[cp]
        if (d == null || (d.compatibility && !compatibility)) {
            out += cp
            return
        }
        for (part in d.parts) decompose(part, compatibility, out)
    }

    /** The canonical ordering algorithm: a stable sort of each run of nonzero classes (UAX #15, D109). */
    private fun reorder(cps: MutableList<Int>) {
        var i = 0
        while (i < cps.size) {
            if (combiningClass(cps[i]) == 0) { i++; continue }
            var j = i
            while (j < cps.size && combiningClass(cps[j]) != 0) j++
            if (j - i > 1) {
                val run = cps.subList(i, j).sortedBy { combiningClass(it) }
                for (k in run.indices) cps[i + k] = run[k]
            }
            i = j
        }
    }

    /** The canonical composition algorithm (UAX #15, D117), with Hangul by Unicode, 3.12. */
    private fun compose(cps: MutableList<Int>) {
        var starter = -1
        // The class of the last code point kept after the starter; a character is blocked from
        // the starter when one between them has class 0 or a class at least its own.
        var lastClass = 0
        val out = ArrayList<Int>(cps.size)
        for (cp in cps) {
            val cc = combiningClass(cp)
            if (starter >= 0) {
                val adjacent = out.size - 1 == starter
                if (adjacent || (lastClass != 0 && lastClass < cc)) {
                    val composite = composePair(out[starter], cp)
                    if (composite != null) {
                        out[starter] = composite
                        continue
                    }
                }
            }
            if (cc == 0) starter = out.size
            lastClass = cc
            out += cp
        }
        cps.clear()
        cps.addAll(out)
    }

    private fun composePair(a: Int, b: Int): Int? {
        if (a in L_BASE until L_BASE + L_COUNT && b in V_BASE until V_BASE + V_COUNT) {
            return S_BASE + ((a - L_BASE) * V_COUNT + (b - V_BASE)) * T_COUNT
        }
        if (a in S_BASE until S_BASE + S_COUNT && (a - S_BASE) % T_COUNT == 0 && b in T_BASE + 1 until T_BASE + T_COUNT) {
            return a + (b - T_BASE)
        }
        return compositions[pairKey(a, b)]
    }

    private fun codePointAt(s: String, i: Int): Int {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            return 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
        }
        return c.code
    }

    private fun appendCodePoint(sb: StringBuilder, cp: Int) {
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            sb.append((0xD800 + (v ushr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
        } else {
            sb.append(cp.toChar())
        }
    }

    private const val S_BASE = 0xAC00
    private const val L_BASE = 0x1100
    private const val V_BASE = 0x1161
    private const val T_BASE = 0x11A7
    private const val L_COUNT = 19
    private const val V_COUNT = 21
    private const val T_COUNT = 28
    private const val N_COUNT = V_COUNT * T_COUNT
    private const val S_COUNT = L_COUNT * N_COUNT
}
