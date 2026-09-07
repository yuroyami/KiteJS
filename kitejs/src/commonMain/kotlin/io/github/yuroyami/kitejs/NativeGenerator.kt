/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The pre-ES6 generator object from JavaScript 1.7: `send`, `next`, `throw`, `close` and the legacy
 * `__iterator__`. Language versions at or above ES6 get [ES6Generator] instead.
 */
class NativeGenerator : IdScriptableObject {

    private var function: JSFunction? = null
    private var savedState: Any? = null
    private var lineSource: String? = null
    private var lineNumber: Int = 0
    private var firstTime: Boolean = true

    /** Only for building the prototype object. */
    private constructor() : super()

    constructor(scope: Scriptable, function: JSFunction, savedState: Any?) : super() {
        this.function = function
        this.savedState = savedState
        // There is no Generator constructor in the top scope, so the prototype is read from the
        // value associated with the scope instead.
        val top = getTopLevelScope(scope)
        this.parentScope = top
        this.prototype = getTopScopeValue(top, GENERATOR_TAG) as? Scriptable
    }

    override val className: String
        get() = "Generator"

    override fun initPrototypeId(id: Int) {
        val s: String
        val arity: Int
        when (id) {
            Id_close -> { arity = 1; s = "close" }
            Id_next -> { arity = 1; s = "next" }
            Id_send -> { arity = 0; s = "send" }
            Id_throw -> { arity = 0; s = "throw" }
            Id___iterator__ -> { arity = 1; s = "__iterator__" }
            else -> throw IllegalArgumentException(id.toString())
        }
        initPrototypeMethod(GENERATOR_TAG, id, s, arity)
    }

    override fun execIdCall(f: IdFunctionObject, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        if (!f.hasTag(GENERATOR_TAG)) {
            return super.execIdCall(f, cx, scope, thisObj, args)
        }
        val generator = ensureType<NativeGenerator>(thisObj, f.functionName)
        return when (f.methodId()) {
            // Closing still has to run any pending finally clauses.
            Id_close -> generator.resume(cx, scope, GENERATOR_CLOSE, GeneratorClosedException())
            Id_next -> {
                // Arguments to next() are ignored.
                generator.firstTime = false
                generator.resume(cx, scope, GENERATOR_SEND, Undefined.instance)
            }
            Id_send -> {
                val arg = if (args.isNotEmpty()) args[0] else Undefined.instance
                if (generator.firstTime && arg != Undefined.instance) {
                    throw ScriptRuntime.typeErrorById("msg.send.newborn")
                }
                generator.resume(cx, scope, GENERATOR_SEND, arg)
            }
            Id_throw -> generator.resume(cx, scope, GENERATOR_THROW, if (args.isNotEmpty()) args[0] else Undefined.instance)
            Id___iterator__ -> thisObj
            else -> throw IllegalArgumentException(f.methodId().toString())
        }
    }

    private fun resume(cx: Context, scope: Scriptable, operation: Int, value: Any?): Any? {
        if (savedState == null) {
            if (operation == GENERATOR_CLOSE) return Undefined.instance
            val thrown = if (operation == GENERATOR_THROW) {
                value
            } else {
                NativeIterator.getStopIterationObject(scope)
            }
            throw JavaScriptException(thrown, lineSource, lineNumber)
        }
        try {
            // Upstream locks here for reentrancy; the port is single-thread confined (D-3), so the
            // flag would only ever be read by the thread that set it.
            return function!!.resumeGenerator(cx, scope, operation, savedState, value)
        } catch (e: GeneratorClosedException) {
            // Closing throws this so every pending finally runs without user code seeing it.
            return Undefined.instance
        } catch (e: RhinoException) {
            lineNumber = e.lineNumber
            lineSource = e.lineSource
            savedState = null
            throw e
        } finally {
            if (operation == GENERATOR_CLOSE) savedState = null
        }
    }

    override fun findPrototypeId(name: String): Int = when (name) {
        "close" -> Id_close
        "next" -> Id_next
        "send" -> Id_send
        "throw" -> Id_throw
        "__iterator__" -> Id___iterator__
        else -> 0
    }

    /** Thrown into a generator to run its `finally` blocks when it is closed early. */
    class GeneratorClosedException(val value: Any? = Undefined.instance) : RuntimeException()

    companion object {
        private val GENERATOR_TAG: Any = "Generator"

        const val GENERATOR_SEND: Int = 0
        const val GENERATOR_THROW: Int = 1
        const val GENERATOR_CLOSE: Int = 2

        private const val Id_close = 1
        private const val Id_next = 2
        private const val Id_send = 3
        private const val Id_throw = 4
        private const val Id___iterator__ = 5
        private const val MAX_PROTOTYPE_ID = 5

        internal fun init(scope: ScriptableObject, sealed: Boolean): NativeGenerator {
            // exportAsJSClass is not used: "Generator" must not become a top-level constructor.
            val prototype = NativeGenerator()
            prototype.parentScope = scope
            prototype.prototype = getObjectPrototype(scope)
            prototype.activatePrototypeMap(MAX_PROTOTYPE_ID)
            if (sealed) prototype.sealObject()
            // Generator instances find this prototype through the scope, since there is no
            // constructor to read it from.
            scope.associateValue(GENERATOR_TAG, prototype)
            return prototype
        }
    }
}
