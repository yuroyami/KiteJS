/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino.typedarrays

import io.github.yuroyami.kitejs.api.KBigInt
import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.NativePromise
import io.github.yuroyami.kitejs.rhino.ScriptRuntime
import io.github.yuroyami.kitejs.rhino.Scriptable
import io.github.yuroyami.kitejs.rhino.ScriptableObject
import io.github.yuroyami.kitejs.rhino.SerializableCallable
import io.github.yuroyami.kitejs.rhino.SymbolKey
import io.github.yuroyami.kitejs.rhino.Undefined

/**
 * `Atomics` (ECMAScript 2026, 25.4). The engine runs one agent, which cannot block, so
 * `Atomics.wait` always throws a TypeError. `Atomics.waitAsync` works: a waiter with a timeout
 * resolves to "timed-out" once the microtask queue is empty (see [Context.enqueueTimeout]).
 */
internal class NativeAtomics private constructor() : ScriptableObject() {

    override val className: String
        get() = CLASS_NAME

    /** One `Atomics.waitAsync` caller, parked on a byte index of a shared buffer. */
    internal class Waiter(val byteIndex: Int, val promise: NativePromise, val scope: Scriptable) {
        var done = false
    }

    companion object {
        private const val CLASS_NAME = "Atomics"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val atomics = NativeAtomics()
            atomics.prototype = getObjectPrototype(scope)
            atomics.parentScope = scope

            atomics.defineBuiltinProperty(scope, "add", 3, SerializableCallable { _, _, _, args -> modify(args) { a, b -> a + b } })
            atomics.defineBuiltinProperty(scope, "and", 3, SerializableCallable { _, _, _, args -> modify(args) { a, b -> a and b } })
            atomics.defineBuiltinProperty(scope, "compareExchange", 4, SerializableCallable { _, _, _, args -> compareExchange(args) })
            atomics.defineBuiltinProperty(scope, "exchange", 3, SerializableCallable { _, _, _, args -> modify(args) { _, b -> b } })
            atomics.defineBuiltinProperty(scope, "isLockFree", 1, SerializableCallable { _, _, _, args -> isLockFree(args) })
            atomics.defineBuiltinProperty(scope, "load", 2, SerializableCallable { _, _, _, args -> load(args) })
            atomics.defineBuiltinProperty(scope, "notify", 3, SerializableCallable { _, _, _, args -> notify(args) })
            atomics.defineBuiltinProperty(scope, "or", 3, SerializableCallable { _, _, _, args -> modify(args) { a, b -> a or b } })
            atomics.defineBuiltinProperty(scope, "pause", 0, SerializableCallable { _, _, _, args -> pause(args) })
            atomics.defineBuiltinProperty(scope, "store", 3, SerializableCallable { _, _, _, args -> store(args) })
            atomics.defineBuiltinProperty(scope, "sub", 3, SerializableCallable { _, _, _, args -> modify(args) { a, b -> a - b } })
            atomics.defineBuiltinProperty(scope, "wait", 4, SerializableCallable { _, _, _, args -> wait(args) })
            atomics.defineBuiltinProperty(scope, "waitAsync", 4, SerializableCallable { icx, s, _, args -> waitAsync(icx, s, args) })
            atomics.defineBuiltinProperty(scope, "xor", 3, SerializableCallable { _, _, _, args -> modify(args) { a, b -> a xor b } })

            atomics.defineProperty(SymbolKey.TO_STRING_TAG, CLASS_NAME, DONTENUM or READONLY)
            if (sealed) atomics.sealObject()
            return atomics
        }

        private fun arg(args: Array<Any?>, i: Int): Any? = if (i < args.size) args[i] else Undefined.instance

        // ---- Validation ----------------------------------------------------------------------

