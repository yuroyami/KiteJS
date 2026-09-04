/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.dtoa.DoubleFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.reflect.KClass
import io.github.yuroyami.kitejs.v8dtoa.DoubleConversion

/**
 * The runtime method collection. Phase 0 ports only what the lexer needs: line
 * terminator and whitespace classification, string-to-number parsing, and the error
 * message lookup. The rest of the file arrives with Phase 3.
 */
object ScriptRuntime {

    val NaN: Double = Double.NaN

    // Preserve backward-compatibility with historical value of this.
    val negativeZero: Double = -0.0

    // KMP: lives on NativeNumber upstream; moves there when Phase 3 ports it.
    internal const val MAX_SAFE_INTEGER: Double = 9007199254740991.0

    fun isJSLineTerminator(c: Int): Boolean {
        // Optimization for faster check for eol character:
        // they do not have 0xDFD0 bits set
        if ((c and 0xDFD0) != 0) {
            return false
        }
        return c == '\n'.code || c == '\r'.code || c == 0x2028 || c == 0x2029
    }

    fun isJSWhitespaceOrLineTerminator(c: Int): Boolean =
        isStrWhiteSpaceChar(c) || isJSLineTerminator(c)

    /**
     * Indicates if the character is a Str whitespace char according to ECMA spec:
     * StrWhiteSpaceChar ::: TAB SP NBSP FF VT CR LF LS PS USP BOM
     */
    internal fun isStrWhiteSpaceChar(c: Int): Boolean = when (c) {
        ' '.code, // <SP>
        '\n'.code, // <LF>
        '\r'.code, // <CR>
        '\t'.code, // <TAB>
        0x00A0, // <NBSP>
        0x000C, // <FF>
        0x000B, // <VT>
        0x2028, // <LS>
        0x2029, // <PS>
        0xFEFF, // <BOM>
        -> true
        else -> Characters.isSpaceSeparator(c)
    }

    internal fun stringPrefixToNumber(s: String, start: Int, radix: Int): Double =
        stringToNumber(s, start, s.length - 1, radix, true)

    internal fun stringToNumber(s: String, start: Int, end: Int, radix: Int): Double =
        stringToNumber(s, start, end, radix, false)

