/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The JavaScript `Object` builtin.
 *
 * The class itself is here, since the rest of the object model builds plain objects with it. The
 * `Object` constructor and its prototype methods arrive with the other natives.
 */
open class NativeObject : ScriptableObject {

    constructor() : super()

    constructor(scope: Scriptable, prototype: Scriptable?) : super(scope, prototype)

    override val className: String
        get() = CLASS_NAME

    override fun toString(): String = ScriptRuntime.defaultObjectToString(this)

    companion object {
        /** `obj.__proto__ = proto` with the spec's checks. */
        fun js_protoSetter(thisObj: Scriptable, proto: Any?) {
            // TODO(P3.8): the full setter, with the cycle and extensibility checks, lands with the
            // Object builtin. Until then a plain assignment.
            thisObj.prototype = proto as? Scriptable
        }

        const val CLASS_NAME = "Object"

        const val PROTO_PROPERTY = "__proto__"

        const val PARENT_PROPERTY = "__parent__"
    }
}
