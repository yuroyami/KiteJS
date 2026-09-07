/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.json.JsonParser

/**
 * The JavaScript `JSON` object.
 *
 * Upstream also serialises wrapped Java maps, collections and arrays; that is LiveConnect and is
 * not ported.
 */
class NativeJSON private constructor() : ScriptableObject() {

    override val className: String
        get() = "JSON"

    private class StringifyState(
        val cx: Context,
        val scope: Scriptable,
        var indent: String,
        val gap: String,
        val replacer: Callable?,
        val propertyList: Array<Any?>?,
    ) {
        val stack = ArrayDeque<Any?>()
    }

    companion object {
        private const val JSON_TAG = "JSON"
        private const val MAX_STRINGIFY_GAP_LENGTH = 10

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val json = NativeJSON()
            json.prototype = getObjectPrototype(scope)
            json.parentScope = scope
            json.defineBuiltinProperty(scope, "parse", 2, ::parse)
            json.defineBuiltinProperty(scope, "stringify", 3, ::stringify)
            json.defineProperty("toSource", "JSON", DONTENUM or READONLY or PERMANENT)
            json.defineProperty(SymbolKey.TO_STRING_TAG, JSON_TAG, DONTENUM or READONLY)
            if (sealed) json.sealObject()
            return json
        }

        private fun parse(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val jtext = ScriptRuntime.toString(args, 0)
            var reviver: Any? = null
            if (args.size > 1) reviver = args[1]
            if (reviver is Callable) {
                return parse(cx, scope, jtext, reviver)
            }
            return parse(cx, scope, jtext)
        }

        private fun stringify(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            var value: Any? = Undefined.instance
            var replacer: Any? = null
            var space: Any? = null
            if (args.isNotEmpty()) {
                value = args[0]
                if (args.size > 1) {
                    replacer = args[1]
                    if (args.size > 2) {
                        space = args[2]
                    }
                }
            }
            return stringify(cx, scope, value, replacer, space)
        }

        private fun parse(cx: Context, scope: Scriptable, jtext: String): Any? {
            try {
                return JsonParser(cx, scope).parseValue(jtext)
            } catch (ex: JsonParser.ParseException) {
                throw ScriptRuntime.constructError("SyntaxError", ex.message ?: "")
            }
        }

        fun parse(cx: Context, scope: Scriptable, jtext: String, reviver: Callable): Any? {
            val unfiltered = parse(cx, scope, jtext)
            val root = cx.newObject(scope)
            root.put("", root, unfiltered)
            return walk(cx, scope, reviver, root, "")
        }

        private fun walk(cx: Context, scope: Scriptable, reviver: Callable, holder: Scriptable, name: Any?): Any? {
            val property: Any? = if (name is Number) {
                holder.get(name.toInt(), holder)
            } else {
                holder.get(name as String, holder)
            }
            if (property is Scriptable) {
                val v = property
                if (v is NativeArray) {
                    val len = v.length
                    for (i in 0 until len) {
                        if (i > Int.MAX_VALUE) {
                            val id = i.toString()
                            val newElement = walk(cx, scope, reviver, v, id)
                            if (newElement === Undefined.instance) {
                                v.delete(id)
                            } else {
                                v.put(id, v, newElement)
                            }
                        } else {
                            val idx = i.toInt()
                            val newElement = walk(cx, scope, reviver, v, idx)
                            if (newElement === Undefined.instance) {
                                v.delete(idx)
                            } else {
                                v.put(idx, v, newElement)
                            }
                        }
                    }
                } else {
                    val keys = v.getIds()
                    for (p in keys) {
                        val newElement = walk(cx, scope, reviver, v, p)
                        if (newElement === Undefined.instance) {
                            if (p is Number) v.delete(p.toInt()) else v.delete(p as String)
                        } else {
                            if (p is Number) v.put(p.toInt(), v, newElement) else v.put(p as String, v, newElement)
                        }
                    }
                }
            }
            return reviver.call(cx, scope, holder, arrayOf(name, property))
        }

        private fun repeat(c: Char, count: Int): String = CharArray(count) { c }.concatToString()

