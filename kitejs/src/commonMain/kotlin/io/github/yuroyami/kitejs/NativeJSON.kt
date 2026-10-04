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
public class NativeJSON private constructor() : ScriptableObject() {

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

    public companion object {
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

        public fun parse(cx: Context, scope: Scriptable, jtext: String, reviver: Callable): Any? {
            val unfiltered = parse(cx, scope, jtext)
            val root = cx.newObject(scope)
            root.put("", root, unfiltered)
            return walk(cx, scope, reviver, root, "")
        }

        /**
         * InternalizeJSONProperty (ES 25.5.1.1). The value is read with [[Get]], so an inherited
         * property counts; an array is recognised with IsArray, a proxy for one included, and walked
         * to its LengthOfArrayLike; an object is walked over its enumerable own keys; and each
         * result is deleted or written back with [[Delete]] and CreateDataProperty, neither of which
         * throws when refused. The reviver gets the key as a string. Upstream read own properties
         * only, recognised only a real array, wrote back with a put that ran a setter the reviver
         * had defined and wrote into an array the reviver had frozen, deleted with a delete that
         * threw in strict code when refused, and passed array indices to the reviver as numbers
         * (D-91).
         */
        private fun walk(cx: Context, scope: Scriptable, reviver: Callable, holder: Scriptable, name: Any): Any? {
            val property = getValue(holder, name)
            if (property is Scriptable && ScriptRuntime.isObject(property)) {
                if (NativeArray.isArray(property)) {
                    val len = AbstractEcmaObjectOperations.lengthOfArrayLike(cx, property)
                    for (i in 0 until len) revive(cx, scope, reviver, property, indexKey(i))
                } else {
                    for (p in property.getIds()) revive(cx, scope, reviver, property, p!!)
                }
            }
            return reviver.call(cx, scope, holder, arrayOf(name.toString(), property))
        }

        private fun revive(cx: Context, scope: Scriptable, reviver: Callable, holder: Scriptable, key: Any) {
            val newElement = walk(cx, scope, reviver, holder, key)
            if (holder !is ScriptableObject) {
                if (newElement === Undefined.instance) {
                    if (key is Int) holder.delete(key) else holder.delete(key.toString())
                } else {
                    if (key is Int) holder.put(key, holder, newElement) else holder.put(key.toString(), holder, newElement)
                }
                return
            }
            if (newElement === Undefined.instance) {
                AbstractEcmaObjectOperations.delete(cx, holder, key)
            } else {
                AbstractEcmaObjectOperations.createDataProperty(cx, holder, key, newElement)
            }
        }

        /** The property key for array index [i]: an int where it fits, as the engine stores it, otherwise its string. */
        private fun indexKey(i: Long): Any = if (i <= Int.MAX_VALUE) i.toInt() else i.toString()

        /** Get(O, P) for a string, int or symbol key, with a missing property read as undefined. */
        private fun getValue(o: Scriptable, key: Any): Any? {
            val value = when (key) {
                is Int -> getProperty(o, key)
                is Symbol -> getProperty(o, key)
                else -> getProperty(o, key.toString())
            }
            return if (value === Scriptable.NOT_FOUND) Undefined.instance else value
        }

        private fun repeat(c: Char, count: Int): String = CharArray(count) { c }.concatToString()

        public fun stringify(cx: Context, scope: Scriptable, value: Any?, replacer: Any?, spaceIn: Any?): Any? {
            var space = spaceIn
            val indent = ""
            var gap = ""
            var propertyList: Array<Any?>? = null
            var replacerFunction: Callable? = null
            if (replacer is Callable) {
                replacerFunction = replacer
            } else if (NativeArray.isArray(replacer)) {
                // The property list comes from every index up to LengthOfArrayLike, read with
                // [[Get]], so a proxy for an array works and a hole reads its prototype. Upstream
                // took only the own indices of a real array (D-91).
                val replacerObj = replacer as Scriptable
                val len = AbstractEcmaObjectOperations.lengthOfArrayLike(cx, replacerObj)
                val propertySet = LinkedHashSet<Any?>()
                for (i in 0 until len) {
                    val v = getValue(replacerObj, indexKey(i))
                    if (v is CharSequence) {
                        propertySet.add(v.toString())
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

        /**
         * SerializeJSONProperty (ES 25.5.2.2). `toJSON` is read once with GetV and called when it is
         * callable, and both it and the replacer get the key as a string. Upstream asked HasProperty
         * first and then read the method twice, and handed array indices to the replacer as numbers
         * (D-91).
         */
        private fun str(key: Any, holder: Scriptable, state: StringifyState): Any? {
            val keyString = key.toString()
            var value = getValue(holder, key)
            if (value is Scriptable && !ScriptRuntime.isSymbol(value)) {
                val toJSON = getProperty(value, "toJSON")
                if (toJSON is Callable) value = toJSON.call(state.cx, state.scope, value, arrayOf(keyString))
            } else if (value is KBigInt) {
                val bigInt = ScriptRuntime.toObject(state.cx, state.scope, value)
                val toJSON = getProperty(bigInt, "toJSON")
                if (toJSON is Callable) value = toJSON.call(state.cx, state.scope, bigInt, arrayOf(keyString))
            }
            val replacer = state.replacer
            if (replacer != null) {
                value = replacer.call(state.cx, state.scope, holder, arrayOf(keyString, value))
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
                if (NativeArray.isArray(value)) {
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
                val strP = str(p!!, value, state)
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
            // SerializeJSONArray walks to LengthOfArrayLike, so a proxy for an array is read
            // through its traps (D-91).
            val len = AbstractEcmaObjectOperations.lengthOfArrayLike(state.cx, value)
            for (index in 0 until len) {
                val strP: Any? = str(indexKey(index), value, state)
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

    }
}