        /** ValidateIntegerTypedArray: an integer view in bounds, or an Int32Array or BigInt64Array when [waitable]. */
        private fun validateArray(value: Any?, waitable: Boolean): NativeTypedArrayView {
            val view = value as? NativeTypedArrayView ?: throw ScriptRuntime.typeErrorById("msg.atomics.bad.array", ScriptRuntime.toString(value))
            if (view.isTypedArrayOutOfBounds) throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")
            if (waitable) {
                if (view !is NativeInt32Array && view !is NativeBigInt64Array) {
                    throw ScriptRuntime.typeErrorById("msg.atomics.not.waitable", view.className)
                }
            } else if (view is NativeFloat16Array || view is NativeFloat32Array || view is NativeFloat64Array || view is NativeUint8ClampedArray) {
                throw ScriptRuntime.typeErrorById("msg.atomics.bad.array", view.className)
            }
            return view
        }

        /** ValidateAtomicAccess, returning an element index rather than a byte index. */
        private fun validateIndex(view: NativeTypedArrayView, requestIndex: Any?): Int {
            val length = view.atomicLength
            val index = ScriptRuntime.toIndex(requestIndex)
            if (index >= length) throw ScriptRuntime.rangeErrorById("msg.atomics.index", index, length)
            return index
        }

        /** RevalidateAtomicAccess: a conversion may have detached or shrunk the buffer. */
        private fun revalidate(view: NativeTypedArrayView, index: Int) {
            if (view.isTypedArrayOutOfBounds) throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")
            if (view.offset + index * view.bytesPerElement >= view.atomicBuffer.length) {
                throw ScriptRuntime.rangeErrorById("msg.atomics.index", index, view.atomicLength)
            }
        }

        /** ToBigInt for a bigint view, ToIntegerOrInfinity for the others. */
        private fun convert(view: NativeTypedArrayView, value: Any?): Any =
            if (view is NativeBigIntArrayView) ScriptRuntime.toBigInt(value) else ScriptRuntime.toIntegerOrInfinity(value)

        /** The element's raw bits, as a two's complement Long. Narrow views keep their low bits only. */
        private fun bits(view: NativeTypedArrayView, value: Any?): Long = when (value) {
            is KBigInt -> value.toLong()
            else -> {
                val i = ScriptRuntime.toInt32((value as Number).toDouble()).toLong()
                when (view.bytesPerElement) {
                    1 -> i and 0xFF
                    2 -> i and 0xFFFF
                    else -> i and 0xFFFFFFFFL
                }
            }
        }

        private fun fromBits(view: NativeTypedArrayView, bits: Long): Any =
            if (view is NativeBigIntArrayView) KBigInt.fromLong(bits) else bits.toInt().toDouble()

        // ---- Read and write ------------------------------------------------------------------

        private fun load(args: Array<Any?>): Any? {
            val view = validateArray(arg(args, 0), waitable = false)
            val index = validateIndex(view, arg(args, 1))
            revalidate(view, index)
            return view.atomicRead(index)
        }

        private fun store(args: Array<Any?>): Any {
            val view = validateArray(arg(args, 0), waitable = false)
            val index = validateIndex(view, arg(args, 1))
            val value = convert(view, arg(args, 2))
            revalidate(view, index)
            view.atomicWrite(index, value)
            return value
        }

        /** AtomicReadModifyWrite: [op] works on raw bits, and the write wraps them to the element width. */
        private fun modify(args: Array<Any?>, op: (Long, Long) -> Long): Any? {
            val view = validateArray(arg(args, 0), waitable = false)
            val index = validateIndex(view, arg(args, 1))
            val value = convert(view, arg(args, 2))
            revalidate(view, index)
            val old = view.atomicRead(index)
            view.atomicWrite(index, fromBits(view, op(bits(view, old), bits(view, value))))
            return old
        }

        private fun compareExchange(args: Array<Any?>): Any? {
            val view = validateArray(arg(args, 0), waitable = false)
            val index = validateIndex(view, arg(args, 1))
            val expected = convert(view, arg(args, 2))
            val replacement = convert(view, arg(args, 3))
            revalidate(view, index)
            val old = view.atomicRead(index)
            if (bits(view, old) == bits(view, expected)) view.atomicWrite(index, replacement)
            return old
        }

        private fun isLockFree(args: Array<Any?>): Boolean =
            when (ScriptRuntime.toIntegerOrInfinity(arg(args, 0))) {
                1.0, 2.0, 4.0, 8.0 -> true
                else -> false
            }

