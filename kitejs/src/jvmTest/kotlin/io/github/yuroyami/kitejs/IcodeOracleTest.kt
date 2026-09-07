/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mozilla.javascript.Context as UContext
import org.mozilla.javascript.JSDescriptor as UJSDescriptor

/**
 * The phase 2 promise kept: every corpus file is compiled to icode by the upstream jar and by this
 * port, and the byte arrays, constant pools, exception tables, frame sizes and nested function
 * tables have to be identical. The icode is exactly what the interpreter executes, so this pins
 * the code generator down completely.
 *
 * Upstream's `InterpreterData` is package-private, so its fields are read by reflection. This
 * port's is `internal`, which a test in the same module can read directly.
 */
class IcodeOracleTest {

    private lateinit var ucx: UContext

    @BeforeTest
    fun enter() {
        ucx = UContext.enter()
        ucx.languageVersion = UContext.VERSION_ES6
        // The jar defaults to the bytecode compiler; the port only has the interpreter.
        ucx.isInterpretedMode = true
        val kcx = Context.enter()
        kcx.languageVersion = Context.VERSION_ES6
        // The regular expression engine lands in phase 4. Literals only need a compiled placeholder
        // here, because the comparison does not look inside them.
        ScriptRuntime.setRegExpProxy(kcx, FakeRegExpProxy)
    }

    @AfterTest
    fun exit() {
        UContext.exit()
        Context.exit()
    }

    private object FakeRegExpProxy : RegExpProxy {
        override fun register(scope: ScriptableObject, sealed: Boolean) {}
        override fun isRegExp(obj: Scriptable?) = false
        override fun compileRegExp(cx: Context, source: String, flags: String?): Any = "/$source/$flags"
        override fun wrapRegExp(cx: Context, scope: Scriptable, compiled: Any): Scriptable = throw UnsupportedOperationException()
        override fun action(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>, actionType: Int): Any? = throw UnsupportedOperationException()
        override fun find_split(cx: Context, scope: Scriptable, target: String, separator: String, re: Scriptable, ip: IntArray, matchlen: IntArray, matched: BooleanArray, parensp: Array<Array<String>?>): Int = throw UnsupportedOperationException()
        override fun js_split(cx: Context, scope: Scriptable, thisString: String, args: Array<Any?>): Any? = throw UnsupportedOperationException()
    }

    private fun corpusFiles(): List<File> {
        val url = javaClass.classLoader.getResource("corpus") ?: error("corpus resources are missing")
        return File(url.toURI()).listFiles { f: File -> f.name.endsWith(".js") }?.sortedBy { it.name }.orEmpty()
    }

    private fun field(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        error("no field $name on ${obj.javaClass}")
    }

    /** Renders a descriptor tree the same way from either side. */
    private fun renderUpstream(desc: UJSDescriptor<*>, sb: StringBuilder, depth: Int) {
        val code = desc.code
        val pad = "  ".repeat(depth)
        sb.append(pad).append("fn=").append(desc.name).append(" type=").append(desc.functionType)
            .append(" strict=").append(desc.isStrict).append(" params=").append(desc.paramCount)
            .append(" vars=").append(desc.paramAndVarCount).append(" arity=").append(desc.arity)
            .append(" flags=").append(field(desc, "flags")).append('\n')
        sb.append(pad).append("names=").append((field(desc, "paramAndVarNames") as Array<*>).joinToString(",")).append('\n')
        if (code != null) {
            sb.append(pad).append("icode=").append((field(code, "itsICode") as ByteArray).joinToString(",")).append('\n')
            sb.append(pad).append("strings=").append((field(code, "itsStringTable") as Array<*>?)?.joinToString("|") ?: "null").append('\n')
            sb.append(pad).append("doubles=").append((field(code, "itsDoubleTable") as DoubleArray?)?.joinToString(",") ?: "null").append('\n')
            sb.append(pad).append("exceptions=").append((field(code, "itsExceptionTable") as IntArray?)?.joinToString(",") ?: "null").append('\n')
            sb.append(pad).append("max=").append(field(code, "itsMaxVars")).append('/').append(field(code, "itsMaxLocals"))
                .append('/').append(field(code, "itsMaxStack")).append('/').append(field(code, "itsMaxFrameArray"))
                .append('/').append(field(code, "itsMaxCalleeArgs")).append(" firstLine=").append(field(code, "firstLinePC")).append('\n')
            sb.append(pad).append("longJumps=").append(field(code, "longJumps")?.toString() ?: "null").append('\n')
            sb.append(pad).append("literals=").append(renderLiterals(field(code, "literalIds") as Array<*>?)).append('\n')
            sb.append(pad).append("templates=").append((field(code, "itsTemplateLiterals") as Array<*>?)?.joinToString("|") { (it as Array<*>).joinToString("~") } ?: "null").append('\n')
        } else {
            sb.append(pad).append("code=null\n")
        }
        sb.append(pad).append("ctor=").append(desc.constructor === code).append('\n')
        for (i in 0 until desc.functionCount) renderUpstream(desc.getFunction(i), sb, depth + 1)
    }

