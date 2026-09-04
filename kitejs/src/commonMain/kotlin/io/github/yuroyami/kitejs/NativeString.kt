/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ScriptRuntimeES6.requireObjectCoercible
import io.github.yuroyami.kitejs.ScriptableObject.DescriptorInfo

/**
 * The JavaScript `String` wrapper object and the `String.prototype` methods.
 *
 * The regular-expression methods (`match`, `search`, `replace` with a pattern, `split` with a
 * pattern, `matchAll`) go through the [RegExpProxy], which phase 4 installs.
 */
class NativeString internal constructor(private val string: CharSequence) : ScriptableObject() {

    init {
        defineProperty("length", { string.length }, null, DONTENUM or READONLY or PERMANENT)
    }

    override val className: String
        get() = CLASS_NAME

    fun toCharSequence(): CharSequence = string

    override fun toString(): String = if (string is String) string else string.toString()

    internal val length: Int
        get() = string.length

    /* Make array-style access to string characters work. */
    override fun get(index: Int, start: Scriptable): Any? {
        if (index >= 0 && index < string.length) {
            return string[index].toString()
        }
        return super.get(index, start)
    }

    override fun put(index: Int, start: Scriptable, value: Any?) {
        if (index >= 0 && index < string.length) return
        super.put(index, start, value)
    }

    override fun has(index: Int, start: Scriptable): Boolean {
        if (index >= 0 && index < string.length) return true
        return super.has(index, start)
    }

    override fun getAttributes(index: Int): Int {
        if (index >= 0 && index < string.length) {
            var attribs = READONLY or PERMANENT
            if (Context.getContext().languageVersion < Context.VERSION_ES6) {
                attribs = attribs or DONTENUM
            }
            return attribs
        }
        return super.getAttributes(index)
    }

    override fun getIds(map: CompoundOperationMap, getNonEnumerable: Boolean, getSymbols: Boolean): Array<Any?> {
        val cx = Context.getCurrentContext()
        if (cx != null && cx.languageVersion >= Context.VERSION_ES6) {
            // In ES6 and up, the character indexes are own, enumerable keys.
            val sids = super.getIds(map, getNonEnumerable, getSymbols)
            val a = arrayOfNulls<Any?>(sids.size + string.length)
            for (i in 0 until string.length) a[i] = i
            sids.copyInto(a, string.length, 0, sids.size)
            return a
        }
        return super.getIds(map, getNonEnumerable, getSymbols)
    }

    override fun getOwnPropertyDescriptor(cx: Context, id: Any?): DescriptorInfo? {
        if (id !is Symbol && cx.languageVersion >= Context.VERSION_ES6) {
            val s = ScriptRuntime.toStringIdOrIndex(id)
            if (s.stringId == null && s.index >= 0 && s.index < string.length) {
                val value = string[s.index].toString()
                return defaultIndexPropertyDescriptor(value)
            }
        }
        return super.getOwnPropertyDescriptor(cx, id)
    }

    private fun defaultIndexPropertyDescriptor(value: Any?): DescriptorInfo =
        DescriptorInfo(true, false, false, Scriptable.NOT_FOUND, Scriptable.NOT_FOUND, value)

