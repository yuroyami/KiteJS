/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.typedarrays

import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.LambdaConstructor
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.ScriptRuntimeES6
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.ScriptableObject
import io.github.yuroyami.kitejs.SerializableConstructable
import io.github.yuroyami.kitejs.Undefined

// The nine numeric views. They differ only in element width and in how a value is read and
// written, so everything else lives in NativeTypedArrayView.

/** The `Int8Array` view: 1 byte per element. */
public class NativeInt8Array : NativeTypedArrayView {

    public constructor() : super()

    public constructor(ab: NativeArrayBuffer, off: Int, len: Int) : super(ab, off, len, len * 1)

    public constructor(len: Int) : this(NativeArrayBuffer(len * 1), 0, len)

    override val className: String
        get() = CLASS_NAME

    override val bytesPerElement: Int get() = 1

    override fun js_get(index: Int): Any? {
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        return ByteIo.readInt8(buf, index + offset)
    }

    override fun js_set(index: Int, c: Any?): Any? {
        val v = Conversions.toInt8(c)
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        ByteIo.writeInt8(buf, index + offset, v)
        return null
    }

    public companion object {
        private const val CLASS_NAME = "Int8Array"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                3,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args ->
                    NativeTypedArrayView.js_constructor(icx, s, args, { ab, off, len -> NativeInt8Array(ab, off, len) }, 1)
                },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            NativeTypedArrayView.init(cx, scope, constructor)
            constructor.defineProperty("BYTES_PER_ELEMENT", 1, DONTENUM or READONLY or PERMANENT)
            constructor.definePrototypeProperty("BYTES_PER_ELEMENT", 1, DONTENUM or READONLY or PERMANENT)

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }
    }
}

/** The `Uint8Array` view: 1 byte per element. */
public class NativeUint8Array : NativeTypedArrayView {

    public constructor() : super()

    public constructor(ab: NativeArrayBuffer, off: Int, len: Int) : super(ab, off, len, len * 1)

    public constructor(len: Int) : this(NativeArrayBuffer(len * 1), 0, len)

    override val className: String
        get() = CLASS_NAME

    override val bytesPerElement: Int get() = 1

    override fun js_get(index: Int): Any? {
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        return ByteIo.readUint8(buf, index + offset)
    }

    override fun js_set(index: Int, c: Any?): Any? {
        val v = Conversions.toUint8(c)
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        ByteIo.writeUint8(buf, index + offset, v)
        return null
    }

    public companion object {
        private const val CLASS_NAME = "Uint8Array"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                3,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args ->
                    NativeTypedArrayView.js_constructor(icx, s, args, { ab, off, len -> NativeUint8Array(ab, off, len) }, 1)
                },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            NativeTypedArrayView.init(cx, scope, constructor)
            constructor.defineProperty("BYTES_PER_ELEMENT", 1, DONTENUM or READONLY or PERMANENT)
            constructor.definePrototypeProperty("BYTES_PER_ELEMENT", 1, DONTENUM or READONLY or PERMANENT)

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }
    }
}

/** The `Uint8ClampedArray` view: 1 byte per element. */
public class NativeUint8ClampedArray : NativeTypedArrayView {

    public constructor() : super()

    public constructor(ab: NativeArrayBuffer, off: Int, len: Int) : super(ab, off, len, len * 1)

    public constructor(len: Int) : this(NativeArrayBuffer(len * 1), 0, len)

    override val className: String
        get() = CLASS_NAME

    override val bytesPerElement: Int get() = 1

    override fun js_get(index: Int): Any? {
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        return ByteIo.readUint8(buf, index + offset)
    }

    override fun js_set(index: Int, c: Any?): Any? {
        val v = Conversions.toUint8Clamp(c)
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        ByteIo.writeUint8(buf, index + offset, v)
        return null
    }

    public companion object {
        private const val CLASS_NAME = "Uint8ClampedArray"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                3,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args ->
                    NativeTypedArrayView.js_constructor(icx, s, args, { ab, off, len -> NativeUint8ClampedArray(ab, off, len) }, 1)
                },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            NativeTypedArrayView.init(cx, scope, constructor)
            constructor.defineProperty("BYTES_PER_ELEMENT", 1, DONTENUM or READONLY or PERMANENT)
            constructor.definePrototypeProperty("BYTES_PER_ELEMENT", 1, DONTENUM or READONLY or PERMANENT)

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }
    }
}

