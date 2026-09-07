/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mozilla.javascript.Context as UContext
import org.mozilla.javascript.IdFunctionObject as UIdFunctionObject
import org.mozilla.javascript.IdScriptableObject as UIdScriptableObject
import org.mozilla.javascript.NativeObject as UNativeObject
import org.mozilla.javascript.Scriptable as UScriptable
import org.mozilla.javascript.ScriptableObject as UScriptableObject

/**
 * The id-based native object machinery, compared against upstream. The same small class, a point
 * with `x` and `y` instance properties and a `sum` prototype method, is written against each side's
 * `IdScriptableObject`, then driven through the same operations.
 */
class IdScriptableObjectOracleTest {

    private lateinit var upstreamCx: UContext

    @BeforeTest
    fun enterContexts() {
        upstreamCx = UContext.enter()
        upstreamCx.languageVersion = UContext.VERSION_ES6
        Context.enter().languageVersion = Context.VERSION_ES6
    }

    @AfterTest
    fun exitContexts() {
        UContext.exit()
        Context.exit()
    }

    private class UPoint : UIdScriptableObject() {
        var x: Any? = 1
        var y: Any? = 2
        override fun getClassName() = "Point"
        override fun getMaxInstanceId() = 2
        override fun findInstanceIdInfo(name: String): Int = when (name) {
            "x" -> instanceIdInfo(PERMANENT, 1)
            "y" -> instanceIdInfo(READONLY, 2)
            else -> 0
        }
        override fun getInstanceIdName(id: Int) = if (id == 1) "x" else "y"
        override fun getInstanceIdValue(id: Int): Any? = if (id == 1) x else y
        override fun setInstanceIdValue(id: Int, value: Any?) { if (id == 1) x = value else y = value }
        override fun findPrototypeId(name: String) = when (name) { "constructor" -> 1; "sum" -> 2; "tag" -> 3; else -> 0 }
        override fun initPrototypeId(id: Int) {
            when (id) {
                1 -> initPrototypeMethod(TAG, id, "constructor", 0)
                2 -> initPrototypeMethod(TAG, id, "sum", 0)
                3 -> initPrototypeValue(id, "tag", "point", DONTENUM or READONLY)
                else -> throw IllegalArgumentException("$id")
            }
        }
        override fun execIdCall(f: UIdFunctionObject, cx: UContext, scope: UScriptable, thisObj: UScriptable?, args: Array<Any?>): Any? {
            if (!f.hasTag(TAG)) return super.execIdCall(f, cx, scope, thisObj, args)
            return when (f.methodId()) {
                1 -> UPoint()
                2 -> { val p = thisObj as UPoint; (p.x as Int) + (p.y as Int) }
                else -> throw f.unknown()
            }
        }
        companion object { val TAG = Any() }
    }

    private class KPoint : IdScriptableObject() {
        var x: Any? = 1
        var y: Any? = 2
        override val className get() = "Point"
        override fun getMaxInstanceId() = 2
        override fun findInstanceIdInfo(name: String): Int = when (name) {
            "x" -> instanceIdInfo(PERMANENT, 1)
            "y" -> instanceIdInfo(READONLY, 2)
            else -> 0
        }
        override fun getInstanceIdName(id: Int) = if (id == 1) "x" else "y"
        override fun getInstanceIdValue(id: Int): Any? = if (id == 1) x else y
        override fun setInstanceIdValue(id: Int, value: Any?) { if (id == 1) x = value else y = value }
        override fun findPrototypeId(name: String) = when (name) { "constructor" -> 1; "sum" -> 2; "tag" -> 3; else -> 0 }
        override fun initPrototypeId(id: Int) {
            when (id) {
                1 -> initPrototypeMethod(TAG, id, "constructor", 0)
                2 -> initPrototypeMethod(TAG, id, "sum", 0)
                3 -> initPrototypeValue(id, "tag", "point", DONTENUM or READONLY)
                else -> throw IllegalArgumentException("$id")
            }
        }
        override fun execIdCall(f: IdFunctionObject, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (!f.hasTag(TAG)) return super.execIdCall(f, cx, scope, thisObj, args)
            return when (f.methodId()) {
                1 -> KPoint()
                2 -> { val p = thisObj as KPoint; (p.x as Int) + (p.y as Int) }
                else -> throw f.unknown()
            }
        }
        companion object { val TAG = Any() }
    }

    private fun render(v: Any?): String = when {
        v == null -> "null"
        v === UScriptable.NOT_FOUND || v === Scriptable.NOT_FOUND -> "NOT_FOUND"
        v === org.mozilla.javascript.Undefined.instance || v === Undefined.instance -> "undefined"
        v is UIdFunctionObject -> "idfn:${v.functionName}/${v.arity}"
        v is IdFunctionObject -> "idfn:${v.functionName}/${v.arity}"
        else -> v.toString()
    }

