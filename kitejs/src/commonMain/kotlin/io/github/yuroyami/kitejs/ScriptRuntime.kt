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

    // ---- Value tests -------------------------------------------------------------------------

    /** True for a symbol, either a well-known [SymbolKey] or a script-made one. */
    internal fun isSymbol(obj: Any?): Boolean =
        // TODO(P3.4): NativeSymbol lands later; upstream also accepts one whose isSymbol() is true.
        obj is SymbolKey

    /** True for what the spec calls an Object: everything except the primitives. */
    fun isObject(value: Any?): Boolean {
        if (value == null) return false
        if (Undefined.isUndefined(value)) return false
        if (value is ScriptableObject) {
            val type = value.typeOf
            return type == "object" || type == "function"
        }
        if (value is Scriptable) return value !is Callable
        return false
    }

    /** The result of the `typeof` operator. */
    fun typeOf(value: Any?): String {
        if (value == null) return "object"
        if (value === Undefined.instance) return "undefined"
        if (value is ScriptableObject) return value.typeOf
        if (value is Scriptable) return if (value is Callable) "function" else "object"
        if (value is CharSequence) return "string"
        if (value is KBigInt) return "bigint"
        if (value is Number) return "number"
        if (value is Boolean) return "boolean"
        if (isSymbol(value)) return "symbol"
        throw errorWithClassName("msg.invalid.type", value)
    }

    // ---- Conversions that need an object -------------------------------------------------------

    /**
     * ToPrimitive: turns an object into a primitive, asking `Symbol.toPrimitive` first and falling
     * back to the object's own `getDefaultValue`.
     *
     * [preferredType] is [StringClass], [NumberClass], or null for no preference.
     */
    fun toPrimitive(input: Any?, preferredType: KClass<*>? = null): Any? {
        // Scriptables always go through getDefaultValue, even the ones isObject rejects.
        if (input !is Scriptable && !isObject(input)) return input

        val s = input as Scriptable
        // getProperty(obj, Symbol) throws when obj is not a SymbolScriptable, so guard it first.
        val exoticToPrim =
            if (s is SymbolScriptable) ScriptableObject.getProperty(s, SymbolKey.TO_PRIMITIVE)
            else null

        if (exoticToPrim is Function) {
            val cx = Context.getCurrentContext()!!
            val hint = when (preferredType) {
                null -> "default"
                StringClass -> "string"
                else -> "number"
            }
            val result = exoticToPrim.call(cx, exoticToPrim.declarationScope!!, s, arrayOf(hint))
            if (isObject(result)) throw typeErrorById("msg.cant.convert.to.primitive")
            return result
        }
        if (exoticToPrim != null &&
            exoticToPrim !== Scriptable.NOT_FOUND &&
            !Undefined.isUndefined(exoticToPrim)
        ) {
            throw notFunctionError(exoticToPrim)
        }

        val result = s.getDefaultValue(preferredType)
        if (result is Scriptable && !isSymbol(result)) throw typeErrorById("msg.bad.default.value")
        return result
    }

    /** ToString: what `String(value)` gives. */
    fun toString(value: Any?): String {
        var v = value
        while (true) {
            when {
                v == null -> return "null"
                Undefined.isUndefined(v) -> return "undefined"
                v is String -> return v
                v is CharSequence -> return v.toString()
                v is KBigInt -> return v.toString(10)
                v is Number -> return numberToString(v.toDouble(), 10)
                v is Boolean -> return v.toString()
                isSymbol(v) -> throw typeErrorById("msg.not.a.string")
                v is Scriptable -> v = toPrimitive(v, StringClass)
                // Upstream warns here about a plain Java object reaching script. There is no Java
                // interop in this port, so there is nothing to warn about.
                else -> return v.toString()
            }
        }
    }

    // ---- Errors ------------------------------------------------------------------------------

    fun constructError(error: String, message: String): EcmaError {
        val linep = IntArray(1)
        val filename = Context.getSourcePositionFromStack(linep)
        return constructError(error, message, filename, linep[0], null, 0)
    }

    fun constructError(
        error: String,
        message: String,
        sourceName: String?,
        lineNumber: Int,
        lineSource: String?,
        columnNumber: Int,
    ): EcmaError = EcmaError(error, message, sourceName, lineNumber, lineSource, columnNumber)

    fun typeError(message: String): EcmaError = constructError("TypeError", message)

    fun typeErrorById(messageId: String, vararg args: Any?): EcmaError =
        typeError(getMessageById(messageId, *args))

    fun rangeError(message: String): EcmaError = constructError("RangeError", message)

    fun rangeErrorById(messageId: String, vararg args: Any?): EcmaError =
        rangeError(getMessageById(messageId, *args))

    fun notFunctionError(value: Any?): RuntimeException = notFunctionError(value, value)

    fun notFunctionError(value: Any?, messageHelper: Any?): RuntimeException {
        val msg = messageHelper?.toString() ?: "null"
        if (value === Scriptable.NOT_FOUND) return typeErrorById("msg.function.not.found", msg)
        return typeErrorById("msg.isnt.function", msg, typeOf(value))
    }

    /**
     * KMP: upstream puts the Java class name in the message. There is no `Class.getName` in common
     * Kotlin, so this uses the simple name (D-23).
     */
    private fun errorWithClassName(msg: String, value: Any): RuntimeException =
        Context.reportRuntimeErrorById(msg, value::class.simpleName ?: "?")

    /**
     * The `===` comparison. Numbers compare by value (so `NaN` is never equal to itself), strings
     * by their characters, and everything else by identity.
     */
    fun shallowEq(x: Any?, y: Any?): Boolean {
        if (x === y) {
            if (x !is Number) return true
            return !x.toDouble().isNaN()
        }
        if (x == null || x === Undefined.instance || x === Undefined.SCRIPTABLE_UNDEFINED) {
            // The two spellings of undefined are equal to each other.
            return (x === Undefined.instance && y === Undefined.SCRIPTABLE_UNDEFINED) ||
                (x === Undefined.SCRIPTABLE_UNDEFINED && y === Undefined.instance)
        }
        when {
            x is KBigInt -> if (y is KBigInt) return x == y
            x is Number -> if (y is Number && y !is KBigInt) return x.toDouble() == y.toDouble()
            x is CharSequence -> if (y is CharSequence) return x.toString() == y.toString()
            x is Boolean -> if (y is Boolean) return x == y
            x is Scriptable -> if (x is Wrapper && y is Wrapper) return x.unwrap() === y.unwrap()
            // Upstream warns about a plain Java object here. There is no Java interop in this port.
            else -> return x === y
        }
        return false
    }

    // ---- Property keys ------------------------------------------------------------------------

    /** A property key resolved to either a string name or an array index, never both. */
    class StringIdOrIndex {
        val stringId: String?
        val index: Int

        constructor(index: Int) {
            this.stringId = null
            this.index = index
        }

        constructor(stringId: String) {
            this.stringId = stringId
            this.index = -1
        }
    }

    /** Works out whether [id] names an array index or an ordinary property. */
    fun toStringIdOrIndex(id: Any?): StringIdOrIndex {
        if (id is Number) {
            val d = id.toDouble()
            if (d < 0.0) return StringIdOrIndex(toString(id))
            val index = d.toInt()
            return if (index.toDouble() == d) StringIdOrIndex(index) else StringIdOrIndex(toString(id))
        }
        val s = if (id is String) id else toString(id)
        val indexTest = indexFromString(s)
        return if (indexTest in 0..Int.MAX_VALUE.toLong()) StringIdOrIndex(indexTest.toInt())
        else StringIdOrIndex(s)
    }

    /** `obj[elem]`, giving `undefined` where the property is missing. */
    fun getObjectElem(obj: Scriptable, elem: Any?, cx: Context?): Any? {
        val result = when {
            isSymbol(elem) -> ScriptableObject.getProperty(obj, elem as Symbol)
            else -> {
                val s = toStringIdOrIndex(elem)
                if (s.stringId == null) ScriptableObject.getProperty(obj, s.index)
                else ScriptableObject.getProperty(obj, s.stringId)
            }
        }
        return if (result === Scriptable.NOT_FOUND) Undefined.instance else result
    }

    // ---- Truthiness and the prototype chain ---------------------------------------------------

    /** ToBoolean. */
    fun toBoolean(value: Any?): Boolean {
        var v = value
        while (true) {
            when {
                v is Boolean -> return v
                v == null || Undefined.isUndefined(v) -> return false
                v is CharSequence -> return v.isNotEmpty()
                v is KBigInt -> return !v.isZero()
                v is Number -> {
                    val d = v.toDouble()
                    return !d.isNaN() && d != 0.0
                }
                v is Scriptable -> {
                    if (v is ScriptableObject && v.avoidObjectDetection()) return false
                    if (Context.getContext().isVersionECMA1()) return true
                    // The pre-ECMA extension: ask the object for a primitive first.
                    v = v.getDefaultValue(BooleanClass)
                    if (v is Scriptable && !isSymbol(v)) {
                        throw errorWithClassName("msg.primitive.expected", v)
                    }
                }
                else -> return true
            }
        }
    }

    /** Whether [rhs] is somewhere in [lhs]'s prototype chain. */
    fun jsDelegatesTo(lhs: Scriptable, rhs: Scriptable): Boolean {
        var proto = lhs.prototype
        while (proto != null) {
            if (proto == rhs) return true
            proto = proto.prototype
        }
        return false
    }

    /** The constructor named [constructorName] in [scope], or a failure explaining why not. */
    fun getExistingCtor(cx: Context, scope: Scriptable, constructorName: String): Function {
        val ctorVal = ScriptableObject.getProperty(scope, constructorName)
        if (ctorVal is Function) return ctorVal
        if (ctorVal === Scriptable.NOT_FOUND) {
            throw Context.reportRuntimeErrorById("msg.ctor.not.found", constructorName)
        }
        throw Context.reportRuntimeErrorById("msg.not.ctor", constructorName)
    }

    /** ToNumber. */
    fun toNumber(value: Any?): Double {
        var v = value
        while (true) {
            when {
                v is KBigInt -> throw typeErrorById("msg.cant.convert.to.number", "BigInt")
                v is Number -> return v.toDouble()
                v == null -> return +0.0
                Undefined.isUndefined(v) -> return Double.NaN
                v is String -> return toNumber(v)
                v is CharSequence -> return toNumber(v.toString())
                v is Boolean -> return if (v) 1.0 else +0.0
                isSymbol(v) -> throw typeErrorById("msg.not.a.number")
                v is Scriptable -> v = toPrimitive(v, NumberClass)
                // Upstream warns about a plain Java object here. There is no Java interop here.
                else -> return Double.NaN
            }
        }
    }

    fun toInt32(value: Any?): Int {
        if (value is Int) return value
        return toInt32(toNumber(value))
    }

    fun toInt32(args: Array<Any?>, index: Int): Int =
        if (index < args.size) toInt32(args[index]) else 0

    /** What `Object.prototype.toString` gives for [obj]. */
    internal fun defaultObjectToString(obj: Scriptable?): String {
        if (obj == null) return "[object Null]"
        if (Undefined.isUndefined(obj)) return "[object Undefined]"
        // NOT_FOUND is not a CharSequence, so a missing tag needs no separate check.
        val tagValue = ScriptableObject.getProperty(obj, SymbolKey.TO_STRING_TAG)
        if (tagValue is CharSequence) return "[object $tagValue]"
        return "[object " + obj.className + "]"
    }

    // ---- Wiring a new object into its scope ----------------------------------------------------

    fun setFunctionProtoAndParent(
        fn: BaseFunction,
        cx: Context?,
        scope: Scriptable,
        es6GeneratorFunction: Boolean = false,
    ) {
        fn.parentScope = scope
        fn.prototype =
            if (es6GeneratorFunction) ScriptableObject.getGeneratorFunctionPrototype(scope)
            else ScriptableObject.getFunctionPrototype(scope)
        if (cx != null && cx.languageVersion >= Context.VERSION_ES6) {
            fn.setStandardPropertyAttributes(ScriptableObject.READONLY or ScriptableObject.DONTENUM)
        }
    }

    fun setObjectProtoAndParent(obj: ScriptableObject, scope: Scriptable) {
        // Unlike a function, an object always hangs off the top scope.
        val top = ScriptableObject.getTopLevelScope(scope)
        obj.parentScope = top
        obj.prototype = ScriptableObject.getClassPrototype(top, obj.className)
    }

    fun setBuiltinProtoAndParent(
        obj: ScriptableObject,
        scope: Scriptable,
        type: TopLevel.Builtins,
    ) {
        val top = ScriptableObject.getTopLevelScope(scope)
        obj.parentScope = top
        obj.prototype = TopLevel.getBuiltinPrototype(top, type)
    }

    // ---- The top call ------------------------------------------------------------------------

    /** The function `caller` and `arguments` throw through in strict mode. */
    private class ThrowTypeError(scope: Scriptable) : BaseFunction() {
        init {
            prototype = ScriptableObject.getFunctionPrototype(scope)
            setAttributes("length", DONTENUM or PERMANENT or READONLY)
            setAttributes("name", DONTENUM or PERMANENT or READONLY)
            // arity and arguments go without the usual checks.
            map.compute(this, "arity", 0) { _, _, _, _, _ -> null }
            map.compute(this, "arguments", 0) { _, _, _, _, _ -> null }
            preventExtensions()
        }

        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            throw typeErrorById("msg.op.not.allowed")
    }

    fun typeErrorThrower(cx: Context): BaseFunction {
        var t = cx.typeErrorThrower
        if (t == null) {
            t = ThrowTypeError(cx.topCallScope!!)
            cx.typeErrorThrower = t
        }
        return t
    }

    fun hasTopCall(cx: Context): Boolean = cx.topCallScope != null

    fun getTopCallScope(cx: Context): Scriptable = cx.topCallScope ?: throw IllegalStateException()

    internal fun findFunctionActivation(cx: Context, f: Function): NativeCall? {
        var call = cx.currentActivationCall
        while (call != null) {
            if (call.function === f) return call
            call = call.parentActivationCall
        }
        return null
    }

    fun doTopCall(callable: Callable, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
        doTopCall(callable, cx, scope, thisObj, args, cx.isTopLevelStrict)

    fun doTopCall(script: Script, cx: Context, scope: Scriptable, thisObj: Scriptable): Any? =
        doTopCall(script, cx, scope, thisObj, cx.isTopLevelStrict)

    /** Runs [callable] as the outermost call, setting up and tearing down the top scope around it. */
    fun doTopCall(callable: Callable, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>, isTopLevelStrict: Boolean): Any? {
        check(cx.topCallScope == null)
        cx.topCallScope = ScriptableObject.getTopLevelScope(scope)
        cx.useDynamicScope = cx.hasFeature(Context.FEATURE_DYNAMIC_SCOPE)
        val previousTopLevelStrict = cx.isTopLevelStrict
        cx.isTopLevelStrict = isTopLevelStrict
        try {
            return cx.factory.doTopCall(callable, cx, scope, thisObj, args)
        } finally {
            cx.topCallScope = null
            cx.isTopLevelStrict = previousTopLevelStrict
            check(cx.currentActivationCall == null)
        }
    }

    fun doTopCall(script: Script, cx: Context, scope: Scriptable, thisObj: Scriptable, isTopLevelStrict: Boolean): Any? {
        check(cx.topCallScope == null)
        cx.topCallScope = ScriptableObject.getTopLevelScope(scope)
        cx.useDynamicScope = cx.hasFeature(Context.FEATURE_DYNAMIC_SCOPE)
        val previousTopLevelStrict = cx.isTopLevelStrict
        cx.isTopLevelStrict = isTopLevelStrict
        try {
            return cx.factory.doTopCall(script, cx, scope, thisObj)
        } finally {
            cx.topCallScope = null
            cx.isTopLevelStrict = previousTopLevelStrict
            check(cx.currentActivationCall == null)
        }
    }

    // ---- apply and call ------------------------------------------------------------------------

    /** `Function.prototype.apply` and `call`, which differ only in how the arguments arrive. */
    fun applyOrCall(isApply: Boolean, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        val l = args.size
        val function = getCallable(thisObj)
        val callThis = getApplyOrCallThis(cx, scope, if (l == 0) null else args[0], l, function)
        val callArgs: Array<Any?> =
            if (isApply) {
                if (l <= 1) emptyArgs else getApplyArguments(cx, args[1])
            } else {
                if (l <= 1) emptyArgs else args.copyOfRange(1, l)
            }
        return function.call(cx, scope, callThis, callArgs)
    }

    internal fun getCallable(thisObj: Scriptable?): Callable {
        if (thisObj is Callable) return thisObj
        if (thisObj == null) throw notFunctionError(null, null)
        val value = thisObj.getDefaultValue(FunctionClass)
        if (value !is Callable) throw notFunctionError(value, thisObj)
        return value
    }

    fun getApplyOrCallThis(cx: Context, scope: Scriptable, arg0: Any?, l: Int, target: Callable): Scriptable? {
        var callThis: Scriptable?
        if (cx.hasFeature(Context.FEATURE_OLD_UNDEF_NULL_THIS)) {
            // The old rule: a missing or null this becomes the global object for everyone.
            callThis = if (l != 0) toObjectOrNull(cx, arg0, scope) else null
            if (callThis == null) callThis = getTopCallScope(cx)
        } else {
            callThis =
                if (l != 0) {
                    if (arg0 === Undefined.instance) Undefined.SCRIPTABLE_UNDEFINED else toObjectOrNull(cx, arg0, scope)
                } else {
                    Undefined.SCRIPTABLE_UNDEFINED
                }
            // Only a sloppy function gets the global object in place of a missing this.
            val missingCallThis = callThis == null || callThis === Undefined.SCRIPTABLE_UNDEFINED
            val isFunctionStrict = target !is JSFunction || target.isStrict
            if (missingCallThis && !isFunctionStrict) callThis = getTopCallScope(cx)
        }
        return callThis
    }

    internal fun getApplyArguments(cx: Context, arg1: Any?): Array<Any?> = when {
        arg1 == null || Undefined.isUndefined(arg1) -> emptyArgs
        arg1 is Scriptable && isArrayLike(arg1) -> cx.getElements(arg1)
        arg1 is ScriptableObject -> emptyArgs
        else -> throw typeErrorById("msg.arg.isnt.array")
    }

    internal fun isArrayLike(obj: Scriptable?): Boolean =
        // TODO(P3.8): NativeArray is also array-like on sight, without a property lookup.
        obj != null && (obj is Arguments || ScriptableObject.hasProperty(obj, "length"))

    /** Calls [fun_] the way script would, with [thisArg] converted to an object. */
    fun call(cx: Context, fun_: Any?, thisArg: Any?, args: Array<Any?>, scope: Scriptable): Any? {
        if (fun_ !is Function) throw notFunctionError(toString(fun_))
        val thisObj = toObjectOrNull(cx, thisArg, scope) ?: throw undefCallError(null, "function")
        return fun_.call(cx, scope, thisObj, args)
    }

    fun undefCallError(obj: Any?, id: Any?): RuntimeException =
        typeErrorById("msg.undef.method.call", toString(obj), toString(id))

    // ---- ToObject ------------------------------------------------------------------------------

    fun toObject(scope: Scriptable, value: Any?): Scriptable {
        if (value is Scriptable) return value
        return toObject(Context.getContext(), scope, value)
    }

    /** ToObject: wraps a primitive in its object, and refuses null and undefined. */
    fun toObject(cx: Context, scope: Scriptable, value: Any?): Scriptable {
        if (value == null) throw typeErrorById("msg.null.to.object")
        if (Undefined.isUndefined(value)) throw typeErrorById("msg.undef.to.object")
        if (value is Scriptable) return value
        // TODO(P3.8): the wrapper objects NativeString, NativeNumber, NativeBoolean, NativeSymbol
        // and NativeBigInt land with the natives. Until then a primitive cannot be wrapped.
        TODO("the primitive wrapper objects land in phase 3.8")
    }

    fun toObjectOrNull(cx: Context, obj: Any?): Scriptable? {
        if (obj is Scriptable) return obj
        if (obj != null && !Undefined.isUndefined(obj)) return toObject(cx, getTopCallScope(cx), obj)
        return null
    }

    fun toObjectOrNull(cx: Context, obj: Any?, scope: Scriptable): Scriptable? {
        if (obj is Scriptable) return obj
        if (obj != null && !Undefined.isUndefined(obj)) return toObject(cx, scope, obj)
        return null
    }

    // ---- Making objects ------------------------------------------------------------------------

    fun newObject(cx: Context, scope: Scriptable, constructorName: String, args: Array<Any?>?): Scriptable {
        val top = ScriptableObject.getTopLevelScope(scope)
        val ctor = getExistingCtor(cx, top, constructorName)
        return ctor.construct(cx, top, args ?: emptyArgs)
    }

    fun newObject(ctor: Any?, cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
        if (ctor !is Constructable) throw notFunctionError(ctor)
        return ctor.construct(cx, scope, args)
    }

    /** The elements of an array-like object, with holes read as `undefined`. */
    fun getArrayElements(obj: Scriptable): Array<Any?> {
        // TODO(P3.8): NativeArray.getLengthProperty handles a real array's length directly.
        val lengthValue = ScriptableObject.getProperty(obj, "length")
        val longLen = if (lengthValue === Scriptable.NOT_FOUND) 0L else toUint32(toNumber(lengthValue))
        require(longLen <= Int.MAX_VALUE)
        val len = longLen.toInt()
        if (len == 0) return emptyArgs
        return Array(len) { i ->
            val elem = ScriptableObject.getProperty(obj, i)
            if (elem === Scriptable.NOT_FOUND) Undefined.instance else elem
        }
    }

    // ---- Generated scripts ---------------------------------------------------------------------

    internal fun makeUrlForGeneratedScript(isEval: Boolean, masterScriptUrl: String?, masterScriptLine: Int): String =
        if (isEval) "$masterScriptUrl#$masterScriptLine(eval)" else "$masterScriptUrl#$masterScriptLine(Function)"

    internal fun isGeneratedScript(sourceUrl: String): Boolean =
        sourceUrl.contains("(eval)") || sourceUrl.contains("(Function)")

    internal fun checkDeprecated(cx: Context, name: String) {
        val version = cx.languageVersion
        if (version >= Context.VERSION_1_4 || version == Context.VERSION_DEFAULT) {
            val msg = getMessageById("msg.deprec.ctor", name)
            if (version == Context.VERSION_DEFAULT) Context.reportWarning(msg) else throw Context.reportRuntimeError(msg)
        }
    }

    /** Builds the global scope. */
    fun initStandardObjects(cx: Context, scope: ScriptableObject?, sealed: Boolean): ScriptableObject =
        initSafeStandardObjects(cx, scope, sealed)

    fun initSafeStandardObjects(cx: Context, scope: ScriptableObject?, sealed: Boolean): ScriptableObject =
        // TODO(P3.8): the natives land in phase 3.8; this is where they get registered.
        TODO("the standard objects land in phase 3.8")
}
