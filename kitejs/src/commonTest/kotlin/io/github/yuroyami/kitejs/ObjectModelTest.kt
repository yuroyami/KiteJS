/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The object model on every target. The JVM oracle proves these answers match upstream; this proves
 * the same code behaves the same off the JVM, where the hash and iteration order of the underlying
 * collections could differ.
 */
class ObjectModelTest {

    private class Obj : ScriptableObject() {
        override val className: String
            get() = "Object"
    }

    @BeforeTest
    fun enterContext() {
        Context.currentContext = Context().apply { languageVersion = Context.VERSION_ES6 }
    }

    @AfterTest
    fun exitContext() {
        Context.currentContext = null
    }

    @Test
    fun basicReadingAndWriting() {
        val o = Obj()
        assertSame(Scriptable.NOT_FOUND, o.get("missing", o))
        assertFalse(o.has("missing", o))

        o.put("a", o, 1)
        o.put("b", o, "two")
        o.put(0, o, "index zero")

        assertEquals(1, o.get("a", o))
        assertEquals("two", o.get("b", o))
        assertEquals("index zero", o.get(0, o))
        assertEquals(3, o.size())
        assertTrue(o.has("a", o))
        assertTrue(o.has(0, o))

        o.delete("a")
        assertFalse(o.has("a", o))
        assertEquals(2, o.size())
    }

    @Test
    fun idsComeBackInDefinitionOrder() {
        val o = Obj()
        for (name in listOf("z", "a", "m", "b")) o.put(name, o, name)
        assertEquals(listOf("z", "a", "m", "b"), o.getIds().toList())

        // Rewriting a property keeps its place.
        o.put("z", o, "changed")
        assertEquals(listOf("z", "a", "m", "b"), o.getIds().toList())

        // Deleting and re-adding moves it to the end.
        o.delete("z")
        o.put("z", o, "again")
        assertEquals(listOf("a", "m", "b", "z"), o.getIds().toList())
    }

    @Test
    fun attributesAreHonoured() {
        val o = Obj()
        o.defineProperty("ro", 1, ScriptableObject.READONLY)
        o.defineProperty("hidden", 2, ScriptableObject.DONTENUM)
        o.defineProperty("perm", 3, ScriptableObject.PERMANENT)

        o.put("ro", o, 99)
        assertEquals(1, o.get("ro", o), "a readonly property should not change")

        // Only DONTENUM keeps a property out of getIds. Readonly on its own does not.
        assertEquals(listOf("ro", "perm"), o.getIds().toList())
        assertEquals(3, o.getAllIds().size)

        o.delete("perm")
        assertTrue(o.has("perm", o), "a permanent property should not be deletable")
    }

    @Test
    fun growingCrossesEveryMapShape() {
        val o = Obj()
        assertEquals("EmptySlotMap", o.map::class.simpleName)
        o.put("one", o, 1)
        assertEquals("SingleEntrySlotMap", o.map::class.simpleName)
        o.put("two", o, 2)
        assertEquals("EmbeddedSlotMap", o.map::class.simpleName)

        var i = 0
        while (o.size() < SlotMapOwner.LARGE_HASH_SIZE) {
            o.put("g$i", o, i)
            i++
        }
        assertEquals("EmbeddedSlotMap", o.map::class.simpleName)
        o.put("g$i", o, i)
        assertEquals("HashSlotMap", o.map::class.simpleName)

        // Everything is still readable, and still in order, after the change of shape.
        assertEquals(SlotMapOwner.LARGE_HASH_SIZE + 1, o.size())
        assertEquals(1, o.get("one", o))
        assertEquals(0, o.get("g0", o))
        assertEquals(listOf("one", "two", "g0"), o.getIds().take(3))
    }