    private fun setUp(): Pair<Pair<UPoint, UPoint>, Pair<KPoint, KPoint>> {
        val uScope = UNativeObject()
        val uProto = UPoint()
        uProto.exportAsJSClass(3, uScope, false)
        val u = UPoint().also { it.prototype = uProto; it.parentScope = uScope }

        val kScope = NativeObject()
        val kProto = KPoint()
        kProto.exportAsJSClass(3, kScope, false)
        val k = KPoint().also { it.prototype = kProto; it.parentScope = kScope }
        return (uProto to u) to (kProto to k)
    }

    @Test
    fun instanceAndPrototypeIdsReadTheSame() {
        val (up, kp) = setUp()
        val (uProto, u) = up
        val (kProto, k) = kp
        for (name in listOf("x", "y", "sum", "tag", "constructor", "missing")) {
            assertEquals(render(UScriptableObject.getProperty(u, name)), render(ScriptableObject.getProperty(k, name)), name)
            assertEquals(UScriptableObject.hasProperty(u, name), ScriptableObject.hasProperty(k, name), "has $name")
        }
        assertEquals(uProto.ids.map { it.toString() }, kProto.getIds().map { it.toString() })
        assertEquals(uProto.allIds.map { it.toString() }.sorted(), kProto.getAllIds().map { it.toString() }.sorted())
        assertEquals(u.ids.map { it.toString() }, k.getIds().map { it.toString() })
        assertEquals(u.getAttributes("x"), k.getAttributes("x"))
        assertEquals(u.getAttributes("y"), k.getAttributes("y"))
        assertEquals(uProto.getAttributes("tag"), kProto.getAttributes("tag"))
    }

    @Test
    fun writesHonourTheAttributes() {
        val (up, kp) = setUp()
        val (_, u) = up
        val (_, k) = kp
        u.put("x", u, 10); k.put("x", k, 10)
        u.put("y", u, 20); k.put("y", k, 20)
        assertEquals(render(u.get("x", u)), render(k.get("x", k)))
        assertEquals(render(u.get("y", u)), render(k.get("y", k)), "y is readonly")
        u.delete("x"); k.delete("x")
        assertEquals(u.has("x", u), k.has("x", k), "x is permanent")
        u.put("extra", u, 1); k.put("extra", k, 1)
        assertEquals(u.ids.map { it.toString() }, k.getIds().map { it.toString() })
    }

    @Test
    fun prototypeMethodsRunAndTheConstructorIsExported() {
        val (up, kp) = setUp()
        val (uProto, u) = up
        val (kProto, k) = kp
        val uSum = UScriptableObject.getProperty(u, "sum") as UIdFunctionObject
        val kSum = ScriptableObject.getProperty(k, "sum") as IdFunctionObject
        assertEquals(uSum.call(upstreamCx, uProto, u, emptyArray()), kSum.call(Context.getContext(), kProto, k, emptyArray()))

        val uScope = uProto.parentScope
        val kScope = kProto.parentScope!!
        val uCtor = UScriptableObject.getProperty(uScope, "Point")
        val kCtor = ScriptableObject.getProperty(kScope, "Point")
        assertEquals(render(uCtor), render(kCtor))
        assertEquals(
            render(UScriptableObject.getProperty(uCtor as UScriptable, "prototype") === uProto),
            render(ScriptableObject.getProperty(kCtor as Scriptable, "prototype") === kProto),
        )
        assertEquals(render(UScriptableObject.getProperty(uProto, "constructor") === uCtor), render(ScriptableObject.getProperty(kProto, "constructor") === kCtor))
    }

    @Test
    fun prototypeValuesCanBeDeletedAndRedefined() {
        val (up, kp) = setUp()
        val (uProto, _) = up
        val (kProto, _) = kp
        uProto.delete("sum"); kProto.delete("sum")
        assertEquals(uProto.has("sum", uProto), kProto.has("sum", kProto))
        uProto.delete("tag"); kProto.delete("tag")
        assertEquals(uProto.has("tag", uProto), kProto.has("tag", kProto))
        uProto.put("sum", uProto, "replaced"); kProto.put("sum", kProto, "replaced")
        assertEquals(render(uProto.get("sum", uProto)), render(kProto.get("sum", kProto)))
        assertEquals(uProto.allIds.map { it.toString() }.sorted(), kProto.getAllIds().map { it.toString() }.sorted())
    }
}