    private fun renderPorted(desc: JSDescriptor<*>, sb: StringBuilder, depth: Int) {
        val code = desc.code as InterpreterData<*>?
        val pad = "  ".repeat(depth)
        sb.append(pad).append("fn=").append(desc.name).append(" type=").append(desc.functionType)
            .append(" strict=").append(desc.isStrict).append(" params=").append(desc.paramCount)
            .append(" vars=").append(desc.paramAndVarCount).append(" arity=").append(desc.arity)
            .append(" flags=").append(field(desc, "flags")).append('\n')
        sb.append(pad).append("names=").append((field(desc, "paramAndVarNames") as Array<*>).joinToString(",")).append('\n')
        if (code != null) {
            sb.append(pad).append("icode=").append(code.itsICode.joinToString(",")).append('\n')
            sb.append(pad).append("strings=").append(if (code.itsStringTable.isEmpty()) "null" else code.itsStringTable.joinToString("|")).append('\n')
            sb.append(pad).append("doubles=").append(code.itsDoubleTable?.joinToString(",") ?: "null").append('\n')
            sb.append(pad).append("exceptions=").append(code.itsExceptionTable?.joinToString(",") ?: "null").append('\n')
            sb.append(pad).append("max=").append(code.itsMaxVars).append('/').append(code.itsMaxLocals)
                .append('/').append(code.itsMaxStack).append('/').append(code.itsMaxFrameArray)
                .append('/').append(code.itsMaxCalleeArgs).append(" firstLine=").append(code.firstLinePC).append('\n')
            sb.append(pad).append("longJumps=").append(code.longJumps?.toString() ?: "null").append('\n')
            sb.append(pad).append("literals=").append(renderLiterals(code.literalIds)).append('\n')
            sb.append(pad).append("templates=").append(code.itsTemplateLiterals?.joinToString("|") { (it as Array<*>).joinToString("~") } ?: "null").append('\n')
        } else {
            sb.append(pad).append("code=null\n")
        }
        sb.append(pad).append("ctor=").append(desc.constructor === code).append('\n')
        for (i in 0 until desc.functionCount) renderPorted(desc.getFunction(i), sb, depth + 1)
    }

    private fun renderLiterals(ids: Array<*>?): String {
        if (ids == null) return "null"
        return ids.joinToString("|") { entry ->
            when (entry) {
                null -> "null"
                is IntArray -> "skip[" + entry.joinToString(",") + "]"
                is Array<*> -> "ids[" + entry.joinToString(",") { id ->
                    when (id) {
                        null -> "null"
                        is org.mozilla.javascript.Node, is Node -> "<computed>"
                        else -> id.toString()
                    }
                } + "]"
                else -> entry.toString()
            }
        }
    }

    private fun upstreamIcode(source: String): String {
        val script = ucx.compileString(source, "corpus.js", 1, null)
        val sb = StringBuilder()
        renderUpstream(script.descriptor, sb, 0)
        return sb.toString()
    }

    private fun portedIcode(source: String): String {
        val script = Context.getContext().compileString(source, "corpus.js", 1) as JSScript
        val sb = StringBuilder()
        renderPorted(script.descriptor, sb, 0)
        return sb.toString()
    }

    @Test
    fun corpusCompilesToIdenticalIcode() {
        val failures = mutableListOf<String>()
        var compared = 0
        for (file in corpusFiles()) {
            val source = file.readText()
            val expected = runCatching { upstreamIcode(source) }
            val actual = runCatching { portedIcode(source) }
            if (expected.isFailure) {
                if (actual.isSuccess) failures.add("${file.name}: upstream rejects it but the port accepts it")
                continue
            }
            if (actual.isFailure) {
                failures.add("${file.name}: upstream compiles it but the port threw ${actual.exceptionOrNull()}")
                continue
            }
            compared++
            val e = expected.getOrThrow()
            val a = actual.getOrThrow()
            if (e != a) {
                val el = e.lines()
                val al = a.lines()
                val i = el.indices.firstOrNull { it >= al.size || el[it] != al[it] } ?: el.size
                failures.add("${file.name}: first difference at line $i\n  upstream: ${el.getOrNull(i)}\n  ported:   ${al.getOrNull(i)}")
            }
        }
        assertTrue(compared > 20, "only $compared files were compared")
        assertEquals(emptyList(), failures, "icode differs from upstream")
    }

