/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** The iterator `Map` and `Set` hand back from `keys`, `values` and `entries`. */
public class NativeCollectionIterator : ES6Iterator {

    private var name: String
    private var type: Type
    private var iterator: Iterator<Hashtable.Entry>

    /** Whether the iterator answers keys, values, or both. */
    public enum class Type {
        KEYS,
        VALUES,
        BOTH,
    }

    /** Only for building the prototype object. */
    public constructor(tag: String) : super() {
        this.name = tag
        this.iterator = emptyList<Hashtable.Entry>().iterator()
        this.type = Type.BOTH
    }

    public constructor(scope: Scriptable, className: String, type: Type, iterator: Iterator<Hashtable.Entry>) : super(scope, className) {
        this.name = className
        this.iterator = iterator
        this.type = type
    }

    override val className: String
        get() = name

    override fun isDone(cx: Context, scope: Scriptable): Boolean = !iterator.hasNext()

    override fun nextValue(cx: Context, scope: Scriptable): Any? {
        val e = iterator.next()
        return when (type) {
            Type.KEYS -> e.key()
            Type.VALUES -> e.value()
            Type.BOTH -> cx.newArray(scope, arrayOf(e.key(), e.value()))
        }
    }

    public companion object {
        internal fun init(scope: ScriptableObject, tag: String, sealed: Boolean) {
            ES6Iterator.init(scope, sealed, NativeCollectionIterator(tag), tag)
        }
    }
}
