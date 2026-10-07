/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * A global scope that remembers the original built-in constructors.
 *
 * The spec says most internal construction has to use the original prototype, but the global
 * constructors are writable and deletable, so a script can replace them. Caching them here keeps
 * internal work pointing at the real ones.
 *
 * `Context.initStandardObjects` fills this cache, in a field for a `TopLevel` and as an associated
 * value for any other global object, and makes a `TopLevel` when it is given no scope. A scope
 * that inherits its globals from a prototype uses the cache of the global it inherits from (D-94).
 */
public open class TopLevel : ScriptableObject() {

    /** The built-in types worth caching. */
    public enum class Builtins {
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
        AsyncFunction,
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

    /** This scope's own [Intrinsics], kept in a field rather than as an associated value. */
    private val intrinsics = Intrinsics()

    override val className: String
        get() = "global"

    /**
     * Takes a copy of the built-in constructors so a script cannot change what the engine itself
     * uses. `ScriptRuntime.initStandardObjects` calls this when the scope is a `TopLevel`.
     */
    public fun cacheBuiltins(scope: Scriptable, sealed: Boolean) {
        intrinsics.fill(this, scope, sealed)
    }

    /** Drops the cache, which the standard objects being rebuilt requires. */
    internal fun clearCache() {
        intrinsics.clear()
    }

    /** The cached constructor, or null when [cacheBuiltins] has not run. */
    public fun getBuiltinCtor(type: Builtins): BaseFunction? = intrinsics.ctors?.get(type)

    internal fun getNativeErrorCtor(type: NativeErrors): BaseFunction? = intrinsics.errors?.get(type)

    /** The cached prototype, or null when [cacheBuiltins] has not run. */
    public fun getBuiltinPrototype(type: Builtins): Scriptable? =
        getBuiltinCtor(type)?.prototypeProperty as? Scriptable

    /**
     * A realm's original constructors, taken from its globals once its standard objects are in,
     * so a script that replaces a global does not change what the engine makes. A `TopLevel`
     * keeps its own in a field; any other global object keeps one as an associated value (D-94).
     */
    internal class Intrinsics {
        var ctors: Map<Builtins, BaseFunction>? = null
            private set
        var errors: Map<NativeErrors, BaseFunction>? = null
            private set
        var mathFunctions: Map<String, Callable>? = null
            private set

        /** Called while the native Math object is being built, before scripts can replace fields. */
        fun fillMath(math: NativeMath) {
            val functions = mutableMapOf<String, Callable>()
            for (slot in math.map) {
                val name = slot.name as? String ?: continue
                val function = slot.value as? Callable ?: continue
                functions[name] = function
            }
            mathFunctions = functions
        }

        fun fill(global: Scriptable, scope: Scriptable, sealed: Boolean) {
            val c = mutableMapOf<Builtins, BaseFunction>()
            for (builtin in Builtins.entries) {
                val value = getProperty(global, builtin.name)
                if (value is BaseFunction) {
                    c[builtin] = value
                } else if (builtin == Builtins.GeneratorFunction) {
                    // GeneratorFunction is a real constructor that never gets registered in the
                    // global scope, so it has to be built here.
                    c[builtin] = BaseFunction.initAsGeneratorFunction(scope, sealed) as BaseFunction
                } else if (builtin == Builtins.AsyncFunction) {
                    // So is AsyncFunction (D-97).
                    c[builtin] = BaseFunction.initAsAsyncFunction(scope, sealed) as BaseFunction
                }
            }
            ctors = c
            val e = mutableMapOf<NativeErrors, BaseFunction>()
            for (error in NativeErrors.entries) {
                val value = getProperty(global, error.name)
                if (value is BaseFunction) e[error] = value
            }
            errors = e
        }

        fun clear() {
            ctors = null
            errors = null
            mathFunctions = null
        }
    }

