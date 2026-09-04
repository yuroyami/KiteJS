/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ScriptableObject.DescriptorInfo

/** The array methods that also work on typed arrays and other array-likes: iteration, reduce, sort. */
object ArrayLikeAbstractOperations {

    enum class IterativeOperation {
        EVERY, FILTER, FOR_EACH, MAP, SOME, FIND, FIND_INDEX, FIND_LAST, FIND_LAST_INDEX,
    }

    enum class ReduceOperation { REDUCE, REDUCE_RIGHT }

    fun interface LengthAccessor {
        fun getLength(cx: Context, o: Scriptable): Long
    }

    fun iterativeMethod(
        cx: Context,
        operation: IterativeOperation,
        scope: Scriptable,
        thisObj: Scriptable?,
        args: Array<Any?>,
        lengthAccessor: LengthAccessor,
    ): Any? = iterativeMethod(cx, null, operation, scope, thisObj, args, lengthAccessor, true)

    fun iterativeMethod(
        cx: Context,
        fn: IdFunctionObject?,
        operation: IterativeOperation,
        scope: Scriptable,
        thisObj: Scriptable?,
        args: Array<Any?>,
        lengthAccessor: LengthAccessor,
    ): Any? = iterativeMethod(cx, fn, operation, scope, thisObj, args, lengthAccessor, false)

    private fun iterativeMethod(
        cx: Context,
        fn: IdFunctionObject?,
        operation: IterativeOperation,
        scope: Scriptable,
        thisObj: Scriptable?,
        args: Array<Any?>,
        lengthAccessor: LengthAccessor,
        skipCoercibleCheck: Boolean,
    ): Any? {
        val o = ScriptRuntime.toObject(cx, scope, thisObj)
        if (!skipCoercibleCheck) {
            if (isFind(operation)) ScriptRuntimeES6.requireObjectCoercible(cx, o, fn!!)
        }
        val length = lengthAccessor.getLength(cx, o)
        return coercibleIterativeMethod(cx, operation, scope, o, args, length)
    }

    fun iterativeMethod(
        cx: Context,
        tag: Any?,
        name: String,
        operation: IterativeOperation,
        scope: Scriptable,
        thisObj: Scriptable?,
        args: Array<Any?>,
        lengthAccessor: LengthAccessor,
    ): Any? = iterativeMethod(cx, tag, name, operation, scope, thisObj, args, lengthAccessor, false)

    private fun iterativeMethod(
        cx: Context,
        tag: Any?,
        name: String,
        operation: IterativeOperation,
        scope: Scriptable,
        thisObj: Scriptable?,
        args: Array<Any?>,
        lengthAccessor: LengthAccessor,
        skipCoercibleCheck: Boolean,
    ): Any? {
        val o = ScriptRuntime.toObject(cx, scope, thisObj)
        if (!skipCoercibleCheck) {
            if (isFind(operation)) ScriptRuntimeES6.requireObjectCoercible(cx, o, tag, name)
        }
        val length = lengthAccessor.getLength(cx, o)
        return coercibleIterativeMethod(cx, operation, scope, o, args, length)
    }

    private fun isFind(operation: IterativeOperation): Boolean =
        operation == IterativeOperation.FIND ||
            operation == IterativeOperation.FIND_INDEX ||
            operation == IterativeOperation.FIND_LAST ||
            operation == IterativeOperation.FIND_LAST_INDEX

