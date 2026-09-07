/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The `Symbol` wrapper object. The primitive symbol value is a [SymbolKey]; this class is only what
 * `Object(sym)` produces, and it carries the key in an internal slot.
 */
public class NativeSymbol internal constructor(internal val key: SymbolKey) : ScriptableObject(), Symbol {

    override val kind: Symbol.Kind
        get() = key.kind

    override val className: String
        get() = CLASS_NAME

    override val name: String
        get() = key.name

    /**
     * False for every instance in this version: a wrapper made from a symbol is an object, not a
     * symbol. Upstream keeps the hook because the checks below read better with it.
     */
    public val isSymbol: Boolean get() = false

    override val typeOf: String
        get() = if (isSymbol) TYPE_NAME else super.typeOf

    // A real symbol takes no properties. A wrapper does, so these all reach super today.

    override fun put(name: String, start: Scriptable, value: Any?) {
        if (!isSymbol) {
            super.put(name, start, value)
        } else if (isStrictMode) {
            throw ScriptRuntime.typeErrorById("msg.no.assign.symbol.strict")
        }
    }

    override fun put(index: Int, start: Scriptable, value: Any?) {
        if (!isSymbol) {
            super.put(index, start, value)
        } else if (isStrictMode) {
            throw ScriptRuntime.typeErrorById("msg.no.assign.symbol.strict")
        }
    }

    override fun put(key: Symbol, start: Scriptable, value: Any?) {
        if (!isSymbol) {
            super.put(key, start, value)
        } else if (isStrictMode) {
            throw ScriptRuntime.typeErrorById("msg.no.assign.symbol.strict")
        }
    }

    override fun toString(): String = key.toString()

    override fun hashCode(): Int = key.hashCode()

    override fun equals(other: Any?): Boolean = key == other

    public companion object {
        public const val CLASS_NAME: String = "Symbol"
        public const val TYPE_NAME: String = "symbol"

        /**
         * The registry behind `Symbol.for`. Upstream uses a weak map keyed on the description, but
         * the keys are strings the map itself holds, so nothing was ever collected (D-40).
         */
        private val globalMap = HashMap<String, SymbolKey>()

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean) {
            val ctor = LambdaConstructor(
                scope,
                CLASS_NAME,
                0,
                SerializableCallable { icx, s, thisObj, args -> js_constructorCall(icx, s, thisObj, args) },
                null,
            )
            ctor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)

            ctor.defineConstructorMethod(scope, "for", 1, SerializableCallable { icx, s, thisObj, args -> js_for(icx, s, thisObj, args) })
            ctor.defineConstructorMethod(scope, "keyFor", 1, SerializableCallable { icx, s, thisObj, args -> js_keyFor(icx, s, thisObj, args) })

            ctor.definePrototypeMethod(scope, "toString", 0, SerializableCallable { icx, s, thisObj, args -> js_toString(icx, s, thisObj, args) })
            ctor.definePrototypeMethod(scope, "valueOf", 0, SerializableCallable { icx, s, thisObj, args -> js_valueOf(icx, s, thisObj, args) })
            ctor.definePrototypeMethod(
                scope,
                SymbolKey.TO_PRIMITIVE,
                1,
                SerializableCallable { icx, s, thisObj, args -> js_valueOf(icx, s, thisObj, args) },
                DONTENUM or READONLY,
            )
            ctor.definePrototypeProperty(SymbolKey.TO_STRING_TAG, CLASS_NAME, DONTENUM or READONLY)
            ctor.definePrototypeProperty(cx, "description", LambdaGetterFunction { thisObj -> js_description(thisObj) })

            defineProperty(scope, CLASS_NAME, ctor, DONTENUM)

            createStandardSymbol(ctor, "iterator", SymbolKey.ITERATOR)
            createStandardSymbol(ctor, "species", SymbolKey.SPECIES)
            createStandardSymbol(ctor, "toStringTag", SymbolKey.TO_STRING_TAG)
            createStandardSymbol(ctor, "hasInstance", SymbolKey.HAS_INSTANCE)
            createStandardSymbol(ctor, "isConcatSpreadable", SymbolKey.IS_CONCAT_SPREADABLE)
            createStandardSymbol(ctor, "isRegExp", SymbolKey.IS_REGEXP)
            createStandardSymbol(ctor, "toPrimitive", SymbolKey.TO_PRIMITIVE)
            createStandardSymbol(ctor, "match", SymbolKey.MATCH)
            createStandardSymbol(ctor, "matchAll", SymbolKey.MATCH_ALL)
            createStandardSymbol(ctor, "replace", SymbolKey.REPLACE)
            createStandardSymbol(ctor, "search", SymbolKey.SEARCH)
            createStandardSymbol(ctor, "split", SymbolKey.SPLIT)
            createStandardSymbol(ctor, "unscopables", SymbolKey.UNSCOPABLES)

            // Sealing waits until every property above is in place.
            if (sealed) ctor.sealObject()
        }

        private fun createStandardSymbol(ctor: LambdaConstructor, name: String, key: SymbolKey) {
            ctor.defineProperty(name, key, DONTENUM or READONLY or PERMANENT)
        }

        private fun getSelf(thisObj: Scriptable?): NativeSymbol =
            LambdaConstructor.convertThisObject<NativeSymbol>(thisObj)

        private val isStrictMode: Boolean get() = Context.getCurrentContext()?.isStrictMode ?: false

        private fun js_constructorCall(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val desc = if (args.isNotEmpty() && !Undefined.isUndefined(args[0])) {
                ScriptRuntime.toString(args[0])
            } else {
                null
            }
            return SymbolKey(desc, Symbol.Kind.REGULAR)
        }

        private fun js_toString(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            getSelf(thisObj).toString()

        private fun js_valueOf(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            getSelf(thisObj).key

        private fun js_description(thisObj: Scriptable?): Any? = getSelf(thisObj).key.description

        private fun js_for(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val name = ScriptRuntime.toString(if (args.isNotEmpty()) args[0] else Undefined.instance)
            return globalMap.getOrPut(name) { SymbolKey(name, Symbol.Kind.REGISTERED) }
        }

        private fun js_keyFor(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val s = if (args.isNotEmpty()) args[0] else Undefined.instance
            val sym = when (s) {
                is NativeSymbol -> s.key
                is SymbolKey -> s
                else -> throw ScriptRuntime.throwCustomError(cx, scope, "TypeError", "Not a Symbol")
            }
            if (globalMap[sym.name] === sym) return sym.name
            return Undefined.instance
        }
    }
}