/** The `Int16Array` view: 2 bytes per element. */
public class NativeInt16Array : NativeTypedArrayView {

    public constructor() : super()

    public constructor(ab: NativeArrayBuffer, off: Int, len: Int) : super(ab, off, len, len * 2)

    public constructor(len: Int) : this(NativeArrayBuffer(len * 2), 0, len)

    override val className: String
        get() = CLASS_NAME

    override val bytesPerElement: Int get() = 2

    override fun js_get(index: Int): Any? {
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        return ByteIo.readInt16(buf, (index * 2) + offset, NativeArrayBufferView.useLittleEndian())
    }

    override fun js_set(index: Int, c: Any?): Any? {
        val v = Conversions.toInt16(c)
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        ByteIo.writeInt16(buf, (index * 2) + offset, v, NativeArrayBufferView.useLittleEndian())
        return null
    }

    public companion object {
        private const val CLASS_NAME = "Int16Array"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                3,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args ->
                    NativeTypedArrayView.js_constructor(icx, s, args, { ab, off, len -> NativeInt16Array(ab, off, len) }, 2)
                },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            NativeTypedArrayView.init(cx, scope, constructor)
            constructor.defineProperty("BYTES_PER_ELEMENT", 2, DONTENUM or READONLY or PERMANENT)
            constructor.definePrototypeProperty("BYTES_PER_ELEMENT", 2, DONTENUM or READONLY or PERMANENT)

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }
    }
}

/** The `Uint16Array` view: 2 bytes per element. */
public class NativeUint16Array : NativeTypedArrayView {

    public constructor() : super()

    public constructor(ab: NativeArrayBuffer, off: Int, len: Int) : super(ab, off, len, len * 2)

    public constructor(len: Int) : this(NativeArrayBuffer(len * 2), 0, len)

    override val className: String
        get() = CLASS_NAME

    override val bytesPerElement: Int get() = 2

    override fun js_get(index: Int): Any? {
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        return ByteIo.readUint16(buf, (index * 2) + offset, NativeArrayBufferView.useLittleEndian())
    }

    override fun js_set(index: Int, c: Any?): Any? {
        val v = Conversions.toUint16(c)
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        ByteIo.writeUint16(buf, (index * 2) + offset, v, NativeArrayBufferView.useLittleEndian())
        return null
    }

    public companion object {
        private const val CLASS_NAME = "Uint16Array"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                3,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args ->
                    NativeTypedArrayView.js_constructor(icx, s, args, { ab, off, len -> NativeUint16Array(ab, off, len) }, 2)
                },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            NativeTypedArrayView.init(cx, scope, constructor)
            constructor.defineProperty("BYTES_PER_ELEMENT", 2, DONTENUM or READONLY or PERMANENT)
            constructor.definePrototypeProperty("BYTES_PER_ELEMENT", 2, DONTENUM or READONLY or PERMANENT)

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }
    }
}

/** The `Int32Array` view: 4 bytes per element. */
public class NativeInt32Array : NativeTypedArrayView {

    public constructor() : super()

    public constructor(ab: NativeArrayBuffer, off: Int, len: Int) : super(ab, off, len, len * 4)

    public constructor(len: Int) : this(NativeArrayBuffer(len * 4), 0, len)

    override val className: String
        get() = CLASS_NAME

    override val bytesPerElement: Int get() = 4

    override fun js_get(index: Int): Any? {
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        return ByteIo.readInt32(buf, (index * 4) + offset, NativeArrayBufferView.useLittleEndian())
    }

    override fun js_set(index: Int, c: Any?): Any? {
        val v = Conversions.toInt32(c)
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        ByteIo.writeInt32(buf, (index * 4) + offset, v, NativeArrayBufferView.useLittleEndian())
        return null
    }

    public companion object {
        private const val CLASS_NAME = "Int32Array"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                3,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args ->
                    NativeTypedArrayView.js_constructor(icx, s, args, { ab, off, len -> NativeInt32Array(ab, off, len) }, 4)
                },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            NativeTypedArrayView.init(cx, scope, constructor)
            constructor.defineProperty("BYTES_PER_ELEMENT", 4, DONTENUM or READONLY or PERMANENT)
            constructor.definePrototypeProperty("BYTES_PER_ELEMENT", 4, DONTENUM or READONLY or PERMANENT)

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }
    }
}

/** The `Uint32Array` view: 4 bytes per element. */
public class NativeUint32Array : NativeTypedArrayView {

