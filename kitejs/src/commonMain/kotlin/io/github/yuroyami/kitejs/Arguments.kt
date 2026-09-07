/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The `arguments` object of a call. In sloppy mode its indexed slots alias the named parameters,
 * so writing one shows through the other.
 */
internal open class Arguments(private val activation: NativeCall, cx: Context) : ScriptableObject() {

    private var calleeObj: Any?
    private val lengthObj: Any
    private var args: Array<Any?>

    init {
        val parent = activation.parentScope!!
        parentScope = parent
        prototype = getObjectPrototype(parent)
        args = activation.originalArgs
        lengthObj = args.size
        val f = activation.function
        calleeObj = f
        // Without the standard objects there is no Array.prototype.values to borrow. Upstream never
        // runs in that state; this port does during the tests that come before phase 3.8.
        val arrayProto = TopLevel.getBuiltinPrototype(getTopLevelScope(parent), TopLevel.Builtins.Array)
        if (arrayProto != null) defineProperty(SymbolKey.ITERATOR, arrayProto.get("values", parent), DONTENUM)
        defineProperty("length", lengthObj, DONTENUM)
        if (activation.isStrict) {
            val typeErrorThrower = ScriptRuntime.typeErrorThrower(cx)
            val version = cx.languageVersion
            if (version <= Context.VERSION_1_8) {
                setGetterOrSetter("caller", 0, typeErrorThrower, true)
                setGetterOrSetter("caller", 0, typeErrorThrower, false)
                setGetterOrSetter("callee", 0, typeErrorThrower, true)
                setGetterOrSetter("callee", 0, typeErrorThrower, false)
                setAttributes("caller", DONTENUM or PERMANENT)
                setAttributes("callee", DONTENUM or PERMANENT)
            } else {
                setGetterOrSetter("callee", 0, typeErrorThrower, true)
                setGetterOrSetter("callee", 0, typeErrorThrower, false)
                setAttributes("callee", DONTENUM or PERMANENT)
            }
            calleeObj = null
        } else {
            defineProperty("callee", calleeObj, DONTENUM)
            val version = cx.languageVersion
            if (version <= Context.VERSION_1_3 && version != Context.VERSION_DEFAULT) {
                defineProperty("caller", null, DONTENUM)
            }
        }
    }

    override val className: String
        get() = CLASS_NAME

    private fun arg(index: Int): Any? = if (index < 0 || args.size <= index) Scriptable.NOT_FOUND else args[index]

    private fun putIntoActivation(index: Int, value: Any?) {
        val argName = activation.function!!.getParamOrVarName(index)
        activation.put(argName, activation, value)
    }

    private fun getFromActivation(index: Int): Any? {
        val argName = activation.function!!.getParamOrVarName(index)
        return activation.get(argName, activation)
    }

    private fun replaceArg(index: Int, value: Any?) {
        if (sharedWithActivation(index)) putIntoActivation(index, value)
        if (args === activation.originalArgs) args = args.copyOf()
        args[index] = value
    }

    private fun removeArg(index: Int) {
        if (args[index] !== Scriptable.NOT_FOUND) {
            if (args === activation.originalArgs) args = args.copyOf()
            args[index] = Scriptable.NOT_FOUND
        }
    }

    override fun has(index: Int, start: Scriptable): Boolean {
        if (arg(index) !== Scriptable.NOT_FOUND) return true
        return super.has(index, start)
    }

    override fun get(index: Int, start: Scriptable): Any? {
        val value = arg(index)
        if (value === Scriptable.NOT_FOUND) return super.get(index, start)
        if (sharedWithActivation(index)) return getFromActivation(index)
        return value
    }

    /** Whether slot [index] is the same variable as a named parameter. */
    private fun sharedWithActivation(index: Int): Boolean {
        if (Context.getContext().isStrictMode) return false
        val f = activation.function
        if (f == null || f.hasDefaultParameters()) return false
        val definedCount = f.paramCount
        if (index < definedCount) {
            // A later parameter with the same name takes the slot.
            if (index < definedCount - 1) {
                val argName = f.getParamOrVarName(index)
                for (i in index + 1 until definedCount) {
                    if (argName == f.getParamOrVarName(i)) return false
                }
            }
            return true
        }
        return false
    }

