/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Makes [Context] objects and answers the questions a context asks about its environment: which
 * optional behaviours are on, and what wraps a top-level call.
 *
 * An embedder subclasses this to change those answers. Upstream also hangs class loaders and
 * thread-safety defaults here; neither exists in this port.
 */
open class ContextFactory {

    /** Told when a context is made and when it is released. */
    interface Listener {
        fun contextCreated(cx: Context)
        fun contextReleased(cx: Context)
    }

    var isSealed: Boolean = false
        private set

    private val listeners = mutableListOf<Listener>()
    private var disabledListening = false

    internal open fun makeContext(): Context = Context(this)

    /**
     * Whether an optional engine behaviour is on. These are the defaults; a subclass changes them
     * by overriding this. Thread-safe objects are always off (D-3).
     */
    open fun hasFeature(cx: Context, featureIndex: Int): Boolean = when (featureIndex) {
        // Kept only for scripts that pin an old language version.
        Context.FEATURE_NON_ECMA_GET_YEAR ->
            cx.languageVersion == Context.VERSION_1_0 ||
                cx.languageVersion == Context.VERSION_1_1 ||
                cx.languageVersion == Context.VERSION_1_2
        Context.FEATURE_MEMBER_EXPR_AS_FUNCTION_NAME -> false
        Context.FEATURE_RESERVED_KEYWORD_AS_IDENTIFIER -> true
        Context.FEATURE_TO_STRING_AS_SOURCE -> cx.languageVersion == Context.VERSION_1_2
        Context.FEATURE_PARENT_PROTO_PROPERTIES -> true
        Context.FEATURE_E4X ->
            cx.languageVersion == Context.VERSION_DEFAULT || cx.languageVersion >= Context.VERSION_1_6
        Context.FEATURE_DYNAMIC_SCOPE -> false
        Context.FEATURE_STRICT_VARS -> false
        Context.FEATURE_STRICT_EVAL -> false
        Context.FEATURE_LOCATION_INFORMATION_IN_ERROR -> false
        Context.FEATURE_STRICT_MODE -> false
        Context.FEATURE_WARNING_AS_ERROR -> false
        Context.FEATURE_ENHANCED_JAVA_ACCESS -> false
        Context.FEATURE_V8_EXTENSIONS -> true
        Context.FEATURE_OLD_UNDEF_NULL_THIS -> cx.languageVersion <= Context.VERSION_1_7
        Context.FEATURE_ENUMERATE_IDS_FIRST -> cx.languageVersion >= Context.VERSION_ES6
        Context.FEATURE_THREAD_SAFE_OBJECTS -> false
        Context.FEATURE_INTEGER_WITHOUT_DECIMAL_PLACE -> false
        Context.FEATURE_LITTLE_ENDIAN -> false
        Context.FEATURE_ENABLE_XML_SECURE_PARSING -> true
        Context.FEATURE_ENABLE_JAVA_MAP_ACCESS -> false
        Context.FEATURE_INTL_402 -> false
        else -> throw IllegalArgumentException("$featureIndex")
    }

    /** Wraps every top-level call. The default flattens a lazy string result. */
    open fun doTopCall(
        callable: Callable,
        cx: Context,
        scope: Scriptable,
        thisObj: Scriptable?,
        args: Array<Any?>,
    ): Any? {
        val result = callable.call(cx, scope, thisObj, args)
        return if (result is ConsString) result.toString() else result
    }

    open fun doTopCall(script: Script, cx: Context, scope: Scriptable, thisObj: Scriptable): Any? {
        val result = script.exec(cx, scope, thisObj)
        return if (result is ConsString) result.toString() else result
    }

    /** Called every time the interpreter has run [instructionCount] more instructions. */
    open fun observeInstructionCount(cx: Context, instructionCount: Int) {}

    open fun onContextCreated(cx: Context) {
        for (l in listeners.toList()) l.contextCreated(cx)
    }

    open fun onContextReleased(cx: Context) {
        for (l in listeners.toList()) l.contextReleased(cx)
    }

    fun addListener(listener: Listener) {
        checkNotSealed()
        check(!disabledListening)
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        checkNotSealed()
        check(!disabledListening)
        listeners.remove(listener)
    }

    internal fun disableContextListening() {
        checkNotSealed()
        disabledListening = true
        listeners.clear()
    }

    fun seal() {
        checkNotSealed()
        isSealed = true
    }

    protected fun checkNotSealed() {
        check(!isSealed)
    }

    /** Runs [action] with a context entered, entering one if none is. */
    fun <T> call(action: ContextAction<T>): T = Context.call(this, action)

    fun enterContext(): Context = enterContext(null)

    fun enterContext(cx: Context?): Context = Context.enter(cx, this)

    companion object {
        private var globalField: ContextFactory = ContextFactory()
        private var hasCustomGlobal = false

        /** The factory used when nothing names one. */
        fun getGlobal(): ContextFactory = globalField

        fun hasExplicitGlobal(): Boolean = hasCustomGlobal

        /** Replaces the global factory. Allowed once, before it is first used. */
        fun initGlobal(factory: ContextFactory) {
            check(!hasCustomGlobal)
            hasCustomGlobal = true
            globalField = factory
        }
    }
}
