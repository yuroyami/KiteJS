/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** A reference to `__proto__` or `__parent__`, which read and write the object's own links. */
internal class SpecialRef private constructor(
    private val target: Scriptable,
    private val type: Int,
    private val name: String,
) : Ref() {

    override fun get(cx: Context): Any? = when (type) {
        SPECIAL_NONE -> ScriptRuntime.getObjectProp(target, name, cx)
        SPECIAL_PROTO -> target.prototype
        SPECIAL_PARENT -> target.parentScope
        else -> throw Kit.codeBug()
    }

    override fun set(cx: Context, value: Any?): Any? = throw IllegalStateException()

    override fun set(cx: Context, scope: Scriptable?, value: Any?): Any? {
        when (type) {
            SPECIAL_NONE -> return ScriptRuntime.setObjectProp(target, name, value, cx)
            SPECIAL_PROTO, SPECIAL_PARENT -> {
                if (type == SPECIAL_PROTO && cx.languageVersion >= Context.VERSION_ES6) return setProto(cx, value)
                val obj = ScriptRuntime.toObjectOrNull(cx, value, scope!!)
                if (obj != null) {
                    // A link back to the target would make a cycle.
                    var search: Scriptable? = obj
                    do {
                        if (search === target) throw Context.reportRuntimeErrorById("msg.cyclic.value", name)
                        search = if (type == SPECIAL_PROTO) search!!.prototype else search!!.parentScope
                    } while (search != null)
                }
                if (type == SPECIAL_PROTO) {
                    if (target is ScriptableObject && !target.isExtensible && cx.languageVersion >= Context.VERSION_1_8) {
                        throw ScriptRuntime.typeErrorById("msg.not.extensible")
                    }
                    target.prototype = obj
                } else {
                    target.parentScope = obj
                }
                return obj
            }
            else -> throw Kit.codeBug()
        }
    }

    /**
     * `o.__proto__ = v` in ES6 code, which is the Object.prototype.__proto__ setter (ES B.2.2.1.2):
     * nothing happens unless v is an object or null and o is an object, and o's [[SetPrototypeOf]]
     * refusing is a TypeError. The assignment's value is v either way. Upstream walked the new
     * chain itself, through any proxy's getPrototypeOf trap, and wrote the property, so a proxy's
     * setPrototypeOf trap was skipped and Object.prototype took a new prototype (D-91).
     */
    private fun setProto(cx: Context, value: Any?): Any? {
        if (value != null && (value !is Scriptable || !ScriptRuntime.isObject(value))) return value
        if (target !is ScriptableObject || !ScriptRuntime.isObject(target)) return value
        if (!target.setPrototypeOf(cx, value as Scriptable?)) throw target.prototypeRefusedError(value)
        return value
    }

    override fun has(cx: Context): Boolean =
        if (type == SPECIAL_NONE) ScriptRuntime.hasObjectElem(target, name, cx) else true

    override fun delete(cx: Context): Boolean =
        if (type == SPECIAL_NONE) ScriptRuntime.deleteObjectElem(target, name, cx) else false

    companion object {
        private const val SPECIAL_NONE = 0
        private const val SPECIAL_PROTO = 1
        private const val SPECIAL_PARENT = 2

        fun createSpecial(cx: Context, scope: Scriptable, obj: Any?, name: String): Ref {
            val target = ScriptRuntime.toObjectOrNull(cx, obj, scope) ?: throw ScriptRuntime.undefReadError(obj, name)
            var type = when (name) {
                NativeObject.PROTO_PROPERTY -> SPECIAL_PROTO
                NativeObject.PARENT_PROPERTY -> SPECIAL_PARENT
                else -> throw IllegalArgumentException(name)
            }
            if (!cx.hasFeature(Context.FEATURE_PARENT_PROTO_PROPERTIES)) type = SPECIAL_NONE
            return SpecialRef(target, type, name)
        }
    }
}
