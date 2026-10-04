/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.facade

import io.github.yuroyami.kitejs.api.ConsoleLevel
import io.github.yuroyami.kitejs.api.Converters
import io.github.yuroyami.kitejs.api.JsArray
import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.JsException
import io.github.yuroyami.kitejs.api.JsFunction
import io.github.yuroyami.kitejs.api.JsObject
import io.github.yuroyami.kitejs.api.JsScript
import io.github.yuroyami.kitejs.api.JsStackFrame
import io.github.yuroyami.kitejs.api.JsSymbol
import io.github.yuroyami.kitejs.api.JsSyntaxError
import io.github.yuroyami.kitejs.api.JsUndefined
import io.github.yuroyami.kitejs.api.JsValue
import io.github.yuroyami.kitejs.api.KBigInt
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.quickjs.QuickJs
import io.github.yuroyami.kitejs.quickjs.QuickJsConfig
import io.github.yuroyami.kitejs.quickjs.bridge.HandleCleaner
import io.github.yuroyami.kitejs.quickjs.bridge.QuickJsBridge
import io.github.yuroyami.kitejs.quickjs.bridge.QuickJsBridge.Companion.FALSE
import io.github.yuroyami.kitejs.quickjs.bridge.QuickJsBridge.Companion.NULL
import io.github.yuroyami.kitejs.quickjs.bridge.QuickJsBridge.Companion.TRUE
import io.github.yuroyami.kitejs.quickjs.bridge.QuickJsBridge.Companion.UNDEFINED
import io.github.yuroyami.kitejs.quickjs.bridge.QuickJsHost
import io.github.yuroyami.kitejs.quickjs.bridge.bridge
import io.github.yuroyami.kitejs.quickjs.bridge.currentThreadToken
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.datetime.offsetAt

/** The prelude's helpers, in the order it hands them back. */
internal enum class Helper {
    GET, SET, HAS, DELETE, KEYS, CALL_METHOD, BIND, TO_STRING_PRIMITIVE, TO_NUMBER_PRIMITIVE, DEFINE_VALUE,
    DEFINE_ACCESSOR, THENABLE, WATCH, BIGINT, DESCRIPTION, STACK, MAKE_CONSTRUCTOR,
}

