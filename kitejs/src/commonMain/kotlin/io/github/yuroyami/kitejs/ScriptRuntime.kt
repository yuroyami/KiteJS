/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.dtoa.DoubleFormatter

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