        private fun pause(args: Array<Any?>): Any {
            val n = arg(args, 0)
            if (!Undefined.isUndefined(n)) {
                val d = (n as? Number)?.toDouble()
                if (d == null || d.isNaN() || d.isInfinite() || d != kotlin.math.truncate(d)) {
                    throw ScriptRuntime.typeErrorById("msg.atomics.pause.arg")
                }
            }
            return Undefined.instance
        }

        // ---- Wait and notify -----------------------------------------------------------------

        private class WaitRequest(val view: NativeTypedArrayView, val index: Int, val value: Any, val timeout: Double)

        /** DoWait, up to the point where the two modes part. */
        private fun waitRequest(args: Array<Any?>, method: String): WaitRequest {
            val view = validateArray(arg(args, 0), waitable = true)
            if (!view.atomicBuffer.isShared) throw ScriptRuntime.typeErrorById("msg.atomics.not.shared", method)
            val index = validateIndex(view, arg(args, 1))
            val value: Any = if (view is NativeBigInt64Array) {
                ScriptRuntime.toBigInt(arg(args, 2))
            } else {
                ScriptRuntime.toInt32(arg(args, 2)).toDouble()
            }
            val q = ScriptRuntime.toNumber(arg(args, 3))
            val timeout = when {
                q.isNaN() || q == Double.POSITIVE_INFINITY -> Double.POSITIVE_INFINITY
                q == Double.NEGATIVE_INFINITY -> 0.0
                else -> maxOf(q, 0.0)
            }
            return WaitRequest(view, index, value, timeout)
        }

        private fun wait(args: Array<Any?>): Any {
            waitRequest(args, "wait")
            throw ScriptRuntime.typeErrorById("msg.atomics.cannot.block")
        }

        private fun waitAsync(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
            val request = waitRequest(args, "waitAsync")
            val view = request.view
            val result = cx.newObject(scope)
            val current = view.atomicRead(request.index)
            if (bits(view, current) != bits(view, request.value)) {
                return answer(result, false, "not-equal")
            }
            if (request.timeout == 0.0) return answer(result, false, "timed-out")

            val promise = NativePromise.newIntrinsic(cx, scope)
            val buffer = view.atomicBuffer
            val waiter = Waiter(view.offset + request.index * view.bytesPerElement, promise, scope)
            (buffer.waiters ?: ArrayList<Waiter>().also { buffer.waiters = it }).add(waiter)
            if (request.timeout != Double.POSITIVE_INFINITY) {
                cx.enqueueTimeout(request.timeout, Context.Runnable {
                    if (!waiter.done) {
                        waiter.done = true
                        buffer.waiters?.remove(waiter)
                        promise.resolveFromEngine(cx, scope, "timed-out")
                    }
                })
            }
            return answer(result, true, promise)
        }

        private fun answer(result: Scriptable, async: Boolean, value: Any): Scriptable {
            result.put("async", result, async)
            result.put("value", result, value)
            return result
        }

        private fun notify(args: Array<Any?>): Double {
            val view = validateArray(arg(args, 0), waitable = true)
            val index = validateIndex(view, arg(args, 1))
            val countArg = arg(args, 2)
            val count = if (Undefined.isUndefined(countArg)) {
                Double.POSITIVE_INFINITY
            } else {
                maxOf(ScriptRuntime.toIntegerOrInfinity(countArg), 0.0)
            }
            val buffer = view.atomicBuffer
            if (!buffer.isShared) return 0.0
            val waiters = buffer.waiters ?: return 0.0

            val byteIndex = view.offset + index * view.bytesPerElement
            var woken = 0
            val it = waiters.iterator()
            while (it.hasNext() && woken < count) {
                val waiter = it.next()
                if (waiter.byteIndex != byteIndex || waiter.done) continue
                it.remove()
                waiter.done = true
                waiter.promise.resolveFromEngine(Context.getContext(), waiter.scope, "ok")
                woken++
            }
            return woken.toDouble()
        }
    }
}