    @Test
    fun handWrittenCasesCompileToIdenticalIcode() {
        // Short sources that hit the paths a corpus of whole programs can skip past.
        val sources = listOf(
            "1",
            "-0",
            "1e300 + 0.5",
            "var a = 1; a++; --a; a += 2",
            "function f(a, b) { return a + b } f(1, 2)",
            "function f(a, a) { return a } f(1, 2)",
            "function f(a) { var a; return a }",
            "function f() { return arguments.length }",
            "function f(...rest) { return rest }",
            "function f(a = 1, b = a + 1) { return b }",
            "var o = { a: 1, 'b': 2, 3: 3, [1 + 1]: 4, get g() { return 1 }, set s(v) {}, m() {} }",
            "var o = { ...x, a: 1 }",
            "var a = [1, , 3, ...b]",
            "a?.b?.[c]?.(d)",
            "a ?? b",
            "try { f() } catch (e) { g(e) } finally { h() }",
            "try { f() } catch ({ x }) { g(x) }",
            "for (var i = 0; i < 10; i++) { if (i == 5) continue; if (i == 8) break }",
            "for (var k in o) { k }",
            "for (var v of arr) { v }",
            "label: for (;;) { for (;;) { break label } }",
            "switch (x) { case 1: a(); case 2: b(); break; default: c() }",
            "with (o) { a }",
            "typeof x; typeof o.p; void 0; delete o.p; delete x",
            "x = y = z = 1",
            "a && b || c && d",
            "a ? b : c ? d : e",
            "function* g() { yield 1; yield* other(); return 3 }",
            "var f = (a, b) => a + b; var g = x => { return x }",
            "var s = `a\${b}c\${d}e`; tag`x\${y}z`",
            "'use strict'; var x = 1",
            "function f() { 'use strict'; return this }",
            "new F(1, 2); new F",
            "o.m(1); o[k](2); f.call(o); f.apply(o, [1])",
            "var [a, b = 2, ...c] = d; var { e, f: g, ...h } = i",
            "x instanceof Y; 'k' in o",
            "a << b >> c >>> d; a & b | c ^ d; ~a; !a; +a; -a",
            "a ** b; a %= b; a **= b",
            "if (a) b; else if (c) d; else e",
            "do { a } while (b); while (c) { d }",
            "throw new Error('x')",
            "eval('1'); (0, eval)('1'); f(eval)",
            "debugger",
            "this.x = 1; super_ = 2",
            "var big = 123n",
            "var r = /ab+c/gi",
            "x = function named() { return named }",
            "(function () { return 1 })()",
            "a = b; a.b = c; a[b] = c; a.b.c = d",
            "delete a[b]; a[b]++; a.b--; ++a[b]",
            "function f() { return; } function g() { }",
            "var x; let y = 1; const z = 2; { let y = 3 }",
            "class C { constructor() {} }",
            "async function f() { await x }",
            "var o = { __proto__: p }",
            "if (a) { function f() {} }",
            "var f = function () { return arguments }; f.arguments",
        )
        val failures = mutableListOf<String>()
        var compared = 0
        for (source in sources) {
            val expected = runCatching { upstreamIcode(source) }
            val actual = runCatching { portedIcode(source) }
            if (expected.isFailure) {
                if (actual.isSuccess) failures.add("$source: upstream rejects it but the port accepts it")
                continue
            }
            if (actual.isFailure) {
                failures.add("$source: upstream compiles it but the port threw ${actual.exceptionOrNull()}")
                continue
            }
            compared++
            val e = expected.getOrThrow()
            val a = actual.getOrThrow()
            if (e != a) {
                val el = e.lines()
                val al = a.lines()
                val i = el.indices.firstOrNull { it >= al.size || el[it] != al[it] } ?: el.size
                failures.add("$source: first difference at line $i\n  upstream: ${el.getOrNull(i)}\n  ported:   ${al.getOrNull(i)}")
            }
        }
        assertTrue(compared > 30, "only $compared sources were compared")
        assertEquals(emptyList(), failures, "icode differs from upstream")
    }
}
