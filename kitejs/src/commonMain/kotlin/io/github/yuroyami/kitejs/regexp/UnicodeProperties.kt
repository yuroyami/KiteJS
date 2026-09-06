/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.regexp

import io.github.yuroyami.kitejs.Characters

/**
 * The `\p{...}` and `\P{...}` escapes: the binary properties ECMA-262 lists, the general category,
 * and `Script=`.
 *
 * [lookup] turns the text inside the braces into one packed int, and [hasProperty] answers it for a
 * code point. The data comes from the generated tables, so every target agrees.
 */
internal object UnicodeProperties {

    // Binary property names, from the ECMA-262 table of binary Unicode properties.
    const val ALPHABETIC = 1
    const val ASCII = ALPHABETIC + 1
    const val CASE_IGNORABLE = ASCII + 1
    const val ASCII_HEX_DIGIT = CASE_IGNORABLE + 1
    const val HEX_DIGIT = ASCII_HEX_DIGIT + 1
    const val ID_CONTINUE = HEX_DIGIT + 1
    const val ID_START = ID_CONTINUE + 1
    const val LOWERCASE = ID_START + 1
    const val UPPERCASE = LOWERCASE + 1
    const val WHITE_SPACE = UPPERCASE + 1

    // The two that take a value.
    const val GENERAL_CATEGORY = WHITE_SPACE + 1
    const val SCRIPT = GENERAL_CATEGORY + 1

    // General category values, from PropertyValueAliases.txt.
    const val OTHER = 1
    const val CONTROL = OTHER + 1
    const val FORMAT = CONTROL + 1
    const val UNASSIGNED = FORMAT + 1
    const val PRIVATE_USE = UNASSIGNED + 1
    const val SURROGATE = PRIVATE_USE + 1
    const val LETTER = SURROGATE + 1
    const val LOWERCASE_LETTER = LETTER + 1
    const val MODIFIER_LETTER = LOWERCASE_LETTER + 1
    const val OTHER_LETTER = MODIFIER_LETTER + 1
    const val TITLECASE_LETTER = OTHER_LETTER + 1
    const val UPPERCASE_LETTER = TITLECASE_LETTER + 1
    const val MARK = UPPERCASE_LETTER + 1
    const val SPACING_MARK = MARK + 1
    const val ENCLOSING_MARK = SPACING_MARK + 1
    const val NONSPACING_MARK = ENCLOSING_MARK + 1
    const val NUMBER = NONSPACING_MARK + 1
    const val DECIMAL_NUMBER = NUMBER + 1
    const val LETTER_NUMBER = DECIMAL_NUMBER + 1
    const val OTHER_NUMBER = LETTER_NUMBER + 1
    const val PUNCTUATION = OTHER_NUMBER + 1
    const val CONNECTOR_PUNCTUATION = PUNCTUATION + 1
    const val DASH_PUNCTUATION = CONNECTOR_PUNCTUATION + 1
    const val CLOSE_PUNCTUATION = DASH_PUNCTUATION + 1
    const val FINAL_PUNCTUATION = CLOSE_PUNCTUATION + 1
    const val INITIAL_PUNCTUATION = FINAL_PUNCTUATION + 1
    const val OTHER_PUNCTUATION = INITIAL_PUNCTUATION + 1
    const val OPEN_PUNCTUATION = OTHER_PUNCTUATION + 1
    const val SYMBOL = OPEN_PUNCTUATION + 1
    const val CURRENCY_SYMBOL = SYMBOL + 1
    const val MODIFIER_SYMBOL = CURRENCY_SYMBOL + 1
    const val MATH_SYMBOL = MODIFIER_SYMBOL + 1
    const val OTHER_SYMBOL = MATH_SYMBOL + 1
    const val SEPARATOR = OTHER_SYMBOL + 1
    const val LINE_SEPARATOR = SEPARATOR + 1
    const val PARAGRAPH_SEPARATOR = LINE_SEPARATOR + 1
    const val SPACE_SEPARATOR = PARAGRAPH_SEPARATOR + 1

    // Binary property values.
    const val TRUE = SPACE_SEPARATOR + 1
    const val FALSE = TRUE + 1

