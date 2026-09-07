/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.typedarrays.NativeTypedArrayView

/** The iterator behind `array.keys()`, `entries()` and `values()`, and `for (x of array)`. */
public class NativeArrayIterator : ES6Iterator {

    /** Which of `entries`, `keys` and `values` this iterator answers. */
    public enum class ARRAY_ITERATOR_TYPE { ENTRIES, KEYS, VALUES }

    private var type: ARRAY_ITERATOR_TYPE = ARRAY_ITERATOR_TYPE.VALUES
    private var arrayLike: Scriptable? = null
    private var index = 0

    private constructor() : super()

    public constructor(scope: Scriptable, arrayLike: Scriptable, type: ARRAY_ITERATOR_TYPE) : super(scope, ITERATOR_TAG) {
        this.index = 0
        this.arrayLike = arrayLike
        this.type = type
    }

    override val className: String
        get() = "Array Iterator"

    override fun isDone(cx: Context, scope: Scriptable): Boolean {
        val typedArray = arrayLike
        if (typedArray is NativeTypedArrayView && typedArray.isTypedArrayOutOfBounds) {
            throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")
        }
        return index >= NativeArray.getLengthProperty(cx, arrayLike!!)
    }

    override fun nextValue(cx: Context, scope: Scriptable): Any? {
        if (type == ARRAY_ITERATOR_TYPE.KEYS) {
            return index++
        }
        val array = arrayLike!!
        var value = array.get(index, array)
        if (value === Scriptable.NOT_FOUND) value = Undefined.instance
        if (type == ARRAY_ITERATOR_TYPE.ENTRIES) {
            value = cx.newArray(scope, arrayOf(index, value))
        }
        index++
        return value
    }

    override val tag: String get() = ITERATOR_TAG

    public companion object {
        private const val ITERATOR_TAG = "ArrayIterator"

        internal fun init(scope: ScriptableObject, sealed: Boolean) {
            ES6Iterator.init(scope, sealed, NativeArrayIterator(), ITERATOR_TAG)
        }
    }
}
