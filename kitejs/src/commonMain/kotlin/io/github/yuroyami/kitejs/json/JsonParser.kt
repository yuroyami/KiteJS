/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.json

import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.Scriptable

/** The strict JSON reader behind `JSON.parse`. Builds script objects and arrays as it goes. */
class JsonParser(private val cx: Context, private val scope: Scriptable) {

    private var pos = 0
    private var length = 0
    private var src = ""

    fun parseValue(json: String?): Any? {
        if (json == null) throw ParseException("Input string may not be null")
        pos = 0
        length = json.length
        src = json
        val value = readValue()
        consumeWhitespace()
        if (pos < length) throw ParseException("Expected end of stream at char $pos")
        return value
    }

    private fun readValue(): Any? {
        consumeWhitespace()
        while (pos < length) {
            val c = src[pos++]
            return when (c) {
                '{' -> readObject()
                '[' -> readArray()
                't' -> readTrue()
                'f' -> readFalse()
                '"' -> readString()
                'n' -> readNull()
                '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '-' -> readNumber(c)
                else -> throw ParseException("Unexpected token: $c")
            }
        }
        throw ParseException("Empty JSON string")
    }

    private fun readObject(): Any? {
        consumeWhitespace()
        val obj = cx.newObject(scope)
        // handle empty object literal case early
        if (pos < length && src[pos] == '}') {
            pos += 1
            return obj
        }
        var needsComma = false
        while (pos < length) {
            val c = src[pos++]
            when (c) {
                '}' -> {
                    if (!needsComma) throw ParseException("Unexpected comma in object literal")
                    return obj
                }
                ',' -> {
                    if (!needsComma) throw ParseException("Unexpected comma in object literal")
                    needsComma = false
                }
                '"' -> {
                    if (needsComma) throw ParseException("Missing comma in object literal")
                    val id = readString()
                    consume(':')
                    val value = readValue()
                    val indexObj = ScriptRuntime.toStringIdOrIndex(id)
                    val stringId = indexObj.stringId
                    if (stringId == null) {
                        obj.put(indexObj.index, obj, value)
                    } else {
                        obj.put(stringId, obj, value)
                    }
                    needsComma = true
                }
                else -> throw ParseException("Unexpected token in object literal")
            }
            consumeWhitespace()
        }
        throw ParseException("Unterminated object literal")
    }

    private fun readArray(): Any? {
        consumeWhitespace()
        // handle empty array literal case early
        if (pos < length && src[pos] == ']') {
            pos += 1
            return cx.newArray(scope, 0)
        }
        val list = ArrayList<Any?>()
        var needsComma = false
        while (pos < length) {
            val c = src[pos]
            when (c) {
                ']' -> {
                    if (!needsComma) throw ParseException("Unexpected comma in array literal")
                    pos += 1
                    return cx.newArray(scope, list.toTypedArray())
                }
                ',' -> {
                    if (!needsComma) throw ParseException("Unexpected comma in array literal")
                    needsComma = false
                    pos += 1
                }
                else -> {
                    if (needsComma) throw ParseException("Missing comma in array literal")
                    list.add(readValue())
                    needsComma = true
                }
            }
            consumeWhitespace()
        }
        throw ParseException("Unterminated array literal")
    }

