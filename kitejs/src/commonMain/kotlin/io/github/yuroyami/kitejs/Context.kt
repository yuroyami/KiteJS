/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.AstRoot
import io.github.yuroyami.kitejs.ast.ScriptNode

/**
 * The state one run of the engine carries: the language version, the error reporter, the feature
 * flags, and the call stack bookkeeping the interpreter needs.
 *
 * Upstream keeps the current context in a `ThreadLocal`, one per thread. This port is single-thread
 * confined (D-3), so there is exactly one slot. Enter a context before running anything and exit it
 * afterwards, or use [ContextFactory.call], which does both.
 *
 * Gone from upstream: class shutters, wrap factories, security controllers, class loaders, the
 * debugger hooks and the E4X and LiveConnect surfaces. None of them have a meaning off the JVM.
 */
open class Context internal constructor(val factory: ContextFactory) : AutoCloseable {

    constructor() : this(ContextFactory.getGlobal())

    var isSealed: Boolean = false
        private set
    private var sealKey: Any? = null

    internal var topCallScope: Scriptable? = null
    internal var isContinuationsTopCall: Boolean = false
    internal var currentActivationCall: NativeCall? = null
    internal var typeErrorThrower: BaseFunction? = null
    internal var iterating: MutableSet<Scriptable>? = null
    internal var interpreterSecurityDomain: Any? = null

    private var version: Int = VERSION_ES6

    private var errorReporterField: ErrorReporter? = null
    internal var regExpProxy: RegExpProxy? = null
    private var generatingDebug: Boolean = false
    private var generatingDebugChanged: Boolean = false
    private var generatingSource: Boolean = true
    internal var useDynamicScope: Boolean = false
    private var interpretedMode: Boolean = true
    private var maximumInterpreterStackDepth: Int = Int.MAX_VALUE
    private var enterCount: Int = 0
    private var threadLocalMap: MutableMap<Any, Any?>? = null

    /**
     * The zone `Date` reads for local time. Defaults to the system's, and a test or an embedder
     * can set it to anything. This is the only place the engine asks the platform about time
     * zones; every other date calculation is its own arithmetic.
     */
    var timeZone: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.currentSystemDefault()
        set(value) {
            field = value
            rawTimeZoneOffsetMs = null
        }

    /** The zone's standard offset, worked out once per zone rather than per date calculation. */
    internal var rawTimeZoneOffsetMs: Int? = null

    /**
     * Where `Date.now()` and `new Date()` get the time, as epoch milliseconds. Defaults to the
     * system clock; a test pins it so results do not depend on when the test runs.
     */
    var clock: () -> Double = { kotlin.time.Clock.System.now().toEpochMilliseconds().toDouble() }

    private val microtasks = ArrayDeque<Runnable>()

    /**
     * Where rejected promises with nothing to catch them are collected. Nothing is collected until
     * [trackUnhandledPromiseRejections] turns it on, because what to do about them is the
     * embedder's decision.
     */
    val unhandledPromiseTracker: UnhandledRejectionTracker = UnhandledRejectionTracker()

    fun trackUnhandledPromiseRejections(track: Boolean) {
        unhandledPromiseTracker.enable(track)
    }

    internal var activationNames: MutableSet<String>? = null

    /** The interpreter's current frame, when one is running. */
    internal var lastInterpreterFrame: Any? = null
    internal var instructionCount: Int = 0
    internal var instructionThreshold: Int = 0

    /** Scratch space the interpreter reuses rather than allocating. */
    internal var scratchUint32: Long = 0
    internal var scratchScriptable: Scriptable? = null
    internal var generateObserverCount: Boolean = false

    /** Set when the script being run has a top-level "use strict". */
    internal var isTopLevelStrict: Boolean = false

    /** A unit of deferred work, run by [processMicrotasks]. */
    fun interface Runnable {
        fun run()
    }

    override fun close() {
        if (enterCount < 1) throw Kit.codeBug()
        if (--enterCount == 0) {
            check(currentContext === this) { "currentContext: $currentContext, this: $this" }
            releaseContext(this)
        }
    }

    /** Freezes every setting. With a non-null [sealKey] the same key unseals it again. */
    fun seal(sealKey: Any?) {
        if (isSealed) onSealedMutation()
        isSealed = true
        this.sealKey = sealKey
    }

