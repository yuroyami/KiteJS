/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * Collects the pieces of an object or array literal while the interpreter evaluates them, then
 * hands the runtime the keysField, valuesField and getter/setter flags in one go.
 */
public abstract class NewLiteralStorage protected constructor(ids: Array<Any?>?, length: Int, createKeys: Boolean) {

    protected var keysField: Array<Any?>?
    protected var getterSettersField: IntArray
    protected var valuesField: Array<Any?>
    protected var index: Int = 0
    protected var skipIndexesField: IntArray? = null

    /** How many extra elements each spread at a source position produced. */
    protected var spreadAdjustments: IntArray? = null

    init {
        val l: Int
        if (ids != null) {
            keysField = ids
            l = ids.size
        } else {
            keysField = if (createKeys) arrayOfNulls(length) else null
            l = length
        }
        getterSettersField = IntArray(l)
        valuesField = arrayOfNulls(l)
    }

    public fun pushValue(value: Any?) {
        valuesField[index] = value
        attemptToInferFunctionName(value)
        ++index
    }

    public fun pushGetter(value: Any?) {
        getterSettersField[index] = -1
        pushValue(value)
    }

    public fun pushSetter(value: Any?) {
        getterSettersField[index] = 1
        pushValue(value)
    }

    public fun pushKey(key: Any?) {
        keysField!![index] = if (key is Symbol) key else ScriptRuntime.toString(key)
    }

    public fun spread(cx: Context, scope: Scriptable, source: Any?, sourcePosition: Int) {
        val indexBefore = index
        if (keysField == null) spreadArray(cx, scope, source) else spreadObject(cx, scope, source)
        val adj = spreadAdjustments
        if (adj != null && sourcePosition < adj.size) adj[sourcePosition] = index - indexBefore - 1
    }

    private fun spreadArray(cx: Context, scope: Scriptable, source: Any?) {
        // ES2015 12.2.5.2 and 12.3.6.1: a spread asks GetIterator for the value, which throws a
        // TypeError when there is no Symbol.iterator to call (D-70).
        if (source == null || Undefined.isUndefined(source)) throw notIterable(source)
        // GetMethod reads Symbol.iterator once, and a getter on a primitive sees the primitive (#78).
        val method = ScriptRuntime.getV(cx, scope, source, SymbolKey.ITERATOR)
        if (method !== Scriptable.NOT_FOUND && method != null && !Undefined.isUndefined(method)) {
            if (method !is Callable) throw ScriptRuntime.notFunctionError(method, SymbolKey.ITERATOR)
            val iterator = method.call(cx, scope, ScriptRuntime.toReceiver(cx, source, scope)!!, ScriptRuntime.emptyArgs)
            if (!Undefined.isUndefined(iterator)) {
                val spreadValues = ArrayList<Any?>()
                IteratorLikeIterable(cx, scope, iterator).use { it -> for (temp in it) spreadValues.add(temp) }
                val newLen = valuesField.size + spreadValues.size
                getterSettersField = getterSettersField.copyOf(newLen)
                valuesField = valuesField.copyOf(newLen)
                for (value in spreadValues) pushValue(value)
                return
            }
        }
        // No Symbol.iterator. An array whose iterator was taken away still spreads by its length,
        // so holes come through as undefined and a non-index own property is left out. Anything
        // else is not iterable, whatever own properties it has (D-70).
        val src = source as? NativeArray ?: throw notIterable(source)
        val newLen = valuesField.size + src.length.toInt()
        getterSettersField = getterSettersField.copyOf(newLen)
        valuesField = valuesField.copyOf(newLen)
        for (i in 0 until src.length) pushValue(NativeArray.getElem(cx, src, i))
    }

    /** Names an object by its type, so building the message never calls back into script. */
    private fun notIterable(source: Any?): EcmaError {
        val name = if (source is Scriptable || ScriptRuntime.isSymbol(source)) ScriptRuntime.typeOf(source) else ScriptRuntime.toString(source)
        return ScriptRuntime.typeErrorById("msg.not.iterable", name)
    }