/**
 * A QuickJS engine behind the API. Every value the host holds is a handle into the C side's
 * table; a handle's wrapper frees its slot when the collector takes the wrapper, and closing the
 * engine frees them all.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class QuickJsKiteJs private constructor(
    private val config: QuickJsConfig,
    private val id: Int,
) : KiteJs() {

    private val bridge: QuickJsBridge = bridge()
    private var ptr: Long = 0
    private var closed = false
    private val owner: Any = currentThreadToken()

    /** How many calls from the host are on the stack; only the outermost meters and drains. */
    private var depth = 0

    /** What a host function or the interrupt hook threw, on its way out of the script. */
    private var hostFailure: Throwable? = null

    private val functions = HashMap<Int, (JsValue, List<JsValue>) -> Any?>()
    private var nextFunction = 0

    /** Handles whose wrappers the collector took, from whichever thread it runs on. */
    private val collected = AtomicReference<List<Int>>(emptyList())
    private val cleaner = HandleCleaner { h ->
        while (true) {
            val now = collected.load()
            if (collected.compareAndSet(now, now + h)) break
        }
    }

    private var helpers = IntArray(0)
    private val started = TimeSource.Monotonic.markNow()

    override val engine: QuickJs get() = QuickJs

    override lateinit var global: JsObject
        private set

    override val version: String get() = "KiteJS on ${bridge.version()}"

    // ---- KiteJs -----------------------------------------------------------------------------

    override fun evaluate(source: String, fileName: String): JsValue = call {
        val compiled = compileHandle(source, fileName)
        try {
            toJs(check(bridge.run(ptr, compiled)))
        } finally {
            bridge.release(ptr, compiled)
        }
    }

    override fun compile(source: String, fileName: String): JsScript = call(drain = false) {
        QuickJsScript(this, compileHandle(source, fileName))
    }

    fun run(script: QuickJsScript): JsValue = call { toJs(check(bridge.run(ptr, script.handle))) }

    override fun runMicrotasks() {
        call { }
    }

    override fun valueOf(value: Any?): JsValue = call(drain = false) { toJs(toHandle(value)) }

    override fun newObject(): JsObject = call(drain = false) { toJs(bridge.newObject(ptr)).asObject() }

    override fun newArray(vararg elements: Any?): JsArray = call(drain = false) { toJs(toHandle(elements)).asArray() }

    override fun close() {
        if (closed) return
        if (currentThreadToken() !== owner) throw wrongThread()
        if (depth > 0) throw JsEngineError("this engine is running a script; close it once that call has returned")
        closed = true
        functions.clear()
        QuickJsHost.unregister(id)
        if (ptr != 0L) bridge.free(ptr)
        ptr = 0
    }

    override fun newFunction(name: String, arity: Int, body: (self: JsValue, args: List<JsValue>) -> Any?): JsFunction =
        call(drain = false) { hostFunction(name, arity, body) }

    override fun newConstructor(name: String, arity: Int, build: (JsObject, List<JsValue>) -> Unit): JsFunction =
        call(drain = false) {
            val impl = hostFunction(name, arity) { self, args -> build(self.asObject(), args) }
            helper(Helper.MAKE_CONSTRUCTOR, name, arity, impl).asFunction()
        }

    override fun thenableCheck(obj: JsObject): Boolean = call(drain = false) { helper(Helper.THENABLE, obj).asBoolean() }

    override fun watchSettlement(value: JsValue, onSettled: (JsValue, JsValue?) -> Unit): Boolean = call {
        val target = value.raw
        val isPromise = target is QuickJsHandle && target.engine === this && bridge.isPromise(ptr, target.handle)
        val ok = hostFunction("", 1) { _, args -> onSettled(args.firstOrNull() ?: JsValue.undefined, null) }
        val fail = hostFunction("", 1) { _, args -> onSettled(JsValue.undefined, args.firstOrNull() ?: JsValue.undefined) }
        helper(Helper.WATCH, value, ok, fail, isPromise).asBoolean()
    }

    // ---- Calls from the host ----------------------------------------------------------------

    /** True when this engine is open and this is its thread, so its handles can be used here. */
    val isUsableHere: Boolean get() = !closed && currentThreadToken() === owner

    /**
     * Runs [body] as a call from the host: on this engine's thread, with what it throws in the
     * API's shape. The outermost call is one call to the budget and, when [drain] is set, runs
     * the Promise jobs it queued before it returns.
     */
    fun <T> call(drain: Boolean = true, body: () -> T): T {
        if (closed) throw JsEngineError("this engine is closed")
        if (currentThreadToken() !== owner) throw wrongThread()
        val outermost = depth == 0
        if (outermost) {
            sweep()
            bridge.enter(ptr)
            bridge.setBudget(ptr, config.instructionBudget.toDouble())
            hostFailure = null
        }
        depth++
        try {
            val result = body()
            if (outermost && drain && bridge.drain(ptr) < 0) raise()
            // A host failure or a stop that a script swallowed, as a Promise executor swallows
            // what it throws, still ends the call.
            hostFailure?.let { failure ->
                hostFailure = null
                throw failure
            }
            stopError()?.let { throw it }
            return result
        } catch (e: Throwable) {
            if (!outermost) throw e
            if (bridge.stopReason(ptr) != QuickJsBridge.STOP_NONE) bridge.discardJobs(ptr)
            throw when (e) {
                is JsException -> e
                else -> JsEngineError(e.message ?: e::class.simpleName ?: "the host threw", e)
            }
        } finally {
            depth--
        }
    }

    private fun wrongThread(): JsEngineError =
        JsEngineError("this engine belongs to the thread that opened it; use it and close it there")

    /** Frees what the collector took and forgets the host functions QuickJS let go of. */
    private fun sweep() {
        cleaner.poll()
        val dead = collected.exchange(emptyList())
        for (h in dead) bridge.release(ptr, h)
        while (true) {
            val fn = bridge.deadFunction(ptr)
            if (fn < 0) break
            functions.remove(fn)
        }
    }

    /** [h] when the C side answered one, or what was thrown. */
    fun check(h: Int): Int = if (h < 0) raise() else h

    /** Takes over what was thrown and raises it in the API's shape. */
    fun raise(): Nothing {
        val h = bridge.exception(ptr)
        hostFailure?.let { failure ->
            hostFailure = null
            bridge.release(ptr, h)
            throw failure
        }
        stopError()?.let { error ->
            bridge.release(ptr, h)
            throw error
        }
        throw errorOf(h)
    }

    private fun stopError(): JsEngineError? = when (bridge.stopReason(ptr)) {
        QuickJsBridge.STOP_BUDGET -> JsEngineError("script used more than ${config.instructionBudget} instructions and was stopped")
        QuickJsBridge.STOP_HOOK, QuickJsBridge.STOP_HOOK_THREW -> JsEngineError("script was interrupted")
        QuickJsBridge.STOP_MEMORY -> JsEngineError("the engine ran out of memory")
        else -> null
    }

    /** What a thrown value [h] becomes: a [JsError] with the script's frames, as a rule. */
    private fun errorOf(h: Int): JsException = errorOf(toJs(h))

    private fun errorOf(value: JsValue): JsException {
        val base = JsError.from(value)
        if (base.name == "InternalError" && base.errorMessage == "out of memory") {
            return JsEngineError("the engine ran out of memory")
        }
        return JsError(value, base.name, base.errorMessage, framesOf(value), null)
    }

    private fun framesOf(value: JsValue): List<JsStackFrame> {
        if (value.asObjectOrNull() == null) return emptyList()
        val stack = try {
            helper(Helper.STACK, value)
        } catch (e: JsError) {
            return emptyList()
        }
        if (stack.isUndefined) return emptyList()
        return stack.asString().lineSequence().mapNotNull(::frameOf).toList()
    }

    private fun compileHandle(source: String, fileName: String): Int {
        val h = bridge.compile(ptr, source, fileName)
        if (h >= 0) return h
        val thrown = bridge.exception(ptr)
        val value = toJs(thrown)
        val base = JsError.from(value)
        hostFailure?.let { failure ->
            hostFailure = null
            throw failure
        }
        if (base.name != "SyntaxError") throw errorOf(value)
        val where = helper(Helper.STACK, value).takeIf { !it.isUndefined }?.asString()
            ?.lineSequence()?.firstNotNullOfOrNull { LOCATION.find(it) }
        throw JsSyntaxError(
            base.errorMessage,
            where?.groupValues?.get(1) ?: fileName,
            where?.groupValues?.get(2)?.toInt() ?: 0,
            where?.groupValues?.get(3)?.toInt() ?: 0,
            null,
            null,
        )
    }

    // ---- Host functions, and the callbacks from C ------------------------------------------

    private fun hostFunction(name: String, arity: Int, body: (JsValue, List<JsValue>) -> Any?): JsFunction {
        val fn = nextFunction++
        functions[fn] = body
        val h = bridge.newFunction(ptr, fn, name, arity)
        if (h < 0) {
            functions.remove(fn)
            raise()
        }
        return toJs(h).asFunction()
    }

    fun hostCall(fn: Int, argc: Int): Int {
        return try {
            val selfHandle = bridge.cbThis(ptr)
            val argHandles = IntArray(argc) { bridge.cbArg(ptr, it) }
            val self = toJs(selfHandle)
            val args = argHandles.map { toJs(it) }
            val body = functions[fn] ?: throw JsEngineError("this host function belongs to an engine that has let it go")
            toHandle(body(self, args))
        } catch (t: Throwable) {
            hostFailure = t
            -1
        }
    }

    fun hostInterrupt(): Int = try {
        if (config.interruptWhen?.invoke() == true) 1 else 0
    } catch (t: Throwable) {
        hostFailure = t
        2
    }

    fun hostNow(): Double = try {
        config.clock?.invoke() ?: 0.0
    } catch (t: Throwable) {
        hostFailure = t
        0.0
    }

    fun hostTimeZoneOffset(time: Double): Int = try {
        val millis = if (time.isNaN()) 0L else time.coerceIn(-8.64e15, 8.64e15).toLong()
        -config.timeZone.offsetAt(Instant.fromEpochMilliseconds(millis)).totalSeconds / 60
    } catch (t: Throwable) {
        hostFailure = t
        0
    }

    // ---- Values between Kotlin and C -----------------------------------------------------------

    /** The value [h] holds, taking the handle over. */
    fun toJs(h: Int): JsValue {
        return when (bridge.type(ptr, h)) {
            QuickJsBridge.TYPE_UNDEFINED -> scalar(h, JsValue.undefined)
            QuickJsBridge.TYPE_NULL -> scalar(h, JsValue.nullValue)
            QuickJsBridge.TYPE_BOOLEAN -> scalar(h, JsValue(bridge.number(ptr, h) != 0.0))
            QuickJsBridge.TYPE_NUMBER -> scalar(h, JsValue(bridge.number(ptr, h)))
            QuickJsBridge.TYPE_STRING -> scalar(h, JsValue(bridge.string(ptr, h) ?: ""))
            QuickJsBridge.TYPE_BIGINT -> scalar(h, JsValue(KBigInt.parse(bridge.string(ptr, h) ?: "0")))
            QuickJsBridge.TYPE_SYMBOL -> {
                val description = toJs(callHelper(Helper.DESCRIPTION, intArrayOf(h)))
                JsValue(QuickJsSymbol(this, adopt(h), description.raw as? String))
            }
            QuickJsBridge.TYPE_ARRAY -> JsValue(QuickJsArray(this, adopt(h)))
            QuickJsBridge.TYPE_FUNCTION -> JsValue(QuickJsFunction(this, adopt(h)))
            else -> JsValue(QuickJsObject(this, adopt(h)))
        }
    }

    private fun scalar(h: Int, value: JsValue): JsValue {
        bridge.release(ptr, h)
        return value
    }

    /** A handle a wrapper is about to own: its identity, and a cleaner for when the wrapper goes. */
    private fun adopt(h: Int): Adopted = Adopted(h, bridge.identity(ptr, h))

    internal class Adopted(val handle: Int, val identity: Double)

    /** Frees [handle] once [owner], its wrapper, is collected. */
    fun register(owner: Any, handle: Int): Any? = cleaner.register(owner, handle)

    /** A handle the caller owns and has to release, for [value] built in this engine. */
    fun toHandle(value: Any?): Int = Converters.toEngine(
        value,
        scalar = ::scalarHandle,
        array = { elements ->
            val array = bridge.newArray(ptr)
            for (e in elements) {
                bridge.arrayPush(ptr, array, e)
                bridge.release(ptr, e)
            }
            array
        },
        obj = { entries ->
            val obj = bridge.newObject(ptr)
            for ((k, v) in entries) {
                bridge.objectPut(ptr, obj, k, v)
                bridge.release(ptr, v)
            }
            obj
        },
    )

    private fun scalarHandle(value: Any?): Int = when (value) {
        JsUndefined -> UNDEFINED
        null -> NULL
        true -> TRUE
        false -> FALSE
        is Double -> bridge.newNumber(ptr, value)
        is String -> check(bridge.newString(ptr, value))
        is KBigInt -> {
            val text = check(bridge.newString(ptr, value.toString()))
            try {
                callHelper(Helper.BIGINT, intArrayOf(text))
            } finally {
                bridge.release(ptr, text)
            }
        }
        is QuickJsHandle -> bridge.dup(ptr, ownHandle(value))
        is QuickJsSymbol -> bridge.dup(ptr, ownHandle(value))
        is JsObject, is JsSymbol -> throw foreign()
        else -> throw JsEngineError("a ${value::class.simpleName} has no JavaScript form")
    }

    private fun ownHandle(value: Any): Int = when (value) {
        is QuickJsHandle -> if (value.engine === this) value.handle else throw foreign()
        is QuickJsSymbol -> if (value.engine === this) value.handle else throw foreign()
        else -> throw foreign()
    }

    private fun foreign(): JsEngineError =
        JsEngineError("this value belongs to another engine, and values cannot move between engines")

    /** Calls a prelude helper with handles it borrows, answering the handle it gives back. */
    private fun callHelper(helper: Helper, args: IntArray): Int {
        for (a in args) bridge.pushArg(ptr, a)
        return check(bridge.call(ptr, helpers[helper.ordinal], UNDEFINED))
    }

    /** Calls a prelude helper with Kotlin values. Only inside [call]. */
    fun helper(helper: Helper, vararg args: Any?): JsValue = toJs(callWith(helpers[helper.ordinal], UNDEFINED, args))

    /** Calls [fn] with `this` [self] and [args] converted, answering the result's handle. */
    fun callWith(fn: Int, self: Int, args: Array<out Any?>): Int {
        val handles = IntArray(args.size)
        var made = 0
        try {
            while (made < args.size) {
                handles[made] = toHandle(args[made])
                made++
            }
            for (h in handles) bridge.pushArg(ptr, h)
        } finally {
            for (i in 0 until made) bridge.release(ptr, handles[i])
        }
        return check(bridge.call(ptr, fn, self))
    }

    fun constructWith(fn: Int, args: Array<out Any?>): Int {
        val handles = IntArray(args.size)
        var made = 0
        try {
            while (made < args.size) {
                handles[made] = toHandle(args[made])
                made++
            }
            for (h in handles) bridge.pushArg(ptr, h)
        } finally {
            for (i in 0 until made) bridge.release(ptr, handles[i])
        }
        return check(bridge.construct(ptr, fn))
    }

    fun releaseHandle(h: Int) = bridge.release(ptr, h)

    /** The own enumerable keys of [obj], as `Object.keys` lists them. */
    fun keysOf(obj: QuickJsHandle): List<String> = call(drain = false) {
        val array = callWith(helpers[Helper.KEYS.ordinal], UNDEFINED, arrayOf(obj))
        try {
            List(bridge.arrayLength(ptr, array)) { i ->
                val key = bridge.arrayGet(ptr, array, i)
                try {
                    bridge.string(ptr, key) ?: ""
                } finally {
                    bridge.release(ptr, key)
                }
            }
        } finally {
            bridge.release(ptr, array)
        }
    }

    // ---- Opening ------------------------------------------------------------------------------

    private fun start() {
        var options = QuickJsBridge.OPT_TIME_ZONE
        if (config.clock != null) options = options or QuickJsBridge.OPT_CLOCK
        ptr = bridge.newEngine(id, config.memoryLimit.toDouble(), config.maxStackSize.toDouble(), options)
        if (ptr == 0L) throw JsEngineError("QuickJS could not start")
        call(drain = false) {
            // The prelude runs whatever budget the configuration has, so it is left unmetered.
            bridge.setBudget(ptr, 0.0)
            global = toJs(bridge.global(ptr)).asObject()
            val compiled = compileHandle(PRELUDE, PRELUDE_FILE)
            val factory = try {
                check(bridge.run(ptr, compiled))
            } finally {
                bridge.release(ptr, compiled)
            }
            try {
                val printer = config.console
                val print = printer?.let {
                    hostFunction("print", 2) { _, args ->
                        printer.print(ConsoleLevel.entries[args[0].asInt()], args[1].asString())
                    }
                }
                val monotonic = hostFunction("monotonic", 0) { _, _ -> started.elapsedNow().inWholeNanoseconds / 1_000_000.0 }
                val list = callWith(factory, UNDEFINED, arrayOf(print, monotonic, config.sealBuiltins))
                try {
                    helpers = IntArray(Helper.entries.size) { bridge.arrayGet(ptr, list, it) }
                } finally {
                    bridge.release(ptr, list)
                }
            } finally {
                bridge.release(ptr, factory)
            }
        }
        if (config.interruptWhen != null) bridge.askHost(ptr, true)
    }

    companion object {
        fun open(config: QuickJsConfig): QuickJsKiteJs {
            val id = QuickJsHost.nextId()
            val engine = QuickJsKiteJs(config, id)
            QuickJsHost.register(id, engine)
            try {
                engine.start()
            } catch (e: Throwable) {
                engine.closeAfterFailedStart()
                throw when (e) {
                    is JsException -> e
                    else -> JsEngineError(e.message ?: "the engine could not start", e)
                }
            }
            return engine
        }

        /** Where a QuickJS stack line says code was: file, line, column. */
        private val LOCATION = Regex("""(?:\(|at )([^()]*?):(\d+):(\d+)\)?\s*$""")

        /** One line of a QuickJS stack: `at name (file:line:column)` or `at file:line:column`. */
        private val FRAME = Regex("""^\s*at (?:(.*?) \((.*):(\d+):(\d+)\)|(.*):(\d+):(\d+))\s*$""")

        private fun frameOf(line: String): JsStackFrame? {
            val m = FRAME.matchEntire(line) ?: return null
            val g = m.groupValues
            val (name, file, lineNumber) = if (g[2].isNotEmpty()) Triple(g[1], g[2], g[3]) else Triple("", g[5], g[6])
            if (file == PRELUDE_FILE) return null
            val function = name.takeIf { it.isNotEmpty() && it != "<eval>" && it != "<anonymous>" }
            return JsStackFrame(function, file, lineNumber.toInt())
        }
    }

    private fun closeAfterFailedStart() {
        closed = true
        functions.clear()
        QuickJsHost.unregister(id)
        if (ptr != 0L) bridge.free(ptr)
        ptr = 0
    }
}