        fun stringify(cx: Context, scope: Scriptable, value: Any?, replacer: Any?, spaceIn: Any?): Any? {
            var space = spaceIn
            val indent = ""
            var gap = ""
            var propertyList: Array<Any?>? = null
            var replacerFunction: Callable? = null
            if (replacer is Callable) {
                replacerFunction = replacer
            } else if (replacer is NativeArray) {
                val propertySet = LinkedHashSet<Any?>()
                for (i in replacer.indexIds) {
                    val v = replacer.get(i, replacer)
                    if (v is String) {
                        propertySet.add(v)
                    } else if (v is Number || v is NativeString || v is NativeNumber) {
                        propertySet.add(ScriptRuntime.toString(v))
                    }
                }
                propertyList = arrayOfNulls<Any?>(propertySet.size)
                var i = 0
                for (prop in propertySet) {
                    val idOrIndex = ScriptRuntime.toStringIdOrIndex(prop)
                    propertyList[i++] = idOrIndex.stringId ?: idOrIndex.index
                }
            }
            if (space is NativeNumber) {
                space = ScriptRuntime.toNumber(space)
            } else if (space is NativeString) {
                space = ScriptRuntime.toString(space)
            }
            if (space is Number) {
                var gapLength = ScriptRuntime.toInteger(space).toInt()
                gapLength = minOf(MAX_STRINGIFY_GAP_LENGTH, gapLength)
                gap = if (gapLength > 0) repeat(' ', gapLength) else ""
            } else if (space is String) {
                gap = space
                if (gap.length > MAX_STRINGIFY_GAP_LENGTH) {
                    gap = gap.substring(0, MAX_STRINGIFY_GAP_LENGTH)
                }
            }
            val state = StringifyState(cx, scope, indent, gap, replacerFunction, propertyList)
            val wrapper = NativeObject()
            wrapper.parentScope = scope
            wrapper.prototype = getObjectPrototype(scope)
            wrapper.defineProperty("", value, 0)
            return str("", wrapper, state)
        }

        private fun str(key: Any?, holder: Scriptable, state: StringifyState): Any? {
            var value: Any?
            var keyString: String? = null
            var keyInt = 0
            if (key is String) {
                keyString = key
                value = getProperty(holder, keyString)
            } else {
                keyInt = (key as Number).toInt()
                value = getProperty(holder, keyInt)
            }
            if (value is Scriptable && hasProperty(value, "toJSON")) {
                val toJSON = getProperty(value, "toJSON")
                if (toJSON is Callable) {
                    value = callMethod(state.cx, value, "toJSON", arrayOf(keyString ?: keyInt.toString()))
                }
            } else if (value is KBigInt) {
                val bigInt = ScriptRuntime.toObject(state.cx, state.scope, value)
                if (hasProperty(bigInt, "toJSON")) {
                    val toJSON = getProperty(bigInt, "toJSON")
                    if (toJSON is Callable) {
                        value = callMethod(state.cx, bigInt, "toJSON", arrayOf(keyString ?: keyInt.toString()))
                    }
                }
            }
            val replacer = state.replacer
            if (replacer != null) {
                value = replacer.call(state.cx, state.scope, holder, arrayOf(key, value))
            }
            if (ScriptRuntime.isSymbol(value)) return Undefined.instance
            if (value is NativeNumber) {
                value = ScriptRuntime.toNumber(value)
            } else if (value is NativeString) {
                value = ScriptRuntime.toString(value)
            } else if (value is NativeBoolean) {
                value = value.getDefaultValue(ScriptRuntime.BooleanClass)
            } else if (state.cx.languageVersion >= Context.VERSION_ES6 && value is NativeBigInt) {
                value = value.getDefaultValue(ScriptRuntime.BigIntegerClass)
            }
            if (value == null) return "null"
            if (value == true) return "true"
            if (value == false) return "false"
            if (value is CharSequence) return quote(value.toString())
            // A bigint is checked on its own, because it is not a Number here (D-54).
            if (value is KBigInt) {
                throw ScriptRuntime.typeErrorById("msg.json.cant.serialize", "BigInt")
            }
            if (value is Number) {
                val d = value.toDouble()
                if (!d.isNaN() && d != Double.POSITIVE_INFINITY && d != Double.NEGATIVE_INFINITY) {
                    return ScriptRuntime.toString(value)
                }
                return "null"
            }
            if (value is Scriptable && value !is Callable) {
                if (isObjectArrayLike(value)) {
                    return ja(value, state)
                }
                return jo(value, state)
            }
            return Undefined.instance
        }