    /** Property names and their aliases. */
    val PROPERTY_NAMES: Map<String, Int> = mapOf(
        "Alphabetic" to ALPHABETIC,
        "Alpha" to ALPHABETIC,
        "ASCII" to ASCII,
        "Case_Ignorable" to CASE_IGNORABLE,
        "CI" to CASE_IGNORABLE,
        "General_Category" to GENERAL_CATEGORY,
        "gc" to GENERAL_CATEGORY,
        "Script" to SCRIPT,
        "sc" to SCRIPT,
        "ASCII_Hex_Digit" to ASCII_HEX_DIGIT,
        "AHex" to ASCII_HEX_DIGIT,
        "Hex_Digit" to HEX_DIGIT,
        "Hex" to HEX_DIGIT,
        "ID_Continue" to ID_CONTINUE,
        "IDC" to ID_CONTINUE,
        "ID_Start" to ID_START,
        "IDS" to ID_START,
        "Lowercase" to LOWERCASE,
        "Lower" to LOWERCASE,
        "Uppercase" to UPPERCASE,
        "Upper" to UPPERCASE,
        "White_Space" to WHITE_SPACE,
        "space" to WHITE_SPACE,
    )

    /** General category names and their aliases. */
    val PROPERTY_VALUES: Map<String, Int> = mapOf(
        "Other" to OTHER,
        "C" to OTHER,
        "Control" to CONTROL,
        "Cc" to CONTROL,
        "cntrl" to CONTROL,
        "Format" to FORMAT,
        "Cf" to FORMAT,
        "Unassigned" to UNASSIGNED,
        "Cn" to UNASSIGNED,
        "Private_Use" to PRIVATE_USE,
        "Co" to PRIVATE_USE,
        "Surrogate" to SURROGATE,
        "Cs" to SURROGATE,
        "Letter" to LETTER,
        "L" to LETTER,
        "Lowercase_Letter" to LOWERCASE_LETTER,
        "Ll" to LOWERCASE_LETTER,
        "Modifier_Letter" to MODIFIER_LETTER,
        "Lm" to MODIFIER_LETTER,
        "Other_Letter" to OTHER_LETTER,
        "Lo" to OTHER_LETTER,
        "Titlecase_Letter" to TITLECASE_LETTER,
        "Lt" to TITLECASE_LETTER,
        "Uppercase_Letter" to UPPERCASE_LETTER,
        "Lu" to UPPERCASE_LETTER,
        "Mark" to MARK,
        "M" to MARK,
        "Combining_Mark" to MARK,
        "Spacing_Mark" to SPACING_MARK,
        "Mc" to SPACING_MARK,
        "Enclosing_Mark" to ENCLOSING_MARK,
        "Me" to ENCLOSING_MARK,
        "Nonspacing_Mark" to NONSPACING_MARK,
        "Mn" to NONSPACING_MARK,
        "Number" to NUMBER,
        "N" to NUMBER,
        "Decimal_Number" to DECIMAL_NUMBER,
        "Nd" to DECIMAL_NUMBER,
        "digit" to NUMBER,
        "Letter_Number" to LETTER_NUMBER,
        "Nl" to LETTER_NUMBER,
        "Other_Number" to OTHER_NUMBER,
        "No" to OTHER_NUMBER,
        "Punctuation" to PUNCTUATION,
        "P" to PUNCTUATION,
        "punct" to PUNCTUATION,
        "Connector_Punctuation" to CONNECTOR_PUNCTUATION,
        "Pc" to CONNECTOR_PUNCTUATION,
        "Dash_Punctuation" to DASH_PUNCTUATION,
        "Pd" to DASH_PUNCTUATION,
        "Close_Punctuation" to CLOSE_PUNCTUATION,
        "Pe" to CLOSE_PUNCTUATION,
        "Final_Punctuation" to FINAL_PUNCTUATION,
        "Pf" to FINAL_PUNCTUATION,
        "Initial_Punctuation" to INITIAL_PUNCTUATION,
        "Pi" to INITIAL_PUNCTUATION,
        "Other_Punctuation" to OTHER_PUNCTUATION,
        "Po" to OTHER_PUNCTUATION,
        "Open_Punctuation" to OPEN_PUNCTUATION,
        "Ps" to OPEN_PUNCTUATION,
        "Symbol" to SYMBOL,
        "S" to SYMBOL,
        "Currency_Symbol" to CURRENCY_SYMBOL,
        "Sc" to CURRENCY_SYMBOL,
        "Modifier_Symbol" to MODIFIER_SYMBOL,
        "Sk" to MODIFIER_SYMBOL,
        "Math_Symbol" to MATH_SYMBOL,
        "Sm" to MATH_SYMBOL,
        "Other_Symbol" to OTHER_SYMBOL,
        "So" to OTHER_SYMBOL,
        "Separator" to SEPARATOR,
        "Z" to SEPARATOR,
        "Line_Separator" to LINE_SEPARATOR,
        "Zl" to LINE_SEPARATOR,
        "Paragraph_Separator" to PARAGRAPH_SEPARATOR,
        "Zp" to PARAGRAPH_SEPARATOR,
        "Space_Separator" to SPACE_SEPARATOR,
        "Zs" to SPACE_SEPARATOR,
    )