    fun unseal(sealKey: Any) {
        require(this.sealKey === sealKey)
        check(isSealed)
        isSealed = false
        this.sealKey = null
    }

    /**
     * The language version this context evaluates at. Changing it affects what gets compiled from
     * then on. New code should use [VERSION_ES6] or [VERSION_ECMASCRIPT].
     */
    open var languageVersion: Int
        get() = version
        set(value) {
            if (isSealed) onSealedMutation()
            checkLanguageVersion(value)
            version = value
        }

    /** The version of this engine. */
    val implementationVersion: String
        get() = IMPLEMENTATION_VERSION

    /** Where warnings and errors go. Defaults to a reporter that throws on error. */
    var errorReporter: ErrorReporter
        get() = errorReporterField ?: DefaultErrorReporter.instance
        set(value) {
            if (isSealed) onSealedMutation()
            errorReporterField = value
        }

    /** Sets the reporter and returns the old one, the way upstream's setter does. */
    fun setErrorReporter(reporter: ErrorReporter): ErrorReporter {
        val old = errorReporter
        errorReporter = reporter
        return old
    }

    // ---- The standard objects ----------------------------------------------------------------

    /** Makes a global scope with every standard object in it. */
    fun initStandardObjects(): ScriptableObject = initStandardObjects(null, false)

    fun initStandardObjects(scope: ScriptableObject?): Scriptable = initStandardObjects(scope, false)

    /** Fills [scope], or a new `TopLevel` when null, with the standard objects. */
    open fun initStandardObjects(scope: ScriptableObject?, sealed: Boolean): ScriptableObject =
        ScriptRuntime.initStandardObjects(this, scope, sealed)

    fun initSafeStandardObjects(): ScriptableObject = initSafeStandardObjects(null, false)

    fun initSafeStandardObjects(scope: ScriptableObject?): Scriptable = initSafeStandardObjects(scope, false)

    open fun initSafeStandardObjects(scope: ScriptableObject?, sealed: Boolean): ScriptableObject =
        ScriptRuntime.initSafeStandardObjects(this, scope, sealed)

    // ---- Running code ------------------------------------------------------------------------

    /** Compiles and runs [source] against [scope], and returns what its last expression gave. */
    fun evaluateString(scope: Scriptable, source: String, sourceName: String?, lineno: Int, securityDomain: Any? = null): Any? {
        val script = compileString(source, sourceName, lineno, securityDomain)
        return script.exec(this, scope, scope)
    }

    /** Whether [source] is a complete statement, or needs more lines before it could parse. */
    fun stringIsCompilableUnit(source: String): Boolean {
        var errorseen = false
        val compilerEnv = CompilerEnvirons()
        compilerEnv.initFromContext(this)
        compilerEnv.generatingSource = false
        val p = Parser(compilerEnv, DefaultErrorReporter.instance)
        try {
            p.parse(source, null, 1)
        } catch (ee: EvaluatorException) {
            errorseen = true
        }
        return !(errorseen && p.eof())
    }

    fun compileString(source: String, sourceName: String?, lineno: Int, securityDomain: Any? = null): Script {
        val line = if (lineno < 0) 0 else lineno
        return compileString(source, null, null, sourceName, line, securityDomain, null)
    }

    internal fun compileString(
        source: String,
        compiler: Evaluator?,
        compilationErrorReporter: ErrorReporter?,
        sourceName: String?,
        lineno: Int,
        securityDomain: Any?,
        compilerEnvironsProcessor: ((CompilerEnvirons) -> Unit)?,
    ): Script = compileImpl(
        null, source, sourceName, lineno, securityDomain, false, compiler, compilationErrorReporter, compilerEnvironsProcessor,
    ) as Script

    /** Compiles [source], which has to hold exactly one function, into a function object. */
    fun compileFunction(scope: Scriptable, source: String, sourceName: String?, lineno: Int, securityDomain: Any? = null): Function =
        compileFunction(scope, source, null, null, sourceName, lineno, securityDomain)

    internal fun compileFunction(
        scope: Scriptable,
        source: String,
        compiler: Evaluator?,
        compilationErrorReporter: ErrorReporter?,
        sourceName: String?,
        lineno: Int,
        securityDomain: Any?,
    ): Function = compileImpl(
        scope, source, sourceName, lineno, securityDomain, true, compiler, compilationErrorReporter, null,
    ) as Function

