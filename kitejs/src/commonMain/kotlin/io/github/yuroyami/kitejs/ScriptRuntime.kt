/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.dtoa.DoubleFormatter
import kotlin.math.ceil
import kotlin.math.floor
import io.github.yuroyami.kitejs.ast.FunctionNode
import kotlin.math.pow
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

    private const val LIBRARY_SCOPE_KEY = "LIBRARY_SCOPE"

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
            throw rangeErrorById("msg.bad.radix", base.toString())
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
    fun getObjectElem(obj: Scriptable, elem: Any?, cx: Context): Any? {
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
        if (value is SymbolKey) {
            // TODO(P4): NativeSymbol wraps a symbol key.
            TODO("NativeSymbol lands in phase 4")
        }
        if (value is Scriptable) return value
        if (value is CharSequence) {
            // TODO(P3.8): NativeString wraps a string.
            TODO("NativeString lands in phase 3.8")
        }
        if (cx.languageVersion >= Context.VERSION_ES6 && value is KBigInt) {
            // TODO(P5): NativeBigInt wraps a big integer.
            TODO("NativeBigInt lands in phase 5")
        }
        if (value is Number) {
            val result = NativeNumber(value.toDouble())
            setBuiltinProtoAndParent(result, scope, TopLevel.Builtins.Number)
            return result
        }
        if (value is Boolean) {
            val result = NativeBoolean(value)
            setBuiltinProtoAndParent(result, scope, TopLevel.Builtins.Boolean)
            return result
        }
        // Wrapping arbitrary Kotlin objects is LiveConnect territory and is not ported.
        throw errorWithClassName("msg.invalid.type", value)
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

    // ---- Small helpers the natives share ---------------------------------------------------------

    fun toNumber(args: Array<Any?>, index: Int): Double =
        if (index < args.size) toNumber(args[index]) else Double.NaN

    fun toString(args: Array<Any?>, index: Int): String =
        if (index < args.size) toString(args[index]) else "undefined"

    fun toInteger(value: Any?): Double = toInteger(toNumber(value))

    fun toInteger(args: Array<Any?>, index: Int): Double =
        if (index < args.size) toInteger(args[index]) else +0.0

    fun toLength(args: Array<Any?>, index: Int): Long {
        val len = toInteger(args, index)
        if (len <= 0.0) return 0
        return minOf(len, NativeNumber.MAX_SAFE_INTEGER).toLong()
    }

    fun toLength(value: Any?): Long {
        val len = toInteger(value)
        if (len <= 0.0) return 0
        return minOf(len, NativeNumber.MAX_SAFE_INTEGER).toLong()
    }

    fun toIntegerOrInfinity(value: Any?): Double {
        if (value is Int) return value.toDouble()
        return toIntegerOrInfinity(toNumber(value))
    }

    fun toIntegerOrInfinity(d: Double): Double = DoubleConversion.truncate(d)

    fun isNaN(n: Any?): Boolean = (n is Double && n.isNaN()) || (n is Float && n.isNaN())

    /** SameValue: `Object.is`. NaN equals NaN, and +0 and -0 differ. */
    fun same(x: Any?, y: Any?): Boolean {
        if (typeOf(x) != typeOf(y)) return false
        if (x is Number) {
            if (isNaN(x) && isNaN(y)) return true
            return x == y
        }
        return eq(x, y)
    }

    fun getTopLevelProp(scope: Scriptable, id: String): Any? {
        val top = ScriptableObject.getTopLevelScope(scope)
        return ScriptableObject.getProperty(top, id)
    }

    internal fun isValidIdentifierName(s: String, cx: Context, isStrict: Boolean): Boolean {
        val l = s.length
        if (l == 0) return false
        if (!isJavaIdentifierStart(s[0])) return false
        for (i in 1 until l) {
            if (!isJavaIdentifierPart(s[i])) return false
        }
        return !TokenStream.isKeyword(s, cx.languageVersion, isStrict)
    }

    // Character.isJavaIdentifierStart / isJavaIdentifierPart, from the Java definitions.
    private fun isJavaIdentifierStart(c: Char): Boolean =
        c.isLetter() ||
            c.category == CharCategory.LETTER_NUMBER ||
            c.category == CharCategory.CURRENCY_SYMBOL ||
            c.category == CharCategory.CONNECTOR_PUNCTUATION

    private fun isJavaIdentifierPart(c: Char): Boolean {
        if (isJavaIdentifierStart(c) || c.isDigit()) return true
        return when (c.category) {
            CharCategory.COMBINING_SPACING_MARK, CharCategory.NON_SPACING_MARK, CharCategory.FORMAT -> true
            else -> c.code in 0..8 || c.code in 0xE..0x1B || c.code in 0x7F..0x9F
        }
    }

    /** `uneval`: source text that rebuilds [value]. */
    internal fun uneval(cx: Context, scope: Scriptable, value: Any?): String {
        if (value == null) return "null"
        if (Undefined.isUndefined(value)) return "undefined"
        if (value is CharSequence) {
            val escaped = escapeString(value.toString())
            val sb = StringBuilder(escaped.length + 2)
            sb.append('"')
            sb.append(escaped)
            sb.append('"')
            return sb.toString()
        }
        if (value is Number) {
            val d = value.toDouble()
            if (d == 0.0 && 1 / d < 0) return "-0"
            return toString(d)
        }
        if (value is Boolean) return toString(value)
        if (value is Scriptable) {
            // Wrapped Java objects won't have "toSource" and will report errors for get()s of
            // nonexistent name, so use has() first.
            if (ScriptableObject.hasProperty(value, "toSource")) {
                val v = ScriptableObject.getProperty(value, "toSource")
                if (v is Function) {
                    return toString(v.call(cx, scope, value, emptyArgs))
                }
            }
            return toString(value)
        }
        return value.toString()
    }

    /** `Object.prototype.toSource`: an object literal that rebuilds [thisObj]. */
    internal fun defaultObjectToSource(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): String {
        val toplevel: Boolean
        val iterating: Boolean
        var cxIterating = cx.iterating
        if (cxIterating == null) {
            toplevel = true
            iterating = false
            cxIterating = mutableSetOf()
            cx.iterating = cxIterating
        } else {
            toplevel = false
            iterating = cxIterating.contains(thisObj)
        }
        val result = StringBuilder(128)
        if (toplevel) result.append("(")
        result.append('{')
        try {
            if (!iterating) {
                cxIterating.add(thisObj!!) // stop recursion
                val ids = thisObj.getIds()
                for (i in ids.indices) {
                    val id = ids[i]
                    val value: Any?
                    if (id is Int) {
                        value = thisObj.get(id, thisObj)
                        if (value === Scriptable.NOT_FOUND) continue // a property has been removed
                        if (i > 0) result.append(", ")
                        result.append(id)
                    } else {
                        val strId = id as String
                        value = thisObj.get(strId, thisObj)
                        if (value === Scriptable.NOT_FOUND) continue // a property has been removed
                        if (i > 0) result.append(", ")
                        if (isValidIdentifierName(strId, cx, cx.isStrictMode())) {
                            result.append(strId)
                        } else {
                            result.append('\'')
                            result.append(escapeString(strId, '\''))
                            result.append('\'')
                        }
                    }
                    result.append(':')
                    result.append(uneval(cx, scope, value))
                }
            }
        } finally {
            if (toplevel) cx.iterating = null
        }
        result.append('}')
        if (toplevel) result.append(')')
        return result.toString()
    }

    /**
     * Walks the iterable [arg1] and hands each `[key, value]` pair to [setter]. Returns false when
     * there is nothing to walk.
     */
    fun loadFromIterable(cx: Context, scope: Scriptable, arg1: Any?, setter: (Any?, Any?) -> Unit): Boolean {
        if (arg1 == null || Undefined.isUndefined(arg1)) return false
        // Call the "[Symbol.iterator]" property as a function.
        val ito = callIterator(arg1, cx, scope)
        if (Undefined.isUndefined(ito)) {
            // Per spec, ignore if the iterator is undefined.
            return false
        }
        // Finally, run through all the iterated values and add them.
        IteratorLikeIterable(cx, scope, ito).use { it ->
            for (value in it) {
                val sVal = ScriptableObject.ensureScriptable(value)
                if (sVal is Symbol) {
                    throw typeErrorById("msg.arg.not.object", typeOf(sVal))
                }
                var finalKey = sVal.get(0, sVal)
                if (finalKey === Scriptable.NOT_FOUND) finalKey = Undefined.instance
                var finalVal = sVal.get(1, sVal)
                if (finalVal === Scriptable.NOT_FOUND) finalVal = Undefined.instance
                setter(finalKey, finalVal)
            }
        }
        return true
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

    fun initSafeStandardObjects(cx: Context, scopeIn: ScriptableObject?, sealed: Boolean): ScriptableObject {
        var scope = scopeIn
        if (scope == null) {
            scope = NativeObject()
        } else if (scope is TopLevel) {
            scope.clearCache()
        }
        scope.put("global", scope, scope)
        scope.associateValue(LIBRARY_SCOPE_KEY, scope)
        // ClassCache and ConcurrentFactory are LiveConnect and threading support, neither ported.

        val function = BaseFunction.init(cx, scope, sealed)
        val obj = NativeObject.init(cx, scope, sealed)

        val objectPrototype = obj.prototypeProperty as ScriptableObject
        val functionPrototype = function.prototypeProperty as ScriptableObject

        // Function.prototype.__proto__ should be Object.prototype
        objectPrototype.prototype = null
        functionPrototype.prototype = objectPrototype
        // Set the prototype of the object passed in if need be
        function.prototype = functionPrototype
        obj.prototype = functionPrototype
        if (scope.prototype == null) scope.prototype = objectPrototype

        NativeError.init(scope, sealed)
        NativeGlobal.init(cx, scope, sealed)

        // TODO(P3.8): NativeArray.init(cx, scope, sealed)
        // TODO(P3.8): NativeString.init(scope, sealed)
        NativeBoolean.init(scope, sealed)
        NativeNumber.init(scope, sealed)
        // TODO(P4): NativeDate.init(scope, sealed)
        LazilyLoadedCtor(scope, "Math", sealed, Initializable { icx, s, sld -> NativeMath.init(icx, s, sld) })
        // TODO(P3.8): LazilyLoadedCtor(scope, "JSON", sealed, NativeJSON::init)

        NativeWith.init(scope, sealed)
        NativeCall.init(scope, sealed)
        NativeScript.init(cx, scope, sealed)

        // TODO(P4): NativeIterator.init(cx, scope, sealed)
        // TODO(P3.8): NativeArrayIterator.init(scope, sealed)
        NativeStringIterator.init(scope, sealed)

        // TODO(P4): registerRegExp(cx, scope, sealed)
        // NativeJavaObject, NativeJavaMap, Continuation and E4X are out of scope.
        // TODO(P4): the typed arrays, ArrayBuffer and DataView.
        // TODO(P4): NativeSymbol, the collection iterators, Map, Set, WeakMap, WeakSet, Promise,
        // Proxy and Reflect. TODO(P5): BigInt.

        if (scope is TopLevel) scope.cacheBuiltins(scope, sealed)
        return scope
    }

    // ---- Regular expressions -------------------------------------------------------------------

    fun getRegExpProxy(cx: Context): RegExpProxy? = cx.regExpProxy

    fun setRegExpProxy(cx: Context, proxy: RegExpProxy?) {
        cx.regExpProxy = proxy
    }

    fun checkRegExpProxy(cx: Context): RegExpProxy =
        getRegExpProxy(cx) ?: throw Context.reportRuntimeErrorById("msg.no.regexp")

    // ---- Numbers -------------------------------------------------------------------------------

    val NaNobj: Double = Double.NaN
    val negativeZeroObj: Double = -0.0
    val zeroObj: Double = 0.0

    /** The `+` operator applied to two values that both have to become strings. */
    fun concat(lhs: Any?, rhs: Any?): Any {
        val rhsString = toString(rhs)
        val lhsString = toString(lhs)
        return ConsString(lhsString, rhsString)
    }

    /** Boxes a double, sharing one NaN. */
    fun wrapNumber(x: Double): Number = if (x.isNaN()) NaNobj else x

    fun wrapBoolean(b: Boolean): Boolean = b

    fun wrapInt(i: Int): Int = i

    /** ToNumeric: a number or a bigint. */
    fun toNumeric(value: Any?): Number {
        val v = toPrimitive(value, NumberClass)
        if (v is Number) return v
        return toNumber(v)
    }

    fun toCharSequence(value: Any?): CharSequence {
        // TODO(P3.8): a NativeString unwraps to its own character sequence.
        return if (value is CharSequence) value else toString(value)
    }

    /** ToBigInt. Until phase 5 only a bigint value or a plain decimal string can be converted (D-5). */
    fun toBigInt(value: Any?): KBigInt {
        val v = toPrimitive(value, NumberClass)
        if (v is KBigInt) return v
        if (v is Number) {
            val d = v.toDouble()
            if (d.isNaN() || d.isInfinite() || d != kotlin.math.floor(d)) {
                throw rangeErrorById("msg.cant.convert.to.bigint.isnt.integer", toString(v))
            }
            return KBigInt.parse(numberToString(d, 10))
        }
        if (v == null || Undefined.isUndefined(v)) throw typeErrorById("msg.cant.convert.to.bigint", toString(v))
        if (v is CharSequence) return toBigInt(v.toString())
        if (v is Boolean) return if (v) KBigInt.ONE else KBigInt.ZERO
        if (isSymbol(v)) throw typeErrorById("msg.cant.convert.to.bigint", toString(v))
        throw errorWithClassName("msg.primitive.expected", v)
    }

    fun toBigInt(s: String): KBigInt {
        val len = s.length
        var start = 0
        var startChar: Char
        while (true) {
            if (start == len) return KBigInt.ZERO
            startChar = s[start]
            if (!isStrWhiteSpaceChar(startChar.code)) break
            start++
        }
        var end = len - 1
        while (isStrWhiteSpaceChar(s[end].code)) end--
        if (startChar == '0' && start + 2 <= end) {
            val radixC = s[start + 1]
            val radix = when (radixC) {
                'x', 'X' -> 16
                'o', 'O' -> 8
                'b', 'B' -> 2
                else -> -1
            }
            if (radix != -1) {
                val body = s.substring(start + 2, end + 1)
                if (body.isEmpty() || body.any { it.digitToIntOrNull(radix) == null }) throw syntaxErrorById("msg.bigint.bad.form")
                return KBigInt.parse(body, radix)
            }
        }
        val sub = s.substring(start, end + 1)
        for (i in sub.length - 1 downTo 0) {
            val c = sub[i]
            if (i == 0 && (c == '+' || c == '-')) continue
            if (c in '0'..'9') continue
            throw syntaxErrorById("msg.bigint.bad.form")
        }
        return KBigInt.parse(sub)
    }

    fun syntaxError(message: String): EcmaError = constructError("SyntaxError", message)

    fun syntaxErrorById(messageId: String, vararg args: Any?): EcmaError = syntaxError(getMessageById(messageId, *args))

    fun referenceError(message: String): EcmaError = constructError("ReferenceError", message)

    fun referenceErrorById(messageId: String, vararg args: Any?): EcmaError = referenceError(getMessageById(messageId, *args))

    private fun bigIntOperand(): RuntimeException = typeErrorById("msg.cant.convert.to.number", "BigInt")

    // ---- Arithmetic ----------------------------------------------------------------------------

    /** The `+` operator: string concatenation when either side is a string, addition otherwise. */
    fun add(lval: Any?, rval: Any?, cx: Context): Any? {
        if (lval is Int && rval is Int) return add(lval, rval)
        if (lval is KBigInt && rval is KBigInt) return lval.add(rval)
        if (lval is Number && lval !is KBigInt && rval is Number && rval !is KBigInt) {
            return wrapNumber(lval.toDouble() + rval.toDouble())
        }
        val lprim = toPrimitive(lval)
        val rprim = toPrimitive(rval)
        if (lprim is CharSequence || rprim is CharSequence) {
            val lstr: CharSequence = if (lprim is CharSequence) lprim else toString(lprim)
            val rstr: CharSequence = if (rprim is CharSequence) rprim else toString(rprim)
            return ConsString(lstr, rstr)
        }
        val lnum = toNumeric(lprim)
        val rnum = toNumeric(rprim)
        if (lnum is KBigInt && rnum is KBigInt) return lnum.add(rnum)
        if (lnum is KBigInt || rnum is KBigInt) throw bigIntOperand()
        return lnum.toDouble() + rnum.toDouble()
    }

    fun add(val1: CharSequence, val2: Any?): CharSequence = ConsString(val1, toCharSequence(val2))

    fun add(val1: Any?, val2: CharSequence): CharSequence = ConsString(toCharSequence(val1), val2)

    /** Int plus Int, falling back to a double on overflow. */
    fun add(i1: Int, i2: Int): Any {
        val r = i1.toLong() + i2.toLong()
        return if (r >= Int.MIN_VALUE && r <= Int.MAX_VALUE) r.toInt() else r.toDouble()
    }

    fun subtract(i1: Int, i2: Int): Number {
        val r = i1.toLong() - i2.toLong()
        return if (r >= Int.MIN_VALUE && r <= Int.MAX_VALUE) r.toInt() else r.toDouble()
    }

    fun multiply(i1: Int, i2: Int): Number {
        val r = i1.toLong() * i2.toLong()
        return if (r >= Int.MIN_VALUE && r <= Int.MAX_VALUE) r.toInt() else r.toDouble()
    }

    fun subtract(val1: Number, val2: Number): Number = when {
        val1 is KBigInt && val2 is KBigInt -> val1.subtract(val2)
        val1 is KBigInt || val2 is KBigInt -> throw bigIntOperand()
        val1 is Int && val2 is Int -> subtract(val1, val2)
        else -> val1.toDouble() - val2.toDouble()
    }

    fun multiply(val1: Number, val2: Number): Number = when {
        val1 is KBigInt && val2 is KBigInt -> val1.multiply(val2)
        val1 is KBigInt || val2 is KBigInt -> throw bigIntOperand()
        val1 is Int && val2 is Int -> multiply(val1, val2)
        else -> val1.toDouble() * val2.toDouble()
    }

    fun divide(val1: Number, val2: Number): Number = when {
        val1 is KBigInt && val2 is KBigInt -> {
            if (val2.isZero()) throw rangeErrorById("msg.division.zero")
            val1.divide(val2)
        }
        val1 is KBigInt || val2 is KBigInt -> throw bigIntOperand()
        else -> val1.toDouble() / val2.toDouble()
    }

    fun remainder(val1: Number, val2: Number): Number = when {
        val1 is KBigInt && val2 is KBigInt -> {
            if (val2.isZero()) throw rangeErrorById("msg.division.zero")
            val1.remainder(val2)
        }
        val1 is KBigInt || val2 is KBigInt -> throw bigIntOperand()
        else -> val1.toDouble().rem(val2.toDouble())
    }

    fun exponentiate(val1: Number, val2: Number): Number = when {
        val1 is KBigInt && val2 is KBigInt -> {
            if (val2.signum() == -1) throw rangeErrorById("msg.bigint.negative.exponent")
            val1.pow(val2.intValueExact())
        }
        val1 is KBigInt || val2 is KBigInt -> throw bigIntOperand()
        else -> val1.toDouble().pow(val2.toDouble())
    }

    fun bitwiseAND(val1: Double, val2: Double): Double = (toInt32(val1) and toInt32(val2)).toDouble()

    fun bitwiseAND(val1: Number, val2: Number): Number = when {
        val1 is KBigInt && val2 is KBigInt -> val1.and(val2)
        val1 is KBigInt || val2 is KBigInt -> throw bigIntOperand()
        val1 is Int && val2 is Int -> val1 and val2
        else -> (toInt32(val1.toDouble()) and toInt32(val2.toDouble())).toDouble()
    }

    fun bitwiseOR(val1: Double, val2: Double): Double = (toInt32(val1) or toInt32(val2)).toDouble()

    fun bitwiseOR(val1: Number, val2: Number): Number = when {
        val1 is KBigInt && val2 is KBigInt -> val1.or(val2)
        val1 is KBigInt || val2 is KBigInt -> throw bigIntOperand()
        val1 is Int && val2 is Int -> val1 or val2
        else -> (toInt32(val1.toDouble()) or toInt32(val2.toDouble())).toDouble()
    }

    fun bitwiseXOR(val1: Double, val2: Double): Double = (toInt32(val1) xor toInt32(val2)).toDouble()

    fun bitwiseXOR(val1: Number, val2: Number): Number = when {
        val1 is KBigInt && val2 is KBigInt -> val1.xor(val2)
        val1 is KBigInt || val2 is KBigInt -> throw bigIntOperand()
        val1 is Int && val2 is Int -> val1 xor val2
        else -> (toInt32(val1.toDouble()) xor toInt32(val2.toDouble())).toDouble()
    }

    fun leftShift(val1: Double, val2: Double): Double = (toInt32(val1) shl toInt32(val2)).toDouble()

    fun leftShift(val1: Number, val2: Number): Number = when {
        val1 is KBigInt && val2 is KBigInt -> val1.shiftLeft(val2.intValueExact())
        val1 is KBigInt || val2 is KBigInt -> throw bigIntOperand()
        val1 is Int && val2 is Int -> val1 shl val2
        else -> (toInt32(val1.toDouble()) shl toInt32(val2.toDouble())).toDouble()
    }

    fun signedRightShift(val1: Double, val2: Double): Double = (toInt32(val1) shr toInt32(val2)).toDouble()

    fun signedRightShift(val1: Number, val2: Number): Number = when {
        val1 is KBigInt && val2 is KBigInt -> val1.shiftRight(val2.intValueExact())
        val1 is KBigInt || val2 is KBigInt -> throw bigIntOperand()
        val1 is Int && val2 is Int -> val1 shr val2
        else -> (toInt32(val1.toDouble()) shr toInt32(val2.toDouble())).toDouble()
    }

    fun bitwiseNOT(value: Number): Number = when (value) {
        is KBigInt -> value.not()
        is Int -> value.inv()
        else -> toInt32(value.toDouble()).inv().toDouble()
    }

    fun negate(value: Number): Number {
        if (value is KBigInt) return value.negate()
        if (value is Int) {
            if (value == 0) return negativeZeroObj
            if (value > Int.MIN_VALUE && value < Int.MAX_VALUE) return -value
        }
        return -value.toDouble()
    }

    // ---- Comparison ----------------------------------------------------------------------------

    /** The `==` operator. */
    fun eq(x: Any?, y: Any?): Boolean {
        if (x == null || Undefined.isUndefined(x)) {
            if (y == null || Undefined.isUndefined(y)) return true
            if (y is ScriptableObject) {
                val test = y.equivalentValuesInternal(x)
                if (test !== Scriptable.NOT_FOUND) return test as Boolean
            }
            return false
        }
        if (x is KBigInt) return eqBigInt(x, y)
        if (x is Number) return eqNumber(x.toDouble(), y)
        if (x === y) return true
        if (x is CharSequence) return eqString(x, y)
        if (x is Boolean) {
            if (y is Boolean) return x == y
            if (y is ScriptableObject) {
                val test = y.equivalentValuesInternal(x)
                if (test !== Scriptable.NOT_FOUND) return test as Boolean
            }
            return eqNumber(if (x) 1.0 else 0.0, y)
        }
        if (isSymbol(x) && isObject(y)) return eq(x, toPrimitive(y))
        if (x is Scriptable) {
            if (isSymbol(y) && isObject(x)) return eq(toPrimitive(x), y)
            if (y == null || Undefined.isUndefined(y)) {
                if (x is ScriptableObject) {
                    val test = x.equivalentValuesInternal(y)
                    if (test !== Scriptable.NOT_FOUND) return test as Boolean
                }
                return false
            }
            if (y is Scriptable) {
                if (x is ScriptableObject) {
                    val test = x.equivalentValuesInternal(y)
                    if (test !== Scriptable.NOT_FOUND) return test as Boolean
                }
                if (y is ScriptableObject) {
                    val test = y.equivalentValuesInternal(x)
                    if (test !== Scriptable.NOT_FOUND) return test as Boolean
                }
                if (x is Wrapper && y is Wrapper) {
                    val ux = x.unwrap()
                    val uy = y.unwrap()
                    return ux === uy || (isPrimitive(ux) && isPrimitive(uy) && eq(ux, uy))
                }
                return false
            }
            if (y is Boolean) {
                if (x is ScriptableObject) {
                    val test = x.equivalentValuesInternal(y)
                    if (test !== Scriptable.NOT_FOUND) return test as Boolean
                }
                return eqNumber(if (y) 1.0 else 0.0, x)
            }
            if (y is KBigInt) return eqBigInt(y, x)
            if (y is Number) return eqNumber(y.toDouble(), x)
            if (y is CharSequence) return eqString(y, x)
            return false
        }
        return x === y
    }

    fun isPrimitive(obj: Any?): Boolean =
        obj == null || Undefined.isUndefined(obj) || obj is Number || obj is String || obj is Boolean

    internal fun eqNumber(x: Double, value: Any?): Boolean {
        var y = value
        while (true) {
            when {
                y == null || Undefined.isUndefined(y) -> return false
                y is KBigInt -> return eqBigInt(y, x)
                y is Number -> return x == y.toDouble()
                y is CharSequence -> return x == toNumber(y)
                y is Boolean -> return x == (if (y) 1.0 else +0.0)
                isSymbol(y) -> return false
                y is Scriptable -> {
                    if (y is ScriptableObject) {
                        val test = y.equivalentValuesInternal(wrapNumber(x))
                        if (test !== Scriptable.NOT_FOUND) return test as Boolean
                    }
                    y = toPrimitive(y)
                }
                else -> return false
            }
        }
    }

    internal fun eqBigInt(x: KBigInt, value: Any?): Boolean {
        var y = value
        while (true) {
            when {
                y == null || Undefined.isUndefined(y) -> return false
                y is KBigInt -> return x == y
                y is Number -> return eqBigInt(x, y.toDouble())
                y is CharSequence -> {
                    val biy = try { toBigInt(y) } catch (e: EcmaError) { return false }
                    return x == biy
                }
                y is Boolean -> return x == (if (y) KBigInt.ONE else KBigInt.ZERO)
                isSymbol(y) -> return false
                y is Scriptable -> {
                    if (y is ScriptableObject) {
                        val test = y.equivalentValuesInternal(x)
                        if (test !== Scriptable.NOT_FOUND) return test as Boolean
                    }
                    y = toPrimitive(y)
                }
                else -> return false
            }
        }
    }

    private fun eqBigInt(x: KBigInt, y: Double): Boolean {
        if (y.isNaN() || y.isInfinite()) return false
        if (kotlin.math.ceil(y) != y) return false
        return x.compareToDouble(y) == 0
    }

    private fun eqString(x: CharSequence, value: Any?): Boolean {
        var y = value
        while (true) {
            when {
                y == null || Undefined.isUndefined(y) -> return false
                y is CharSequence -> return x.length == y.length && x.toString() == y.toString()
                y is KBigInt -> {
                    val bix = try { toBigInt(x.toString()) } catch (e: EcmaError) { return false }
                    return bix == y
                }
                y is Number -> return toNumber(x.toString()) == y.toDouble()
                y is Boolean -> return toNumber(x.toString()) == (if (y) 1.0 else 0.0)
                isSymbol(y) -> return false
                y is Scriptable -> {
                    if (y is ScriptableObject) {
                        val test = y.equivalentValuesInternal(x.toString())
                        if (test !== Scriptable.NOT_FOUND) return test as Boolean
                    }
                    y = toPrimitive(y)
                }
                else -> return false
            }
        }
    }

    fun instanceOf(a: Any?, b: Any?, cx: Context): Boolean {
        if (b !is Scriptable) throw typeErrorById("msg.instanceof.not.object")
        if (a !is Scriptable) return false
        return b.hasInstance(a)
    }

    fun `in`(a: Any?, b: Any?, cx: Context): Boolean {
        if (b !is Scriptable) throw typeErrorById("msg.in.not.object")
        return hasObjectElem(b, a, cx)
    }

    /** The relational operators, [op] being one of GE, LE, GT and LT. */
    fun compare(val1: Any?, val2: Any?, op: Int): Boolean {
        if (val1 is Number && val2 is Number) return compare(val1, val2, op)
        if (isSymbol(val1) || isSymbol(val2)) throw typeErrorById("msg.compare.symbol")
        val v1 = toPrimitive(val1, NumberClass)
        val v2 = toPrimitive(val2, NumberClass)
        if (v1 is CharSequence) {
            if (v2 is CharSequence) return compareTo(v1.toString(), v2.toString(), op)
            if (v2 is KBigInt) {
                return try { compareTo(toBigInt(v1.toString()).compareTo(v2), op) } catch (e: EcmaError) { false }
            }
        }
        if (v1 is KBigInt && v2 is CharSequence) {
            return try { compareTo(v1.compareTo(toBigInt(v2.toString())), op) } catch (e: EcmaError) { false }
        }
        return compare(toNumeric(v1), toNumeric(v2), op)
    }

    fun compare(val1: Number, val2: Number, op: Int): Boolean {
        if (val1 is KBigInt && val2 is KBigInt) return compareTo(val1.compareTo(val2), op)
        if (val1 is KBigInt || val2 is KBigInt) {
            // A bigint against a double, with the infinities settled first.
            if (val1 is KBigInt) {
                val d = val2.toDouble()
                if (d.isNaN()) return false
                if (d == Double.POSITIVE_INFINITY) return op == Token.LE || op == Token.LT
                if (d == Double.NEGATIVE_INFINITY) return op == Token.GE || op == Token.GT
                return compareTo(val1.compareToDouble(d), op)
            }
            val d = val1.toDouble()
            if (d.isNaN()) return false
            if (d == Double.POSITIVE_INFINITY) return op == Token.GE || op == Token.GT
            if (d == Double.NEGATIVE_INFINITY) return op == Token.LE || op == Token.LT
            return compareTo(-(val2 as KBigInt).compareToDouble(d), op)
        }
        return compareTo(val1.toDouble(), val2.toDouble(), op)
    }

    private fun compareTo(val1: String, val2: String, op: Int): Boolean = compareTo(val1.compareTo(val2), op)

    private fun compareTo(cmp: Int, op: Int): Boolean = when (op) {
        Token.GE -> cmp >= 0
        Token.LE -> cmp <= 0
        Token.GT -> cmp > 0
        Token.LT -> cmp < 0
        else -> throw Kit.codeBug()
    }

    internal fun compareTo(d1: Double, d2: Double, op: Int): Boolean = when (op) {
        Token.GE -> d1 >= d2
        Token.LE -> d1 <= d2
        Token.GT -> d1 > d2
        Token.LT -> d1 < d2
        else -> throw Kit.codeBug()
    }

    // ---- Property access from script ---------------------------------------------------------

    private fun asScriptableOrThrowUndefReadError(cx: Context, scope: Scriptable, obj: Any?, elem: Any?): Scriptable =
        toObjectOrNull(cx, obj, scope) ?: throw undefReadError(obj, elem)

    private fun asScriptableOrThrowUndefWriteError(cx: Context, scope: Scriptable, obj: Any?, elem: Any?, value: Any?): Scriptable =
        toObjectOrNull(cx, obj, scope) ?: throw undefWriteError(obj, elem, value)

    private fun verifyIsScriptableOrComplainWriteErrorInEs5Strict(obj: Any?, elem: Any?, value: Any?, cx: Context) {
        if (obj !is Scriptable && cx.isStrictMode() && cx.languageVersion >= Context.VERSION_1_8) {
            throw undefWriteError(obj, elem, value)
        }
    }

    fun undefReadError(obj: Any?, id: Any?): RuntimeException =
        typeErrorById("msg.undef.prop.read", toString(obj), toString(id))

    fun undefWriteError(obj: Any?, id: Any?, value: Any?): RuntimeException =
        typeErrorById("msg.undef.prop.write", toString(obj), toString(id), toString(value))

    private fun undefDeleteError(obj: Any?, id: Any?): RuntimeException =
        typeErrorById("msg.undef.prop.delete", toString(obj), toString(id))

    fun notFoundError(obj: Scriptable?, property: String): RuntimeException =
        constructError("ReferenceError", getMessageById("msg.is.not.defined", property))

    fun notFunctionError(obj: Any?, value: Any?, propertyName: String): RuntimeException {
        var objString = toString(obj)
        if (obj is JSFunction) {
            // Show the head of the function's source, not the whole body.
            val paren = objString.indexOf(')')
            val curly = objString.indexOf('{', paren)
            if (curly > -1) objString = objString.substring(0, curly + 1) + "...}"
        }
        if (value === Scriptable.NOT_FOUND) return typeErrorById("msg.function.not.found.in", propertyName, objString)
        return typeErrorById("msg.isnt.function.in", propertyName, objString, typeOf(value))
    }

    fun getObjectElem(obj: Any?, elem: Any?, cx: Context): Any? = getObjectElem(obj, elem, cx, getTopCallScope(cx))

    fun getObjectElem(obj: Any?, elem: Any?, cx: Context, scope: Scriptable): Any? =
        getObjectElem(asScriptableOrThrowUndefReadError(cx, scope, obj, elem), elem, cx)

    fun getSuperElem(superObject: Any?, elem: Any?, cx: Context, scope: Scriptable, thisObject: Any?): Any? {
        val superScriptable = asScriptableOrThrowUndefReadError(cx, scope, superObject, elem)
        val thisScriptable = asScriptableOrThrowUndefReadError(cx, scope, thisObject, elem)
        return getSuperElem(elem, superScriptable, thisScriptable)
    }

    fun getSuperElem(elem: Any?, superScriptable: Scriptable, thisScriptable: Scriptable): Any? {
        val result = if (isSymbol(elem)) {
            ScriptableObject.getSuperProperty(superScriptable, thisScriptable, elem as Symbol)
        } else {
            val s = toStringIdOrIndex(elem)
            if (s.stringId == null) ScriptableObject.getSuperProperty(superScriptable, thisScriptable, s.index)
            else ScriptableObject.getSuperProperty(superScriptable, thisScriptable, s.stringId)
        }
        return if (result === Scriptable.NOT_FOUND) Undefined.instance else result
    }

    fun getObjectProp(obj: Any?, property: String, cx: Context): Any? = getObjectProp(obj, property, cx, getTopCallScope(cx))

    fun getObjectProp(obj: Any?, property: String, cx: Context, scope: Scriptable): Any? =
        getObjectProp(asScriptableOrThrowUndefReadError(cx, scope, obj, property), property, cx)

    fun getObjectProp(obj: Scriptable, property: String, cx: Context): Any? {
        val result = ScriptableObject.getProperty(obj, property)
        if (result === Scriptable.NOT_FOUND) {
            if (cx.hasFeature(Context.FEATURE_STRICT_MODE)) {
                Context.reportWarning(getMessageById("msg.ref.undefined.prop", property))
            }
            return Undefined.instance
        }
        return result
    }

    fun getObjectPropNoWarn(obj: Any?, property: String, cx: Context): Any? =
        getObjectPropNoWarn(obj, property, cx, getTopCallScope(cx))

    fun getObjectPropNoWarn(obj: Any?, property: String, cx: Context, scope: Scriptable): Any? {
        val sobj = asScriptableOrThrowUndefReadError(cx, scope, obj, property)
        val result = ScriptableObject.getProperty(sobj, property)
        return if (result === Scriptable.NOT_FOUND) Undefined.instance else result
    }

    fun getSuperProp(superObject: Any?, property: String, cx: Context, scope: Scriptable, thisObject: Any?, noWarn: Boolean): Any? {
        val superScriptable = asScriptableOrThrowUndefReadError(cx, scope, superObject, property)
        val thisScriptable = asScriptableOrThrowUndefReadError(cx, scope, thisObject, property)
        return getSuperProp(superScriptable, thisScriptable, property, cx, noWarn)
    }

    private fun getSuperProp(superScriptable: Scriptable, thisScriptable: Scriptable, property: String, cx: Context, noWarn: Boolean): Any? {
        val result = ScriptableObject.getSuperProperty(superScriptable, thisScriptable, property)
        if (result === Scriptable.NOT_FOUND) {
            if (noWarn) return Undefined.instance
            if (cx.hasFeature(Context.FEATURE_STRICT_MODE)) {
                Context.reportWarning(getMessageById("msg.ref.undefined.prop", property))
            }
            return Undefined.instance
        }
        return result
    }

    fun getObjectIndex(obj: Any?, dblIndex: Double, cx: Context): Any? = getObjectIndex(obj, dblIndex, cx, getTopCallScope(cx))

    fun getObjectIndex(obj: Any?, dblIndex: Double, cx: Context, scope: Scriptable): Any? {
        val sobj = asScriptableOrThrowUndefReadError(cx, scope, obj, dblIndex)
        val index = dblIndex.toInt()
        if (index.toDouble() == dblIndex && index >= 0) return getObjectIndex(sobj, index, cx)
        return getObjectProp(sobj, toString(dblIndex), cx)
    }

    fun getObjectIndex(obj: Scriptable, index: Int, cx: Context): Any? {
        val result = ScriptableObject.getProperty(obj, index)
        return if (result === Scriptable.NOT_FOUND) Undefined.instance else result
    }

    fun getSuperIndex(superObject: Any?, dblIndex: Double, cx: Context, scope: Scriptable, thisObject: Any?): Any? {
        val superScriptable = asScriptableOrThrowUndefReadError(cx, scope, superObject, dblIndex)
        val thisScriptable = asScriptableOrThrowUndefReadError(cx, scope, thisObject, dblIndex)
        val index = dblIndex.toInt()
        if (index.toDouble() == dblIndex && index >= 0) {
            val result = ScriptableObject.getSuperProperty(superScriptable, thisScriptable, index)
            return if (result === Scriptable.NOT_FOUND) Undefined.instance else result
        }
        return getSuperProp(superScriptable, thisScriptable, toString(dblIndex), cx, false)
    }

    fun setObjectElem(obj: Any?, elem: Any?, value: Any?, cx: Context): Any? = setObjectElem(obj, elem, value, cx, getTopCallScope(cx))

    fun setObjectElem(obj: Any?, elem: Any?, value: Any?, cx: Context, scope: Scriptable): Any? {
        verifyIsScriptableOrComplainWriteErrorInEs5Strict(obj, elem, value, cx)
        return setObjectElem(asScriptableOrThrowUndefWriteError(cx, scope, obj, elem, value), elem, value, cx)
    }

    fun setObjectElem(obj: Scriptable, elem: Any?, value: Any?, cx: Context): Any? {
        if (isSymbol(elem)) {
            ScriptableObject.putProperty(obj, elem as Symbol, value)
        } else {
            val s = toStringIdOrIndex(elem)
            if (s.stringId == null) ScriptableObject.putProperty(obj, s.index, value)
            else ScriptableObject.putProperty(obj, s.stringId, value)
        }
        return value
    }

    fun setSuperElem(superObject: Any?, elem: Any?, value: Any?, cx: Context, scope: Scriptable, thisObject: Any?): Any? {
        val superScriptable = asScriptableOrThrowUndefWriteError(cx, scope, superObject, elem, value)
        val thisScriptable = asScriptableOrThrowUndefWriteError(cx, scope, thisObject, elem, value)
        return setSuperElem(superScriptable, thisScriptable, elem, value, cx)
    }

    fun setSuperElem(superScriptable: Scriptable, thisScriptable: Scriptable, elem: Any?, value: Any?, cx: Context): Any? {
        if (isSymbol(elem)) {
            ScriptableObject.putSuperProperty(superScriptable, thisScriptable, elem as Symbol, value)
        } else {
            val s = toStringIdOrIndex(elem)
            if (s.stringId == null) ScriptableObject.putSuperProperty(superScriptable, thisScriptable, s.index, value)
            else ScriptableObject.putSuperProperty(superScriptable, thisScriptable, s.stringId, value)
        }
        return value
    }

    fun setObjectProp(obj: Any?, property: String, value: Any?, cx: Context): Any? = setObjectProp(obj, property, value, cx, getTopCallScope(cx))

    fun setObjectProp(obj: Any?, property: String, value: Any?, cx: Context, scope: Scriptable): Any? {
        verifyIsScriptableOrComplainWriteErrorInEs5Strict(obj, property, value, cx)
        return setObjectProp(asScriptableOrThrowUndefWriteError(cx, scope, obj, property, value), property, value, cx)
    }

    fun setObjectProp(obj: Scriptable, property: String, value: Any?, cx: Context): Any? {
        ScriptableObject.putProperty(obj, property, value)
        return value
    }

    fun setSuperProp(superObject: Any?, property: String, value: Any?, cx: Context, scope: Scriptable, thisObject: Any?): Any? {
        verifyIsScriptableOrComplainWriteErrorInEs5Strict(superObject, property, value, cx)
        verifyIsScriptableOrComplainWriteErrorInEs5Strict(thisObject, property, value, cx)
        val superScriptable = asScriptableOrThrowUndefWriteError(cx, scope, superObject, property, value)
        val thisScriptable = asScriptableOrThrowUndefWriteError(cx, scope, thisObject, property, value)
        ScriptableObject.putSuperProperty(superScriptable, thisScriptable, property, value)
        return value
    }

    fun setObjectIndex(obj: Any?, dblIndex: Double, value: Any?, cx: Context): Any? = setObjectIndex(obj, dblIndex, value, cx, getTopCallScope(cx))

    fun setObjectIndex(obj: Any?, dblIndex: Double, value: Any?, cx: Context, scope: Scriptable): Any? {
        verifyIsScriptableOrComplainWriteErrorInEs5Strict(obj, dblIndex, value, cx)
        val sobj = asScriptableOrThrowUndefWriteError(cx, scope, obj, dblIndex, value)
        val index = dblIndex.toInt()
        if (index.toDouble() == dblIndex && index >= 0) return setObjectIndex(sobj, index, value, cx)
        return setObjectProp(sobj, toString(dblIndex), value, cx)
    }

    fun setObjectIndex(obj: Scriptable, index: Int, value: Any?, cx: Context): Any? {
        ScriptableObject.putProperty(obj, index, value)
        return value
    }

    fun setSuperIndex(superObject: Any?, dblIndex: Double, value: Any?, cx: Context, scope: Scriptable, thisObject: Any?): Any? {
        val superScriptable = asScriptableOrThrowUndefWriteError(cx, scope, superObject, dblIndex, value)
        val thisScriptable = asScriptableOrThrowUndefWriteError(cx, scope, thisObject, dblIndex, value)
        val index = dblIndex.toInt()
        if (index.toDouble() == dblIndex && index >= 0) {
            ScriptableObject.putSuperProperty(superScriptable, thisScriptable, index, value)
            return value
        }
        ScriptableObject.putSuperProperty(superScriptable, thisScriptable, toString(dblIndex), value)
        return value
    }

    fun deleteObjectElem(target: Scriptable, elem: Any?, cx: Context): Boolean {
        if (isSymbol(elem)) {
            val so = ScriptableObject.ensureSymbolScriptable(target)
            val sym = elem as Symbol
            so.delete(sym)
            return !so.has(sym, target)
        }
        val s = toStringIdOrIndex(elem)
        if (s.stringId == null) {
            target.delete(s.index)
            return !target.has(s.index, target)
        }
        target.delete(s.stringId)
        return !target.has(s.stringId, target)
    }

    fun hasObjectElem(target: Scriptable, elem: Any?, cx: Context): Boolean {
        if (isSymbol(elem)) return ScriptableObject.hasProperty(target, elem as Symbol)
        val s = toStringIdOrIndex(elem)
        return if (s.stringId == null) ScriptableObject.hasProperty(target, s.index)
        else ScriptableObject.hasProperty(target, s.stringId)
    }

    fun refGet(ref: Ref, cx: Context): Any? = ref.get(cx)

    fun refSet(ref: Ref, value: Any?, cx: Context): Any? = refSet(ref, value, cx, getTopCallScope(cx))

    fun refSet(ref: Ref, value: Any?, cx: Context, scope: Scriptable): Any? = ref.set(cx, scope, value)

    fun refDel(ref: Ref, cx: Context): Any? = wrapBoolean(ref.delete(cx))

    fun specialRef(obj: Any?, specialProperty: String, cx: Context): Ref = specialRef(obj, specialProperty, cx, getTopCallScope(cx))

    fun specialRef(obj: Any?, specialProperty: String, cx: Context, scope: Scriptable): Ref =
        SpecialRef.createSpecial(cx, scope, obj, specialProperty)

    fun delete(obj: Any?, id: Any?, cx: Context): Any? = delete(obj, id, cx, false)

    fun delete(obj: Any?, id: Any?, cx: Context, isName: Boolean): Any? = delete(obj, id, cx, getTopCallScope(cx), isName)

    fun delete(obj: Any?, id: Any?, cx: Context, scope: Scriptable, isName: Boolean): Any? {
        val sobj = toObjectOrNull(cx, obj, scope)
        if (sobj == null) {
            if (isName) return true
            throw undefDeleteError(obj, id)
        }
        return wrapBoolean(deleteObjectElem(sobj, id, cx))
    }

    // ---- Names and scopes ----------------------------------------------------------------------

    /** Looks [name] up through the scope chain, the way a bare identifier does. */
    fun name(cx: Context, scope: Scriptable, name: String): Any? {
        val parent = scope.parentScope
        if (parent == null) {
            val result = topScopeName(cx, scope, name)
            if (result === Scriptable.NOT_FOUND) throw notFoundError(scope, name)
            return result
        }
        return nameOrFunction(cx, scope, parent, name, false, false)
    }

    /** Finds the object in the scope chain that holds [id], for an assignment. Null if none does. */
    fun bind(cx: Context, scope: Scriptable, id: String): Scriptable? {
        var s = scope
        var parent = s.parentScope
        if (parent != null) {
            while (s is NativeWith) {
                val withObj = s.prototype!!
                if (ScriptableObject.hasProperty(withObj, id)) return withObj
                s = parent!!
                parent = parent.parentScope
                if (parent == null) return bindTop(cx, s, id)
            }
            while (true) {
                if (ScriptableObject.hasProperty(s, id)) return s
                s = parent!!
                parent = parent.parentScope
                if (parent == null) break
            }
        }
        return bindTop(cx, s, id)
    }

    private fun bindTop(cx: Context, scope: Scriptable, id: String): Scriptable? {
        var s = scope
        if (cx.useDynamicScope) s = checkDynamicScope(cx.topCallScope!!, s)
        return if (ScriptableObject.hasProperty(s, id)) s else null
    }

    fun setName(bound: Scriptable?, value: Any?, cx: Context, scope: Scriptable, id: String): Any? {
        if (bound != null) {
            ScriptableObject.putProperty(bound, id, value)
        } else {
            // Assigning to a name nothing declares creates a global.
            if (cx.hasFeature(Context.FEATURE_STRICT_MODE) || cx.hasFeature(Context.FEATURE_STRICT_VARS)) {
                Context.reportWarning(getMessageById("msg.assn.create.strict", id))
            }
            var b = ScriptableObject.getTopLevelScope(scope)
            if (cx.useDynamicScope) b = checkDynamicScope(cx.topCallScope!!, b)
            b.put(id, b, value)
        }
        return value
    }

    fun strictSetName(bound: Scriptable?, value: Any?, cx: Context, scope: Scriptable, id: String): Any? {
        if (bound != null) {
            ScriptableObject.putProperty(bound, id, value)
            return value
        }
        throw constructError("ReferenceError", "Assignment to undefined \"$id\" in strict mode")
    }

    fun setConst(bound: Scriptable, value: Any?, cx: Context, id: String): Any? {
        ScriptableObject.putConstProperty(bound, id, value)
        return value
    }

    private fun nameOrFunction(cx: Context, scope: Scriptable, parentScope: Scriptable, name: String, asFunctionCall: Boolean, isOptionalChainingCall: Boolean): Any? {
        var s = scope
        var parent: Scriptable? = parentScope
        var result: Any?
        // Only meaningful when asFunctionCall is true.
        var thisObj: Scriptable = s
        while (true) {
            if (s is NativeWith) {
                val withObj = s.prototype!!
                result = ScriptableObject.getProperty(withObj, name)
                if (result !== Scriptable.NOT_FOUND) {
                    thisObj = withObj
                    break
                }
            } else if (s is NativeCall) {
                result = s.get(name, s)
                if (result !== Scriptable.NOT_FOUND) {
                    if (asFunctionCall) thisObj = ScriptableObject.getTopLevelScope(parent!!)
                    break
                }
            } else {
                result = ScriptableObject.getProperty(s, name)
                if (result !== Scriptable.NOT_FOUND) {
                    thisObj = s
                    break
                }
            }
            s = parent!!
            parent = parent.parentScope
            if (parent == null) {
                result = topScopeName(cx, s, name)
                if (result === Scriptable.NOT_FOUND) throw notFoundError(s, name)
                thisObj = s
                break
            }
        }
        if (asFunctionCall) {
            if (result !is Callable) {
                if (isOptionalChainingCall && (result === Scriptable.NOT_FOUND || result == null || Undefined.isUndefined(result))) {
                    storeScriptable(cx, null)
                    return null
                }
                throw notFunctionError(result, name)
            }
            storeScriptable(cx, thisObj)
        }
        return result
    }

    private fun nameOrFunction(cx: Context, scope: Scriptable, parentScope: Scriptable, name: String, isOptionalChainingCall: Boolean): LookupResult? {
        var s = scope
        var parent: Scriptable? = parentScope
        var result: Any?
        var thisObj: Scriptable = s
        while (true) {
            if (s is NativeWith) {
                val withObj = s.prototype!!
                result = ScriptableObject.getProperty(withObj, name)
                if (result !== Scriptable.NOT_FOUND) {
                    thisObj = withObj
                    break
                }
            } else if (s is NativeCall) {
                result = s.get(name, s)
                if (result !== Scriptable.NOT_FOUND) {
                    thisObj = ScriptableObject.getTopLevelScope(parent!!)
                    break
                }
            } else {
                result = ScriptableObject.getProperty(s, name)
                if (result !== Scriptable.NOT_FOUND) {
                    thisObj = s
                    break
                }
            }
            s = parent!!
            parent = parent.parentScope
            if (parent == null) {
                result = topScopeName(cx, s, name)
                if (result === Scriptable.NOT_FOUND) throw notFoundError(s, name)
                thisObj = s
                break
            }
        }
        if (result !is Callable && isOptionalChainingCall && (result === Scriptable.NOT_FOUND || result == null || Undefined.isUndefined(result))) {
            return null
        }
        return LookupResult(result, thisObj, name)
    }

    private fun topScopeName(cx: Context, scope: Scriptable, name: String): Any? {
        var s = scope
        if (cx.useDynamicScope) s = checkDynamicScope(cx.topCallScope!!, s)
        return ScriptableObject.getProperty(s, name)
    }

    /** With dynamic scope on, a lookup that reached the static top may continue into the dynamic one. */
    internal fun checkDynamicScope(possibleDynamicScope: Scriptable, staticTopScope: Scriptable): Scriptable {
        if (possibleDynamicScope === staticTopScope) return possibleDynamicScope
        var proto: Scriptable? = possibleDynamicScope
        while (true) {
            proto = proto!!.prototype
            if (proto === staticTopScope) return possibleDynamicScope
            if (proto == null) return staticTopScope
        }
    }

    private fun storeScriptable(cx: Context, value: Scriptable?) {
        check(cx.scratchScriptable == null)
        cx.scratchScriptable = value
    }

    fun lastStoredScriptable(cx: Context): Scriptable? {
        val result = cx.scratchScriptable
        cx.scratchScriptable = null
        return result
    }

    fun typeofName(scope: Scriptable, id: String): String {
        val cx = Context.getContext()
        val v = bind(cx, scope, id) ?: return "undefined"
        return typeOf(getObjectProp(v, id, cx))
    }

    // ---- Function lookups for calls ------------------------------------------------------------

    /** What a call site needs: the callee and the `this` it goes with. */
    class LookupResult internal constructor(private val result: Any?, private val thisObj: Scriptable?, private val name: Any?) {
        fun getResult(): Any? = result
        fun getThis(): Scriptable? = thisObj
        fun getName(): String = name?.toString() ?: "null"
        fun getCallable(): Callable = result as? Callable ?: throw notFunctionError(result, name)
        fun call(cx: Context, scope: Scriptable, args: Array<Any?>): Any? = getCallable().call(cx, scope, thisObj, args)
    }

    /** Stands in for a missing method when the object has a `__noSuchMethod__` hook. */
    internal class NoSuchMethodShim(val noSuchMethodMethod: Callable, val methodName: String) : Callable {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            noSuchMethodMethod.call(cx, scope, thisObj, arrayOf(methodName, newArrayLiteral(args, null, cx, scope)))
    }

    fun getNameAndThis(name: String, cx: Context, scope: Scriptable): LookupResult? = getNameAndThisInner(name, cx, scope, false)

    fun getNameAndThisOptional(name: String, cx: Context, scope: Scriptable): LookupResult? = getNameAndThisInner(name, cx, scope, true)

    private fun getNameAndThisInner(name: String, cx: Context, scope: Scriptable, isOptionalChainingCall: Boolean): LookupResult? {
        val parent = scope.parentScope
        if (parent == null) {
            val result = topScopeName(cx, scope, name)
            if (result !is Callable) {
                if (isOptionalChainingCall && (result === Scriptable.NOT_FOUND || result == null || Undefined.isUndefined(result))) return null
                if (result === Scriptable.NOT_FOUND) throw notFoundError(scope, name)
            }
            return LookupResult(result, scope, name)
        }
        return nameOrFunction(cx, scope, parent, name, isOptionalChainingCall)
    }

    fun getElemAndThis(obj: Any?, elem: Any?, cx: Context, scope: Scriptable): LookupResult? = getElemAndThisInner(obj, elem, cx, scope, false)

    fun getElemAndThisOptional(obj: Any?, elem: Any?, cx: Context, scope: Scriptable): LookupResult? = getElemAndThisInner(obj, elem, cx, scope, true)

    private fun getElemAndThisInner(obj: Any?, elem: Any?, cx: Context, scope: Scriptable, isOptionalChainingCall: Boolean): LookupResult? {
        val thisObj: Scriptable
        val value: Any?
        if (isSymbol(elem)) {
            thisObj = toObjectOrNull(cx, obj, scope) ?: throw undefCallError(obj, elem.toString())
            value = ScriptableObject.getProperty(thisObj, elem as Symbol)
        } else {
            val s = toStringIdOrIndex(elem)
            if (s.stringId != null) return getPropAndThis(obj, s.stringId, cx, scope)
            thisObj = toObjectOrNull(cx, obj, scope) ?: throw undefCallError(obj, elem.toString())
            value = ScriptableObject.getProperty(thisObj, s.index)
        }
        if (value !is Callable && isOptionalChainingCall && (value === Scriptable.NOT_FOUND || value == null || Undefined.isUndefined(value))) {
            return null
        }
        return LookupResult(value, thisObj, elem.toString())
    }

    fun getPropAndThis(obj: Any?, property: String, cx: Context, scope: Scriptable): LookupResult? = getPropAndThisInner(obj, property, cx, scope, false)

    fun getPropAndThisOptional(obj: Any?, property: String, cx: Context, scope: Scriptable): LookupResult? = getPropAndThisInner(obj, property, cx, scope, true)

    private fun getPropAndThisInner(obj: Any?, property: String, cx: Context, scope: Scriptable, isOptionalChainingCall: Boolean): LookupResult? =
        getPropAndThisHelper(obj, property, cx, toObjectOrNull(cx, obj, scope), isOptionalChainingCall)

    private fun getPropAndThisHelper(obj: Any?, property: String, cx: Context, thisObj: Scriptable?, isOptionalChainingCall: Boolean): LookupResult? {
        if (thisObj == null) {
            if (isOptionalChainingCall) return null
            throw undefCallError(obj, property)
        }
        var value = ScriptableObject.getProperty(thisObj, property)
        if (value === Scriptable.NOT_FOUND) {
            val noSuchMethod = ScriptableObject.getProperty(thisObj, "__noSuchMethod__")
            if (noSuchMethod is Callable) value = NoSuchMethodShim(noSuchMethod, property)
        }
        if (value !is Callable && isOptionalChainingCall && (value === Scriptable.NOT_FOUND || value == null || Undefined.isUndefined(value))) {
            return null
        }
        return LookupResult(value, thisObj, property)
    }

    fun getValueAndThis(value: Any?, cx: Context): LookupResult? = getValueAndThisInner(value, cx, false)

    fun getValueAndThisOptional(value: Any?, cx: Context): LookupResult? = getValueAndThisInner(value, cx, true)

    private fun getValueAndThisInner(value: Any?, cx: Context, isOptionalChainingCall: Boolean): LookupResult? {
        if (value !is Callable) {
            if (isOptionalChainingCall && (value === Scriptable.NOT_FOUND || value == null || Undefined.isUndefined(value))) return null
            return LookupResult(value, null, value)
        }
        var thisObj: Scriptable? = if (value is Function) value.declarationScope else null
        if (thisObj == null) thisObj = cx.topCallScope ?: throw IllegalStateException()
        if (thisObj is NativeCall) thisObj = ScriptableObject.getTopLevelScope(thisObj)
        return LookupResult(value, thisObj, value)
    }

    fun getElemFunctionAndThis(obj: Any?, elem: Any?, cx: Context, scope: Scriptable): Callable? {
        val thisObj: Scriptable
        val value: Any?
        if (isSymbol(elem)) {
            thisObj = toObjectOrNull(cx, obj, scope) ?: throw undefCallError(obj, elem.toString())
            value = ScriptableObject.getProperty(thisObj, elem as Symbol)
        } else {
            val s = toStringIdOrIndex(elem)
            if (s.stringId != null) {
                val r = getPropAndThisHelper(obj, s.stringId, cx, toObjectOrNull(cx, obj, scope), false)!!
                val f = r.getResult()
                if (f !is Callable) throw notFunctionError(r.getThis(), f, s.stringId)
                storeScriptable(cx, r.getThis())
                return f
            }
            thisObj = toObjectOrNull(cx, obj, scope) ?: throw undefCallError(obj, elem.toString())
            value = ScriptableObject.getProperty(thisObj, s.index)
        }
        if (value !is Callable) throw notFunctionError(value, elem)
        storeScriptable(cx, thisObj)
        return value
    }

    /** Calls `obj[Symbol.iterator]()`. */
    fun callIterator(obj: Any?, cx: Context, scope: Scriptable): Any? {
        val getIterator = getElemFunctionAndThis(obj, SymbolKey.ITERATOR, cx, scope)!!
        val iterable = lastStoredScriptable(cx)
        return getIterator.call(cx, scope, iterable, emptyArgs)
    }

    fun callRef(function: Callable, thisObj: Scriptable?, args: Array<Any?>, cx: Context): Ref {
        if (function is RefCallable) {
            return function.refCall(cx, thisObj, args)
        }
        throw constructError("ReferenceError", getMessageById("msg.no.ref.from.function", toString(function)))
    }

    /** A call to `eval` or `With`, which get special treatment when they are the real ones. */
    fun callSpecial(cx: Context, fun_: Callable?, thisObj: Scriptable?, args: Array<Any?>, scope: Scriptable, callerThis: Scriptable?, callType: Int, filename: String?, lineNumber: Int, isOptionalChainingCall: Boolean): Any? {
        if (fun_ == null && isOptionalChainingCall) return Undefined.instance
        when (callType) {
            Node.SPECIALCALL_EVAL -> {
                if (thisObj!!.parentScope == null && NativeGlobal.isEvalFunction(fun_)) {
                    return evalSpecial(cx, scope, callerThis, args, filename, lineNumber)
                }
            }
            Node.SPECIALCALL_WITH -> {
                if (NativeWith.isWithFunction(fun_)) throw Context.reportRuntimeErrorById("msg.only.from.new", "With")
            }
            else -> throw Kit.codeBug()
        }
        return fun_!!.call(cx, scope, thisObj, args)
    }

    fun newSpecial(cx: Context, fun_: Any?, args: Array<Any?>, scope: Scriptable, callType: Int): Any? {
        when (callType) {
            Node.SPECIALCALL_EVAL -> if (NativeGlobal.isEvalFunction(fun_)) throw typeErrorById("msg.not.ctor", "eval")
            Node.SPECIALCALL_WITH -> if (NativeWith.isWithFunction(fun_)) return NativeWith.newWithSpecial(cx, scope, args)
            else -> throw Kit.codeBug()
        }
        return newObject(fun_, cx, scope, args)
    }

    /** Direct `eval`: compiles the string in the caller's scope. */
    fun evalSpecial(cx: Context, scope: Scriptable, thisArg: Any?, args: Array<Any?>, filenameIn: String?, lineNumberIn: Int): Any? {
        if (args.isEmpty()) return Undefined.instance
        val x = args[0]
        if (x !is CharSequence) {
            if (cx.hasFeature(Context.FEATURE_STRICT_MODE) || cx.hasFeature(Context.FEATURE_STRICT_EVAL)) {
                throw Context.reportRuntimeErrorById("msg.eval.nonstring.strict")
            }
            Context.reportWarning(getMessageById("msg.eval.nonstring"))
            return x
        }
        var filename = filenameIn
        var lineNumber = lineNumberIn
        if (filename == null) {
            val linep = IntArray(1)
            filename = Context.getSourcePositionFromStack(linep)
            if (filename != null) lineNumber = linep[0] else filename = ""
        }
        val sourceName = makeUrlForGeneratedScript(true, filename, lineNumber)
        val reporter = DefaultErrorReporter.forEval(cx.errorReporter)
        val evaluator = Context.createInterpreter()
        val homeObject = if (scope is NativeCall) scope.getHomeObject() else null
        val script = cx.compileString(x.toString(), evaluator, reporter, sourceName, 1, null) { compilerEnvs ->
            compilerEnvs.strictMode = cx.isStrictMode()
            val isInsideMethod = scope is NativeCall && scope.getHomeObject() != null
            compilerEnvs.allowSuper = isInsideMethod
            compilerEnvs.inEval = true
            compilerEnvs.setHomeObject(homeObject)
        }
        val thisObject = if (thisArg === Undefined.instance) Undefined.SCRIPTABLE_UNDEFINED else thisArg as Scriptable
        return script.exec(cx, scope, thisObject)
    }

    // ---- Increment and decrement -----------------------------------------------------------------

    fun nameIncrDecr(scopeChain: Scriptable, id: String, incrDecrMask: Int): Any? = nameIncrDecr(scopeChain, id, Context.getContext(), incrDecrMask)

    fun nameIncrDecr(scopeChainIn: Scriptable, id: String, cx: Context, incrDecrMask: Int): Any? {
        var scopeChain: Scriptable? = scopeChainIn
        var target: Scriptable?
        var value: Any?
        do {
            if (cx.useDynamicScope && scopeChain!!.parentScope == null) scopeChain = checkDynamicScope(cx.topCallScope!!, scopeChain)
            target = scopeChain
            do {
                value = target!!.get(id, scopeChain!!)
                if (value !== Scriptable.NOT_FOUND) return doScriptableIncrDecr(target, id, scopeChain, value, incrDecrMask)
                target = target.prototype
            } while (target != null)
            scopeChain = scopeChain!!.parentScope
        } while (scopeChain != null)
        throw notFoundError(null, id)
    }

    fun propIncrDecr(obj: Any?, id: String, cx: Context, incrDecrMask: Int): Any? = propIncrDecr(obj, id, cx, getTopCallScope(cx), incrDecrMask)

    fun propIncrDecr(obj: Any?, id: String, cx: Context, scope: Scriptable, incrDecrMask: Int): Any? {
        val start = asScriptableOrThrowUndefReadError(cx, scope, obj, id)
        var target: Scriptable? = start
        do {
            val value = target!!.get(id, start)
            if (value !== Scriptable.NOT_FOUND) return doScriptableIncrDecr(target, id, start, value, incrDecrMask)
            target = target.prototype
        } while (target != null)
        start.put(id, start, NaNobj)
        return NaNobj
    }

    private fun incrDecrResult(number: Number, incrDecrMask: Int): Number = when (number) {
        is KBigInt -> if ((incrDecrMask and Node.DECR_FLAG) == 0) number.add(KBigInt.ONE) else number.subtract(KBigInt.ONE)
        is Int -> if ((incrDecrMask and Node.DECR_FLAG) == 0) number + 1 else number - 1
        else -> if ((incrDecrMask and Node.DECR_FLAG) == 0) number.toDouble() + 1.0 else number.toDouble() - 1.0
    }

    private fun doScriptableIncrDecr(target: Scriptable, id: String, protoChainStart: Scriptable, value: Any?, incrDecrMask: Int): Any? {
        val post = (incrDecrMask and Node.POST_FLAG) != 0
        val number = if (value is Number) value else toNumeric(value)
        val result = incrDecrResult(number, incrDecrMask)
        target.put(id, protoChainStart, result)
        return if (post) number else result
    }

    fun elemIncrDecr(obj: Any?, index: Any?, cx: Context, incrDecrMask: Int): Any? = elemIncrDecr(obj, index, cx, getTopCallScope(cx), incrDecrMask)

    fun elemIncrDecr(obj: Any?, index: Any?, cx: Context, scope: Scriptable, incrDecrMask: Int): Any? {
        val value = getObjectElem(obj, index, cx, scope)
        val post = (incrDecrMask and Node.POST_FLAG) != 0
        val number = if (value is Number) value else toNumeric(value)
        val result = incrDecrResult(number, incrDecrMask)
        setObjectElem(obj, index, result, cx, scope)
        return if (post) number else result
    }

    fun refIncrDecr(ref: Ref, cx: Context, incrDecrMask: Int): Any? = refIncrDecr(ref, cx, getTopCallScope(cx), incrDecrMask)

    fun refIncrDecr(ref: Ref, cx: Context, scope: Scriptable, incrDecrMask: Int): Any? {
        val value = ref.get(cx)
        val post = (incrDecrMask and Node.POST_FLAG) != 0
        val number = if (value is Number) value else toNumeric(value)
        val result = incrDecrResult(number, incrDecrMask)
        ref.set(cx, scope, result)
        return if (post) number else result
    }

    // ---- Enumeration (for..in, for..of) -------------------------------------------------------

    const val ENUMERATE_KEYS = 0
    const val ENUMERATE_VALUES = 1
    const val ENUMERATE_ARRAY = 2
    const val ENUMERATE_KEYS_NO_ITERATOR = 3
    const val ENUMERATE_VALUES_NO_ITERATOR = 4
    const val ENUMERATE_ARRAY_NO_ITERATOR = 5
    const val ENUMERATE_VALUES_IN_ORDER = 6

    /** The state of one `for..in` or `for..of` loop. */
    private class IdEnumeration {
        var obj: Scriptable? = null
        var ids: Array<Any?>? = null
        var used: HashSet<Any?>? = null
        var currentId: Any? = null
        var index = 0
        var enumType = 0
        var enumNumbers = false
        var iterator: Scriptable? = null
    }

    fun enumInit(value: Any?, cx: Context, enumValues: Boolean): Any =
        enumInit(value, cx, if (enumValues) ENUMERATE_VALUES else ENUMERATE_KEYS)

    fun enumInit(value: Any?, cx: Context, enumType: Int): Any = enumInit(value, cx, getTopCallScope(cx), enumType)

    fun enumInit(value: Any?, cx: Context, scope: Scriptable, enumType: Int): Any {
        val x = IdEnumeration()
        x.obj = toObjectOrNull(cx, value, scope)
        if (enumType == ENUMERATE_VALUES_IN_ORDER) {
            x.enumType = enumType
            x.iterator = null
            return enumInitInOrder(cx, x)
        }
        if (x.obj == null) return x
        x.enumType = enumType
        x.iterator = null
        if (enumType != ENUMERATE_KEYS_NO_ITERATOR && enumType != ENUMERATE_VALUES_NO_ITERATOR && enumType != ENUMERATE_ARRAY_NO_ITERATOR) {
            x.iterator = toIterator(cx, x.obj!!, enumType == ENUMERATE_KEYS)
        }
        if (x.iterator == null) enumChangeObject(x)
        return x
    }

    private fun enumInitInOrder(cx: Context, x: IdEnumeration): Any {
        val obj = x.obj
        if (obj !is SymbolScriptable || !ScriptableObject.hasProperty(obj, SymbolKey.ITERATOR)) {
            throw typeErrorById("msg.not.iterable", toString(obj))
        }
        val iterator = ScriptableObject.getProperty(obj, SymbolKey.ITERATOR)
        if (iterator !is Callable) throw typeErrorById("msg.not.iterable", toString(obj))
        val scope = if (iterator is Function) iterator.declarationScope!! else cx.topCallScope!!
        val v = iterator.call(cx, scope, obj, emptyArgs)
        if (v !is Scriptable) throw typeErrorById("msg.not.iterable", toString(obj))
        x.iterator = v
        return x
    }

    /** The legacy `__iterator__` protocol. Null when the object does not use it. */
    fun toIterator(cx: Context, obj: Scriptable, keyOnly: Boolean): Scriptable? {
        if (ScriptableObject.hasProperty(obj, NativeIterator.ITERATOR_PROPERTY_NAME)) {
            val v = ScriptableObject.getProperty(obj, NativeIterator.ITERATOR_PROPERTY_NAME)
            if (v !is Function) throw typeErrorById("msg.invalid.iterator")
            val r = v.call(cx, v.declarationScope!!, obj, arrayOf(keyOnly))
            if (r !is Scriptable) throw typeErrorById("msg.iterator.primitive")
            return r
        }
        return null
    }

    fun enumNext(enumObj: Any?): Boolean = enumNext(enumObj, Context.getContext())

    fun enumNext(enumObj: Any?, cx: Context): Boolean {
        val x = enumObj as IdEnumeration
        val iterator = x.iterator
        if (iterator != null) {
            if (x.enumType == ENUMERATE_VALUES_IN_ORDER) return enumNextInOrder(x, cx)
            val v = ScriptableObject.getProperty(iterator, "next")
            if (v !is Callable) return false
            val scope = if (v is Function) v.declarationScope!! else cx.topCallScope!!
            try {
                x.currentId = v.call(cx, scope, iterator, emptyArgs)
                return true
            } catch (e: JavaScriptException) {
                if (e.value is NativeIterator.StopIteration) return false
                throw e
            }
        }
        while (true) {
            val obj = x.obj ?: return false
            val ids = x.ids!!
            if (x.index == ids.size) {
                x.obj = obj.prototype
                enumChangeObject(x)
                continue
            }
            val id = ids[x.index++]
            if (x.used?.contains(id) == true) continue
            if (id is Symbol) continue
            if (id is String) {
                // Deleted since the ids were taken.
                if (!obj.has(id, obj)) continue
                x.currentId = id
            } else {
                val intId = (id as Number).toInt()
                if (!obj.has(intId, obj)) continue
                x.currentId = if (x.enumNumbers) intId else intId.toString()
            }
            return true
        }
    }

    private fun enumNextInOrder(enumObj: IdEnumeration, cx: Context): Boolean {
        val iterator = enumObj.iterator!!
        val v = ScriptableObject.getProperty(iterator, ES6Iterator.NEXT_METHOD)
        if (v !is Callable) throw notFunctionError(iterator, ES6Iterator.NEXT_METHOD)
        val scope = if (v is Function) v.declarationScope!! else cx.topCallScope!!
        val r = v.call(cx, scope, iterator, emptyArgs)
        val iteratorResult = toObject(cx, scope, r)
        val done = ScriptableObject.getProperty(iteratorResult, ES6Iterator.DONE_PROPERTY)
        if (done !== Scriptable.NOT_FOUND && toBoolean(done)) return false
        enumObj.currentId = ScriptableObject.getProperty(iteratorResult, ES6Iterator.VALUE_PROPERTY)
        return true
    }

    fun enumId(enumObj: Any?, cx: Context): Any? {
        val x = enumObj as IdEnumeration
        if (x.iterator != null) return x.currentId
        return when (x.enumType) {
            ENUMERATE_KEYS, ENUMERATE_KEYS_NO_ITERATOR -> x.currentId
            ENUMERATE_VALUES, ENUMERATE_VALUES_NO_ITERATOR -> enumValue(enumObj, cx)
            ENUMERATE_ARRAY, ENUMERATE_ARRAY_NO_ITERATOR ->
                cx.newArray(ScriptableObject.getTopLevelScope(x.obj!!), arrayOf(x.currentId, enumValue(enumObj, cx)))
            else -> throw Kit.codeBug()
        }
    }

    fun enumValue(enumObj: Any?, cx: Context): Any? {
        val x = enumObj as IdEnumeration
        val obj = x.obj!!
        val id = x.currentId
        if (isSymbol(id)) return ScriptableObject.ensureSymbolScriptable(obj).get(id as Symbol, obj)
        val s = toStringIdOrIndex(id)
        return if (s.stringId == null) obj.get(s.index, obj) else obj.get(s.stringId, obj)
    }

    private fun enumChangeObject(x: IdEnumeration) {
        var ids: Array<Any?>? = null
        while (x.obj != null) {
            ids = x.obj!!.getIds()
            if (ids.isNotEmpty()) break
            x.obj = x.obj!!.prototype
        }
        val previous = x.ids
        if (x.obj != null && previous != null) {
            val used = x.used ?: HashSet<Any?>().also { x.used = it }
            for (p in previous) used.add(p)
        }
        x.ids = ids
        x.index = 0
    }

    // ---- Activations, scripts and scopes ------------------------------------------------------

    /** Puts a script's declared variables into [scope] before it runs. */
    fun initScript(execObj: ScriptOrFn<*>, thisObj: Scriptable?, cx: Context, scope: Scriptable, evalScript: Boolean) {
        if (cx.topCallScope == null) throw IllegalStateException()
        val desc = execObj.descriptor!!
        val varCount = desc.paramAndVarCount
        if (varCount != 0) {
            var varScope = scope
            while (varScope is NativeWith) varScope = varScope.parentScope!!
            for (i in varCount - 1 downTo 0) {
                val name = desc.getParamOrVarName(i)
                val isConst = desc.getParamOrVarConst(i)
                if (!ScriptableObject.hasProperty(scope, name)) {
                    if (isConst) {
                        ScriptableObject.defineConstProperty(varScope, name)
                    } else if (!evalScript) {
                        if (desc.hasFunctionNamed(name)) {
                            ScriptableObject.defineProperty(varScope, name, Undefined.instance, ScriptableObject.PERMANENT)
                        }
                    } else {
                        varScope.put(name, varScope, Undefined.instance)
                    }
                } else {
                    ScriptableObject.redefineProperty(scope, name, isConst)
                }
            }
        }
    }

    fun createFunctionActivation(funObj: JSFunction, cx: Context, scope: Scriptable, args: Array<Any?>?, isStrict: Boolean, argsHasRest: Boolean, requiresArgumentObject: Boolean = true): Scriptable =
        NativeCall(funObj, cx, scope, args, false, isStrict, argsHasRest, requiresArgumentObject)

    fun createArrowFunctionActivation(funObj: JSFunction, cx: Context, scope: Scriptable, args: Array<Any?>?, isStrict: Boolean, argsHasRest: Boolean, requiresArgumentObject: Boolean = true): Scriptable =
        NativeCall(funObj, cx, scope, args, true, isStrict, argsHasRest, requiresArgumentObject)

    fun enterActivationFunction(cx: Context, scope: Scriptable) {
        if (cx.topCallScope == null) throw IllegalStateException()
        val call = scope as NativeCall
        call.parentActivationCall = cx.currentActivationCall
        cx.currentActivationCall = call
    }

    fun exitActivationFunction(cx: Context) {
        val call = cx.currentActivationCall!!
        cx.currentActivationCall = call.parentActivationCall
        call.parentActivationCall = null
    }

    /** Puts a declared function into the scope it belongs to. */
    fun initFunction(cx: Context, scope: Scriptable, function: JSFunction, type: Int, fromEvalCode: Boolean) {
        if (type == FunctionNode.FUNCTION_STATEMENT) {
            val name = function.getFunctionName()
            if (name.isNotEmpty()) {
                if (!fromEvalCode) ScriptableObject.defineProperty(scope, name, function, ScriptableObject.PERMANENT)
                else scope.put(name, scope, function)
            }
        } else if (type == FunctionNode.FUNCTION_EXPRESSION_STATEMENT) {
            val name = function.getFunctionName()
            if (name.isNotEmpty()) {
                var s = scope
                while (s is NativeWith) s = s.parentScope!!
                s.put(name, s, function)
            }
        } else {
            throw Kit.codeBug()
        }
    }

    /** Builds the scope object a `catch` block runs in, with the caught value bound to its name. */
    fun newCatchScope(t: Throwable, lastCatchScope: Scriptable?, exceptionName: String?, cx: Context, scope: Scriptable): Scriptable {
        val obj: Any?
        val cacheObj: Boolean
        if (t is JavaScriptException) {
            cacheObj = false
            obj = t.value
        } else {
            cacheObj = true
            if (lastCatchScope != null) {
                // A second catch scope for the same throw reuses the error object.
                obj = (lastCatchScope as NativeObject).getAssociatedValue(t) ?: throw Kit.codeBug()
            } else {
                val re: RhinoException
                val type: TopLevel.NativeErrors
                val errorMsg: String?
                when (t) {
                    is EcmaError -> {
                        re = t
                        type = TopLevel.NativeErrors.valueOf(t.name)
                        errorMsg = t.errorMessage
                    }
                    is WrappedException -> {
                        re = t
                        type = TopLevel.NativeErrors.InternalError
                        errorMsg = t.wrappedException.message
                    }
                    is EvaluatorException -> {
                        re = t
                        type = TopLevel.NativeErrors.InternalError
                        errorMsg = t.message
                    }
                    else -> throw Kit.codeBug()
                }
                val sourceUri = re.sourceName ?: ""
                val line = re.lineNumber
                val args: Array<Any?> = if (line > 0) arrayOf(errorMsg, sourceUri, line) else arrayOf(errorMsg, sourceUri)
                // TODO(P3.8): NativeError gets the stack provider once it lands.
                obj = newNativeError(cx, scope, type, args)
            }
        }
        val catchScopeObject = NativeObject()
        if (exceptionName != null) catchScopeObject.defineProperty(exceptionName, obj, ScriptableObject.PERMANENT)
        if (cacheObj) catchScopeObject.associateValue(t, obj!!)
        return catchScopeObject
    }

    fun enterWith(obj: Any?, cx: Context, scope: Scriptable): Scriptable {
        val sobj = toObjectOrNull(cx, obj, scope) ?: throw typeErrorById("msg.undef.with", toString(obj))
        return NativeWith.create(scope, sobj)
    }

    fun leaveWith(scope: Scriptable): Scriptable = (scope as NativeWith).parentScope!!

    fun newBuiltinObject(cx: Context, scope: Scriptable, type: TopLevel.Builtins, args: Array<Any?>?): Scriptable {
        val top = ScriptableObject.getTopLevelScope(scope)
        val ctor = TopLevel.getBuiltinCtor(cx, top, type)!!
        return ctor.construct(cx, top, args ?: emptyArgs)
    }

    internal fun newNativeError(cx: Context, scope: Scriptable, type: TopLevel.NativeErrors, args: Array<Any?>?): Scriptable {
        val top = ScriptableObject.getTopLevelScope(scope)
        val ctor = TopLevel.getNativeErrorCtor(cx, top, type)!!
        return ctor.construct(cx, top, args ?: emptyArgs)
    }

    // ---- Literals ------------------------------------------------------------------------------

    /** Builds an array from a literal's values, with [skipIndices] naming the holes. */
    fun newArrayLiteral(objects: Array<Any?>, skipIndices: IntArray?, cx: Context, scope: Scriptable): Scriptable {
        val skipDensity = 2
        val count = objects.size
        val skipCount = skipIndices?.size ?: 0
        val length = count + skipCount
        if (length > 1 && skipCount * skipDensity < length) {
            // Dense enough to build straight from an element array, with NOT_FOUND for holes.
            val sparse: Array<Any?>
            if (skipCount == 0) {
                sparse = objects
            } else {
                sparse = arrayOfNulls(length)
                var skip = 0
                var j = 0
                for (i in 0 until length) {
                    if (skip != skipCount && skipIndices!![skip] == i) {
                        sparse[i] = Scriptable.NOT_FOUND
                        ++skip
                        continue
                    }
                    sparse[i] = objects[j++]
                }
            }
            return cx.newArray(scope, sparse)
        }
        val array = cx.newArray(scope, length)
        var skip = 0
        var j = 0
        for (i in 0 until length) {
            if (skip != skipCount && skipIndices!![skip] == i) {
                ++skip
                continue
            }
            array.put(i, array, objects[j++])
        }
        return array
    }

    fun newObjectLiteral(propertyIds: Array<Any?>?, propertyValues: Array<Any?>, getterSetters: IntArray?, cx: Context, scope: Scriptable): Scriptable {
        val obj = cx.newObject(scope)
        fillObjectLiteral(obj, propertyIds, propertyValues, getterSetters, cx, scope)
        return obj
    }

    fun fillObjectLiteral(obj: Scriptable, propertyIds: Array<Any?>?, propertyValues: Array<Any?>, getterSetters: IntArray?, cx: Context, scope: Scriptable) {
        val end = propertyIds?.size ?: 0
        for (i in 0 until end) {
            val id = propertyIds!![i]
            val getterSetter = getterSetters?.get(i) ?: 0
            val value = propertyValues[i]
            if (getterSetter == 0) {
                when {
                    id is Symbol -> (obj as SymbolScriptable).put(id, obj, value)
                    id is Int && id >= 0 -> obj.put(id, obj, value)
                    else -> {
                        val s = toStringIdOrIndex(id)
                        if (s.stringId == null) {
                            obj.put(s.index, obj, value)
                        } else {
                            val stringId = s.stringId
                            if (cx.languageVersion < Context.VERSION_ES6 && isSpecialProperty(stringId)) {
                                specialRef(obj, stringId, cx, scope).set(cx, scope, value)
                            } else if (cx.languageVersion >= Context.VERSION_ES6 && NativeObject.PROTO_PROPERTY == stringId) {
                                when {
                                    value == null -> obj.prototype = null
                                    value is JSFunction -> if (value.isShorthand) obj.put(stringId, obj, value) else NativeObject.js_protoSetter(obj, value)
                                    value is Scriptable -> NativeObject.js_protoSetter(obj, value)
                                }
                            } else {
                                obj.put(stringId, obj, value)
                            }
                        }
                    }
                }
            } else {
                val so = obj as ScriptableObject
                val getterOrSetter = value as Callable
                val isSetter = getterSetter == 1
                when {
                    isSymbol(id) -> so.setGetterOrSetter(id, 0, getterOrSetter, isSetter)
                    id is Int && id >= 0 -> so.setGetterOrSetter(null, id, getterOrSetter, isSetter)
                    else -> {
                        val s = toStringIdOrIndex(id)
                        so.setGetterOrSetter(s.stringId, if (s.index == -1) 0 else s.index, getterOrSetter, isSetter)
                    }
                }
            }
        }
    }

    fun wrapRegExp(cx: Context, scope: Scriptable, compiled: Any): Scriptable = checkRegExpProxy(cx).wrapRegExp(cx, scope, compiled)

    /** The frozen strings object a tagged template passes to its tag, built once per call site. */
    fun getTemplateLiteralCallSite(cx: Context, scope: Scriptable, strings: Array<Any?>, index: Int): Scriptable {
        val callsite = strings[index]
        if (callsite is Scriptable) return callsite
        @Suppress("UNCHECKED_CAST")
        val vals = callsite as Array<String?>
        check((vals.size and 1) == 0)
        val siteObj = cx.newArray(scope, vals.size ushr 1) as ScriptableObject
        val rawObj = cx.newArray(scope, vals.size ushr 1) as ScriptableObject
        siteObj.put("raw", siteObj, rawObj)
        siteObj.setAttributes("raw", ScriptableObject.DONTENUM)
        var i = 0
        while (i < vals.size) {
            val idx = i ushr 1
            siteObj.put(idx, siteObj, vals[i] ?: Undefined.instance)
            rawObj.put(idx, rawObj, vals[i + 1])
            i += 2
        }
        AbstractEcmaObjectOperations.setIntegrityLevel(cx, rawObj, AbstractEcmaObjectOperations.INTEGRITY_LEVEL.FROZEN)
        AbstractEcmaObjectOperations.setIntegrityLevel(cx, siteObj, AbstractEcmaObjectOperations.INTEGRITY_LEVEL.FROZEN)
        strings[index] = siteObj
        return siteObj
    }

    // Upstream passes the key as the message text here, so the text is the key. Copied as written.
    fun throwDeleteOnSuperPropertyNotAllowed(): Nothing = throw referenceError("msg.delete.super")
}
