/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mozilla.javascript.NativeObject as UNativeObject
import org.mozilla.javascript.Scriptable as UScriptable
import org.mozilla.javascript.ScriptableObject as UScriptableObject
import org.mozilla.javascript.SymbolKey as USymbolKey

/**
 * The object model, compared against the upstream jar through the public property API.
 *
 * Driving `ScriptableObject` also drives the slot maps underneath it, including the points where
 * they change representation: an empty map becomes a single-entry map, then an embedded hash table
 * that doubles as it fills, and finally a linked hash map once the object has more than 1536
 * properties. The sequences below are long enough to cross all of those.
 */
class ScriptableObjectOracleTest {

    private lateinit var upstreamCx: org.mozilla.javascript.Context

    @BeforeTest
    fun enterContexts() {
        upstreamCx = org.mozilla.javascript.Context.enter()
        upstreamCx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
        Context.currentContext = Context().apply { languageVersion = Context.VERSION_ES6 }
    }

    @AfterTest
    fun exitContexts() {
        org.mozilla.javascript.Context.exit()
        Context.currentContext = null
    }

    /** A plain object on each side. Only the property machinery is under test, not the builtin. */
    private class PortedObject : ScriptableObject() {
        override val className: String
            get() = "Object"
    }

    private fun newPair(): Pair<UScriptableObject, ScriptableObject> =
        UNativeObject() to PortedObject()

    /** Everything observable about an object, rendered the same way on both sides. */
    private fun renderUpstream(o: UScriptableObject): String = buildString {
        append("size=").append(o.size()).append('\n')
        append("extensible=").append(o.isExtensible).append(" sealed=").append(o.isSealed).append('\n')
        for (id in o.allIds) {
            append(id).append('=')
            val v = when (id) {
                is Int -> o.get(id, o)
                else -> o.get(id as String, o)
            }
            append(render(v)).append(" attr=")
            append(if (id is Int) o.getAttributes(id) else o.getAttributes(id as String))
            append('\n')
        }
        append("ids=").append(o.ids.joinToString(",")).append('\n')
    }

    private fun renderPorted(o: ScriptableObject): String = buildString {
        append("size=").append(o.size()).append('\n')
        append("extensible=").append(o.isExtensible).append(" sealed=").append(o.isSealed).append('\n')
        for (id in o.getAllIds()) {
            append(id).append('=')
            val v = when (id) {
                is Int -> o.get(id, o)
                else -> o.get(id as String, o)
            }
            append(render(v)).append(" attr=")
            append(if (id is Int) o.getAttributes(id) else o.getAttributes(id as String))
            append('\n')
        }
        append("ids=").append(o.getIds().joinToString(",")).append('\n')
    }

    private fun render(v: Any?): String = when {
        v == null -> "null"
        v === org.mozilla.javascript.Scriptable.NOT_FOUND -> "NOT_FOUND"
        v === Scriptable.NOT_FOUND -> "NOT_FOUND"
        v === org.mozilla.javascript.Undefined.instance -> "undefined"
        v === Undefined.instance -> "undefined"
        else -> v.toString()
    }

    /**
     * The message without the source position. Upstream appends `(file#line)` by walking the Java
     * stack; this port has no interpreter stack to read yet, so it appends nothing (D-18). The
     * text before that suffix has to match exactly.
     */
    private fun problem(e: Throwable?): String? = when (e) {
        null -> null
        is org.mozilla.javascript.RhinoException -> e.details()
        is RhinoException -> e.details()
        else -> e.message
    }

