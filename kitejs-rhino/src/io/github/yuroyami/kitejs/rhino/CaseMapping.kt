/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * `toLowerCase` and `toUpperCase` from the engine's own [CaseTables], so every target gives one
 * answer (ECMAScript 2015, 21.1.3.22; #136). The platform's case tables differed: the JVM had no
 * Garay letters of Unicode 16, and the targets disagreed on the final sigma.
 */
internal object CaseMapping {

    private val lower: Map<Int, String> by lazy { parse(CaseTables.LOWER) }
    private val upper: Map<Int, String> by lazy { parse(CaseTables.UPPER) }
    private val cased: IntArray by lazy { ranges(CaseTables.CASED) }
    private val caseIgnorable: IntArray by lazy { ranges(CaseTables.CASE_IGNORABLE) }

    fun toLowerCase(s: String): String = convert(s, lowerCase = true)

    fun toUpperCase(s: String): String = convert(s, lowerCase = false)

    private fun convert(s: String, lowerCase: Boolean): String {
        // The common case, ASCII only, needs no table.
        var ascii = true
        for (c in s) if (c.code >= 0x80) { ascii = false; break }
        if (ascii) {
            val chars = CharArray(s.length) { i ->
                val c = s[i]
                if (lowerCase && c in 'A'..'Z') (c.code + 32).toChar() else if (!lowerCase && c in 'a'..'z') (c.code - 32).toChar() else c
            }
            return chars.concatToString()
        }
        val table = if (lowerCase) lower else upper
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = codePointAt(s, i)
            val n = if (cp >= 0x10000) 2 else 1
            if (lowerCase && cp == CAPITAL_SIGMA) {
                sb.append(if (finalSigma(s, i, n)) 'ς' else 'σ')
            } else {
                val mapped = table[cp]
                if (mapped != null) sb.append(mapped) else sb.append(s, i, i + n)
            }
            i += n
        }
        return sb.toString()
    }

    /**
     * Unicode, 3.13, Final_Sigma: the sigma at [at] follows a cased letter, with only case-ignorable
     * characters between, and no cased letter follows it in the same way.
     */
    private fun finalSigma(s: String, at: Int, width: Int): Boolean {
        var j = at
        var before = false
        while (j > 0) {
            val cp = codePointBefore(s, j)
            j -= if (cp >= 0x10000) 2 else 1
            if (isIn(caseIgnorable, cp)) continue
            before = isIn(cased, cp)
            break
        }
        if (!before) return false
        var k = at + width
        while (k < s.length) {
            val cp = codePointAt(s, k)
            k += if (cp >= 0x10000) 2 else 1
            if (isIn(caseIgnorable, cp)) continue
            return !isIn(cased, cp)
        }
        return true
    }

    private fun codePointAt(s: String, i: Int): Int {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            return 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
        }
        return c.code
    }

    private fun codePointBefore(s: String, end: Int): Int {
        val c = s[end - 1]
        if (c.isLowSurrogate() && end - 2 >= 0 && s[end - 2].isHighSurrogate()) {
            return 0x10000 + ((s[end - 2].code - 0xD800) shl 10) + (c.code - 0xDC00)
        }
        return c.code
    }

    /** Whether [cp] lies in one of the inclusive [ranges], start and end in turn. */
    private fun isIn(ranges: IntArray, cp: Int): Boolean {
        var lo = 0
        var hi = ranges.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < ranges[2 * mid] -> hi = mid - 1
                cp > ranges[2 * mid + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    private fun parse(rows: String): Map<Int, String> {
        val map = HashMap<Int, String>(2048)
        for (row in rows.split(';')) {
            if (row.isEmpty()) continue
            val colon = row.indexOf(':')
            val key = row.substring(0, colon).toInt(16)
            val sb = StringBuilder(2)
            for (hex in row.substring(colon + 1).split('.')) {
                val cp = hex.toInt(16)
                if (cp >= 0x10000) {
                    val v = cp - 0x10000
                    sb.append((0xD800 + (v ushr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
                } else {
                    sb.append(cp.toChar())
                }
            }
            map[key] = sb.toString()
        }
        return map
    }

    private fun ranges(text: String): IntArray {
        val parts = text.split(',')
        val out = IntArray(parts.size * 2)
        for ((k, p) in parts.withIndex()) {
            val dash = p.indexOf('-')
            out[2 * k] = p.substring(0, dash).toInt(16)
            out[2 * k + 1] = p.substring(dash + 1).toInt(16)
        }
        return out
    }

    private const val CAPITAL_SIGMA = 0x3A3
}