    /*
     * Helper function for toNumber, parseInt, and TokenStream.getToken.
     */
    private fun stringToNumber(
        source: String, sourceStart: Int, sourceEnd: Int, radix: Int, isPrefix: Boolean,
    ): Double {
        var digitMax = '9'
        var lowerCaseBound = 'a'
        var upperCaseBound = 'A'
        if (radix < 10) {
            digitMax = ('0' + (radix - 1))
        }
        if (radix > 10) {
            lowerCaseBound = ('a' + (radix - 10))
            upperCaseBound = ('A' + (radix - 10))
        }
        var end = sourceStart
        var sum = 0.0
        while (end <= sourceEnd) {
            val c = source[end]
            val newDigit: Int = when {
                c in '0'..digitMax -> c - '0'
                c >= 'a' && c < lowerCaseBound -> c - 'a' + 10
                c >= 'A' && c < upperCaseBound -> c - 'A' + 10
                !isPrefix -> return NaN // isn't a prefix but found unexpected char
                else -> break // unexpected char
            }
            sum = sum * radix + newDigit
            end++
        }
        if (sourceStart == end) { // stopped right at the beginning
            return NaN
        }
        if (sum > MAX_SAFE_INTEGER) {
            if (radix == 10) {
                /* If we're accumulating a decimal number and the number is >= 2^53, then
                 * the result from the repeated multiply-add above may be inaccurate. Use
                 * the full string-to-double conversion to get the correct answer.
                 */
                return try {
                    source.substring(sourceStart, end).toDouble()
                } catch (nfe: NumberFormatException) {
                    NaN
                }
            } else if (radix == 2 || radix == 4 || radix == 8 || radix == 16 || radix == 32) {
                /* The number may also be inaccurate for one of these bases. This happens
                 * if the addition in value*radix + digit causes a round-down to an even
                 * least significant mantissa bit when the first dropped bit is a one. If
                 * any of the following digits in the number (which haven't been added in
                 * yet) are nonzero, then the correct action would have been to round up
                 * instead of down. An example occurs when reading the number
                 * 0x1000000000000081, which rounds to 0x1000000000000000 instead of
                 * 0x1000000000000100.
                 */
                val SKIP_LEADING_ZEROS = 0
                val FIRST_EXACT_53_BITS = 1
                val AFTER_BIT_53 = 2
                val ZEROS_AFTER_54 = 3
                val MIXED_AFTER_54 = 4

                var bitShiftInChar = 1
                var digit = 0

                var state = SKIP_LEADING_ZEROS
                var exactBitsLimit = 53
                var factor = 0.0
                var bit53 = false
                // bit54 is the 54th bit (the first dropped from the mantissa)
                var bit54 = false
                var pos = sourceStart
                sum = 0.0

                while (true) {
                    if (bitShiftInChar == 1) {
                        if (pos == end) break
                        digit = source[pos++].code
                        digit -= when {
                            digit in '0'.code..'9'.code -> '0'.code
                            digit in 'a'.code..'z'.code -> 'a'.code - 10
                            else -> 'A'.code - 10
                        }
                        bitShiftInChar = radix
                    }
                    bitShiftInChar = bitShiftInChar shr 1
                    val bit = (digit and bitShiftInChar) != 0

                    when (state) {
                        SKIP_LEADING_ZEROS ->
                            if (bit) {
                                --exactBitsLimit
                                sum = 1.0
                                state = FIRST_EXACT_53_BITS
                            }
                        FIRST_EXACT_53_BITS -> {
                            sum *= 2.0
                            if (bit) sum += 1.0
                            --exactBitsLimit
                            if (exactBitsLimit == 0) {
                                bit53 = bit
                                state = AFTER_BIT_53
                            }
                        }
                        AFTER_BIT_53 -> {
                            bit54 = bit
                            factor = 2.0
                            state = ZEROS_AFTER_54
                        }
                        ZEROS_AFTER_54 -> {
                            if (bit) {
                                state = MIXED_AFTER_54
                            }
                            factor *= 2 // fallthrough behavior of the upstream switch
                        }
                        MIXED_AFTER_54 -> factor *= 2
                    }
                }
                when (state) {
                    SKIP_LEADING_ZEROS -> sum = 0.0
                    FIRST_EXACT_53_BITS, AFTER_BIT_53 -> {
                        // do nothing
                    }
                    ZEROS_AFTER_54 -> {
                        // x1.1 -> x1 + 1 (round up)
                        // x0.1 -> x0 (round down)
                        if (bit54 && bit53) sum += 1.0
                        sum *= factor
                    }
                    MIXED_AFTER_54 -> {
                        // x.100...1.. -> x + 1 (round up)
                        // x.0anything -> x (round down)
                        if (bit54) sum += 1.0
                        sum *= factor
                    }
                }
            }
            /* We don't worry about inaccurate numbers for any other base. */
        }
        return sum
    }

    /*
     * Type sentinels for the getDefaultValue hint and the runtime's type tests.
     *
     * KMP: upstream looks these up reflectively by name and compares java.lang.Class objects. They
     * are only ever compared by identity, so KClass values map exactly (D-20).
     */
    val BooleanClass: KClass<*> = Boolean::class
    val StringClass: KClass<*> = String::class
    val NumberClass: KClass<*> = Number::class
    val FunctionClass: KClass<*> = Function::class
    val ScriptableClass: KClass<*> = Scriptable::class
    val ObjectClass: KClass<*> = Any::class
    val BigIntegerClass: KClass<*> = KBigInt::class

    val emptyArgs: Array<Any?> = arrayOf()

    fun toInt32(d: Double): Int = DoubleConversion.doubleToInt32(d)

    fun toUint32(d: Double): Long = DoubleConversion.doubleToInt32(d).toLong() and 0xffffffffL

    /** ECMAScript ToInteger: truncates toward zero, and maps NaN to positive zero. */
    fun toInteger(d: Double): Double {
        if (d.isNaN()) return +0.0
        if (d == 0.0 || d.isInfinite()) return d
        return if (d > 0.0) floor(d) else ceil(d)
    }