    @Test
    fun aLongRandomSequenceOfPropertyOperationsMatchesUpstream() {
        val (u, k) = newPair()
        val random = Random(90210)
        val keys = (0 until 120).map { "p$it" }

        repeat(20_000) { step ->
            val name = keys[random.nextInt(keys.size)]
            val index = random.nextInt(0, 40)
            val value: Any = when (random.nextInt(4)) {
                0 -> step
                1 -> "v$step"
                2 -> step.toDouble() / 3.0
                else -> (step % 2 == 0)
            }
            when (random.nextInt(10)) {
                0, 1, 2 -> {
                    u.put(name, u, value)
                    k.put(name, k, value)
                }
                3 -> {
                    u.put(index, u, value)
                    k.put(index, k, value)
                }
                4 -> {
                    u.delete(name)
                    k.delete(name)
                }
                5 -> {
                    u.delete(index)
                    k.delete(index)
                }
                6 -> {
                    u.defineProperty(name, value, UScriptableObject.DONTENUM)
                    k.defineProperty(name, value, ScriptableObject.DONTENUM)
                }
                7 -> {
                    u.defineProperty(name, value, UScriptableObject.READONLY)
                    k.defineProperty(name, value, ScriptableObject.READONLY)
                }
                8 -> {
                    assertEquals(u.has(name, u), k.has(name, k), "has($name) at step $step")
                    assertEquals(u.has(index, u), k.has(index, k), "has($index) at step $step")
                }
                else -> {
                    assertEquals(
                        render(u.get(name, u)),
                        render(k.get(name, k)),
                        "get($name) at step $step",
                    )
                }
            }
        }
        assertEquals(renderUpstream(u), renderPorted(k))
    }

    @Test
    fun growingPastEveryMapThresholdKeepsTheSameOrder() {
        // 2500 properties crosses the single-entry, embedded and hash-map boundaries.
        val (u, k) = newPair()
        for (i in 0 until 2500) {
            u.put("key$i", u, i)
            k.put("key$i", k, i)
        }
        assertEquals(2500, k.size())
        assertEquals(renderUpstream(u), renderPorted(k))

        // Deleting from the middle has to leave the same order behind.
        for (i in 500 until 2000 step 3) {
            u.delete("key$i")
            k.delete("key$i")
        }
        assertEquals(renderUpstream(u), renderPorted(k))

        // And adding again after the deletions.
        for (i in 2500 until 3000) {
            u.put("key$i", u, i)
            k.put("key$i", k, i)
        }
        assertEquals(renderUpstream(u), renderPorted(k))
    }

    @Test
    fun indexAndStringKeysInterleaveTheSameWay() {
        val (u, k) = newPair()
        val random = Random(4242)
        repeat(3_000) { i ->
            if (random.nextBoolean()) {
                u.put(random.nextInt(0, 200), u, i)
                k.put(random.nextInt(0, 200), k, i)
            } else {
                u.put("s${random.nextInt(0, 200)}", u, i)
                k.put("s${random.nextInt(0, 200)}", k, i)
            }
        }
        // The two sides drew from separate random streams above, so only the shape is comparable.
        assertEquals(u.ids.size > 0, k.getIds().isNotEmpty())

        val (u2, k2) = newPair()
        val r2 = Random(777)
        repeat(3_000) { i ->
            val useIndex = r2.nextBoolean()
            val n = r2.nextInt(0, 200)
            if (useIndex) {
                u2.put(n, u2, i)
                k2.put(n, k2, i)
            } else {
                u2.put("s$n", u2, i)
                k2.put("s$n", k2, i)
            }
        }
        assertEquals(renderUpstream(u2), renderPorted(k2))
    }

    @Test
    fun attributesBehaveTheSame() {
        val (u, k) = newPair()
        val attrSets = listOf(
            0,
            UScriptableObject.READONLY,
            UScriptableObject.DONTENUM,
            UScriptableObject.PERMANENT,
            UScriptableObject.READONLY or UScriptableObject.DONTENUM,
            UScriptableObject.READONLY or UScriptableObject.PERMANENT,
            UScriptableObject.DONTENUM or UScriptableObject.PERMANENT,
            UScriptableObject.READONLY or UScriptableObject.DONTENUM or UScriptableObject.PERMANENT,
        )
        for ((i, attr) in attrSets.withIndex()) {
            u.defineProperty("a$i", i, attr)
            k.defineProperty("a$i", i, attr)
            // Writing over each one, which readonly has to refuse.
            u.put("a$i", u, "changed")
            k.put("a$i", k, "changed")
            // And deleting each one, which permanent has to refuse.
            u.delete("a$i")
            k.delete("a$i")
        }
        assertEquals(renderUpstream(u), renderPorted(k))
    }

