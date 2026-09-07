/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Collects the pieces of an object or array literal while the interpreter evaluates them, then
 * hands the runtime the keysField, valuesField and getter/setter flags in one go.
 */
abstract class NewLiteralStorage protected constructor(ids: Array<Any?>?, length: Int, createKeys: Boolean) {

    protected var keysField: Array<Any?>?
    protected var getterSettersField: IntArray
    protected var valuesField: Array<Any?>
    protected var index = 0
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

    fun pushValue(value: Any?) {
        valuesField[index] = value
        attemptToInferFunctionName(value)
        ++index
    }

    fun pushGetter(value: Any?) {
        getterSettersField[index] = -1
        pushValue(value)
    }

    fun pushSetter(value: Any?) {
        getterSettersField[index] = 1
        pushValue(value)
    }

    fun pushKey(key: Any?) {
        keysField!![index] = if (key is Symbol) key else ScriptRuntime.toString(key)
    }

    fun spread(cx: Context, scope: Scriptable, source: Any?, sourcePosition: Int) {
        val indexBefore = index
        if (keysField == null) spreadArray(cx, scope, source) else spreadObject(cx, scope, source)
        val adj = spreadAdjustments
        if (adj != null && sourcePosition < adj.size) adj[sourcePosition] = index - indexBefore - 1
    }

    private fun spreadArray(cx: Context, scope: Scriptable, source: Any?) {
        if (source == null || Undefined.isUndefined(source)) return
        val src = ScriptRuntime.toObject(cx, scope, source)
        val iteratorProp = ScriptableObject.getProperty(src, SymbolKey.ITERATOR)
        if (iteratorProp !== Scriptable.NOT_FOUND && !Undefined.isUndefined(iteratorProp)) {
            val iterator = ScriptRuntime.callIterator(src, cx, scope)
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
        // No Symbol.iterator. An array still spreads by its length, so holes come through as
        // undefined and a non-index own property is left out; anything else spreads by its own ids.
        val spreadSize = if (src is NativeArray) src.length.toInt() else src.getIds().size
        val newLen = valuesField.size + spreadSize
        getterSettersField = getterSettersField.copyOf(newLen)
        valuesField = valuesField.copyOf(newLen)
        if (src is NativeArray) {
            for (i in 0 until src.length) pushValue(NativeArray.getElem(cx, src, i))
        } else {
            for (id in src.getIds()) pushValue(getPropertyById(src, id))
        }
    }

    private fun spreadObject(cx: Context, scope: Scriptable, source: Any?) {
        if (source == null || Undefined.isUndefined(source)) return
        val src = ScriptRuntime.toObject(cx, scope, source)
        val ids: Array<Any?> =
            if (src is ScriptableObject) src.startCompoundOp(false).use { src.getIds(it, false, true) }
            else src.getIds()
        val newLen = valuesField.size + ids.size
        keysField = keysField!!.copyOf(newLen)
        getterSettersField = getterSettersField.copyOf(newLen)
        valuesField = valuesField.copyOf(newLen)
        for (id in ids) {
            val value = getPropertyById(src, id)
            pushKey(id)
            pushValue(value)
        }
    }

    val keys: Array<Any?>? get() = keysField

    val getterSetters: IntArray get() = getterSettersField

    val values: Array<Any?> get() = valuesField

    fun setSkipIndexes(skipIndexes: IntArray?) {
        this.skipIndexesField = skipIndexes
        if (skipIndexes != null && skipIndexes.isNotEmpty()) spreadAdjustments = IntArray(valuesField.size + skipIndexes.size)
    }

    fun hasSkipIndexes(): Boolean = skipIndexesField != null

    /** The holes' positions once every spread before them has been counted in. */
    val adjustedSkipIndexes: IntArray?
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

    private fun getPropertyById(src: Scriptable, id: Any?): Any? = when {
        id is String -> ScriptableObject.getProperty(src, id)
        id is Int -> ScriptableObject.getProperty(src, id)
        ScriptRuntime.isSymbol(id) -> ScriptableObject.getProperty(src, id as Symbol)
        else -> throw Kit.codeBug()
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

    companion object {
        fun create(cx: Context, ids: Array<Any?>?): NewLiteralStorage =
            if (cx.languageVersion >= Context.VERSION_ES6) NameInference(ids, -1, false) else NoInference(ids, -1, false)

        fun create(cx: Context, length: Int, createKeys: Boolean): NewLiteralStorage =
            if (cx.languageVersion >= Context.VERSION_ES6) NameInference(null, length, createKeys) else NoInference(null, length, createKeys)
    }
}