    companion object {
        private const val CLASS_NAME = "String"

        internal fun init(scope: Scriptable, sealed: Boolean) {
            val c = LambdaConstructor(scope, CLASS_NAME, 1, ::js_constructorFunc, ::js_constructor)
            c.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            c.setPrototypeScriptable(NativeString(""))
            defConsMethod(c, scope, "fromCharCode", 1, ::js_fromCharCode)
            defConsMethod(c, scope, "fromCodePoint", 1, ::js_fromCodePoint)
            defConsMethod(c, scope, "raw", 1, ::js_raw)
            defConsMethod(c, scope, "charAt", 1, wrapConstructor(::js_charAt))
            defConsMethod(c, scope, "charCodeAt", 1, wrapConstructor(::js_charCodeAt))
            defConsMethod(c, scope, "indexOf", 2, wrapConstructor(::js_indexOf))
            defConsMethod(c, scope, "lastIndexOf", 2, wrapConstructor(::js_lastIndexOf))
            defConsMethod(c, scope, "split", 3, wrapConstructor(::js_split))
            defConsMethod(c, scope, "substring", 3, wrapConstructor(::js_substring))
            defConsMethod(c, scope, "toLowerCase", 1, wrapConstructor(::js_toLowerCase))
            defConsMethod(c, scope, "toUpperCase", 1, wrapConstructor(::js_toUpperCase))
            defConsMethod(c, scope, "substr", 3, wrapConstructor(::js_substr))
            defConsMethod(c, scope, "concat", 2, wrapConstructor(::js_concat))
            defConsMethod(c, scope, "slice", 3, wrapConstructor(::js_slice))
            defConsMethod(c, scope, "equalsIgnoreCase", 2, wrapConstructor(::js_equalsIgnoreCase))
            defConsMethod(c, scope, "match", 2, wrapConstructor(::js_match))
            defConsMethod(c, scope, "search", 2, wrapConstructor(::js_search))
            defConsMethod(c, scope, "replace", 2, wrapConstructor(::js_replace))
            defConsMethod(c, scope, "replaceAll", 2, wrapConstructor(::js_replaceAll))
            defConsMethod(c, scope, "localeCompare", 2, wrapConstructor(::js_localeCompare))
            defConsMethod(c, scope, "toLocaleLowerCase", 1, wrapConstructor(::js_toLocaleLowerCase))
            defProtoMethod(c, scope, SymbolKey.ITERATOR, 0, ::js_iterator)
            defProtoMethod(c, scope, "toString", 0, ::js_toString)
            defProtoMethod(c, scope, "toSource", 0, ::js_toSource)
            defProtoMethod(c, scope, "valueOf", 0, ::js_toString)
            defProtoMethod(c, scope, "charAt", 1, ::js_charAt)
            defProtoMethod(c, scope, "charCodeAt", 1, ::js_charCodeAt)
            defProtoMethod(c, scope, "indexOf", 1, ::js_indexOf)
            defProtoMethod(c, scope, "lastIndexOf", 1, ::js_lastIndexOf)
            defProtoMethod(c, scope, "split", 2, ::js_split)
            defProtoMethod(c, scope, "substring", 2, ::js_substring)
            defProtoMethod(c, scope, "toLowerCase", 0, ::js_toLowerCase)
            defProtoMethod(c, scope, "toUpperCase", 0, ::js_toUpperCase)
            defProtoMethod(c, scope, "substr", 2, ::js_substr)
            defProtoMethod(c, scope, "concat", 1, ::js_concat)
            defProtoMethod(c, scope, "slice", 2, ::js_slice)
            defProtoMethod(c, scope, "bold", 0, ::js_bold)
            defProtoMethod(c, scope, "italics", 0, ::js_italics)
            defProtoMethod(c, scope, "fixed", 0, ::js_fixed)
            defProtoMethod(c, scope, "strike", 0, ::js_strike)
            defProtoMethod(c, scope, "small", 0, ::js_small)
            defProtoMethod(c, scope, "big", 0, ::js_big)
            defProtoMethod(c, scope, "blink", 0, ::js_blink)
            defProtoMethod(c, scope, "sup", 0, ::js_sup)
            defProtoMethod(c, scope, "sub", 0, ::js_sub)
            defProtoMethod(c, scope, "fontsize", 0, ::js_fontsize)
            defProtoMethod(c, scope, "fontcolor", 0, ::js_fontcolor)
            defProtoMethod(c, scope, "link", 0, ::js_link)
            defProtoMethod(c, scope, "anchor", 0, ::js_anchor)
            defProtoMethod(c, scope, "equals", 1, ::js_equals)
            defProtoMethod(c, scope, "equalsIgnoreCase", 1, ::js_equalsIgnoreCase)
            defProtoMethod(c, scope, "match", 1, ::js_match)
            defProtoMethod(c, scope, "matchAll", 1, ::js_matchAll)
            defProtoMethod(c, scope, "search", 1, ::js_search)
            defProtoMethod(c, scope, "replace", 2, ::js_replace)
            defProtoMethod(c, scope, "replaceAll", 2, ::js_replaceAll)
            defProtoMethod(c, scope, "at", 1, ::js_at)
            defProtoMethod(c, scope, "localeCompare", 1, ::js_localeCompare)
            defProtoMethod(c, scope, "toLocaleLowerCase", 0, ::js_toLocaleLowerCase)
            defProtoMethod(c, scope, "toLocaleUpperCase", 0, ::js_toLocaleUpperCase)
            defProtoMethod(c, scope, "trim", 0, ::js_trim)
            defProtoMethod(c, scope, "trimLeft", 0, ::js_trimLeft)
            defProtoMethod(c, scope, "trimStart", 0, ::js_trimLeft)
            defProtoMethod(c, scope, "trimRight", 0, ::js_trimRight)
            defProtoMethod(c, scope, "trimEnd", 0, ::js_trimRight)
            defProtoMethod(c, scope, "includes", 1, ::js_includes)
            defProtoMethod(c, scope, "startsWith", 1, ::js_startsWith)
            defProtoMethod(c, scope, "endsWith", 1, ::js_endsWith)
            defProtoMethod(c, scope, "normalize", 0, ::js_normalize)
            defProtoMethod(c, scope, "repeat", 1, ::js_repeat)
            defProtoMethod(c, scope, "codePointAt", 1, ::js_codePointAt)
            defProtoMethod(c, scope, "padStart", 1, ::js_padStart)
            defProtoMethod(c, scope, "padEnd", 1, ::js_padEnd)
            defProtoMethod(c, scope, "isWellFormed", 0, ::js_isWellFormed)
            defProtoMethod(c, scope, "toWellFormed", 0, ::js_toWellFormed)
            if (sealed) {
                c.sealObject()
                (c.prototypeProperty as NativeString).sealObject()
            }
            defineProperty(scope, CLASS_NAME, c, DONTENUM)
        }

        private fun defConsMethod(c: LambdaConstructor, scope: Scriptable, name: String, length: Int, target: SerializableCallable) {
            c.defineConstructorMethod(scope, name, length, target)
        }

        private fun defProtoMethod(c: LambdaConstructor, scope: Scriptable, key: SymbolKey, length: Int, target: SerializableCallable) {
            c.definePrototypeMethod(scope, key, length, target)
        }

        private fun defProtoMethod(c: LambdaConstructor, scope: Scriptable, name: String, length: Int, target: SerializableCallable) {
            c.definePrototypeMethod(scope, name, length, target)
        }

        /** The non-standard `String.charAt(s, i)` style statics: the first argument is `this`. */
        private fun wrapConstructor(target: SerializableCallable): SerializableCallable =
            SerializableCallable { cx, scope, origThis, origArgs ->
                val thisObj: Scriptable
                val newArgs: Array<Any?>
                if (origArgs.isNotEmpty()) {
                    thisObj = ScriptRuntime.toObject(cx, scope, ScriptRuntime.toCharSequence(origArgs[0]))
                    newArgs = origArgs.copyOfRange(1, origArgs.size)
                } else {
                    thisObj = ScriptRuntime.toObject(cx, scope, ScriptRuntime.toCharSequence(origThis))
                    newArgs = origArgs
                }
                target.call(cx, scope, thisObj, newArgs)
            }

        private fun js_constructor(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
            val s: CharSequence = if (args.isEmpty()) "" else ScriptRuntime.toCharSequence(args[0])
            return NativeString(s)
        }

        private fun js_constructorFunc(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val s: CharSequence = if (args.isEmpty()) {
                ""
            } else if (ScriptRuntime.isSymbol(args[0])) {
                args[0].toString()
            } else {
                ScriptRuntime.toCharSequence(args[0])
            }
            return if (s is String) s else s.toString()
        }

        private fun js_fromCharCode(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val n = args.size
            if (n < 1) return ""
            val chars = CharArray(n)
            for (i in 0 until n) {
                chars[i] = ScriptRuntime.toUint16(args[i])
            }
            return chars.concatToString()
        }

        private fun js_fromCodePoint(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val n = args.size
            if (n < 1) return ""
            val sb = StringBuilder()
            for (i in 0 until n) {
                val arg = args[i]
                val codePoint = ScriptRuntime.toInt32(arg)
                val num = ScriptRuntime.toNumber(arg)
                if (!ScriptRuntime.eqNumber(num, codePoint) || codePoint !in 0..0x10FFFF) {
                    throw ScriptRuntime.rangeError("Invalid code point " + ScriptRuntime.toString(arg))
                }
                sb.append(Characters.codePointToString(codePoint))
            }
            return sb.toString()
        }

        private fun js_charAt(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            charAt(cx, thisObj, args, false)

        private fun js_charCodeAt(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            charAt(cx, thisObj, args, true)

        private fun charAt(cx: Context, thisObj: Scriptable?, args: Array<Any?>, getCode: Boolean): Any? {
            val target = ScriptRuntime.toCharSequence(requireObjectCoercible(cx, thisObj, CLASS_NAME, "charAt"))
            val pos = ScriptRuntime.toInteger(args, 0)
            if (pos < 0 || pos >= target.length) {
                if (!getCode) return ""
                return ScriptRuntime.NaNobj
            }
            val c = target[pos.toInt()]
            if (!getCode) return c.toString()
            return ScriptRuntime.wrapInt(c.code)
        }

        private fun js_indexOf(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val target = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "indexOf"))
            val searchStr = ScriptRuntime.toString(args, 0)
            var position = ScriptRuntime.toInteger(args, 1)
            if (searchStr.isEmpty()) {
                return if (position > target.length) target.length else position.toInt()
            }
            if (position > target.length) return -1
            if (position < 0) position = 0.0
            return target.indexOf(searchStr, position.toInt())
        }

        private fun js_startsWith(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val target = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "startsWith"))
            checkValidRegex(cx, args, 0, "startsWith")
            val searchStr = ScriptRuntime.toString(args, 0)
            var position = ScriptRuntime.toInteger(args, 1)
            if (position < 0) position = 0.0 else if (position > target.length) position = target.length.toDouble()
            return target.startsWith(searchStr, position.toInt())
        }

        private fun js_endsWith(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val target = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "endsWith"))
            checkValidRegex(cx, args, 0, "endsWith")
            val searchStr = ScriptRuntime.toString(args, 0)
            var position = ScriptRuntime.toInteger(args, 1)
            if (position < 0) position = 0.0 else if (position.isNaN() || position > target.length) position = target.length.toDouble()
            if (args.isEmpty() || args.size == 1 || (args.size == 2 && Undefined.isUndefined(args[1]))) position = target.length.toDouble()
            return target.substring(0, position.toInt()).endsWith(searchStr)
        }

        private fun js_includes(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val target = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "includes"))
            val searchStr = ScriptRuntime.toString(args, 0)
            checkValidRegex(cx, args, 0, "includes")
            val position = ScriptRuntime.toInteger(args, 1).toInt()
            return target.indexOf(searchStr, maxOf(position, 0)) != -1
        }

        private fun checkValidRegex(cx: Context, args: Array<Any?>, pos: Int, functionName: String) {
            if (args.size > pos && args[pos] is Scriptable) {
                val reProxy = ScriptRuntime.getRegExpProxy(cx)
                if (reProxy != null) {
                    val arg = args[pos] as Scriptable
                    if (reProxy.isRegExp(arg)) {
                        if (isTrue(getProperty(arg, SymbolKey.MATCH))) {
                            throw ScriptRuntime.typeErrorById("msg.first.arg.not.regexp", CLASS_NAME, functionName)
                        }
                    }
                }
            }
        }

        private fun js_split(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = requireObjectCoercible(cx, thisObj, CLASS_NAME, "split")
            if (cx.languageVersion <= Context.VERSION_1_8) {
                return ScriptRuntime.checkRegExpProxy(cx).js_split(cx, scope, ScriptRuntime.toString(o), args)
            }
            val separator = if (args.isNotEmpty()) args[0] else Undefined.instance
            val limit = if (args.size > 1) args[1] else Undefined.instance
            if (!Undefined.isUndefined(separator) && separator != null) {
                val splitter = ScriptRuntime.getObjectElem(separator, SymbolKey.SPLIT, cx, scope)
                if (splitter != null && !Undefined.isUndefined(splitter)) {
                    if (splitter !is Callable) {
                        throw ScriptRuntime.notFunctionError(separator, splitter, SymbolKey.SPLIT.name)
                    }
                    return splitter.call(
                        cx,
                        scope,
                        ScriptRuntime.toObject(scope, separator),
                        arrayOf(if (o is NativeString) o.string else o, limit),
                    )
                }
            }
            val s = ScriptRuntime.toString(o)
            val lim: Long = if (Undefined.isUndefined(limit)) Int.MAX_VALUE.toLong() else ScriptRuntime.toUint32(ScriptRuntime.toNumber(limit))
            val r = ScriptRuntime.toString(separator)
            if (lim == 0L) return cx.newArray(scope, 0)
            if (Undefined.isUndefined(separator)) return cx.newArray(scope, arrayOf<Any?>(s))
            val separatorLength = r.length
            if (separatorLength == 0) {
                val strLen = s.length
                val outLen = ScriptRuntime.clamp(lim.toInt(), 0, strLen)
                val head = s.substring(0, outLen)
                val codeUnits = ArrayList<Any?>()
                var i = 0
                while (i < head.length) {
                    val c = head[i]
                    codeUnits.add(c.toString())
                    i += 1
                }
                return cx.newArray(scope, codeUnits.toTypedArray())
            }
            if (s.isEmpty()) return cx.newArray(scope, arrayOf<Any?>(s))
            val substrings = ArrayList<Any?>()
            var i = 0
            var j = s.indexOf(r)
            while (j != -1) {
                val t = s.substring(i, j)
                substrings.add(t)
                if (substrings.size >= lim) {
                    return cx.newArray(scope, substrings.toTypedArray())
                }
                i = j + separatorLength
                j = s.indexOf(r, i)
            }
            val t = s.substring(i)
            substrings.add(t)
            return cx.newArray(scope, substrings.toTypedArray())
        }

        private fun realThis(thisObj: Scriptable?): NativeString =
            LambdaConstructor.convertThisObject<NativeString>(thisObj)

        private fun js_iterator(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            NativeStringIterator(scope, requireObjectCoercible(cx, thisObj, CLASS_NAME, "[Symbol.iterator]"))

        private fun js_toString(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val cs = realThis(thisObj).string
            return if (cs is String) cs else cs.toString()
        }

        private fun js_toSource(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val s = realThis(thisObj).string
            return "(new String(\"" + ScriptRuntime.escapeString(s.toString()) + "\"))"
        }

        private fun tagify(cx: Context, thisObj: Scriptable?, functionName: String, tag: String, attribute: String?, args: Array<Any?>): String {
            val str = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, functionName))
            val result = StringBuilder()
            result.append('<').append(tag)
            if (!attribute.isNullOrEmpty()) {
                var attributeValue = ScriptRuntime.toString(args, 0)
                attributeValue = attributeValue.replace("\"", "&quot;")
                result.append(' ').append(attribute).append("=\"").append(attributeValue).append('"')
            }
            result.append('>').append(str).append("</").append(tag).append('>')
            return result.toString()
        }

        private fun js_match(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = requireObjectCoercible(cx, thisObj, CLASS_NAME, "match")
            val regexp = if (args.isNotEmpty()) args[0] else Undefined.instance
            val regExpProxy = ScriptRuntime.checkRegExpProxy(cx)
            if (regexp != null && !Undefined.isUndefined(regexp)) {
                val matcher = ScriptRuntime.getObjectElem(regexp, SymbolKey.MATCH, cx, scope)
                if (matcher != null && !Undefined.isUndefined(matcher)) {
                    if (matcher !is Callable) {
                        throw ScriptRuntime.notFunctionError(regexp, matcher, SymbolKey.MATCH.name)
                    }
                    return matcher.call(cx, scope, ScriptRuntime.toObject(scope, regexp), arrayOf(o))
                }
            }
            val s = ScriptRuntime.toString(o)
            val regexpToString = if (Undefined.isUndefined(regexp)) "" else ScriptRuntime.toString(regexp)
            var flags: String? = null
            if (cx.languageVersion < Context.VERSION_1_6 && args.size > 1) {
                flags = ScriptRuntime.toString(args[1])
            }
            val compiledRegExp = regExpProxy.compileRegExp(cx, regexpToString, flags)
            val rx = regExpProxy.wrapRegExp(cx, scope, compiledRegExp)
            val method = ScriptRuntime.getObjectElem(rx, SymbolKey.MATCH, cx, scope)
            if (method !is Callable) {
                throw ScriptRuntime.notFunctionError(rx, method, SymbolKey.MATCH.name)
            }
            return method.call(cx, scope, rx, arrayOf(s))
        }

        private fun js_lastIndexOf(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val target = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "lastIndexOf"))
            val search = ScriptRuntime.toString(args, 0)
            var end = ScriptRuntime.toNumber(args, 1)
            if (end.isNaN() || end > target.length) end = target.length.toDouble() else if (end < 0) end = 0.0
            return target.lastIndexOf(search, end.toInt())
        }

        private fun js_substring(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val target = ScriptRuntime.toCharSequence(requireObjectCoercible(cx, thisObj, CLASS_NAME, "substring"))
            val length = target.length
            var start = ScriptRuntime.toInteger(args, 0)
            var end: Double
            if (start < 0) start = 0.0 else if (start > length) start = length.toDouble()
            if (args.size <= 1 || args[1] === Undefined.instance) {
                end = length.toDouble()
            } else {
                end = ScriptRuntime.toInteger(args[1])
                if (end < 0) end = 0.0 else if (end > length) end = length.toDouble()
                if (end < start) {
                    if (cx.languageVersion != Context.VERSION_1_2) {
                        val temp = start
                        start = end
                        end = temp
                    } else {
                        // Emulate JS1.2 but don't tell java.lang.String
                        end = start
                    }
                }
            }
            return target.subSequence(start.toInt(), end.toInt())
        }

        private fun js_toLowerCase(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val thisStr = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "toLowerCase"))
            return thisStr.lowercase()
        }

        private fun js_toUpperCase(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val thisStr = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "toUpperCase"))
            return thisStr.uppercase()
        }

        private fun js_substr(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val target = ScriptRuntime.toCharSequence(requireObjectCoercible(cx, thisObj, CLASS_NAME, "substr"))
            if (args.isEmpty()) return target
            var begin = ScriptRuntime.toInteger(args[0])
            var end: Double
            val length = target.length
            if (begin < 0) {
                begin += length
                if (begin < 0) begin = 0.0
            } else if (begin > length) {
                begin = length.toDouble()
            }
            end = length.toDouble()
            if (args.size > 1) {
                val lengthArg = args[1]
                if (!Undefined.isUndefined(lengthArg)) {
                    end = ScriptRuntime.toInteger(lengthArg)
                    if (end < 0) end = 0.0
                    end += begin
                    if (end > length) end = length.toDouble()
                }
            }
            return target.subSequence(begin.toInt(), end.toInt())
        }

        private fun js_concat(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val target = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "concat"))
            val n = args.size
            if (n == 0) {
                return target
            } else if (n == 1) {
                val arg = ScriptRuntime.toString(args[0])
                return target + arg
            }
            // Find total capacity for the final string to avoid unnecessary re-allocations in StringBuilder
            var size = target.length
            val argsAsStrings = arrayOfNulls<String>(n)
            for (i in 0 until n) {
                val s = ScriptRuntime.toString(args[i])
                argsAsStrings[i] = s
                size += s.length
            }
            val result = StringBuilder(size)
            result.append(target)
            for (i in 0 until n) {
                result.append(argsAsStrings[i])
            }
            return result.toString()
        }

        private fun js_slice(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val target = ScriptRuntime.toCharSequence(requireObjectCoercible(cx, thisObj, CLASS_NAME, "slice"))
            var begin = if (args.isEmpty()) 0.0 else ScriptRuntime.toInteger(args[0])
            var end: Double
            val length = target.length
            if (begin < 0) {
                begin += length
                if (begin < 0) begin = 0.0
            } else if (begin > length) {
                begin = length.toDouble()
            }
            if (args.size < 2 || args[1] === Undefined.instance) {
                end = length.toDouble()
            } else {
                end = ScriptRuntime.toInteger(args[1])
                if (end < 0) {
                    end += length
                    if (end < 0) end = 0.0
                } else if (end > length) {
                    end = length.toDouble()
                }
                if (end < begin) end = begin
            }
            return target.subSequence(begin.toInt(), end.toInt())
        }

        private fun js_at(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val str = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "at"))
            val targetArg = if (args.isNotEmpty()) args[0] else Undefined.instance
            val len = str.length
            val relativeIndex = ScriptRuntime.toInteger(targetArg).toInt()
            val k = if (relativeIndex >= 0) relativeIndex else len + relativeIndex
            if (k < 0 || k >= len) return Undefined.instance
            return str.substring(k, k + 1)
        }

        private fun js_equals(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val s1 = ScriptRuntime.toString(thisObj)
            val s2 = ScriptRuntime.toString(args, 0)
            return s1 == s2
        }

        private fun js_equalsIgnoreCase(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val s1 = ScriptRuntime.toString(thisObj)
            val s2 = ScriptRuntime.toString(args, 0)
            return s1.equals(s2, ignoreCase = true)
        }

        private fun js_search(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = requireObjectCoercible(cx, thisObj, CLASS_NAME, "search")
            val regexp = if (args.isNotEmpty()) args[0] else Undefined.instance
            val regExpProxy = ScriptRuntime.checkRegExpProxy(cx)
            if (regexp != null && !Undefined.isUndefined(regexp)) {
                val matcher = ScriptRuntime.getObjectElem(regexp, SymbolKey.SEARCH, cx, scope)
                if (matcher != null && !Undefined.isUndefined(matcher)) {
                    if (matcher !is Callable) {
                        throw ScriptRuntime.notFunctionError(regexp, matcher, SymbolKey.SEARCH.name)
                    }
                    return matcher.call(cx, scope, ScriptRuntime.toObject(scope, regexp), arrayOf(o))
                }
            }
            val s = ScriptRuntime.toString(o)
            val regexpToString = if (Undefined.isUndefined(regexp)) "" else ScriptRuntime.toString(regexp)
            var flags: String? = null
            if (cx.languageVersion < Context.VERSION_1_6 && args.size > 1) {
                flags = ScriptRuntime.toString(args[1])
            }
            val compiledRegExp = regExpProxy.compileRegExp(cx, regexpToString, flags)
            val rx = regExpProxy.wrapRegExp(cx, scope, compiledRegExp)
            val method = ScriptRuntime.getObjectElem(rx, SymbolKey.SEARCH, cx, scope)
            if (method !is Callable) {
                throw ScriptRuntime.notFunctionError(rx, method, SymbolKey.SEARCH.name)
            }
            return method.call(cx, scope, rx, arrayOf(s))
        }

        private fun js_replace(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = requireObjectCoercible(cx, thisObj, CLASS_NAME, "replace")
            if (cx.languageVersion <= Context.VERSION_1_8) {
                return ScriptRuntime.checkRegExpProxy(cx).action(cx, scope, thisObj, args, RegExpProxy.RA_REPLACE)
            }
            val searchValue = if (args.isNotEmpty()) args[0] else Undefined.instance
            val replaceValue = if (args.size > 1) args[1] else Undefined.instance
            if (!Undefined.isUndefined(searchValue) && searchValue != null) {
                val replacer = ScriptRuntime.getObjectElem(searchValue, SymbolKey.REPLACE, cx, scope)
                if (replacer != null && !Undefined.isUndefined(replacer)) {
                    if (replacer !is Callable) {
                        throw ScriptRuntime.notFunctionError(searchValue, replacer, SymbolKey.REPLACE.name)
                    }
                    return replacer.call(
                        cx,
                        scope,
                        ScriptRuntime.toObject(scope, searchValue),
                        arrayOf(if (o is NativeString) o.string else o, replaceValue),
                    )
                }
            }
            val string = ScriptRuntime.toString(o)
            val searchString = ScriptRuntime.toString(searchValue)
            val functionalReplace = replaceValue is Callable
            val replaceOps: List<AbstractEcmaStringOperations.ReplacementOperation> = if (!functionalReplace) {
                AbstractEcmaStringOperations.buildReplacementList(ScriptRuntime.toString(replaceValue))
            } else {
                emptyList()
            }
            val searchLength = searchString.length
            val position = string.indexOf(searchString)
            if (position == -1) return string
            val preceding = string.substring(0, position)
            val following = string.substring(position + searchLength)
            val replacement: String
            if (functionalReplace) {
                val callThis = ScriptRuntime.getApplyOrCallThis(cx, scope, null, 0, replaceValue as Callable)
                val replacementObj = replaceValue.call(cx, scope, callThis, arrayOf(searchString, position, string))
                replacement = ScriptRuntime.toString(replacementObj)
            } else {
                val captures = emptyList<Any?>()
                replacement = AbstractEcmaStringOperations.getSubstitution(
                    cx, scope, searchString, string, position, captures, Undefined.SCRIPTABLE_UNDEFINED, replaceOps,
                )
            }
            return preceding + replacement + following
        }

        private fun js_replaceAll(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = requireObjectCoercible(cx, thisObj, CLASS_NAME, "replaceAll")
            val searchValue = if (args.isNotEmpty()) args[0] else Undefined.instance
            val replaceValue = if (args.size > 1) args[1] else Undefined.instance
            if (searchValue != null && !Undefined.isUndefined(searchValue)) {
                val isRegExp = searchValue is Scriptable && AbstractEcmaObjectOperations.isRegExp(cx, scope, searchValue)
                if (isRegExp) {
                    val flags = ScriptRuntime.getObjectProp(searchValue, "flags", cx, scope)
                    requireObjectCoercible(cx, flags, CLASS_NAME, "replaceAll")
                    val flagsStr = ScriptRuntime.toString(flags)
                    if (!flagsStr.contains("g")) {
                        throw ScriptRuntime.typeErrorById("msg.str.replace.all.no.global.flag")
                    }
                }
                val matcher = ScriptRuntime.getObjectElem(searchValue, SymbolKey.REPLACE, cx, scope)
                if (matcher != null && !Undefined.isUndefined(matcher)) {
                    if (matcher !is Callable) {
                        throw ScriptRuntime.notFunctionError(searchValue, matcher, SymbolKey.REPLACE.name)
                    }
                    return matcher.call(cx, scope, ScriptRuntime.toObject(scope, searchValue), arrayOf(o, replaceValue))
                }
            }
            val string = ScriptRuntime.toString(o)
            val searchString = ScriptRuntime.toString(searchValue)
            val functionalReplace = replaceValue is Callable
            val replaceOps: List<AbstractEcmaStringOperations.ReplacementOperation> = if (!functionalReplace) {
                AbstractEcmaStringOperations.buildReplacementList(ScriptRuntime.toString(replaceValue))
            } else {
                emptyList()
            }
            val searchLength = searchString.length
            val advanceBy = maxOf(1, searchLength)
            val matchPositions = ArrayList<Int>()
            var position = string.indexOf(searchString)
            while (position != -1) {
                matchPositions.add(position)
                val newPosition = string.indexOf(searchString, position + advanceBy)
                if (newPosition == position) break
                position = newPosition
            }
            var endOfLastMatch = 0
            val result = StringBuilder()
            for (p in matchPositions) {
                val preserved = string.substring(endOfLastMatch, p)
                val replacement: String
                if (functionalReplace) {
                    val callThis = ScriptRuntime.getApplyOrCallThis(cx, scope, null, 0, replaceValue as Callable)
                    val replacementObj = replaceValue.call(cx, scope, callThis, arrayOf(searchString, p, string))
                    replacement = ScriptRuntime.toString(replacementObj)
                } else {
                    val captures = emptyList<Any?>()
                    replacement = AbstractEcmaStringOperations.getSubstitution(
                        cx, scope, searchString, string, p, captures, Undefined.SCRIPTABLE_UNDEFINED, replaceOps,
                    )
                }
                result.append(preserved)
                result.append(replacement)
                endOfLastMatch = p + searchLength
            }
            if (endOfLastMatch < string.length) {
                result.append(string.substring(endOfLastMatch))
            }
            return result.toString()
        }

        private fun js_matchAll(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = requireObjectCoercible(cx, thisObj, CLASS_NAME, "matchAll")
            val regexp = if (args.isNotEmpty()) args[0] else Undefined.instance
            if (regexp != null && !Undefined.isUndefined(regexp)) {
                val isRegExp = AbstractEcmaObjectOperations.isRegExp(cx, scope, regexp)
                if (isRegExp) {
                    val flags = ScriptRuntime.getObjectProp(regexp, "flags", cx, scope)
                    requireObjectCoercible(cx, flags, CLASS_NAME, "matchAll")
                    val flagsStr = ScriptRuntime.toString(flags)
                    if (!flagsStr.contains("g")) {
                        throw ScriptRuntime.typeErrorById("msg.str.match.all.no.global.flag")
                    }
                }
                val matcher = ScriptRuntime.getObjectElem(regexp, SymbolKey.MATCH_ALL, cx, scope)
                if (matcher != null && !Undefined.isUndefined(matcher)) {
                    if (matcher !is Callable) {
                        throw ScriptRuntime.notFunctionError(regexp, matcher, SymbolKey.MATCH_ALL.name)
                    }
                    return matcher.call(cx, scope, ScriptRuntime.toObject(scope, regexp), arrayOf(o))
                }
            }
            val s = ScriptRuntime.toString(o)
            val regexpToString = if (Undefined.isUndefined(regexp)) "" else ScriptRuntime.toString(regexp)
            val regExpProxy = ScriptRuntime.checkRegExpProxy(cx)
            val compiledRegExp = regExpProxy.compileRegExp(cx, regexpToString, "g")
            val rx = regExpProxy.wrapRegExp(cx, scope, compiledRegExp)
            val method = ScriptRuntime.getObjectElem(rx, SymbolKey.MATCH_ALL, cx, scope)
            if (method !is Callable) {
                throw ScriptRuntime.notFunctionError(rx, method, SymbolKey.MATCH_ALL.name)
            }
            return method.call(cx, scope, rx, arrayOf(s))
        }

        private fun js_localeCompare(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val thisStr = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "localeCompare"))
            // Common Kotlin has no collator (D-37), so this is code-unit order, clamped to -1, 0, 1.
            val cmp = thisStr.compareTo(ScriptRuntime.toString(args, 0))
            return if (cmp < 0) -1 else if (cmp > 0) 1 else 0
        }

        private fun js_toLocaleLowerCase(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val thisStr = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "toLocaleLowerCase"))
            // No locale support (D-28), so this is the locale-independent conversion.
            return thisStr.lowercase()
        }

        private fun js_toLocaleUpperCase(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val thisStr = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "toLocaleUpperCase"))
            return thisStr.uppercase()
        }

        private fun js_trim(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val str = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "trim"))
            var start = 0
            while (start < str.length && ScriptRuntime.isJSWhitespaceOrLineTerminator(str[start].code)) start++
            var end = str.length
            while (end > start && ScriptRuntime.isJSWhitespaceOrLineTerminator(str[end - 1].code)) end--
            return str.substring(start, end)
        }

        private fun js_trimLeft(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val str = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "trimLeft"))
            var start = 0
            while (start < str.length && ScriptRuntime.isJSWhitespaceOrLineTerminator(str[start].code)) start++
            return str.substring(start, str.length)
        }

        private fun js_trimRight(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val str = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "trimRight"))
            var end = str.length
            while (end > 0 && ScriptRuntime.isJSWhitespaceOrLineTerminator(str[end - 1].code)) end--
            return str.substring(0, end)
        }

        private fun js_normalize(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            // Common Kotlin has no Unicode normalizer (D-38): the form name is checked, the text
            // comes back as it was.
            if (args.isEmpty() || Undefined.isUndefined(args[0])) {
                return ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "normalize"))
            }
            val formStr = ScriptRuntime.toString(args, 0)
            if (formStr != "NFD" && formStr != "NFKC" && formStr != "NFKD" && formStr != "NFC") {
                throw ScriptRuntime.rangeError("The normalization form should be one of 'NFC', 'NFD', 'NFKC', 'NFKD'.")
            }
            return ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "normalize"))
        }

        private fun js_repeat(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val str = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "repeat"))
            val cnt = ScriptRuntime.toInteger(args, 0)
            if (cnt < 0.0 || cnt == Double.POSITIVE_INFINITY) {
                throw ScriptRuntime.rangeError("Invalid count value")
            }
            if (cnt == 0.0 || str.isEmpty()) return ""
            val size = str.length * cnt.toLong()
            if (cnt > Int.MAX_VALUE || size > Int.MAX_VALUE) {
                throw ScriptRuntime.rangeError("Invalid size or count value")
            }
            val retval = StringBuilder(size.toInt())
            retval.append(str)
            var i = 1
            val icnt = cnt.toInt()
            while (i <= icnt / 2) {
                retval.append(retval.toString())
                i *= 2
            }
            if (i < icnt) {
                retval.append(retval.toString(), 0, str.length * (icnt - i))
            }
            return retval.toString()
        }

        private fun js_codePointAt(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val str = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, "codePointAt"))
            val cnt = ScriptRuntime.toInteger(args, 0)
            return if (cnt < 0 || cnt >= str.length) Undefined.instance else Characters.codePointAt(str, cnt.toInt())
        }

        private fun pad(cx: Context, thisObj: Scriptable?, functionName: String, args: Array<Any?>, atStart: Boolean): String {
            val pad = ScriptRuntime.toString(requireObjectCoercible(cx, thisObj, CLASS_NAME, functionName))
            val intMaxLength = ScriptRuntime.toLength(args, 0)
            if (intMaxLength <= pad.length) return pad
            var filler = " "
            if (args.size >= 2 && !Undefined.isUndefined(args[1])) {
                filler = ScriptRuntime.toString(args[1])
                if (filler.isEmpty()) return pad
            }
            // cast is not really correct here
            val fillLen = (intMaxLength - pad.length).toInt()
            val concat = StringBuilder()
            do {
                concat.append(filler)
            } while (concat.length < fillLen)
            concat.setLength(fillLen)
            if (atStart) {
                return concat.append(pad).toString()
            }
            return concat.insert(0, pad).toString()
        }

        private fun js_padStart(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            pad(cx, thisObj, "padStart", args, true)

        private fun js_padEnd(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            pad(cx, thisObj, "padEnd", args, false)

        private fun js_raw(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            /* step 1-3 */
            val arg0 = if (args.isNotEmpty()) args[0] else Undefined.instance
            val cooked = ScriptRuntime.toObject(cx, scope, arg0)
            /* step 4-6 */
            val rawValue = ScriptRuntime.getObjectProp(cooked, "raw", cx)
            val raw = ScriptRuntime.toObject(cx, scope, rawValue)
            val rawLength = NativeArray.getLengthProperty(cx, raw)
            if (rawLength > Int.MAX_VALUE) {
                throw ScriptRuntime.rangeError("raw.length > " + Int.MAX_VALUE)
            }
            val literalSegments = rawLength.toInt()
            /* step 7 */
            if (literalSegments <= 0) return ""
            /* step 8-9 */
            val elements = StringBuilder()
            var nextIndex = 0
            while (true) {
                /* step 9.a-c */
                var next = ScriptRuntime.getObjectIndex(raw, nextIndex, cx)
                val nextSeg = ScriptRuntime.toString(next)
                /* step 9.d */
                elements.append(nextSeg)
                nextIndex += 1
                /* step 9.e */
                if (nextIndex == literalSegments) break
                /* step 9.f-h */
                if (args.size > nextIndex) {
                    next = args[nextIndex]
                    val nextSub = ScriptRuntime.toString(next)
                    elements.append(nextSub)
                }
            }
            return elements
        }

        private fun js_isWellFormed(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val str = ScriptRuntime.toCharSequence(requireObjectCoercible(cx, thisObj, CLASS_NAME, "isWellFormed"))
            val len = str.length
            var foundLeadingSurrogate = false
            for (i in 0 until len) {
                val c = str[i]
                if (NativeJSON.isLeadingSurrogate(c)) {
                    if (foundLeadingSurrogate) return false
                    foundLeadingSurrogate = true
                } else if (NativeJSON.isTrailingSurrogate(c)) {
                    if (!foundLeadingSurrogate) return false
                    foundLeadingSurrogate = false
                } else if (foundLeadingSurrogate) {
                    return false
                }
            }
            return !foundLeadingSurrogate
        }

        private fun js_toWellFormed(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val str = ScriptRuntime.toCharSequence(requireObjectCoercible(cx, thisObj, CLASS_NAME, "toWellFormed"))
            // true: surrogate pair, false: lone surrogate
            val surrogates = HashMap<Int, Boolean>()
            val len = str.length
            var prev = 0.toChar()
            var firstSurrogateIndex = -1
            for (i in 0 until len) {
                val c = str[i]
                if (NativeJSON.isLeadingSurrogate(prev) && NativeJSON.isTrailingSurrogate(c)) {
                    surrogates[i - 1] = true
                    surrogates[i] = true
                } else if (NativeJSON.isLeadingSurrogate(c) || NativeJSON.isTrailingSurrogate(c)) {
                    surrogates[i] = false
                    if (firstSurrogateIndex == -1) firstSurrogateIndex = i
                }
                prev = c
            }
            if (surrogates.isEmpty()) return str.toString()
            val sb = StringBuilder(str.subSequence(0, firstSurrogateIndex))
            for (i in firstSurrogateIndex until len) {
                val c = str[i]
                val pairOrNormal = surrogates[i]
                if (pairOrNormal == null || pairOrNormal) {
                    sb.append(c)
                } else {
                    sb.append('�')
                }
            }
            return sb.toString()
        }

        private fun js_bold(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            tagify(cx, thisObj, "bold", "b", null, args)

        private fun js_italics(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            tagify(cx, thisObj, "italics", "i", null, args)

        private fun js_fixed(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            tagify(cx, thisObj, "fixed", "tt", null, args)

        private fun js_strike(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            tagify(cx, thisObj, "strike", "strike", null, args)

        private fun js_small(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            tagify(cx, thisObj, "small", "small", null, args)

        private fun js_big(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            tagify(cx, thisObj, "big", "big", null, args)

        private fun js_blink(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            tagify(cx, thisObj, "blink", "blink", null, args)

        private fun js_sup(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            tagify(cx, thisObj, "sup", "sup", null, args)

        private fun js_sub(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            tagify(cx, thisObj, "sub", "sub", null, args)

        private fun js_fontsize(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            tagify(cx, thisObj, "fontsize", "font", "size", args)

        private fun js_fontcolor(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            tagify(cx, thisObj, "fontcolor", "font", "color", args)

        private fun js_link(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            tagify(cx, thisObj, "link", "a", "href", args)

        private fun js_anchor(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            tagify(cx, thisObj, "anchor", "a", "name", args)
    }
}
