/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** The ES6-only runtime helpers. */
object ScriptRuntimeES6 {

    fun requireObjectCoercible(cx: Context?, value: Any?, idFuncObj: IdFunctionObject): Any? {
        if (value == null || Undefined.isUndefined(value)) {
            throw ScriptRuntime.typeErrorById("msg.called.null.or.undefined", idFuncObj.tag, idFuncObj.getFunctionName())
        }
        return value
    }

    fun requireObjectCoercible(cx: Context?, value: Any?, tag: Any?, functionName: String): Any? {
        if (value == null || Undefined.isUndefined(value)) {
            throw ScriptRuntime.typeErrorById("msg.called.null.or.undefined", tag, functionName)
        }
        return value
    }

    /** Adds the `get [Symbol.species]` accessor that just returns `this`. */
    fun addSymbolSpecies(cx: Context, scope: Scriptable, constructor: ScriptableObject) {
        val getter = LambdaFunction(
            scope,
            "get [Symbol.species]",
            0,
            SerializableCallable { _, _, thisObj, _ -> thisObj },
            false,
        )
        val desc = ScriptableObject.DescriptorInfo(
            enumerable = false,
            writable = Scriptable.NOT_FOUND,
            configurable = true,
            getter = getter,
            setter = Scriptable.NOT_FOUND,
            value = Scriptable.NOT_FOUND,
        )
        constructor.defineOwnProperty(cx, SymbolKey.SPECIES, desc, false)
    }

    fun addSymbolUnscopables(cx: Context, scope: Scriptable, constructor: ScriptableObject, value: LazilyLoadedCtor) {
        constructor.addLazilyInitializedValue(
            SymbolKey.UNSCOPABLES,
            0,
            value,
            ScriptableObject.DONTENUM or ScriptableObject.READONLY,
        )
    }
}