    fun coercibleIterativeMethod(
        cx: Context,
        operation: IterativeOperation,
        scope: Scriptable,
        o: Scriptable,
        args: Array<Any?>,
        length: Long,
    ): Any? {
        if (operation == IterativeOperation.MAP && length > Int.MAX_VALUE) {
            val msg = ScriptRuntime.getMessageById("msg.arraylength.bad")
            throw ScriptRuntime.rangeError(msg)
        }
        val callbackArg = if (args.isNotEmpty()) args[0] else Undefined.instance
        val f = getCallbackArg(cx, callbackArg)
        val parent = ScriptableObject.getTopLevelScope(f)
        val thisArg: Scriptable = if (args.size < 2 || args[1] == null || args[1] === Undefined.instance) {
            parent
        } else {
            ScriptRuntime.toObject(cx, scope, args[1])
        }
        var array: Scriptable? = null
        if (operation == IterativeOperation.FILTER || operation == IterativeOperation.MAP) {
            val resultLength = if (operation == IterativeOperation.MAP) length.toInt() else 0
            array = arraySpeciesCreate(cx, scope, o, resultLength)
        }
        var j = 0L
        val backwards = operation == IterativeOperation.FIND_LAST || operation == IterativeOperation.FIND_LAST_INDEX
        val start = if (backwards) length - 1 else 0L
        val end = if (backwards) -1L else length
        val increment = if (backwards) -1L else 1L
        var i = start
        while (i != end) {
            val innerArgs = arrayOfNulls<Any?>(3)
            var elem = getRawElem(o, i)
            if (elem === Scriptable.NOT_FOUND) {
                if (isFind(operation)) {
                    elem = Undefined.instance
                } else {
                    i += increment
                    continue
                }
            }
            innerArgs[0] = elem
            innerArgs[1] = i
            innerArgs[2] = o
            val result = f.call(cx, parent, thisArg, innerArgs)
            when (operation) {
                IterativeOperation.EVERY -> if (!ScriptRuntime.toBoolean(result)) return false
                IterativeOperation.FILTER -> if (ScriptRuntime.toBoolean(result)) defineElem(cx, array!!, j++, innerArgs[0])
                IterativeOperation.FOR_EACH -> {}
                IterativeOperation.MAP -> defineElem(cx, array!!, i, result)
                IterativeOperation.SOME -> if (ScriptRuntime.toBoolean(result)) return true
                IterativeOperation.FIND, IterativeOperation.FIND_LAST -> if (ScriptRuntime.toBoolean(result)) return elem
                IterativeOperation.FIND_INDEX, IterativeOperation.FIND_LAST_INDEX ->
                    if (ScriptRuntime.toBoolean(result)) return ScriptRuntime.wrapNumber(i.toDouble())
            }
            i += increment
        }
        return when (operation) {
            IterativeOperation.EVERY -> true
            IterativeOperation.FILTER, IterativeOperation.MAP -> array
            IterativeOperation.SOME -> false
            IterativeOperation.FIND_INDEX, IterativeOperation.FIND_LAST_INDEX -> ScriptRuntime.wrapNumber(-1.0)
            else -> Undefined.instance
        }
    }

    /** ArraySpeciesCreate: a new array from `o.constructor[Symbol.species]`, or a plain one. */
    internal fun arraySpeciesCreate(cx: Context, scope: Scriptable, o: Scriptable, length: Int): Scriptable {
        if (o is NativeArray) {
            var c = ScriptableObject.getProperty(o, "constructor")
            if (c is Scriptable) {
                c = ScriptableObject.getProperty(c, SymbolKey.SPECIES)
                if (c == null || c === Scriptable.NOT_FOUND) c = Undefined.instance
            }
            if (!Undefined.isUndefined(c)) {
                if (c is Constructable) {
                    return c.construct(cx, scope, arrayOf(length.toDouble()))
                }
                throw ScriptRuntime.typeErrorById("msg.ctor.not.found", o)
            }
        }
        return cx.newArray(scope, length)
    }

    internal fun getCallbackArg(cx: Context, callbackArg: Any?): Function {
        if (callbackArg !is Function) {
            throw ScriptRuntime.notFunctionError(callbackArg)
        }
        if (cx.languageVersion >= Context.VERSION_ES6) {
            val reProxy = ScriptRuntime.getRegExpProxy(cx)
            if (reProxy != null && reProxy.isRegExp(callbackArg)) throw ScriptRuntime.notFunctionError(callbackArg)
        }
        return callbackArg
    }

    internal fun defineElem(cx: Context, target: Scriptable, index: Long, value: Any?) {
        if (!(target is NativeArray && target.getDenseOnly()) && target is ScriptableObject) {
            val desc = DescriptorInfo(true, true, true, value)
            target.defineOwnProperty(cx, index, desc)
            return
        }
        if (index > Int.MAX_VALUE) {
            val id = index.toString()
            target.put(id, target, value)
        } else {
            target.put(index.toInt(), target, value)
        }
    }

