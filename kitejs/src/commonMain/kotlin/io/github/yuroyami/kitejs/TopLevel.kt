/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A global scope that remembers the original built-in constructors.
 *
 * The spec says most internal construction has to use the original prototype, but the global
 * constructors are writable and deletable, so a script can replace them. Caching them here keeps
 * internal work pointing at the real ones.
 *
 * `Context.initStandardObjects` fills this cache when the scope is a `TopLevel`. A scope that
 * inherits its globals from a prototype instead has to call [cacheBuiltins] itself.
 */
open class TopLevel : ScriptableObject() {

    /** The built-in types worth caching. */
    enum class Builtins {
        Object,
        Array,
        Function,
        String,
        Number,
        Boolean,
        RegExp,
        Error,
        Symbol,
        GeneratorFunction,
        BigInt,
        Promise,
        ArrayBuffer,
        Int8Array,
        Uint8Array,
        Uint8ClampedArray,
        Int16Array,
        Uint16Array,
        Int32Array,
        Uint32Array,
        BigInt64Array,
        BigUint64Array,
        Float32Array,
        Float64Array,
        DataView,
    }

    /** The standard error types, from ECMAScript 5 section 15.11.6. */
    internal enum class NativeErrors {
        AggregateError,
        Error,
        EvalError,
        RangeError,
        ReferenceError,
        SyntaxError,
        TypeError,
        URIError,

        /** Not in the spec. */
        InternalError,

        /** Not in the spec. */
        JavaException,
    }

    private var ctors: MutableMap<Builtins, BaseFunction>? = null
    private var errors: MutableMap<NativeErrors, BaseFunction>? = null

    override val className: String
        get() = "global"

    /**
     * Takes a copy of the built-in constructors so a script cannot change what the engine itself
     * uses. `ScriptRuntime.initStandardObjects` calls this when the scope is a `TopLevel`.
     */
    fun cacheBuiltins(scope: Scriptable, sealed: Boolean) {
        val c = mutableMapOf<Builtins, BaseFunction>()
        for (builtin in Builtins.entries) {
            val value = getProperty(this, builtin.name)
            if (value is BaseFunction) {
                c[builtin] = value
            } else if (builtin == Builtins.GeneratorFunction) {
                // GeneratorFunction is a real constructor that never gets registered in the global
                // scope, so it has to be built here.
                c[builtin] = BaseFunction.initAsGeneratorFunction(scope, sealed) as BaseFunction
            }
        }
        ctors = c

        val e = mutableMapOf<NativeErrors, BaseFunction>()
        for (error in NativeErrors.entries) {
            val value = getProperty(this, error.name)
            if (value is BaseFunction) e[error] = value
        }
        errors = e
    }

    /** Drops the cache, which the standard objects being rebuilt requires. */
    internal fun clearCache() {
        ctors = null
        errors = null
    }

    /** The cached constructor, or null when [cacheBuiltins] has not run. */
    fun getBuiltinCtor(type: Builtins): BaseFunction? = ctors?.get(type)

    internal fun getNativeErrorCtor(type: NativeErrors): BaseFunction? = errors?.get(type)

    /** The cached prototype, or null when [cacheBuiltins] has not run. */
    fun getBuiltinPrototype(type: Builtins): Scriptable? =
        getBuiltinCtor(type)?.prototypeProperty as? Scriptable

    companion object {

        /**
         * The built-in constructor for [type]. Falls back to an ordinary property lookup when the
         * scope has no cache.
         */
        fun getBuiltinCtor(cx: Context, scope: Scriptable, type: Builtins): Function? {
            check(scope.parentScope == null) { "the scope has to be a top-level scope" }
            if (scope is TopLevel) scope.getBuiltinCtor(type)?.let { return it }
            // GeneratorFunction is not stored under its own name, so the fallback uses the hidden
            // one.
            val typeName =
                if (type == Builtins.GeneratorFunction) BaseFunction.GENERATOR_FUNCTION_CLASS
                else type.name
            return ScriptRuntime.getExistingCtor(cx, scope, typeName)
        }

        internal fun getNativeErrorCtor(
            cx: Context,
            scope: Scriptable,
            type: NativeErrors,
        ): Function? {
            check(scope.parentScope == null) { "the scope has to be a top-level scope" }
            if (scope is TopLevel) scope.getNativeErrorCtor(type)?.let { return it }
            return ScriptRuntime.getExistingCtor(cx, scope, type.name)
        }

        /**
         * The built-in prototype for [type]. Falls back to an ordinary property lookup when the
         * scope has no cache.
         */
        fun getBuiltinPrototype(scope: Scriptable, type: Builtins): Scriptable? {
            check(scope.parentScope == null) { "the scope has to be a top-level scope" }
            if (scope is TopLevel) scope.getBuiltinPrototype(type)?.let { return it }
            val typeName =
                if (type == Builtins.GeneratorFunction) BaseFunction.GENERATOR_FUNCTION_CLASS
                else type.name
            return getClassPrototype(scope, typeName)
        }
    }
}