    /**
     * Reads `name` or `name=value` and packs it into one int, or answers -1 when it is not a
     * property this engine knows. Upstream uses a regex here; a scan avoids bootstrapping the
     * regexp engine from inside the regexp engine.
     */
    fun lookup(propertyOrValue: String?): Int {
        if (propertyOrValue.isNullOrEmpty()) return -1

        var eq = -1
        for (i in propertyOrValue.indices) {
            val c = propertyOrValue[i]
            if (c == '=') {
                if (eq >= 0) return -1
                eq = i
            }
        }
        val name = if (eq < 0) propertyOrValue else propertyOrValue.substring(0, eq)
        val value = if (eq < 0) null else propertyOrValue.substring(eq + 1)

        // The name is letters and underscores; a value may also hold digits. Both must be non-empty.
        if (name.isEmpty()) return -1
        for (c in name) if (!(c in 'a'..'z' || c in 'A'..'Z' || c == '_')) return -1
        if (value != null) {
            if (value.isEmpty()) return -1
            for (c in value) if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_')) return -1
        }

        if (value == null) {
            val prop = PROPERTY_NAMES[name]
            if (prop == null) {
                // A bare general category value, such as `\p{Lu}`, means gc=Lu.
                val valueByte = PROPERTY_VALUES[name] ?: return -1
                return encodeProperty(GENERAL_CATEGORY, valueByte)
            }
            if (prop == GENERAL_CATEGORY || prop == SCRIPT) return -1
            return encodeProperty(prop, TRUE)
        }

        val prop = PROPERTY_NAMES[name] ?: return -1
        return when (prop) {
            GENERAL_CATEGORY -> {
                val valueByte = PROPERTY_VALUES[value] ?: return -1
                encodeProperty(GENERAL_CATEGORY, valueByte)
            }
            SCRIPT -> {
                val ordinal = Characters.scriptForName(value)
                if (ordinal < 0) -1 else encodeProperty(SCRIPT, ordinal)
            }
            // A binary property takes no value.
            else -> -1
        }
    }

    /** The property in the high byte, the value in the low one. */
    private fun encodeProperty(property: Int, value: Int): Int =
        ((property and 0xFF) shl 8) or (value and 0xFF)

    /** Answers a packed property from [lookup] for one code point. */
    fun hasProperty(property: Int, codePoint: Int): Boolean {
        val propByte = (property shr 8) and 0xFF
        val valueByte = property and 0xFF

        return when (propByte) {
            ALPHABETIC -> Characters.isAlphabetic(codePoint) == (valueByte == TRUE)
            ASCII -> (codePoint <= 0x7F) == (valueByte == TRUE)
            CASE_IGNORABLE -> {
                // Java has no Case_Ignorable, so upstream approximates it with three categories.
                val t = Characters.getType(codePoint)
                (t == Characters.MODIFIER_SYMBOL || t == Characters.MODIFIER_LETTER || t == Characters.NON_SPACING_MARK) ==
                    (valueByte == TRUE)
            }
            GENERAL_CATEGORY -> checkGeneralCategory(valueByte, Characters.getType(codePoint))
            ASCII_HEX_DIGIT -> isHexDigit(codePoint) == (valueByte == TRUE)
            HEX_DIGIT -> Characters.isHexDigitInAnyScript(codePoint) == (valueByte == TRUE)
            ID_CONTINUE -> Characters.isUnicodeIdentifierPart(codePoint) == (valueByte == TRUE)
            ID_START -> Characters.isUnicodeIdentifierStart(codePoint) == (valueByte == TRUE)
            LOWERCASE -> Characters.isLowerCase(codePoint) == (valueByte == TRUE)
            UPPERCASE -> Characters.isUpperCase(codePoint) == (valueByte == TRUE)
            // Also an approximation upstream: Java's space and whitespace, not Unicode White_Space.
            WHITE_SPACE -> (valueByte == TRUE) == Characters.isSpaceOrWhitespace(codePoint)
            SCRIPT -> Characters.scriptOf(codePoint) == valueByte
            else -> false
        }
    }

