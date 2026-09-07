/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ScriptableObject.Companion.DONTENUM
import io.github.yuroyami.kitejs.ScriptableObject.Companion.PERMANENT
import io.github.yuroyami.kitejs.ScriptableObject.Companion.READONLY

/** The global functions and values: `eval`, `parseInt`, `isNaN`, `NaN`, the error constructors and the rest. */
public object NativeGlobal {

    public fun init(cx: Context, scope: Scriptable, sealed: Boolean) {
        defineGlobalFunction(scope, sealed, "decodeURI", 1, ::js_decodeURI)
        defineGlobalFunction(scope, sealed, "decodeURIComponent", 1, ::js_decodeURIComponent)
        defineGlobalFunction(scope, sealed, "encodeURI", 1, ::js_encodeURI)
        defineGlobalFunction(scope, sealed, "encodeURIComponent", 1, ::js_encodeURIComponent)
        defineGlobalFunction(scope, sealed, "escape", 1, ::js_escape)
        defineGlobalFunction(scope, sealed, "isFinite", 1, ::js_isFinite)
        defineGlobalFunction(scope, sealed, "isNaN", 1, ::js_isNaN)
        defineGlobalFunction(scope, sealed, "isXMLName", 1, ::js_isXMLName)
        defineGlobalFunction(scope, sealed, "parseFloat", 1, ::js_parseFloat)
        defineGlobalFunction(scope, sealed, "parseInt", 2, ::js_parseInt)
        defineGlobalFunction(scope, sealed, "unescape", 1, ::js_unescape)
        defineGlobalFunction(scope, sealed, "uneval", 1, ::js_uneval)
        defineGlobalFunctionEval(scope, sealed)
        ScriptableObject.defineProperty(scope, "NaN", ScriptRuntime.NaNobj, READONLY or DONTENUM or PERMANENT)
        ScriptableObject.defineProperty(scope, "Infinity", Double.POSITIVE_INFINITY, READONLY or DONTENUM or PERMANENT)
        ScriptableObject.defineProperty(scope, "undefined", Undefined.instance, READONLY or DONTENUM or PERMANENT)
        ScriptableObject.defineProperty(scope, "globalThis", scope, DONTENUM)

        // Each error constructor gets its own Error object as a prototype, with the 'name'
        // property set to the name of the error.
        val nativeError = ScriptableObject.ensureScriptable(ScriptableObject.getProperty(scope, "Error"))
        val nativeErrorProto = ScriptableObject.ensureScriptable(ScriptableObject.getProperty(nativeError, "prototype"))
        for (error in TopLevel.NativeErrors.entries) {
            if (error == TopLevel.NativeErrors.Error) continue
            val name = error.name
            val topLevelScope = ScriptableObject.getTopLevelScope(scope)
            val builtinErrorCtor = TopLevel.getBuiltinCtor(cx, topLevelScope, TopLevel.Builtins.Error)!!
            val errorProto = NativeError.makeProto(topLevelScope, builtinErrorCtor)
            errorProto.defineProperty("name", name, DONTENUM)
            errorProto.defineProperty("message", "", DONTENUM)
            val ctor: BaseFunction
            if (error == TopLevel.NativeErrors.AggregateError) {
                val target = LateBoundErrorTarget(NativeError::makeAggregate)
                ctor = object : LambdaConstructor(scope, name, 2, target) {
                    override fun createObject(cx: Context, scope: Scriptable): Scriptable? = null
                }
                target.lateBoundCtor = ctor
            } else {
                val target = LateBoundErrorTarget(NativeError::make)
                ctor = LambdaConstructor(scope, name, 1, target)
                target.lateBoundCtor = ctor
            }
            ctor.setImmunePrototypeProperty(errorProto)
            ctor.prototype = nativeError
            errorProto.put("constructor", errorProto, ctor)
            errorProto.setAttributes("constructor", DONTENUM)
            errorProto.prototype = nativeErrorProto
            ctor.setAttributes("name", DONTENUM or READONLY)
            ctor.setAttributes("length", DONTENUM or READONLY)
            if (sealed) {
                errorProto.sealObject()
                ctor.sealObject()
            }
            ScriptableObject.defineProperty(scope, name, ctor, DONTENUM)
        }
    }