    fun decompileScript(script: Script, indent: Int): String? = (script as JSScript).descriptor.getRawSource()

    fun decompileFunction(fun_: Function, indent: Int): String {
        if (fun_ is BaseFunction) return fun_.decompile(indent, emptySet())
        return "function " + fun_.className + "() {\n\t[native code]\n}\n"
    }

    fun decompileFunctionBody(fun_: Function, indent: Int): String {
        if (fun_ is BaseFunction) return fun_.decompile(indent, setOf(DecompilerFlag.ONLY_BODY))
        return "[native code]\n"
    }

    // ---- Making objects ------------------------------------------------------------------------

    open fun newObject(scope: Scriptable): Scriptable {
        val result = NativeObject()
        ScriptRuntime.setBuiltinProtoAndParent(result, scope, TopLevel.Builtins.Object)
        return result
    }

    open fun newObject(scope: Scriptable, constructorName: String): Scriptable =
        newObject(scope, constructorName, ScriptRuntime.emptyArgs)

    open fun newObject(scope: Scriptable, constructorName: String, args: Array<Any?>): Scriptable =
        ScriptRuntime.newObject(this, scope, constructorName, args)

    open fun newArray(scope: Scriptable, length: Int): Scriptable {
        val result = NativeArray(length.toLong())
        ScriptRuntime.setBuiltinProtoAndParent(result, scope, TopLevel.Builtins.Array)
        return result
    }

    open fun newArray(scope: Scriptable, elements: Array<Any?>): Scriptable {
        val result = NativeArray(elements)
        ScriptRuntime.setBuiltinProtoAndParent(result, scope, TopLevel.Builtins.Array)
        return result
    }

    /** The elements of an array-like object, with holes read as `undefined`. */
    fun getElements(obj: Scriptable): Array<Any?> = ScriptRuntime.getArrayElements(obj)

    // ---- Settings ------------------------------------------------------------------------------

    fun isGeneratingDebug(): Boolean = generatingDebug

    fun setGeneratingDebug(generatingDebug: Boolean) {
        if (isSealed) onSealedMutation()
        generatingDebugChanged = true
        this.generatingDebug = generatingDebug
    }

    fun isGeneratingDebugChanged(): Boolean = generatingDebugChanged

    fun isGeneratingSource(): Boolean = generatingSource

    fun setGeneratingSource(generatingSource: Boolean) {
        if (isSealed) onSealedMutation()
        this.generatingSource = generatingSource
    }

    /** Always true: there is no bytecode compiler in this port (D-15). */
    fun isInterpretedMode(): Boolean = interpretedMode

    fun setInterpretedMode(interpretedMode: Boolean) {
        if (isSealed) onSealedMutation()
        this.interpretedMode = interpretedMode
    }

    fun getMaximumInterpreterStackDepth(): Int = maximumInterpreterStackDepth

    fun setMaximumInterpreterStackDepth(max: Int) {
        if (isSealed) onSealedMutation()
        check(interpretedMode) { "Cannot set maximumInterpreterStackDepth outside interpreted mode" }
        require(max >= 1) { "Cannot set maximumInterpreterStackDepth to less than 1" }
        maximumInterpreterStackDepth = max
    }

    /** A value an embedder parks on the context under [key]. */
    fun getThreadLocal(key: Any): Any? = threadLocalMap?.get(key)

    fun putThreadLocal(key: Any, value: Any?) {
        if (isSealed) onSealedMutation()
        val m = threadLocalMap ?: HashMap<Any, Any?>().also { threadLocalMap = it }
        m[key] = value
    }

    fun removeThreadLocal(key: Any) {
        if (isSealed) onSealedMutation()
        threadLocalMap?.remove(key)
    }

    /** Whether an optional engine behaviour is on. The [factory] decides. */
    open fun hasFeature(featureIndex: Int): Boolean = factory.hasFeature(this, featureIndex)

    fun getInstructionObserverThreshold(): Int = instructionThreshold

    /**
     * How many instructions the interpreter runs between calls to
     * [ContextFactory.observeInstructionCount]. Zero turns the observer off.
     */
    fun setInstructionObserverThreshold(threshold: Int) {
        if (isSealed) onSealedMutation()
        require(threshold >= 0)
        instructionThreshold = threshold
        setGenerateObserverCount(threshold > 0)
    }