    private fun readString(): String {
        // Fast case: no escapes, so the string is a plain substring.
        var stringStart = pos
        while (pos < length) {
            val c = src[pos++]
            if (c <= '\u001F') {
                throw ParseException("String contains control character")
            } else if (c == '\\') {
                break
            } else if (c == '"') {
                return src.substring(stringStart, pos - 1)
            }
        }
        // Slow case: the string has escapes, so the pieces go through a builder.
        val b = StringBuilder()
        while (pos < length) {
            b.append(src, stringStart, pos - 1)
            if (pos >= length) throw ParseException("Unterminated string")
            var c = src[pos++]
            when (c) {
                '"' -> b.append('"')
                '\\' -> b.append('\\')
                '/' -> b.append('/')
                'b' -> b.append('\b')
                'f' -> b.append('\u000C')
                'n' -> b.append('\n')
                'r' -> b.append('\r')
                't' -> b.append('\t')
                'u' -> {
                    if (length - pos < 5) {
                        throw ParseException("Invalid character code: \\u" + src.substring(pos))
                    }
                    val code = (fromHex(src[pos + 0]) shl 12) or
                        (fromHex(src[pos + 1]) shl 8) or
                        (fromHex(src[pos + 2]) shl 4) or
                        fromHex(src[pos + 3])
                    if (code < 0) {
                        throw ParseException("Invalid character code: " + src.substring(pos, pos + 4))
                    }
                    pos += 4
                    b.append(code.toChar())
                }
                else -> throw ParseException("Unexpected character in string: '\\$c'")
            }
            stringStart = pos
            while (pos < length) {
                c = src[pos++]
                if (c <= '\u001F') {
                    throw ParseException("String contains control character")
                } else if (c == '\\') {
                    break
                } else if (c == '"') {
                    b.append(src, stringStart, pos - 1)
                    return b.toString()
                }
            }
        }
        throw ParseException("Unterminated string literal")
    }

    private fun fromHex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }

    private fun readNumber(first: Char): Number {
        var c = first
        val numberStart = pos - 1
        if (c == '-') {
            c = nextOrNumberError(numberStart)
            if (c !in '0'..'9') throw numberError(numberStart, pos)
        }
        if (c != '0') readDigits()
        // read optional fraction part
        if (pos < length) {
            c = src[pos]
            if (c == '.') {
                pos += 1
                c = nextOrNumberError(numberStart)
                if (c !in '0'..'9') throw numberError(numberStart, pos)
                readDigits()
            }
        }
        // read optional exponent part
        if (pos < length) {
            c = src[pos]
            if (c == 'e' || c == 'E') {
                pos += 1
                c = nextOrNumberError(numberStart)
                if (c == '-' || c == '+') {
                    c = nextOrNumberError(numberStart)
                }
                if (c !in '0'..'9') throw numberError(numberStart, pos)
                readDigits()
            }
        }
        val num = src.substring(numberStart, pos)
        val dval = num.toDouble()
        val ival = dval.toInt()
        if (ival.toDouble() == dval) return ival
        return dval
    }

    private fun numberError(start: Int, end: Int): ParseException =
        ParseException("Unsupported number format: " + src.substring(start, end))

    private fun nextOrNumberError(numberStart: Int): Char {
        if (pos >= length) throw numberError(numberStart, length)
        return src[pos++]
    }

    private fun readDigits() {
        while (pos < length) {
            val c = src[pos]
            if (c !in '0'..'9') break
            ++pos
        }
    }

    private fun readTrue(): Boolean {
        if (length - pos < 3 || src[pos] != 'r' || src[pos + 1] != 'u' || src[pos + 2] != 'e') {
            throw ParseException("Unexpected token: t")
        }
        pos += 3
        return true
    }

    private fun readFalse(): Boolean {
        if (length - pos < 4 || src[pos] != 'a' || src[pos + 1] != 'l' || src[pos + 2] != 's' || src[pos + 3] != 'e') {
            throw ParseException("Unexpected token: f")
        }
        pos += 4
        return false
    }

    private fun readNull(): Any? {
        if (length - pos < 3 || src[pos] != 'u' || src[pos + 1] != 'l' || src[pos + 2] != 'l') {
            throw ParseException("Unexpected token: n")
        }
        pos += 3
        return null
    }

    private fun consumeWhitespace() {
        while (pos < length) {
            when (src[pos]) {
                ' ', '\t', '\r', '\n' -> pos += 1
                else -> return
            }
        }
    }

    private fun consume(token: Char) {
        consumeWhitespace()
        if (pos >= length) throw ParseException("Expected $token but reached end of stream")
        val c = src[pos++]
        if (c == token) return
        throw ParseException("Expected $token found $c")
    }

    class ParseException(message: String) : Exception(message)
}