    /**
     * ECMAScript ToNumber for a string.
     *
     * Two old behaviours are kept on purpose below the ES6 language level, so scripts that relied
     * on them keep working (upstream bug 368): a hexadecimal literal parses only its valid prefix,
     * like `parseInt` does, a sign is allowed in front of one, and the binary and octal prefixes
     * are not recognised at all.
     */
    fun toNumber(s: String): Double {
        val len = s.length

        // Skip the leading whitespace.
        var start = 0
        var startChar: Char
        while (true) {
            if (start == len) {
                // Empty, or nothing but whitespace.
                return +0.0
            }
            startChar = s[start]
            if (!isStrWhiteSpaceChar(startChar.code)) {
                break
            }
            start++
        }

        // Skip the trailing whitespace.
        var end = len - 1
        var endChar = s[end]
        while (isStrWhiteSpaceChar(endChar.code)) {
            end--
            endChar = s[end]
        }

        val cx = Context.getCurrentContext()
        val oldParsingMode = cx == null || cx.languageVersion < Context.VERSION_ES6

        // Handle the non-decimal prefixes.
        if (startChar == '0') {
            if (start + 2 <= end) {
                val radixC = s[start + 1]
                var radix = -1
                if (radixC == 'x' || radixC == 'X') {
                    radix = 16
                } else if (!oldParsingMode && (radixC == 'o' || radixC == 'O')) {
                    radix = 8
                } else if (!oldParsingMode && (radixC == 'b' || radixC == 'B')) {
                    radix = 2
                }
                if (radix != -1) {
                    if (oldParsingMode) {
                        return stringPrefixToNumber(s, start + 2, radix)
                    }
                    return stringToNumber(s, start + 2, end, radix)
                }
            }
        } else if (oldParsingMode && (startChar == '+' || startChar == '-')) {
            // In the old mode a hexadecimal literal may carry a sign.
            if (start + 3 <= end && s[start + 1] == '0') {
                val radixC = s[start + 2]
                if (radixC == 'x' || radixC == 'X') {
                    val value = stringPrefixToNumber(s, start + 3, 16)
                    return if (startChar == '-') -value else value
                }
            }
        }

        if (endChar == 'y') {
            // Could be "Infinity".
            if (startChar == '+' || startChar == '-') {
                start++
            }
            if (start + 7 == end && s.regionMatches(start, "Infinity", 0, 8)) {
                return if (startChar == '-') {
                    Double.NEGATIVE_INFINITY
                } else {
                    Double.POSITIVE_INFINITY
                }
            }
            return NaN
        }

        // A finite decimal number, so a plain floating point conversion will do. The character
        // check first, because the parser is slow and accepts input this has to reject.
        val sub = s.substring(start, end + 1)
        for (i in sub.length - 1 downTo 0) {
            val c = sub[i]
            if ((c in '0'..'9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                continue
            }
            return NaN
        }
        return sub.toDoubleOrNull() ?: NaN
    }


    internal fun isSpecialProperty(s: String): Boolean =
        s == NativeObject.PROTO_PROPERTY || s == NativeObject.PARENT_PROPERTY

    /**
     * If [str] is an array index, returns it as a value in 0..2^32-1. Otherwise returns -1.
     * Leading zeroes and "-0" are not indexes.
     */
    fun indexFromString(str: String): Long {
        // The length of the decimal form of Int.MAX_VALUE, 2147483647.
        val maxValueLength = 10

        val len = str.length
        if (len > 0) {
            var i = 0
            var negate = false
            var c: Int = str[0].code
            if (c == '-'.code) {
                if (len > 1) {
                    c = str[1].code
                    if (c == '0'.code) return -1L // "-0" is not an index
                    i = 1
                    negate = true
                }
            }
            c -= '0'.code
            if (c in 0..9 && len <= (if (negate) maxValueLength + 1 else maxValueLength)) {
                // Accumulate as a negative number, so Int.MIN_VALUE, whose absolute value is one
                // greater than Int.MAX_VALUE, still fits.
                var index = -c
                var oldIndex = 0
                i++
                if (index != 0) {
                    // 00, 01, 000 and so on are not indexes.
                    while (i != len) {
                        c = str[i].code - '0'.code
                        if (c < 0 || c > 9) break
                        oldIndex = index
                        index = 10 * index - c
                        i++
                    }
                }
                // Every character must be consumed, and the value must not have overflowed.
                if (i == len &&
                    (oldIndex > (Int.MIN_VALUE / 10) ||
                        (oldIndex == (Int.MIN_VALUE / 10) &&
                            c <= (if (negate) -(Int.MIN_VALUE % 10) else (Int.MAX_VALUE % 10))))
                ) {
                    return 0xFFFFFFFFL and (if (negate) index else -index).toLong()
                }
            }
        }
        return -1L
    }

    /** If [s] is an array index, returns it boxed as an Int. Otherwise returns [s] itself. */
    internal fun getIndexObject(s: String): Any {
        val indexTest = indexFromString(s)
        if (indexTest in 0..Int.MAX_VALUE.toLong()) {
            return indexTest.toInt()
        }
        return s
    }

    /** If [d] is an exact int, returns it boxed as an Int. Otherwise returns it as a String. */
    internal fun getIndexObject(d: Double): Any {
        val i = d.toInt()
        if (i.toDouble() == d) {
            return i
        }
        return toString(d)
    }

    /**
     * Converts a number to its ECMAScript string form.
     *
     * Only radix 10 is ported so far. The other radixes go through `DToA.JS_dtobasestr`, which
     * needs arbitrary-precision integers, so they arrive with real BigInt support in phase 5.
     */
    fun numberToString(d: Double, base: Int): String {
        if (base == 10) {
            // The common case. DoubleFormatter identifies the non-finite values efficiently, so
            // it runs before any other check.
            return DoubleFormatter.toString(d)
        }
        if (base < 2 || base > 36) {
            throw IllegalArgumentException(getMessageById("msg.bad.radix", base.toString()))
        }
        if (d.isNaN()) return "NaN"
        if (d == Double.POSITIVE_INFINITY) return "Infinity"
        if (d == Double.NEGATIVE_INFINITY) return "-Infinity"
        if (d == 0.0) return "0"
        throw UnsupportedOperationException("radix $base needs BigInt, which arrives in phase 5")
    }

    fun toString(d: Double): String = numberToString(d, 10)

    fun getMessageById(messageId: String, vararg args: Any?): String =
        Messages.getMessageById(messageId, *args)

    /**
     * Escapes a string for printing inside an object or array literal. Not quite the same as
     * the `escape` builtin: control characters become the short escapes where they exist and
     * hex escapes otherwise.
     */
    fun escapeString(s: String, escapeQuote: Char = '"'): String {
        if (!(escapeQuote == '"' || escapeQuote == '\'')) Kit.codeBug()
        var sb: StringBuilder? = null

        for (i in s.indices) {
            val c = s[i].code

            if (' '.code <= c && c <= '~'.code && c != escapeQuote.code && c != '\\'.code) {
                // An ordinary printable character, and neither the quote nor a backslash.
                sb?.append(c.toChar())
                continue
            }
            if (sb == null) {
                sb = StringBuilder(s.length + 3)
                sb.append(s, 0, i)
            }

            val escape = when (c) {
                '\b'.code -> 'b'.code
                '\u000C'.code -> 'f'.code
                '\n'.code -> 'n'.code
                '\r'.code -> 'r'.code
                '\t'.code -> 't'.code
                0xb -> 'v'.code // Java lacks \v.
                ' '.code -> ' '.code
                '\\'.code -> '\\'.code
                else -> -1
            }
            if (escape >= 0) {
                // An escaped character.
                sb.append('\\')
                sb.append(escape.toChar())
            } else if (c == escapeQuote.code) {
                sb.append('\\')
                sb.append(escapeQuote)
            } else {
                val hexSize: Int
                if (c < 256) {
                    // Two-digit hex.
                    sb.append("\\x")
                    hexSize = 2
                } else {
                    // Unicode.
                    sb.append("\\u")
                    hexSize = 4
                }
                // Append the hexadecimal form of c, left-padded with zeroes.
                var shift = (hexSize - 1) * 4
                while (shift >= 0) {
                    val digit = 0xf and (c shr shift)
                    val hc = if (digit < 10) '0'.code + digit else 'a'.code - 10 + digit
                    sb.append(hc.toChar())
                    shift -= 4
                }
            }
        }
        return sb?.toString() ?: s
    }
}