    open fun setGenerateObserverCount(generateObserverCount: Boolean) {
        this.generateObserverCount = generateObserverCount
    }

    open fun isGenerateObserverCount(): Boolean = generateObserverCount

    protected open fun observeInstructionCount(instructionCount: Int) {
        factory.observeInstructionCount(this, instructionCount)
    }

    internal fun observeInstructionCountInternal(instructionCount: Int) = observeInstructionCount(instructionCount)

    // ---- Microtasks ----------------------------------------------------------------------------

    /** Queues work to run after the current top-level call finishes. */
    open fun enqueueMicrotask(task: Runnable) {
        microtasks.addLast(task)
    }

    /** Runs the queued work, including anything it queues in turn, until the queue is empty. */
    open fun processMicrotasks() {
        while (true) {
            val head = microtasks.removeFirstOrNull() ?: break
            head.run()
        }
    }

    // ---- Compilation ---------------------------------------------------------------------------

    protected open fun compileImpl(
        scope: Scriptable?,
        sourceString: String,
        sourceName: String?,
        lineno: Int,
        securityDomain: Any?,
        returnFunction: Boolean,
        compiler: Evaluator?,
        compilationErrorReporter: ErrorReporter?,
        compilerEnvironProcessor: ((CompilerEnvirons) -> Unit)?,
    ): Any {
        val name = sourceName ?: "unnamed script"
        // A scope is given exactly when a function is wanted.
        if (!((scope == null) xor returnFunction)) throw Kit.codeBug()

        val compilerEnv = CompilerEnvirons()
        compilerEnv.initFromContext(this)
        val reporter = compilationErrorReporter ?: compilerEnv.errorReporter
        compilerEnvironProcessor?.invoke(compilerEnv)

        val tree = parse(sourceString, name, lineno, compilerEnv, reporter, returnFunction)
        val evaluator = compiler ?: createInterpreter()
        val bytecode = evaluator.compile(compilerEnv, tree, sourceString, returnFunction)

        return if (returnFunction) evaluator.createFunctionObject(this, scope!!, bytecode, securityDomain)
        else evaluator.createScriptObject(bytecode, securityDomain)
    }

    private fun parse(
        sourceString: String,
        sourceName: String,
        lineno: Int,
        compilerEnv: CompilerEnvirons,
        compilationErrorReporter: ErrorReporter,
        returnFunction: Boolean,
    ): ScriptNode {
        val p = Parser(compilerEnv, compilationErrorReporter)
        if (returnFunction) p.calledByCompileFunction = true
        val ast: AstRoot = p.parse(sourceString, sourceName, lineno)
        if (returnFunction) {
            val first = ast.firstChild
            require(first != null && first.type == Token.FUNCTION) {
                "compileFunction only accepts source with single JS function: $sourceString"
            }
        }
        val irf = IRFactory(compilerEnv, sourceName, sourceString, compilationErrorReporter)
        val tree = irf.transformTree(ast)!!
        if (compilerEnv.generatingSource) {
            tree.rawSource = sourceString
            tree.setRawSourceBounds(0, sourceString.length)
        }
        return tree
    }

    // ---- Activation names and strict mode ------------------------------------------------------

    fun addActivationName(name: String) {
        if (isSealed) onSealedMutation()
        val s = activationNames ?: HashSet<String>().also { activationNames = it }
        s.add(name)
    }

    fun isActivationNeeded(name: String): Boolean = activationNames?.contains(name) == true

    fun removeActivationName(name: String) {
        if (isSealed) onSealedMutation()
        activationNames?.remove(name)
    }

    /** True unless the script pinned a language version older than 1.3. */
    internal fun isVersionECMA1(): Boolean = version == VERSION_DEFAULT || version >= VERSION_1_3

    /** Whether the code running right now is in strict mode. */
    fun isStrictMode(): Boolean =
        isTopLevelStrict || (currentActivationCall?.isStrict == true)

