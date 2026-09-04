/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.reflect.KClass

/** The JavaScript `Boolean` wrapper object. */
class NativeBoolean internal constructor(private val booleanValue: Boolean) : ScriptableObject() {

    override val className: String
        get() = CLASS_NAME

    override fun getDefaultValue(hint: KClass<*>?): Any? {
        if (hint == ScriptRuntime.BooleanClass) return ScriptRuntime.wrapBoolean(booleanValue)
        return super.getDefaultValue(hint)
    }

    companion object {
        private const val CLASS_NAME = "Boolean"

        internal fun init(scope: Scriptable, sealed: Boolean) {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                1,
                SerializableCallable { cx, s, thisObj, args -> js_constructorFunc(cx, s, thisObj, args) },
                SerializableConstructable { cx, s, args -> js_constructor(cx, s, args) },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            constructor.setPrototypeScriptable(NativeBoolean(false))
            constructor.definePrototypeMethod(scope, "toString", 0, SerializableCallable { cx, s, thisObj, args -> js_toString(cx, s, thisObj, args) })
            constructor.definePrototypeMethod(scope, "toSource", 0, SerializableCallable { cx, s, thisObj, args -> js_toSource(cx, s, thisObj, args) })
            constructor.definePrototypeMethod(scope, "valueOf", 0, SerializableCallable { cx, s, thisObj, args -> js_valueOf(cx, s, thisObj, args) })
            defineProperty(scope, CLASS_NAME, constructor, DONTENUM)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
        }

        private fun toValue(thisObj: Scriptable?): Boolean =
            ensureType<NativeBoolean>(thisObj, "Boolean").booleanValue

        private fun js_constructorFunc(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val b = ScriptRuntime.toBoolean(if (args.isNotEmpty()) args[0] else Undefined.instance)
            return ScriptRuntime.wrapBoolean(b)
        }

        private fun js_constructor(cx: Context, scope: Scriptable, args: Array<Any?>): NativeBoolean {
            val b = ScriptRuntime.toBoolean(if (args.isNotEmpty()) args[0] else Undefined.instance)
            return NativeBoolean(b)
        }

        private fun js_toString(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): String =
            if (toValue(thisObj)) "true" else "false"

        private fun js_valueOf(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            toValue(thisObj)

        private fun js_toSource(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            "(new Boolean(" + ScriptRuntime.toString(toValue(thisObj)) + "))"
    }
}