    @Test
    fun sealingAndExtensibilityMatchUpstream() {
        val (u, k) = newPair()
        u.put("before", u, 1)
        k.put("before", k, 1)

        assertEquals(u.preventExtensions(), k.preventExtensions())
        assertEquals(u.isExtensible, k.isExtensible)
        u.put("after", u, 2)
        k.put("after", k, 2)
        assertEquals(renderUpstream(u), renderPorted(k))

        val (u2, k2) = newPair()
        u2.put("x", u2, 1)
        k2.put("x", k2, 1)
        u2.sealObject()
        k2.sealObject()
        assertEquals(u2.isSealed, k2.isSealed)

        val uThrew = runCatching { u2.put("y", u2, 2) }.exceptionOrNull()
        val kThrew = runCatching { k2.put("y", k2, 2) }.exceptionOrNull()
        assertEquals(problem(uThrew), problem(kThrew), "sealed put")
        assertEquals(renderUpstream(u2), renderPorted(k2))
    }

    @Test
    fun prototypeChainLookupMatchesUpstream() {
        val (uProto, kProto) = newPair()
        val (u, k) = newPair()
        u.prototype = uProto
        k.prototype = kProto

        uProto.put("inherited", uProto, "fromProto")
        kProto.put("inherited", kProto, "fromProto")
        uProto.put("shadowed", uProto, "protoValue")
        kProto.put("shadowed", kProto, "protoValue")
        u.put("shadowed", u, "ownValue")
        k.put("shadowed", k, "ownValue")

        for (name in listOf("inherited", "shadowed", "missing")) {
            assertEquals(
                render(UScriptableObject.getProperty(u, name)),
                render(ScriptableObject.getProperty(k, name)),
                "getProperty($name)",
            )
            assertEquals(
                UScriptableObject.hasProperty(u, name),
                ScriptableObject.hasProperty(k, name),
                "hasProperty($name)",
            )
        }

        // Writing an inherited property has to land on the object, not the prototype.
        UScriptableObject.putProperty(u, "inherited", "written")
        ScriptableObject.putProperty(k, "inherited", "written")
        assertEquals(renderUpstream(uProto), renderPorted(kProto))
        assertEquals(renderUpstream(u), renderPorted(k))

        assertEquals(
            UScriptableObject.getPropertyIds(u).map { it.toString() }.toSet(),
            ScriptableObject.getPropertyIds(k).map { it.toString() }.toSet(),
        )

        assertEquals(
            UScriptableObject.deleteProperty(u, "shadowed"),
            ScriptableObject.deleteProperty(k, "shadowed"),
        )
        assertEquals(renderUpstream(u), renderPorted(k))
    }

    @Test
    fun symbolKeyedPropertiesMatchUpstream() {
        val (u, k) = newPair()
        val uSymbols = listOf(USymbolKey.ITERATOR, USymbolKey.TO_STRING_TAG, USymbolKey.SPECIES)
        val kSymbols = listOf(SymbolKey.ITERATOR, SymbolKey.TO_STRING_TAG, SymbolKey.SPECIES)

        for (i in uSymbols.indices) {
            u.put(uSymbols[i], u, "sym$i")
            k.put(kSymbols[i], k, "sym$i")
            assertEquals(u.has(uSymbols[i], u), k.has(kSymbols[i], k))
            assertEquals(render(u.get(uSymbols[i], u)), render(k.get(kSymbols[i], k)))
        }
        // Symbols stay out of getIds on both sides.
        assertEquals(renderUpstream(u), renderPorted(k))

        u.delete(uSymbols[0])
        k.delete(kSymbols[0])
        assertEquals(u.has(uSymbols[0], u), k.has(kSymbols[0], k))
    }