    companion object {

        /** What `Context.implementationVersion` answers. Upstream reads it from a jar manifest. */
        const val IMPLEMENTATION_VERSION: String = "KiteJS 0.1 (Rhino 1.9.1 port)"

        /** The version number a script asked for was not one of the known ones. */
        const val VERSION_UNKNOWN = -1
        const val VERSION_DEFAULT = 0
        const val VERSION_1_0 = 100
        const val VERSION_1_1 = 110
        const val VERSION_1_2 = 120
        const val VERSION_1_3 = 130
        const val VERSION_1_4 = 140
        const val VERSION_1_5 = 150
        const val VERSION_1_6 = 160
        const val VERSION_1_7 = 170
        const val VERSION_1_8 = 180

        /** The default: everything up to ES6 that this engine implements. */
        const val VERSION_ES6 = 200

        /** Like [VERSION_ES6] with the remaining legacy leniencies switched off. */
        const val VERSION_ECMASCRIPT = 250

        // The feature flags. See ContextFactory.hasFeature for what each one defaults to.
        const val FEATURE_NON_ECMA_GET_YEAR = 1
        const val FEATURE_MEMBER_EXPR_AS_FUNCTION_NAME = 2
        const val FEATURE_RESERVED_KEYWORD_AS_IDENTIFIER = 3
        const val FEATURE_TO_STRING_AS_SOURCE = 4
        const val FEATURE_PARENT_PROTO_PROPERTIES = 5
        const val FEATURE_E4X = 6
        const val FEATURE_DYNAMIC_SCOPE = 7
        const val FEATURE_STRICT_VARS = 8
        const val FEATURE_STRICT_EVAL = 9
        const val FEATURE_LOCATION_INFORMATION_IN_ERROR = 10
        const val FEATURE_STRICT_MODE = 11
        const val FEATURE_WARNING_AS_ERROR = 12
        const val FEATURE_ENHANCED_JAVA_ACCESS = 13
        const val FEATURE_V8_EXTENSIONS = 14
        const val FEATURE_OLD_UNDEF_NULL_THIS = 15
        const val FEATURE_ENUMERATE_IDS_FIRST = 16
        const val FEATURE_THREAD_SAFE_OBJECTS = 17
        const val FEATURE_INTEGER_WITHOUT_DECIMAL_PLACE = 18
        const val FEATURE_LITTLE_ENDIAN = 19
        const val FEATURE_ENABLE_XML_SECURE_PARSING = 20
        const val FEATURE_ENABLE_JAVA_MAP_ACCESS = 21
        const val FEATURE_INTL_402 = 22

        const val languageVersionProperty = "language version"
        const val errorReporterProperty = "error reporter"

        /**
         * The entered context, if any. Upstream keeps one per thread; this port is single-thread
         * confined (D-3), so this is a plain slot.
         */
        internal var currentContext: Context? = null

        fun getCurrentContext(): Context? = currentContext

        /** Enters a context from the global factory, or returns the one already entered. */
        fun enter(): Context = enter(null, ContextFactory.getGlobal())

        internal fun enter(cx: Context?, factory: ContextFactory): Context {
            val old = currentContext
            val c: Context
            if (old != null) {
                c = old
            } else {
                if (cx == null) {
                    c = factory.makeContext()
                    check(c.enterCount == 0) { "factory.makeContext() returned Context instance already associated with some thread" }
                    factory.onContextCreated(c)
                    if (factory.isSealed && !c.isSealed) c.seal(null)
                } else {
                    check(cx.enterCount == 0) { "can not use Context instance already associated with some thread" }
                    c = cx
                }
                currentContext = c
            }
            ++c.enterCount
            return c
        }

        /** Leaves the current context. Each [enter] needs one [exit]. */
        fun exit() {
            val cx = currentContext ?: throw IllegalStateException("Calling Context.exit without previous Context.enter")
            if (cx.enterCount < 1) throw Kit.codeBug()
            if (--cx.enterCount == 0) releaseContext(cx)
        }

        private fun releaseContext(cx: Context) {
            currentContext = null
            cx.factory.onContextReleased(cx)
        }

        /** Calls [callable] with a context entered, entering one from [factory] if needed. */
        fun call(factory: ContextFactory?, callable: Callable, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            call(factory ?: ContextFactory.getGlobal()) { cx -> callable.call(cx, scope, thisObj, args) }

        internal fun <T> call(factory: ContextFactory, action: ContextAction<T>): T =
            enter(null, factory).use { cx -> action.run(cx) }

        internal fun onSealedMutation(): Nothing = throw IllegalStateException()

        fun isValidLanguageVersion(version: Int): Boolean = when (version) {
            VERSION_DEFAULT, VERSION_1_0, VERSION_1_1, VERSION_1_2, VERSION_1_3, VERSION_1_4,
            VERSION_1_5, VERSION_1_6, VERSION_1_7, VERSION_1_8, VERSION_ES6, VERSION_ECMASCRIPT -> true
            else -> false
        }

        fun checkLanguageVersion(version: Int) {
            if (isValidLanguageVersion(version)) return
            throw IllegalArgumentException("Bad language version: $version")
        }

        // ---- Reporting ---------------------------------------------------------------------

        fun reportWarning(message: String, sourceName: String?, lineno: Int, lineSource: String?, lineOffset: Int) {
            val cx = getContext()
            if (cx.hasFeature(FEATURE_WARNING_AS_ERROR)) reportError(message, sourceName, lineno, lineSource, lineOffset)
            else cx.errorReporter.warning(message, sourceName, lineno, lineSource, lineOffset)
        }

        fun reportWarning(message: String) {
            val linep = IntArray(1)
            val filename = getSourcePositionFromStack(linep)
            reportWarning(message, filename, linep[0], null, 0)
        }

        /** Reports an error through the entered context's reporter, or throws when there is none. */
        fun reportError(message: String, sourceName: String?, lineno: Int, lineSource: String?, lineOffset: Int) {
            val cx = currentContext
            if (cx != null) cx.errorReporter.error(message, sourceName, lineno, lineSource, lineOffset)
            else throw EvaluatorException(message, sourceName, lineno, lineSource, lineOffset)
        }

        fun reportError(message: String) {
            val linep = IntArray(1)
            val filename = getSourcePositionFromStack(linep)
            reportError(message, filename, linep[0], null, 0)
        }

        fun reportRuntimeError(message: String, sourceName: String?, lineno: Int, lineSource: String?, lineOffset: Int): EvaluatorException {
            val cx = currentContext
            if (cx != null) return cx.errorReporter.runtimeError(message, sourceName, lineno, lineSource, lineOffset)
            throw EvaluatorException(message, sourceName, lineno, lineSource, lineOffset)
        }

        fun reportRuntimeError(message: String): EvaluatorException {
            val linep = IntArray(1)
            val filename = getSourcePositionFromStack(linep)
            return reportRuntimeError(message, filename, linep[0], null, 0)
        }

        internal fun reportRuntimeErrorById(messageId: String, vararg args: Any?): EvaluatorException =
            reportRuntimeError(ScriptRuntime.getMessageById(messageId, *args))

        /** The value of `undefined`. */
        fun getUndefinedValue(): Any = Undefined.instance

        fun toBoolean(value: Any?): Boolean = ScriptRuntime.toBoolean(value)

        fun toNumber(value: Any?): Double = ScriptRuntime.toNumber(value)

        fun toString(value: Any?): String = ScriptRuntime.toString(value)

        fun toObject(value: Any?, scope: Scriptable): Scriptable = ScriptRuntime.toObject(scope, value)

        /** Rethrows [e] as something script can catch: a [RhinoException] as is, anything else wrapped. */
        fun throwAsScriptRuntimeEx(e: Throwable): RuntimeException {
            if (e is RhinoException) throw e
            throw WrappedException(e)
        }

        /** The entered context, or a failure if nothing entered one. */
        fun getContext(): Context =
            currentContext ?: throw RuntimeException("No Context associated with current Thread")

        /** The one evaluator this port has. */
        internal fun createInterpreter(): Evaluator = Interpreter()

        /**
         * The source name and line the interpreter is currently at. The line goes into `linep[0]`.
         * Upstream falls back to walking the Java stack; there is no such fallback here (D-18).
         */
        internal fun getSourcePositionFromStack(linep: IntArray): String? {
            val cx = currentContext ?: return null
            if (cx.lastInterpreterFrame != null) return createInterpreter().getSourcePositionFromStack(cx, linep)
            return null
        }

        /** Whether the code running right now is in strict mode. False when there is no context. */
        fun isCurrentContextStrict(): Boolean = currentContext?.isStrictMode() ?: false
    }
}