    public companion object {

        private const val INTRINSICS_KEY = "TopLevel.Intrinsics"

        /**
         * Takes [global]'s intrinsics from its globals, whatever class backs it. Upstream kept
         * them for a `TopLevel` only, so in any other global, the one `initStandardObjects()`
         * makes included, a replaced `String`, `Object` or `TypeError` changed the prototype of
         * every string, literal and engine error that followed (D-94).
         */
        internal fun cacheIntrinsics(global: ScriptableObject, sealed: Boolean) {
            if (global is TopLevel) return global.cacheBuiltins(global, sealed)
            val holder = global.associateValue(INTRINSICS_KEY, Intrinsics()) as Intrinsics
            holder.fill(global, global, sealed)
        }

        /** Retains native Math identities without forcing its lazy initialization. */
        internal fun cacheMathFunctions(global: Scriptable, math: NativeMath) {
            val owner = global as? ScriptableObject ?: return
            val holder = if (owner is TopLevel) owner.intrinsics
                else owner.associateValue(INTRINSICS_KEY, Intrinsics()) as Intrinsics
            holder.fillMath(math)
        }

        /** Drops [global]'s intrinsics, which rebuilding its standard objects requires. */
        internal fun clearIntrinsics(global: ScriptableObject) {
            if (global is TopLevel) global.clearCache()
            else (global.getAssociatedValue(INTRINSICS_KEY) as? Intrinsics)?.clear()
        }

        /**
         * The intrinsics [scope] keeps, or else those of the global it inherits from, as a scope
         * made per request on top of a shared one does. Null when none has them.
         */
        private fun intrinsicsOf(scope: Scriptable): Intrinsics? {
            var s: Scriptable? = scope
            while (s != null && s !is NativeProxy) {
                val found = if (s is TopLevel) s.intrinsics else (s as? ScriptableObject)?.getAssociatedValue(INTRINSICS_KEY) as? Intrinsics
                if (found?.ctors != null) return found
                s = s.prototype
            }
            return null
        }

        /** The cached constructor for [type] of [scope]'s realm, or null when it has none. */
        internal fun cachedBuiltinCtor(scope: Scriptable, type: Builtins): BaseFunction? = intrinsicsOf(scope)?.ctors?.get(type)

        /** The original Math function, even if its global object or field was replaced later. */
        internal fun cachedMathFunction(scope: Scriptable, name: String): Callable? =
            intrinsicsOf(scope)?.mathFunctions?.get(name)

        /**
         * The built-in constructor for [type]. Falls back to an ordinary property lookup when the
         * scope has no cache.
         */
        public fun getBuiltinCtor(cx: Context, scope: Scriptable, type: Builtins): Function? {
            check(scope.parentScope == null) { "the scope has to be a top-level scope" }
            cachedBuiltinCtor(scope, type)?.let { return it }
            // GeneratorFunction is no global, so the fallback finds it parked on the scope.
            if (type == Builtins.GeneratorFunction) return generatorFunction(scope)
            if (type == Builtins.AsyncFunction) return asyncFunction(scope)
            return ScriptRuntime.getExistingCtor(cx, scope, type.name)
        }

        private fun generatorFunction(scope: Scriptable): BaseFunction? =
            ScriptableObject.getTopScopeValue(scope, BaseFunction.GENERATOR_FUNCTION_CLASS) as? BaseFunction

        private fun asyncFunction(scope: Scriptable): BaseFunction? =
            ScriptableObject.getTopScopeValue(scope, BaseFunction.ASYNC_FUNCTION_CLASS) as? BaseFunction

        internal fun getNativeErrorCtor(
            cx: Context,
            scope: Scriptable,
            type: NativeErrors,
        ): Function? {
            check(scope.parentScope == null) { "the scope has to be a top-level scope" }
            intrinsicsOf(scope)?.errors?.get(type)?.let { return it }
            return ScriptRuntime.getExistingCtor(cx, scope, type.name)
        }

        /**
         * The built-in prototype for [type]. Falls back to an ordinary property lookup when the
         * scope has no cache.
         */
        public fun getBuiltinPrototype(scope: Scriptable, type: Builtins): Scriptable? {
            check(scope.parentScope == null) { "the scope has to be a top-level scope" }
            (cachedBuiltinCtor(scope, type)?.prototypeProperty as? Scriptable)?.let { return it }
            if (type == Builtins.GeneratorFunction) return generatorFunction(scope)?.prototypeProperty as? Scriptable
            if (type == Builtins.AsyncFunction) return asyncFunction(scope)?.prototypeProperty as? Scriptable
            return getClassPrototype(scope, type.name)
        }
    }
}