    @Test
    fun aRandomSequenceStaysConsistentWithAPlainMap() {
        // A model check rather than a comparison: the object has to agree with an ordinary map
        // kept alongside it, including the order of the keys.
        val o = Obj()
        val model = LinkedHashMap<String, Any?>()
        val random = Random(5150)
        val keys = (0 until 80).map { "k$it" }

        repeat(20_000) { step ->
            val key = keys[random.nextInt(keys.size)]
            if (random.nextInt(4) == 0) {
                o.delete(key)
                model.remove(key)
            } else {
                o.put(key, o, step)
                model[key] = step
            }
        }
        assertEquals(model.keys.toList(), o.getIds().map { it as String })
        for ((key, value) in model) assertEquals(value, o.get(key, o), "value of $key")
        assertEquals(model.size, o.size())
    }

    @Test
    fun prototypeChainLookup() {
        val proto = Obj()
        val o = Obj()
        o.prototype = proto

        proto.put("inherited", proto, "fromProto")
        assertEquals("fromProto", ScriptableObject.getProperty(o, "inherited"))
        assertTrue(ScriptableObject.hasProperty(o, "inherited"))
        assertFalse(o.has("inherited", o), "the property is not the object's own")

        // Writing lands on the object, not the prototype.
        ScriptableObject.putProperty(o, "inherited", "written")
        assertEquals("written", o.get("inherited", o))
        assertEquals("fromProto", proto.get("inherited", proto))

        assertSame(Scriptable.NOT_FOUND, ScriptableObject.getProperty(o, "nowhere"))
        assertEquals(proto, ScriptableObject.getTopLevelScope(proto))
    }

    @Test
    fun symbolKeysStayOutOfIds() {
        val o = Obj()
        o.put("plain", o, 1)
        o.put(SymbolKey.ITERATOR, o, "iter")

        assertEquals("iter", o.get(SymbolKey.ITERATOR, o))
        assertTrue(o.has(SymbolKey.ITERATOR, o))
        assertEquals(listOf("plain"), o.getIds().toList())

        o.delete(SymbolKey.ITERATOR)
        assertFalse(o.has(SymbolKey.ITERATOR, o))
    }

    @Test
    fun sealingAndExtensibility() {
        val o = Obj()
        o.put("before", o, 1)
        assertTrue(o.isExtensible)
        o.preventExtensions()
        assertFalse(o.isExtensible)
        o.put("after", o, 2)
        assertFalse(o.has("after", o), "a non-extensible object takes no new properties")

        val sealed = Obj()
        sealed.put("x", sealed, 1)
        sealed.sealObject()
        assertTrue(sealed.isSealed)
        assertFailsWith<EvaluatorException> { sealed.put("y", sealed, 2) }
    }

    @Test
    fun accessorProperties() {
        val o = Obj()
        var stored: Any? = "initial"
        o.defineProperty("viaLambda", { stored }, { v -> stored = v }, ScriptableObject.EMPTY)

        assertEquals("initial", o.get("viaLambda", o))
        o.put("viaLambda", o, "written")
        assertEquals("written", stored)
        assertEquals("written", o.get("viaLambda", o))
    }

    @Test
    fun descriptorsDriveTheAttributes() {
        val cx = Context.getContext()
        val o = Obj()

        o.defineOwnProperty(cx, "frozen", ScriptableObject.DescriptorInfo(false, false, false, 7))
        assertEquals(7, o.get("frozen", o))
        assertEquals(listOf<Any?>(), o.getIds().toList(), "not enumerable")
        o.put("frozen", o, 8)
        assertEquals(7, o.get("frozen", o), "not writable")

        assertFailsWith<EcmaError> {
            o.defineOwnProperty(cx, "frozen", ScriptableObject.DescriptorInfo(true, true, true, 9))
        }
    }

    @Test
    fun constants() {
        val o = Obj()
        o.defineConst("c", o)
        assertTrue(o.isConst("c"))
        o.putConst("c", o, 1)
        assertEquals(1, o.get("c", o))
        o.putConst("c", o, 2)
        assertEquals(1, o.get("c", o), "a const takes its value once")
    }

    @Test
    fun associatedValuesArePerObject() {
        val o = Obj()
        assertNull(o.getAssociatedValue("tag"))
        assertEquals("first", o.associateValue("tag", "first"))
        assertEquals("first", o.associateValue("tag", "second"), "the first value wins")
        assertEquals("first", o.getAssociatedValue("tag"))
    }
}