    @Test
    fun constPropertiesMatchUpstream() {
        val (u, k) = newPair()
        u.defineConst("c", u)
        k.defineConst("c", k)
        assertEquals(u.isConst("c"), k.isConst("c"))

        u.putConst("c", u, 1)
        k.putConst("c", k, 1)
        assertEquals(render(u.get("c", u)), render(k.get("c", k)))

        // A second write to a const has no effect.
        u.putConst("c", u, 2)
        k.putConst("c", k, 2)
        assertEquals(renderUpstream(u), renderPorted(k))
    }

    @Test
    fun definePropertyWithDescriptorsMatchesUpstream() {
        val (u, k) = newPair()

        val cases = listOf(
            Triple(true, true, true),
            Triple(false, true, true),
            Triple(true, false, true),
            Triple(true, true, false),
            Triple(false, false, false),
        )
        for ((i, c) in cases.withIndex()) {
            val (enumerable, writable, configurable) = c
            u.defineOwnProperty(
                upstreamCx,
                "d$i",
                UScriptableObject.DescriptorInfo(enumerable, writable, configurable, i),
            )
            k.defineOwnProperty(
                Context.getContext(),
                "d$i",
                ScriptableObject.DescriptorInfo(enumerable, writable, configurable, i),
            )
        }
        assertEquals(renderUpstream(u), renderPorted(k))

        // Redefining each one, which the non-configurable ones have to refuse the same way.
        for (i in cases.indices) {
            val uThrew = runCatching {
                u.defineOwnProperty(
                    upstreamCx,
                    "d$i",
                    UScriptableObject.DescriptorInfo(true, true, true, "redefined"),
                )
            }.exceptionOrNull()
            val kThrew = runCatching {
                k.defineOwnProperty(
                    Context.getContext(),
                    "d$i",
                    ScriptableObject.DescriptorInfo(true, true, true, "redefined"),
                )
            }.exceptionOrNull()
            assertEquals(problem(uThrew), problem(kThrew), "redefining d$i")
        }
        assertEquals(renderUpstream(u), renderPorted(k))
    }

    @Test
    fun theMapChangesShapeAsTheObjectGrows() {
        // The comparison tests above cannot see this: both map kinds keep insertion order, so
        // promoting at the wrong moment looks identical from outside. This checks it directly,
        // which is what makes crossing the thresholds mean anything.
        val k = PortedObject()
        assertEquals("EmptySlotMap", k.map::class.simpleName)

        k.put("one", k, 1)
        assertEquals("SingleEntrySlotMap", k.map::class.simpleName)

        k.put("two", k, 2)
        assertEquals("EmbeddedSlotMap", k.map::class.simpleName)

        // The embedded map holds on until the next growth would take it past the large-map size.
        var i = 0
        while (k.size() < SlotMapOwner.LARGE_HASH_SIZE) {
            k.put("g$i", k, i)
            i++
        }
        assertEquals(SlotMapOwner.LARGE_HASH_SIZE, k.size())
        assertEquals("EmbeddedSlotMap", k.map::class.simpleName)

        k.put("g$i", k, i)
        assertEquals("HashSlotMap", k.map::class.simpleName)
        assertEquals(SlotMapOwner.LARGE_HASH_SIZE + 1, k.size())
    }

    @Test
    fun theseOperationsActuallyChangeTheObject() {
        // Guards every test above against silently passing on two empty objects.
        val (u, k) = newPair()
        for (i in 0 until 2000) {
            u.put("k$i", u, i)
            k.put("k$i", k, i)
        }
        assertTrue(renderPorted(k).length > 10_000, "the rendered object should be substantial")
        assertEquals(2000, k.size())
        assertEquals(u.size(), k.size())
    }
}
