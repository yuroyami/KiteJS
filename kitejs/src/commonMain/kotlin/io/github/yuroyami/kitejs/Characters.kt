/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The `java.lang.Character` code-point and category calls the engine makes, answered from
 * [UnicodeTables] rather than from the platform.
 *
 * `Char.category` disagrees between the JVM, Kotlin/JS and Kotlin/Native, and has no answer at all
 * for supplementary code points in common Kotlin. The tables are read from JDK 21, so every target
 * classifies a code point the way upstream Rhino does.
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

    /** The general category, using the same numbering `java.lang.Character.getType` uses. */
    fun getType(codePoint: Int): Int {
        if (codePoint < 0 || codePoint > 0x10FFFF) return 0
        return UnicodeTables.runValue(UnicodeTables.CATEGORY_STARTS, UnicodeTables.CATEGORY_VALUES, codePoint)
    }

    fun isUnicodeIdentifierStart(codePoint: Int): Boolean =
        codePoint >= 0 && UnicodeTables.inRanges(UnicodeTables.ID_START, codePoint)

    fun isUnicodeIdentifierPart(codePoint: Int): Boolean =
        codePoint >= 0 && UnicodeTables.inRanges(UnicodeTables.ID_CONTINUE, codePoint)

    fun isJavaIdentifierStart(codePoint: Int): Boolean =
        codePoint >= 0 && UnicodeTables.inRanges(UnicodeTables.JAVA_IDENTIFIER_START, codePoint)

    fun isJavaIdentifierPart(codePoint: Int): Boolean =
        codePoint >= 0 && UnicodeTables.inRanges(UnicodeTables.JAVA_IDENTIFIER_PART, codePoint)

    fun isAlphabetic(codePoint: Int): Boolean =
        codePoint >= 0 && UnicodeTables.inRanges(UnicodeTables.ALPHABETIC, codePoint)

    fun isLowerCase(codePoint: Int): Boolean =
        codePoint >= 0 && UnicodeTables.inRanges(UnicodeTables.LOWERCASE, codePoint)

    fun isUpperCase(codePoint: Int): Boolean =
        codePoint >= 0 && UnicodeTables.inRanges(UnicodeTables.UPPERCASE, codePoint)

    /** `isSpaceChar(cp) || isWhitespace(cp)`, which is the pair upstream's regexp engine asks for. */
    fun isSpaceOrWhitespace(codePoint: Int): Boolean =
        codePoint >= 0 && UnicodeTables.inRanges(UnicodeTables.WHITE_SPACE, codePoint)

    /** True when `Character.digit(cp, 16)` would answer anything but -1. */
    fun isHexDigitInAnyScript(codePoint: Int): Boolean =
        codePoint >= 0 && UnicodeTables.inRanges(UnicodeTables.HEX_DIGIT, codePoint)

    fun isSpaceSeparator(codePoint: Int): Boolean = getType(codePoint) == SPACE_SEPARATOR

    fun isFormat(codePoint: Int): Boolean = getType(codePoint) == FORMAT

    /** Simple upper case mapping. One code point in, one out, never a multi-character expansion. */
    fun toUpperCase(codePoint: Int): Int =
        UnicodeTables.mapCase(UnicodeTables.UPPER_KEYS, UnicodeTables.UPPER_DELTAS, codePoint)

    fun toLowerCase(codePoint: Int): Int =
        UnicodeTables.mapCase(UnicodeTables.LOWER_KEYS, UnicodeTables.LOWER_DELTAS, codePoint)

    /** The script's index in the table, matching `Character.UnicodeScript`'s ordinals. */
    fun scriptOf(codePoint: Int): Int {
        if (codePoint < 0 || codePoint > 0x10FFFF) return -1
        return UnicodeTables.runValue(UnicodeTables.SCRIPT_STARTS, UnicodeTables.SCRIPT_VALUES, codePoint)
    }

    /**
     * The index of a script named [name], or -1. Matches `Character.UnicodeScript.forName`: case
     * and the separators `_`, `-` and space are ignored, and the four-letter aliases work.
     */
    fun scriptForName(name: String): Int {
        val sb = StringBuilder(name.length)
        for (c in name) {
            if (c == '_' || c == '-' || c == ' ') continue
            sb.append(if (c in 'a'..'z') c - 32 else c)
        }
        return UnicodeTables.SCRIPT_NAMES[sb.toString()] ?: -1
    }

    // The category numbers, same values as java.lang.Character's.
    const val UNASSIGNED = 0
    const val UPPERCASE_LETTER = 1
    const val LOWERCASE_LETTER = 2
    const val TITLECASE_LETTER = 3
    const val MODIFIER_LETTER = 4
    const val OTHER_LETTER = 5
    const val NON_SPACING_MARK = 6
    const val ENCLOSING_MARK = 7
    const val COMBINING_SPACING_MARK = 8
    const val DECIMAL_DIGIT_NUMBER = 9
    const val LETTER_NUMBER = 10
    const val OTHER_NUMBER = 11
    const val SPACE_SEPARATOR = 12
    const val LINE_SEPARATOR = 13
    const val PARAGRAPH_SEPARATOR = 14
    const val CONTROL = 15
    const val FORMAT = 16
    const val PRIVATE_USE = 18
    const val SURROGATE = 19
    const val DASH_PUNCTUATION = 20
    const val START_PUNCTUATION = 21
    const val END_PUNCTUATION = 22
    const val CONNECTOR_PUNCTUATION = 23
    const val OTHER_PUNCTUATION = 24
    const val MATH_SYMBOL = 25
    const val CURRENCY_SYMBOL = 26
    const val MODIFIER_SYMBOL = 27
    const val OTHER_SYMBOL = 28
    const val INITIAL_QUOTE_PUNCTUATION = 29
    const val FINAL_QUOTE_PUNCTUATION = 30
}