    /**
     * CopyDataProperties: [[GetOwnProperty]] is asked for each key just before its value is read,
     * so a proxy's traps run in the spec's order and a property a getter deletes is left out.
     * Upstream listed the enumerable keys first and then read them all (D-91).
     */
    private fun spreadObject(cx: Context, scope: Scriptable, source: Any?) {
        if (source == null || Undefined.isUndefined(source)) return
        val src = ScriptRuntime.toObject(cx, scope, source)
        val keys = ArrayList<Any?>()
        val values = ArrayList<Any?>()
        for (id in AbstractEcmaObjectOperations.ownKeysForEnumeration(src, true)) {
            if (!AbstractEcmaObjectOperations.isOwnEnumerable(cx, src, id!!)) continue
            keys.add(id)
            values.add(AbstractEcmaObjectOperations.getForEnumeration(cx, src, id))
        }
        val newLen = valuesField.size + keys.size
        keysField = keysField!!.copyOf(newLen)
        getterSettersField = getterSettersField.copyOf(newLen)
        valuesField = valuesField.copyOf(newLen)
        for (i in keys.indices) {
            pushKey(keys[i])
            pushValue(values[i])
        }
    }

    public val keys: Array<Any?>? get() = keysField

    public val getterSetters: IntArray get() = getterSettersField

    public val values: Array<Any?> get() = valuesField

    public fun setSkipIndexes(skipIndexes: IntArray?) {
        this.skipIndexesField = skipIndexes
        if (skipIndexes != null && skipIndexes.isNotEmpty()) spreadAdjustments = IntArray(valuesField.size + skipIndexes.size)
    }

    public fun hasSkipIndexes(): Boolean = skipIndexesField != null

    /** The holes' positions once every spread before them has been counted in. */
    public val adjustedSkipIndexes: IntArray?
        get() {
        val skips = skipIndexesField ?: return null
        val adj = spreadAdjustments
        return IntArray(skips.size) { i ->
            val sourceSkip = skips[i]
            var adjustment = 0
            if (adj != null) {
                var sourcePos = 0
                while (sourcePos < sourceSkip && sourcePos < adj.size) {
                    adjustment += adj[sourcePos]
                    sourcePos++
                }
            }
            sourceSkip + adjustment
        }
    }

    protected abstract fun attemptToInferFunctionName(value: Any?)

    private class NoInference(ids: Array<Any?>?, length: Int, createKeys: Boolean) : NewLiteralStorage(ids, length, createKeys) {
        override fun attemptToInferFunctionName(value: Any?) {}
    }

    /** ES6 names an anonymous function after the property it is stored under. */
    private class NameInference(ids: Array<Any?>?, length: Int, createKeys: Boolean) : NewLiteralStorage(ids, length, createKeys) {
        override fun attemptToInferFunctionName(value: Any?) {
            val k = keysField ?: return
            if (value !is JSFunction) return
            val fun_: BaseFunction = value
            if (fun_.get("name", fun_) != "") return
            val prefix = when (getterSettersField[index]) {
                -1 -> "get "
                1 -> "set "
                else -> ""
            }
            val propKey = k[index]
            if (propKey is Symbol) {
                val symbolName = propKey.name
                if (symbolName.isNotEmpty()) fun_.setFunctionName("$prefix[$symbolName]")
                else if (prefix.isNotEmpty()) fun_.setFunctionName(prefix)
            } else if (propKey != NativeObject.PROTO_PROPERTY) {
                fun_.setFunctionName(prefix + propKey)
            } else if (value.isShorthand) {
                fun_.setFunctionName(prefix + propKey)
            }
        }
    }

    public companion object {
        public fun create(cx: Context, ids: Array<Any?>?): NewLiteralStorage =
            if (cx.languageVersion >= Context.VERSION_ES6) NameInference(ids, -1, false) else NoInference(ids, -1, false)

        public fun create(cx: Context, length: Int, createKeys: Boolean): NewLiteralStorage =
            if (cx.languageVersion >= Context.VERSION_ES6) NameInference(null, length, createKeys) else NoInference(null, length, createKeys)
    }
}