        private fun join(objs: Collection<Any?>, delimiter: String): String {
            if (objs.isEmpty()) return ""
            val iter = objs.iterator()
            if (!iter.hasNext()) return ""
            val builder = StringBuilder(iter.next().toString())
            while (iter.hasNext()) {
                builder.append(delimiter).append(iter.next())
            }
            return builder.toString()
        }

        private fun jo(value: Scriptable, state: StringifyState): String {
            val trackValue: Any = value
            if (state.stack.contains(trackValue)) {
                throw ScriptRuntime.typeErrorById("msg.cyclic.value", trackValue::class.simpleName)
            }
            state.stack.addFirst(trackValue)
            val stepback = state.indent
            state.indent = state.indent + state.gap
            val k: Array<Any?> = state.propertyList ?: value.getIds()
            val partial = ArrayList<Any?>()
            for (p in k) {
                val strP = str(p, value, state)
                if (strP !== Undefined.instance) {
                    var member = quote(p.toString()) + ":"
                    if (state.gap.isNotEmpty()) {
                        member += " "
                    }
                    member += strP
                    partial.add(member)
                }
            }
            val finalValue: String
            if (partial.isEmpty()) {
                finalValue = "{}"
            } else {
                if (state.gap.isEmpty()) {
                    finalValue = '{' + join(partial, ",") + '}'
                } else {
                    val separator = ",\n" + state.indent
                    val properties = join(partial, separator)
                    finalValue = "{\n" + state.indent + properties + '\n' + stepback + '}'
                }
            }
            state.stack.removeFirst()
            state.indent = stepback
            return finalValue
        }

        private fun ja(value: Scriptable, state: StringifyState): String {
            val trackValue: Any = value
            if (state.stack.contains(trackValue)) {
                throw ScriptRuntime.typeErrorById("msg.cyclic.value", trackValue::class.simpleName)
            }
            state.stack.addFirst(trackValue)
            val stepback = state.indent
            state.indent = state.indent + state.gap
            val partial = ArrayList<Any?>()
            val len = (value as NativeArray).length
            for (index in 0 until len) {
                val strP: Any? = if (index > Int.MAX_VALUE) {
                    str(index.toString(), value, state)
                } else {
                    str(index.toInt(), value, state)
                }
                if (strP === Undefined.instance) {
                    partial.add("null")
                } else {
                    partial.add(strP)
                }
            }
            val finalValue: String
            if (partial.isEmpty()) {
                finalValue = "[]"
            } else {
                if (state.gap.isEmpty()) {
                    finalValue = '[' + join(partial, ",") + ']'
                } else {
                    val separator = ",\n" + state.indent
                    val properties = join(partial, separator)
                    finalValue = "[\n" + state.indent + properties + '\n' + stepback + ']'
                }
            }
            state.stack.removeFirst()
            state.indent = stepback
            return finalValue
        }

        private fun quote(string: String): String {
            val product = StringBuilder(string.length + 2) // two extra chars for " on either side
            product.append('"')
            val length = string.length
            var prev = 0.toChar()
            for (i in 0 until length) {
                val c = string[i]
                when (c) {
                    '"' -> product.append("\\\"")
                    '\\' -> product.append("\\\\")
                    '\b' -> product.append("\\b")
                    '\u000C' -> product.append("\\f")
                    '\n' -> product.append("\\n")
                    '\r' -> product.append("\\r")
                    '\t' -> product.append("\\t")
                    else -> {
                        if (isLeadingSurrogate(c) && i < length - 1 && isTrailingSurrogate(string[i + 1])) {
                            // A pair; wait for the trailing half, then write both.
                        } else if (isTrailingSurrogate(c) && isLeadingSurrogate(prev)) {
                            product.append(prev).append(c)
                        } else if (c < ' ' || isLeadingSurrogate(c) || isTrailingSurrogate(c)) {
                            product.append("\\u")
                            val hex = c.code.toString(16).padStart(4, '0')
                            product.append(hex)
                        } else {
                            product.append(c)
                        }
                    }
                }
                prev = c
            }
            product.append('"')
            return product.toString()
        }

        internal fun isLeadingSurrogate(c: Char): Boolean = c.code in 0xD800..0xDBFF

        internal fun isTrailingSurrogate(c: Char): Boolean = c.code in 0xDC00..0xDFFF

        private fun isObjectArrayLike(o: Any?): Boolean = o is NativeArray
    }
}
