/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import java.lang.ref.WeakReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `WeakMap` entry goes when its key does, whatever its value refers to (issue 22, D-82). Upstream
 * keeps a `WeakHashMap`, so a value that leads back to its own key keeps both alive for as long as
 * the map lives. Only the JVM can watch a collection, so this is where it is checked.
 */
class WeakMapGcTest {

    private lateinit var cx: Context
    private lateinit var scope: Scriptable

    @BeforeTest
    fun open() {
        cx = Context.enter()
        cx.languageVersion = Context.VERSION_ES6
        scope = cx.initStandardObjects()
        eval("var map = new WeakMap()")
    }

    @AfterTest
    fun close() {
        Context.exit()
    }

    private fun eval(source: String): Any? = cx.evaluateString(scope, source, "weak", 1)

    /** A weak reference to what [source] answers, with no strong one left behind. */
    private fun weakly(source: String): WeakReference<Any> = WeakReference(eval(source)!!)

    private fun collect() {
        repeat(20) {
            System.gc()
            Thread.sleep(10)
        }
    }

    @Test
    fun aValueThatLeadsBackToItsKeyDoesNotKeepIt() {
        val itself = weakly("(function () { var k = {}; map.set(k, k); return k })()")
        val owner = weakly("(function () { var k = {}; map.set(k, { owner: k }); return k })()")
        val deep = weakly("(function () { var k = {}; map.set(k, { a: { b: [k] } }); return k })()")
        val control = weakly("(function () { var k = {}; map.set(k, {}); return k })()")
        collect()
        assertNull(control.get(), "an ordinary key was kept")
        assertNull(itself.get(), "a key that is its own value was kept")
        assertNull(owner.get(), "a key its value points to was kept")
        assertNull(deep.get(), "a key deep in its value's graph was kept")
    }

    /** Weak references to the two keys [source] answers in an array, with nothing strong left. */
    private fun weakPair(source: String): Pair<WeakReference<Any>, WeakReference<Any>> {
        val keys = eval(source) as NativeArray
        return WeakReference(keys.get(0, keys)!!) to WeakReference(keys.get(1, keys)!!)
    }

    @Test
    fun entriesThatLeadToEachOthersKeysGoTogether() {
        val (a, b) = weakPair(
            "(function () { var a = {}, b = {}; map.set(a, { other: b }); map.set(b, { other: a }); return [a, b] })()",
        )
        collect()
        assertNull(a.get())
        assertNull(b.get())
    }

    @Test
    fun aLiveKeyKeepsItsValue() {
        eval("var live = {}; map.set(live, { tag: 'kept', self: live })")
        val value = weakly("map.get(live)")
        collect()
        assertNotNull(value.get(), "the value of a live key was dropped")
        assertEquals("kept", eval("map.get(live).tag"))
        assertEquals(true, eval("map.get(live).self === live && map.has(live)"))
    }

    @Test
    fun aSymbolKeyGoesWithItsValue() {
        val symbol = weakly("(function () { var s = Symbol('k'); map.set(s, { s: s }); return s })()")
        collect()
        assertNull(symbol.get(), "a symbol its value points to was kept")
        assertEquals("kept", eval("var w = Symbol.iterator; map.set(w, 'kept'); map.get(Symbol.iterator)"))
    }

    @Test
    fun aMapThatGoesFirstLeavesNothingOnALiveKey() {
        eval("var key = {}; (function () { var m = new WeakMap(); m.set(key, { big: new Array(1000).join('x') }) })()")
        val value = weakly("(function () { var m = new WeakMap(); var v = { k: key }; m.set(key, v); return v })()")
        collect()
        // The key next looking a map up sweeps out the values of maps that are gone.
        eval("map.get(key)")
        collect()
        assertNull(value.get(), "the value of a collected map stayed on its key")
        assertEquals(false, eval("map.has(key)"))
    }

    @Test
    fun aLongRunningCacheDoesNotGrow() {
        eval(
            "var cache = new WeakMap(); function remember(o) { var meta = { owner: o, seen: 1 }; cache.set(o, meta); return o }",
        )
        val refs = (0 until 2000).map { weakly("remember({ id: $it })") }
        collect()
        val alive = refs.count { it.get() != null }
        assertTrue(alive < 50, "expected the cached keys to be collected, $alive of 2000 are alive")
    }

    /** Upstream's half, pinned so a fix there shows: its `WeakHashMap` keeps such a key. */
    @Test
    fun upstreamKeepsAKeyItsValueLeadsTo() {
        val ucx = org.mozilla.javascript.Context.enter()
        try {
            ucx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
            ucx.isInterpretedMode = true
            val uscope = ucx.initStandardObjects()
            ucx.evaluateString(uscope, "var map = new WeakMap()", "weak", 1, null)
            fun upstream(source: String) = WeakReference(ucx.evaluateString(uscope, source, "weak", 1, null)!!)
            val owner = upstream("(function () { var k = {}; map.set(k, { owner: k }); return k })()")
            val control = upstream("(function () { var k = {}; map.set(k, {}); return k })()")
            collect()
            assertNull(control.get())
            assertNotNull(owner.get(), "upstream let the key go; D-82 can be retired")
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }

    @Test
    fun theMapStillBehavesAsAMap() {
        assertEquals(
            "1,2,true,false,undefined,null,true",
            eval(
                "var k = {}, other = new WeakMap(); map.set(k, 1); other.set(k, 2);" +
                    " var r = [map.get(k), other.get(k)]; other.delete(k);" +
                    " r.push(map.has(k), other.has(k), String(other.get(k)));" +
                    " map.set(k, null); r.push(String(map.get(k)), map.has(k)); r.join()",
            ),
        )
        assertEquals(
            "f,p,s,true",
            eval(
                "var f = Object.freeze({}), p = new Proxy({}, {}), s = Object(Symbol());" +
                    " map.set(f, 'f').set(p, 'p').set(s, 's'); [map.get(f), map.get(p), map.get(s), map.delete(p) && !map.has(p)].join()",
            ),
        )
    }
}
