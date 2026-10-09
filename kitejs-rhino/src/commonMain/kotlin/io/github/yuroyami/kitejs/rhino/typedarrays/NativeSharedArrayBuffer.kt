/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino.typedarrays

import io.github.yuroyami.kitejs.rhino.AbstractEcmaObjectOperations
import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.Intrinsics
import io.github.yuroyami.kitejs.rhino.LambdaConstructor
import io.github.yuroyami.kitejs.rhino.ScriptRuntime
import io.github.yuroyami.kitejs.rhino.ScriptRuntimeES6
import io.github.yuroyami.kitejs.rhino.Scriptable
import io.github.yuroyami.kitejs.rhino.ScriptableObject
import io.github.yuroyami.kitejs.rhino.SerializableCallable
import io.github.yuroyami.kitejs.rhino.SerializableConstructable
import io.github.yuroyami.kitejs.rhino.SymbolKey
import io.github.yuroyami.kitejs.rhino.Undefined

/**
 * `SharedArrayBuffer` (ECMAScript 2024, 25.2). The engine runs one agent, so the bytes are never
 * shared with another thread. A shared buffer is a [NativeArrayBuffer] with [NativeArrayBuffer.isShared]
 * set: typed arrays and DataViews take either kind, it is never detached, and it can grow but
 * never shrink.
 */
internal object NativeSharedArrayBuffer {
    const val CLASS_NAME: String = "SharedArrayBuffer"

    fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
        val constructor = LambdaConstructor(
            scope,
            CLASS_NAME,
            1,
            LambdaConstructor.CONSTRUCTOR_NEW,
            SerializableConstructable { _, _, args -> NativeArrayBuffer.construct(args, shared = true) },
        )
        constructor.setPrototypePropertyAttributes(ScriptableObject.DONTENUM or ScriptableObject.READONLY or ScriptableObject.PERMANENT)
        Intrinsics.register(scope, CLASS_NAME, constructor)
        ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)

        constructor.definePrototypeMethod(scope, "grow", 1, SerializableCallable { _, _, thisObj, args -> js_grow(thisObj, args) })
        constructor.definePrototypeMethod(scope, "slice", 2, SerializableCallable { icx, s, thisObj, args -> js_slice(icx, s, thisObj, args) })
        constructor.definePrototypeProperty(cx, "byteLength", ScriptableObject.LambdaGetterFunction { getSelf(it).length })
        constructor.definePrototypeProperty(cx, "growable", ScriptableObject.LambdaGetterFunction { getSelf(it).isResizable })
        constructor.definePrototypeProperty(cx, "maxByteLength", ScriptableObject.LambdaGetterFunction {
            val self = getSelf(it)
            if (self.isResizable) self.maxByteLength else self.length
        })
        constructor.definePrototypeProperty(SymbolKey.TO_STRING_TAG, CLASS_NAME, ScriptableObject.DONTENUM or ScriptableObject.READONLY)

        if (sealed) {
            constructor.sealObject()
            (constructor.prototypeProperty as ScriptableObject).sealObject()
        }
        return constructor
    }

    private fun getSelf(thisObj: Scriptable?): NativeArrayBuffer {
        val self = thisObj as? NativeArrayBuffer
        if (self == null || !self.isShared) throw ScriptRuntime.typeErrorById("msg.this.not.instance", CLASS_NAME)
        return self
    }

    /** SharedArrayBuffer.prototype.grow (ECMAScript 2024, 25.2.5.3): a shared buffer never shrinks. */
    private fun js_grow(thisObj: Scriptable?, args: Array<Any?>): Any {
        val self = getSelf(thisObj)
        if (!self.isResizable) throw ScriptRuntime.typeErrorById("msg.sharedarraybuf.not.growable")
        val newLength = ScriptRuntime.toIndex(args.getOrElse(0) { Undefined.instance })
        if (newLength < self.length) throw ScriptRuntime.rangeErrorById("msg.sharedarraybuf.shrink", newLength, self.length)
        if (newLength > self.maxByteLength) {
            throw ScriptRuntime.rangeErrorById("msg.arraybuf.max.length", newLength, self.maxByteLength)
        }
        if (newLength != self.length) self.resize(newLength)
        return Undefined.instance
    }

    /**
     * SharedArrayBuffer.prototype.slice (ECMAScript 2024, 25.2.5.6). The species constructor has
     * to make another shared buffer, and nothing can detach either one.
     */
    private fun js_slice(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): NativeArrayBuffer {
        val self = getSelf(thisObj)
        val len = self.length.toDouble()

        val relativeStart = ScriptRuntime.toIntegerOrInfinity(args.getOrElse(0) { Undefined.instance })
        val first = if (relativeStart < 0) maxOf(len + relativeStart, 0.0) else minOf(relativeStart, len)
        val endArg = args.getOrElse(1) { Undefined.instance }
        val relativeEnd = if (Undefined.isUndefined(endArg)) len else ScriptRuntime.toIntegerOrInfinity(endArg)
        val final = if (relativeEnd < 0) maxOf(len + relativeEnd, 0.0) else minOf(relativeEnd, len)
        val newLen = maxOf(final - first, 0.0).toInt()

        val ctor = AbstractEcmaObjectOperations.speciesConstructor(cx, self, Intrinsics.constructor(cx, scope, CLASS_NAME))
        val buf = ctor.construct(cx, scope, arrayOf<Any?>(newLen))
        if (buf !is NativeArrayBuffer || !buf.isShared) throw ScriptRuntime.typeErrorById("msg.species.invalid.ctor")
        if (buf === self) throw ScriptRuntime.typeErrorById("msg.arraybuf.same")
        if (buf.length < newLen) throw ScriptRuntime.typeErrorById("msg.arraybuf.smaller.len", newLen, buf.length)

        if (newLen > 0) self.buffer!!.copyInto(buf.buffer!!, 0, first.toInt(), first.toInt() + newLen)
        return buf
    }
}