    override fun put(index: Int, start: Scriptable, value: Any?) {
        if (arg(index) === Scriptable.NOT_FOUND) super.put(index, start, value) else replaceArg(index, value)
    }

    override fun delete(index: Int) {
        if (index in args.indices) removeArg(index)
        super.delete(index)
    }

    override fun getIds(map: CompoundOperationMap, getNonEnumerable: Boolean, getSymbols: Boolean): Array<Any?> {
        var ids = super.getIds(map, getNonEnumerable, getSymbols)
        if (args.isNotEmpty()) {
            val present = BooleanArray(args.size)
            var extraCount = args.size
            for (id in ids) {
                if (id is Int && id in args.indices && !present[id]) {
                    present[id] = true
                    extraCount--
                }
            }
            if (!getNonEnumerable) {
                // A deleted slot that was then given a non-enumerable property stays out.
                for (i in present.indices) {
                    if (!present[i] && super.has(i, this)) {
                        present[i] = true
                        extraCount--
                    }
                }
            }
            if (extraCount != 0) {
                val tmp = arrayOfNulls<Any?>(extraCount + ids.size)
                ids.copyInto(tmp, extraCount)
                ids = tmp
                var offset = 0
                for (i in args.indices) {
                    if (!present[i]) ids[offset++] = i
                }
                if (offset != extraCount) throw Kit.codeBug()
            }
        }
        return ids
    }

    override fun getOwnPropertyDescriptor(cx: Context, id: Any?): DescriptorInfo? {
        if (ScriptRuntime.isSymbol(id) || id is Scriptable) return super.getOwnPropertyDescriptor(cx, id)
        val d = ScriptRuntime.toNumber(id)
        val index = d.toInt()
        if (d != index.toDouble()) return super.getOwnPropertyDescriptor(cx, id)
        var value = arg(index)
        if (value === Scriptable.NOT_FOUND) return super.getOwnPropertyDescriptor(cx, id)
        if (sharedWithActivation(index)) value = getFromActivation(index)
        if (super.has(index, this)) {
            // The slot was given attributes through defineProperty, so keep them.
            val desc = super.getOwnPropertyDescriptor(cx, id)!!
            desc.value = value
            return desc
        }
        return buildDataDescriptor(value, EMPTY)
    }

    override fun defineOwnProperty(cx: Context, id: Any?, desc: DescriptorInfo, checkValid: Boolean): Boolean {
        super.defineOwnProperty(cx, id, desc, checkValid)
        if (ScriptRuntime.isSymbol(id)) return true
        val d = ScriptRuntime.toNumber(id)
        val index = d.toInt()
        if (d != index.toDouble()) return true
        val value = arg(index)
        if (value === Scriptable.NOT_FOUND) return true
        if (desc.isAccessorDescriptor) {
            removeArg(index)
            return true
        }
        val newValue = desc.value
        if (newValue === Scriptable.NOT_FOUND) return true
        replaceArg(index, newValue)
        if (isFalse(desc.writable)) removeArg(index)
        return true
    }

    /** What `<function>.arguments` gives in ES6: a copy that refuses every write. */
    internal class ReadonlyArguments(arguments: Arguments, cx: Context) : Arguments(arguments.activation, cx) {
        private val initialized = true

        override fun put(index: Int, start: Scriptable, value: Any?) {
            if (initialized) return
            super.put(index, start, value)
        }

        override fun put(name: String, start: Scriptable, value: Any?) {
            if (initialized) return
            super.put(name, start, value)
        }

        override fun put(key: Symbol, start: Scriptable, value: Any?) {
            if (initialized) return
            super.put(key, start, value)
        }

        override fun delete(index: Int) {
            if (initialized) return
            super.delete(index)
        }

        override fun delete(name: String) {
            if (initialized) return
            super.delete(name)
        }

        override fun delete(key: Symbol) {
            if (initialized) return
            super.delete(key)
        }
    }

    private companion object {
        const val CLASS_NAME = "Arguments"
    }
}