    internal fun getRawElem(target: Scriptable, index: Long): Any? {
        if (index < 0 || index > Int.MAX_VALUE) {
            return ScriptableObject.getProperty(target, index.toString())
        }
        return ScriptableObject.getProperty(target, index.toInt())
    }

    fun toSliceIndex(value: Double, length: Long): Long = when {
        value < 0.0 -> if (value + length < 0.0) 0 else (value + length).toLong()
        value > length -> length
        else -> value.toLong()
    }

    fun reduceMethod(cx: Context, operation: ReduceOperation, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        val o = ScriptRuntime.toObject(cx, scope, thisObj)
        val length = NativeArray.getLengthProperty(cx, o)
        return reduceMethodWithLength(cx, operation, scope, o, args, length)
    }

    fun reduceMethodWithLength(
        cx: Context,
        operation: ReduceOperation,
        scope: Scriptable,
        o: Scriptable,
        args: Array<Any?>,
        length: Long,
    ): Any? {
        val callbackArg = if (args.isNotEmpty()) args[0] else Undefined.instance
        if (callbackArg == null || callbackArg !is Function) {
            throw ScriptRuntime.notFunctionError(callbackArg)
        }
        val parent = ScriptableObject.getTopLevelScope(callbackArg)
        val movingLeft = operation == ReduceOperation.REDUCE
        var value: Any? = if (args.size > 1) args[1] else Scriptable.NOT_FOUND
        for (i in 0 until length) {
            val index = if (movingLeft) i else (length - 1 - i)
            val elem = getRawElem(o, index)
            if (elem === Scriptable.NOT_FOUND) continue
            if (value === Scriptable.NOT_FOUND) {
                value = elem
            } else {
                val innerArgs = arrayOf(value, elem, index, o)
                value = callbackArg.call(cx, parent, parent, innerArgs)
            }
        }
        if (value === Scriptable.NOT_FOUND) {
            throw ScriptRuntime.typeErrorById("msg.empty.array.reduce")
        }
        return value
    }

    fun getSortComparator(cx: Context, scope: Scriptable, args: Array<Any?>): Comparator<Any?> =
        if (args.isNotEmpty() && Undefined.instance !== args[0]) {
            getSortComparatorFromArguments(cx, scope, args)
        } else {
            DEFAULT_COMPARATOR
        }

    fun getSortComparatorFromArguments(cx: Context, scope: Scriptable, args: Array<Any?>): ElementComparator {
        val compareFunc = ScriptRuntime.getValueAndThis(args[0], cx)!!
        val compare = compareFunc.getCallable()
        val compareThis = compareFunc.getThis()
        val cmpBuf = arrayOfNulls<Any?>(2) // Buffer for cmp arguments
        return ElementComparator(
            Comparator { x, y ->
                cmpBuf[0] = x
                cmpBuf[1] = y
                val ret = compare.call(cx, scope, compareThis, cmpBuf)
                val d = ScriptRuntime.toNumber(ret)
                val cmp = d.compareTo(0.0)
                if (cmp < 0) -1 else if (cmp > 0) 1 else 0
            },
        )
    }

    private val STRING_COMPARATOR: Comparator<Any?> = StringLikeComparator()
    private val DEFAULT_COMPARATOR: Comparator<Any?> = ElementComparator()

    class StringLikeComparator : Comparator<Any?> {
        override fun compare(a: Any?, b: Any?): Int {
            val x = ScriptRuntime.toString(a)
            val y = ScriptRuntime.toString(b)
            return x.compareTo(y)
        }
    }

    /** Sorts holes last, `undefined` just before them, and everything else by [child]. */
    class ElementComparator(private val child: Comparator<Any?>) : Comparator<Any?> {

        constructor() : this(STRING_COMPARATOR)

        override fun compare(a: Any?, b: Any?): Int {
            if (a === Undefined.instance) {
                if (b === Undefined.instance) return 0
                if (b === Scriptable.NOT_FOUND) return -1
                return 1
            } else if (a === Scriptable.NOT_FOUND) {
                return if (b === Scriptable.NOT_FOUND) 0 else 1
            }
            if (b === Scriptable.NOT_FOUND) return -1
            if (b === Undefined.instance) return -1
            return child.compare(a, b)
        }
    }
}