    /** The error constructors need their own function object as an argument, so it is filled in after construction. */
    private class LateBoundErrorTarget(
        private val make: (Context, Scriptable, Function, Array<Any?>) -> Scriptable,
    ) : SerializableConstructable {
        lateinit var lateBoundCtor: Function

        override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable =
            make(cx, scope, lateBoundCtor, args)
    }

    private fun defineGlobalFunction(scope: Scriptable, sealed: Boolean, name: String, length: Int, callable: SerializableCallable) {
        val fn = LambdaFunction(scope, name, length, null, callable)
        registerGlobalFunction(scope, sealed, name, fn)
    }

    private fun registerGlobalFunction(scope: Scriptable, sealed: Boolean, name: String, fn: LambdaFunction) {
        ScriptableObject.defineProperty(scope, name, fn, DONTENUM)
        if (sealed) fn.sealObject()
    }

    private fun defineGlobalFunctionEval(scope: Scriptable, sealed: Boolean) {
        val evalFun = EvalLambdaFunction(scope)
        registerGlobalFunction(scope, sealed, "eval", evalFun)
    }

    internal fun isEvalFunction(functionObj: Any?): Boolean = functionObj is EvalLambdaFunction

    private fun js_uneval(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        val value = if (args.isNotEmpty()) args[0] else Undefined.instance
        return ScriptRuntime.uneval(cx, scope, value)
    }

    private fun js_isXMLName(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        // E4X is not ported, so there is never an XML library in scope.
        throw Context.reportRuntimeErrorById("msg.XML.not.available")
    }

    private fun js_isNaN(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        if (args.isEmpty()) return true
        val d = ScriptRuntime.toNumber(args[0])
        return d.isNaN()
    }

    private fun js_isFinite(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        if (args.isEmpty()) return false
        return NativeNumber.isFinite(args[0])
    }

    private fun js_decodeURI(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        val str = ScriptRuntime.toString(args, 0)
        return decode(str, true)
    }

    private fun js_decodeURIComponent(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        val str = ScriptRuntime.toString(args, 0)
        return decode(str, false)
    }

    private fun js_encodeURI(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        val str = ScriptRuntime.toString(args, 0)
        return encode(str, true)
    }

    private fun js_encodeURIComponent(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        val str = ScriptRuntime.toString(args, 0)
        return encode(str, false)
    }

    internal fun js_parseInt(cx: Context?, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        val s = ScriptRuntime.toString(args, 0)
        var radix = ScriptRuntime.toInt32(args, 1)
        val len = s.length
        if (len == 0) return ScriptRuntime.NaNobj
        var negative = false
        var start = 0
        var c: Char
        do {
            c = s[start]
            if (!ScriptRuntime.isStrWhiteSpaceChar(c.code)) break
            start++
        } while (start < len)
        if (c == '+') {
            start++
        } else if (c == '-') {
            negative = true
            start++
        }
        val noRadix = -1
        if (radix == 0) {
            radix = noRadix
        } else if (radix < 2 || radix > 36) {
            return ScriptRuntime.NaNobj
        } else if (radix == 16 && len - start > 1 && s[start] == '0') {
            c = s[start + 1]
            if (c == 'x' || c == 'X') start += 2
        }
        if (radix == noRadix) {
            radix = 10
            if (len - start > 1 && s[start] == '0') {
                c = s[start + 1]
                if (c == 'x' || c == 'X') {
                    radix = 16
                    start += 2
                } else if (c in '0'..'9') {
                    if (cx == null || cx.languageVersion < Context.VERSION_1_5) {
                        radix = 8
                        start++
                    }
                }
            }
        }
        val d = ScriptRuntime.stringPrefixToNumber(s, start, radix)
        return if (negative) -d else d
    }