    public constructor() : super()

    public constructor(ab: NativeArrayBuffer, off: Int, len: Int) : super(ab, off, len, len * 4)

    public constructor(len: Int) : this(NativeArrayBuffer(len * 4), 0, len)

    override val className: String
        get() = CLASS_NAME

    override val bytesPerElement: Int get() = 4

    override fun js_get(index: Int): Any? {
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        return ByteIo.readUint32(buf, (index * 4) + offset, NativeArrayBufferView.useLittleEndian())
    }

    override fun js_set(index: Int, c: Any?): Any? {
        val v = Conversions.toUint32(c)
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        ByteIo.writeUint32(buf, (index * 4) + offset, v, NativeArrayBufferView.useLittleEndian())
        return null
    }

    public companion object {
        private const val CLASS_NAME = "Uint32Array"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                3,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args ->
                    NativeTypedArrayView.js_constructor(icx, s, args, { ab, off, len -> NativeUint32Array(ab, off, len) }, 4)
                },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            NativeTypedArrayView.init(cx, scope, constructor)
            constructor.defineProperty("BYTES_PER_ELEMENT", 4, DONTENUM or READONLY or PERMANENT)
            constructor.definePrototypeProperty("BYTES_PER_ELEMENT", 4, DONTENUM or READONLY or PERMANENT)

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }
    }
}

/** The `Float32Array` view: 4 bytes per element. */
public class NativeFloat32Array : NativeTypedArrayView {

    public constructor() : super()

    public constructor(ab: NativeArrayBuffer, off: Int, len: Int) : super(ab, off, len, len * 4)

    public constructor(len: Int) : this(NativeArrayBuffer(len * 4), 0, len)

    override val className: String
        get() = CLASS_NAME

    override val bytesPerElement: Int get() = 4

    override fun js_get(index: Int): Any? {
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        return ByteIo.readFloat32(buf, (index * 4) + offset, NativeArrayBufferView.useLittleEndian())
    }

    override fun js_set(index: Int, c: Any?): Any? {
        val v = ScriptRuntime.toNumber(c)
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        ByteIo.writeFloat32(buf, (index * 4) + offset, v, NativeArrayBufferView.useLittleEndian())
        return null
    }

    public companion object {
        private const val CLASS_NAME = "Float32Array"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                3,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args ->
                    NativeTypedArrayView.js_constructor(icx, s, args, { ab, off, len -> NativeFloat32Array(ab, off, len) }, 4)
                },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            NativeTypedArrayView.init(cx, scope, constructor)
            constructor.defineProperty("BYTES_PER_ELEMENT", 4, DONTENUM or READONLY or PERMANENT)
            constructor.definePrototypeProperty("BYTES_PER_ELEMENT", 4, DONTENUM or READONLY or PERMANENT)

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }
    }
}

/** The `Float64Array` view: 8 bytes per element. */
public class NativeFloat64Array : NativeTypedArrayView {

    public constructor() : super()

    public constructor(ab: NativeArrayBuffer, off: Int, len: Int) : super(ab, off, len, len * 8)

    public constructor(len: Int) : this(NativeArrayBuffer(len * 8), 0, len)

    override val className: String
        get() = CLASS_NAME

    override val bytesPerElement: Int get() = 8

    override fun js_get(index: Int): Any? {
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        return ByteIo.readFloat64(buf, (index * 8) + offset, NativeArrayBufferView.useLittleEndian())
    }

    override fun js_set(index: Int, c: Any?): Any? {
        val v = ScriptRuntime.toNumber(c)
        if (checkIndex(index)) return Undefined.instance
        val buf = arrayBuffer.buffer!!
        ByteIo.writeFloat64(buf, (index * 8) + offset, v, NativeArrayBufferView.useLittleEndian())
        return null
    }

    public companion object {
        private const val CLASS_NAME = "Float64Array"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                3,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args ->
                    NativeTypedArrayView.js_constructor(icx, s, args, { ab, off, len -> NativeFloat64Array(ab, off, len) }, 8)
                },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            NativeTypedArrayView.init(cx, scope, constructor)
            constructor.defineProperty("BYTES_PER_ELEMENT", 8, DONTENUM or READONLY or PERMANENT)
            constructor.definePrototypeProperty("BYTES_PER_ELEMENT", 8, DONTENUM or READONLY or PERMANENT)

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }
    }
}
