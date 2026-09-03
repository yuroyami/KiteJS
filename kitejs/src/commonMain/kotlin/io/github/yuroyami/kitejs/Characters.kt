/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * KMP replacement for the java.lang.Character code-point and category APIs the engine uses.
 *
 * BMP classification matches the JVM through Char.category. Common Kotlin has no category
 * lookup for supplementary code points, so those are classified permissively as identifier
 * characters and never as space or format characters (ledger D-1).
 */
internal object Characters {

    const val MIN_SUPPLEMENTARY_CODE_POINT = 0x10000

    fun charCount(codePoint: Int): Int = if (codePoint >= MIN_SUPPLEMENTARY_CODE_POINT) 2 else 1

    fun highSurrogate(codePoint: Int): Char =
        (((codePoint - MIN_SUPPLEMENTARY_CODE_POINT) ushr 10) + 0xD800).toChar()

    fun lowSurrogate(codePoint: Int): Char =
        (((codePoint - MIN_SUPPLEMENTARY_CODE_POINT) and 0x3FF) + 0xDC00).toChar()

    fun toCodePoint(high: Char, low: Char): Int =
        ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + MIN_SUPPLEMENTARY_CODE_POINT

    fun codePointAt(s: String, index: Int): Int {
        val c1 = s[index]
        if (c1.isHighSurrogate() && index + 1 < s.length) {
            val c2 = s[index + 1]
            if (c2.isLowSurrogate()) return toCodePoint(c1, c2)
        }
        return c1.code
    }

    fun codePointToString(codePoint: Int): String =
        if (codePoint < MIN_SUPPLEMENTARY_CODE_POINT) codePoint.toChar().toString()
        else charArrayOf(highSurrogate(codePoint), lowSurrogate(codePoint)).concatToString()

    fun isUnicodeIdentifierStart(codePoint: Int): Boolean {
        if (codePoint < 0) return false
        if (codePoint >= MIN_SUPPLEMENTARY_CODE_POINT) return true
        return when (codePoint.toChar().category) {
            CharCategory.UPPERCASE_LETTER,
            CharCategory.LOWERCASE_LETTER,
            CharCategory.TITLECASE_LETTER,
            CharCategory.MODIFIER_LETTER,
            CharCategory.OTHER_LETTER,
            CharCategory.LETTER_NUMBER -> true
            else -> false
        }
    }

    fun isUnicodeIdentifierPart(codePoint: Int): Boolean {
        if (codePoint < 0) return false
        if (codePoint >= MIN_SUPPLEMENTARY_CODE_POINT) return true
        if (isIdentifierIgnorable(codePoint)) return true
        return when (codePoint.toChar().category) {
            CharCategory.UPPERCASE_LETTER,
            CharCategory.LOWERCASE_LETTER,
            CharCategory.TITLECASE_LETTER,
            CharCategory.MODIFIER_LETTER,
            CharCategory.OTHER_LETTER,
            CharCategory.LETTER_NUMBER,
            CharCategory.NON_SPACING_MARK,
            CharCategory.COMBINING_SPACING_MARK,
            CharCategory.DECIMAL_DIGIT_NUMBER,
            CharCategory.CONNECTOR_PUNCTUATION -> true
            else -> false
        }
    }

    private fun isIdentifierIgnorable(codePoint: Int): Boolean =
        codePoint in 0x0000..0x0008 ||
            codePoint in 0x000E..0x001B ||
            codePoint in 0x007F..0x009F ||
            isFormat(codePoint)

    fun isSpaceSeparator(codePoint: Int): Boolean =
        codePoint in 0..0xFFFF && codePoint.toChar().category == CharCategory.SPACE_SEPARATOR

    fun isFormat(codePoint: Int): Boolean =
        codePoint in 0..0xFFFF && codePoint.toChar().category == CharCategory.FORMAT
}