    internal fun js_parseFloat(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        if (args.isEmpty()) return ScriptRuntime.NaNobj
        var s = ScriptRuntime.toString(args[0])
        val len = s.length
        var start = 0
        var c: Char
        while (true) {
            if (start == len) return ScriptRuntime.NaNobj
            c = s[start]
            if (!ScriptRuntime.isStrWhiteSpaceChar(c.code)) break
            ++start
        }
        var i = start
        if (c == '+' || c == '-') {
            ++i
            if (i == len) return ScriptRuntime.NaNobj
            c = s[i]
        }
        if (c == 'I') {
            if (i + 8 <= len && s.regionMatches(i, "Infinity", 0, 8)) {
                return if (s[start] == '-') Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            }
            return ScriptRuntime.NaNobj
        }
        var decimal = -1
        var exponent = -1
        var exponentValid = false
        scan@ while (i < len) {
            when (s[i]) {
                '.' -> {
                    if (decimal != -1) break@scan
                    decimal = i
                }
                'e', 'E' -> {
                    if (exponent != -1) break@scan
                    if (i == len - 1) break@scan
                    exponent = i
                }
                '+', '-' -> {
                    if (exponent != i - 1) break@scan
                    if (i == len - 1) {
                        --i
                        break@scan
                    }
                }
                '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                    if (exponent != -1) exponentValid = true
                }
                else -> break@scan
            }
            i++
        }
        if (exponent != -1 && !exponentValid) i = exponent
        s = s.substring(start, i)
        return s.toDoubleOrNull() ?: ScriptRuntime.NaNobj
    }

    private fun js_escape(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        val urlXalphas = 1
        val urlXpalphas = 2
        val urlPath = 4
        val s = ScriptRuntime.toString(args, 0)
        var mask = urlXalphas or urlXpalphas or urlPath
        if (args.size > 1) {
            val d = ScriptRuntime.toNumber(args[1])
            mask = d.toInt()
            if (d.isNaN() || mask.toDouble() != d || 0 != (mask and (urlXalphas or urlXpalphas or urlPath).inv())) {
                throw Context.reportRuntimeErrorById("msg.bad.esc.mask")
            }
        }
        var sb: StringBuilder? = null
        val l = s.length
        for (k in 0 until l) {
            val c = s[k].code
            if (mask != 0 &&
                (
                    (c >= '0'.code && c <= '9'.code) ||
                        (c >= 'A'.code && c <= 'Z'.code) ||
                        (c >= 'a'.code && c <= 'z'.code) ||
                        c == '@'.code ||
                        c == '*'.code ||
                        c == '_'.code ||
                        c == '-'.code ||
                        c == '.'.code ||
                        (0 != (mask and urlPath) && (c == '/'.code || c == '+'.code))
                    )
            ) {
                sb?.append(c.toChar())
            } else {
                if (sb == null) {
                    sb = StringBuilder(l + 3)
                    sb.append(s)
                    sb.setLength(k)
                }
                val hexSize: Int
                if (c < 256) {
                    if (c == ' '.code && mask == urlXpalphas) {
                        sb.append('+')
                        continue
                    }
                    sb.append('%')
                    hexSize = 2
                } else {
                    sb.append('%')
                    sb.append('u')
                    hexSize = 4
                }
                var shift = (hexSize - 1) * 4
                while (shift >= 0) {
                    val digit = 0xf and (c shr shift)
                    val hc = if (digit < 10) '0'.code + digit else 'A'.code - 10 + digit
                    sb.append(hc.toChar())
                    shift -= 4
                }
            }
        }
        return sb?.toString() ?: s
    }

    private fun js_unescape(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        var s = ScriptRuntime.toString(args, 0)
        val firstEscapePos = s.indexOf('%')
        if (firstEscapePos >= 0) {
            val l = s.length
            val buf = s.toCharArray()
            var destination = firstEscapePos
            var k = firstEscapePos
            while (k != l) {
                var c = buf[k]
                ++k
                if (c == '%' && k != l) {
                    val end: Int
                    val start: Int
                    if (buf[k] == 'u') {
                        start = k + 1
                        end = k + 5
                    } else {
                        start = k
                        end = k + 2
                    }
                    if (end <= l) {
                        var x = 0
                        for (i in start until end) {
                            x = Kit.xDigitToInt(buf[i].code, x)
                        }
                        if (x >= 0) {
                            c = x.toChar()
                            k = end
                        }
                    }
                }
                buf[destination] = c
                ++destination
            }
            s = buf.concatToString(0, destination)
        }
        return s
    }

    private fun js_eval(cx: Context, scope: Scriptable, args: Array<Any?>): Any? {
        val global = ScriptableObject.getTopLevelScope(scope)
        return ScriptRuntime.evalSpecial(cx, global, global, args, "eval code", 1)
    }

    private fun encode(str: String, fullUri: Boolean): String {
        var utf8buf: ByteArray? = null
        var sb: StringBuilder? = null
        val length = str.length
        var k = 0
        while (k != length) {
            val c = str[k]
            if (encodeUnescaped(c, fullUri)) {
                sb?.append(c)
            } else {
                if (sb == null) {
                    sb = StringBuilder(length + 3)
                    sb.append(str)
                    sb.setLength(k)
                    utf8buf = ByteArray(6)
                }
                if (c.code in 0xDC00..0xDFFF) {
                    throw uriError()
                }
                val v: Int
                if (c.code < 0xD800 || 0xDBFF < c.code) {
                    v = c.code
                } else {
                    k++
                    if (k == length) throw uriError()
                    val c2 = str[k]
                    if (c2.code !in 0xDC00..0xDFFF) throw uriError()
                    v = ((c.code - 0xD800) shl 10) + (c2.code - 0xDC00) + 0x10000
                }
                val l = oneUcs4ToUtf8Char(utf8buf!!, v)
                for (j in 0 until l) {
                    val d = 0xff and utf8buf[j].toInt()
                    sb.append('%')
                    sb.append(toHexChar(d ushr 4))
                    sb.append(toHexChar(d and 0xf))
                }
            }
            ++k
        }
        return sb?.toString() ?: str
    }

    private fun toHexChar(i: Int): Char {
        if (i shr 4 != 0) throw Kit.codeBug()
        return (if (i < 10) i + '0'.code else i - 10 + 'A'.code).toChar()
    }

    private fun unHex(c: Char): Int = when (c) {
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        in '0'..'9' -> c - '0'
        else -> -1
    }

    private fun unHex(c1: Char, c2: Char): Int {
        val i1 = unHex(c1)
        val i2 = unHex(c2)
        if (i1 >= 0 && i2 >= 0) return (i1 shl 4) or i2
        return -1
    }

    private fun decode(str: String, fullUri: Boolean): String {
        var buf: CharArray? = null
        var bufTop = 0
        val length = str.length
        var k = 0
        while (k != length) {
            var c = str[k]
            if (c != '%') {
                if (buf != null) buf[bufTop++] = c
                ++k
            } else {
                if (buf == null) {
                    buf = CharArray(length)
                    str.toCharArray(buf, 0, 0, k)
                    bufTop = k
                }
                val start = k
                if (k + 3 > length) throw uriError()
                var b = unHex(str[k + 1], str[k + 2])
                if (b < 0) throw uriError()
                k += 3
                if ((b and 0x80) == 0) {
                    c = b.toChar()
                } else {
                    val utf8Tail: Int
                    var ucs4Char: Int
                    val minUcs4Char: Int
                    if ((b and 0xC0) == 0x80) {
                        throw uriError()
                    } else if ((b and 0x20) == 0) {
                        utf8Tail = 1
                        ucs4Char = b and 0x1F
                        minUcs4Char = 0x80
                    } else if ((b and 0x10) == 0) {
                        utf8Tail = 2
                        ucs4Char = b and 0x0F
                        minUcs4Char = 0x800
                    } else if ((b and 0x08) == 0) {
                        utf8Tail = 3
                        ucs4Char = b and 0x07
                        minUcs4Char = 0x10000
                    } else if ((b and 0x04) == 0) {
                        utf8Tail = 4
                        ucs4Char = b and 0x03
                        minUcs4Char = 0x200000
                    } else if ((b and 0x02) == 0) {
                        utf8Tail = 5
                        ucs4Char = b and 0x01
                        minUcs4Char = 0x4000000
                    } else {
                        throw uriError()
                    }
                    if (k + 3 * utf8Tail > length) throw uriError()
                    for (j in 0 until utf8Tail) {
                        if (str[k] != '%') throw uriError()
                        b = unHex(str[k + 1], str[k + 2])
                        if (b < 0 || (b and 0xC0) != 0x80) throw uriError()
                        ucs4Char = (ucs4Char shl 6) or (b and 0x3F)
                        k += 3
                    }
                    if (ucs4Char < minUcs4Char || (ucs4Char in 0xD800..0xDFFF)) {
                        ucs4Char = INVALID_UTF8
                    } else if (ucs4Char == 0xFFFE || ucs4Char == 0xFFFF) {
                        ucs4Char = 0xFFFD
                    }
                    if (ucs4Char >= 0x10000) {
                        ucs4Char -= 0x10000
                        if (ucs4Char > 0xFFFFF) throw uriError()
                        val h = ((ucs4Char ushr 10) + 0xD800).toChar()
                        c = ((ucs4Char and 0x3FF) + 0xDC00).toChar()
                        buf[bufTop++] = h
                    } else {
                        c = ucs4Char.toChar()
                    }
                }
                if (fullUri && URI_DECODE_RESERVED.indexOf(c) >= 0) {
                    for (x in start until k) {
                        buf[bufTop++] = str[x]
                    }
                } else {
                    buf[bufTop++] = c
                }
            }
        }
        return buf?.concatToString(0, bufTop) ?: str
    }

    private fun encodeUnescaped(c: Char, fullUri: Boolean): Boolean {
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9') return true
        if ("-_.!~*'()".indexOf(c) >= 0) return true
        if (fullUri) return URI_DECODE_RESERVED.indexOf(c) >= 0
        return false
    }

    private fun uriError(): EcmaError =
        ScriptRuntime.constructError("URIError", ScriptRuntime.getMessageById("msg.bad.uri"))

    private const val URI_DECODE_RESERVED = ";/?:@&=+$,#"
    private const val INVALID_UTF8 = Int.MAX_VALUE

    private fun oneUcs4ToUtf8Char(utf8Buffer: ByteArray, ucs4CharIn: Int): Int {
        var ucs4Char = ucs4CharIn
        var utf8Length = 1
        if ((ucs4Char and 0x7F.inv()) == 0) {
            utf8Buffer[0] = ucs4Char.toByte()
        } else {
            var a = ucs4Char ushr 11
            utf8Length = 2
            while (a != 0) {
                a = a ushr 5
                utf8Length++
            }
            var i = utf8Length
            while (--i > 0) {
                utf8Buffer[i] = ((ucs4Char and 0x3F) or 0x80).toByte()
                ucs4Char = ucs4Char ushr 6
            }
            utf8Buffer[0] = (0x100 - (1 shl (8 - utf8Length)) + ucs4Char).toByte()
        }
        return utf8Length
    }

    /** The real `eval`. The runtime recognises it by type, so it cannot be faked by a script. */
    private class EvalLambdaFunction(scope: Scriptable) : LambdaFunction(
        scope,
        "eval",
        1,
        null,
        SerializableCallable { callCx, callScope, _, args -> js_eval(callCx, callScope, args) },
    )
}