    /** Maps a general category value onto the category numbers the tables use. */
    private fun checkGeneralCategory(propertyValueByte: Int, category: Int): Boolean = when (propertyValueByte) {
        LETTER -> category == Characters.UPPERCASE_LETTER ||
            category == Characters.LOWERCASE_LETTER ||
            category == Characters.TITLECASE_LETTER ||
            category == Characters.MODIFIER_LETTER ||
            category == Characters.OTHER_LETTER
        UPPERCASE_LETTER -> category == Characters.UPPERCASE_LETTER
        LOWERCASE_LETTER -> category == Characters.LOWERCASE_LETTER
        TITLECASE_LETTER -> category == Characters.TITLECASE_LETTER
        MODIFIER_LETTER -> category == Characters.MODIFIER_LETTER
        OTHER_LETTER -> category == Characters.OTHER_LETTER
        MARK -> category == Characters.NON_SPACING_MARK ||
            category == Characters.ENCLOSING_MARK ||
            category == Characters.COMBINING_SPACING_MARK
        NONSPACING_MARK -> category == Characters.NON_SPACING_MARK
        ENCLOSING_MARK -> category == Characters.ENCLOSING_MARK
        SPACING_MARK -> category == Characters.COMBINING_SPACING_MARK
        NUMBER -> category == Characters.DECIMAL_DIGIT_NUMBER ||
            category == Characters.LETTER_NUMBER ||
            category == Characters.OTHER_NUMBER
        DECIMAL_NUMBER -> category == Characters.DECIMAL_DIGIT_NUMBER
        LETTER_NUMBER -> category == Characters.LETTER_NUMBER
        OTHER_NUMBER -> category == Characters.OTHER_NUMBER
        SEPARATOR -> category == Characters.SPACE_SEPARATOR ||
            category == Characters.LINE_SEPARATOR ||
            category == Characters.PARAGRAPH_SEPARATOR
        SPACE_SEPARATOR -> category == Characters.SPACE_SEPARATOR
        LINE_SEPARATOR -> category == Characters.LINE_SEPARATOR
        PARAGRAPH_SEPARATOR -> category == Characters.PARAGRAPH_SEPARATOR
        OTHER -> category == Characters.OTHER_LETTER ||
            category == Characters.OTHER_NUMBER ||
            category == Characters.OTHER_PUNCTUATION ||
            category == Characters.OTHER_SYMBOL
        CONTROL -> category == Characters.CONTROL
        FORMAT -> category == Characters.FORMAT
        SURROGATE -> category == Characters.SURROGATE
        PRIVATE_USE -> category == Characters.PRIVATE_USE
        PUNCTUATION -> category == Characters.CONNECTOR_PUNCTUATION ||
            category == Characters.DASH_PUNCTUATION ||
            category == Characters.START_PUNCTUATION ||
            category == Characters.END_PUNCTUATION ||
            category == Characters.OTHER_PUNCTUATION ||
            category == Characters.INITIAL_QUOTE_PUNCTUATION ||
            category == Characters.FINAL_QUOTE_PUNCTUATION
        DASH_PUNCTUATION -> category == Characters.DASH_PUNCTUATION
        OPEN_PUNCTUATION -> category == Characters.START_PUNCTUATION
        CLOSE_PUNCTUATION -> category == Characters.END_PUNCTUATION
        CONNECTOR_PUNCTUATION -> category == Characters.CONNECTOR_PUNCTUATION
        OTHER_PUNCTUATION -> category == Characters.OTHER_PUNCTUATION
        INITIAL_PUNCTUATION -> category == Characters.INITIAL_QUOTE_PUNCTUATION
        FINAL_PUNCTUATION -> category == Characters.FINAL_QUOTE_PUNCTUATION
        SYMBOL -> category == Characters.MATH_SYMBOL ||
            category == Characters.CURRENCY_SYMBOL ||
            category == Characters.MODIFIER_SYMBOL ||
            category == Characters.OTHER_SYMBOL
        MATH_SYMBOL -> category == Characters.MATH_SYMBOL
        CURRENCY_SYMBOL -> category == Characters.CURRENCY_SYMBOL
        MODIFIER_SYMBOL -> category == Characters.MODIFIER_SYMBOL
        OTHER_SYMBOL -> category == Characters.OTHER_SYMBOL
        UNASSIGNED -> category == Characters.UNASSIGNED
        else -> false
    }

    private fun isHexDigit(codePoint: Int): Boolean =
        (codePoint >= '0'.code && codePoint <= '9'.code) ||
            (codePoint >= 'a'.code && codePoint <= 'f'.code) ||
            (codePoint >= 'A'.code && codePoint <= 'F'.code)
}
