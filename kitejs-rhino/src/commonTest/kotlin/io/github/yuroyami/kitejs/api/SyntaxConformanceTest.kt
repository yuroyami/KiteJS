/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Parser and runtime rules that the class and async function tests of test262 turned up, which
 * apply to plain functions as much as to classes and async functions, and which upstream Rhino
 * gets wrong (#2, D-95; #12, D-97). Every expected value is what V8 answers. An early error is
 * checked through `eval`, whose SyntaxError a script can catch.
 */
class SyntaxConformanceTest {

    private fun check(expected: String, source: String) {
        assertEquals(expected, KiteJs().use { js -> js.evaluate(source).asString() }, source)
    }

    /** What `eval` of [source] throws, or "ok". */
    private fun early(source: String): String =
        "try { eval(${quote(source)}); 'ok' } catch (e) { e.name }"

    private fun quote(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"

    /** A reserved word spelled with an escape is a name: fine for a property, an error as an identifier (ECMAScript 2015, 11.6.2). */
    @Test
    fun escaped_keywords() {
        check("1", "({ \\u0069f: 1 }).\\u0069f")
        check("8", "var o = { \\u0069f() { return 8 } }; o.if()")
        check("SyntaxError", early("var \\u0069f"))
        check("SyntaxError", early("var o = { \\u0069f }"))
        check("SyntaxError", early("tru\\u0065"))
        check("SyntaxError", early("function f() { return n\\u0065w.target }"))
        check("SyntaxError", early("for (var x \\u006ff [1]) ;"))
        check("SyntaxError", early("class C { st\\u0061tic m() {} }"))
        check("4", "class C { st\\u0061tic() { return 4 } }; new C().static()")
        check("SyntaxError", early("({ g\\u0065t x() {} })"))
        check("5", "({ g\\u0065t: 5 }).get")
        check("3", "var l\\u0065t = 3; l\\u0065t")
        check("SyntaxError", early("'use strict'; var l\\u0065t = 3"))
        check("SyntaxError", early("function* g() { yi\\u0065ld }"))
        check("SyntaxError", early("({ *g() { var yi\\u0065ld } })"))
    }

    /** A getter takes no parameter and a setter exactly one, which is no rest parameter (ECMAScript 2015, 14.3.1). */
    @Test
    fun accessor_parameters() {
        check("SyntaxError", early("({ get x(a) {} })"))
        check("SyntaxError", early("({ set x() {} })"))
        check("SyntaxError", early("({ set x(a, b) {} })"))
        check("SyntaxError", early("class C { set x(...a) {} }"))
        check("ok", early("({ set x({ a }) {} })"))
    }

    /** `get` before a line break and `*` is a field: no accessor name starts with `*`. */
    @Test
    fun get_before_a_generator_method() {
        check(",1", "class C { get\n*a() { yield 1 } }; var c = new C(); [c.get, c.a().next().value].join()")
        check("SyntaxError", early("class C { get *a() {} }"))
    }

    /** Only the bare expression of a `for` head loses the `in` operator (ECMAScript 2015, 12.2). */
    @Test
    fun in_between_brackets_in_a_for_head() {
        check("false", "var r; for (var o = { ['x' in {}]: 1 }; ;) { r = Object.keys(o)[0]; break } r")
        check("true", "var r; for (var a = ['x' in { x: 1 }]; ;) { r = a[0]; break } r")
        check("true", "var r; for (var a = { v: 'x' in { x: 1 } }; ;) { r = a.v; break } r")
        check("true", "var r; for (var a = `\${'x' in { x: 1 }}`; ;) { r = a; break } r")
        check("a", "var k; for (k in { a: 1 }) ; k")
    }

    /** A rest parameter may be a pattern, has no default, and makes the parameter list non-simple. */
    @Test
    fun rest_parameters_and_simple_lists() {
        check("3", "function f(...[a, b]) { return a + b } f(1, 2)")
        check("2", "function f(x, ...{ length }) { return length } f(1, 2, 3)")
        check("12", "class C { m(...[a, b]) { return a * b } }; new C().m(3, 4)")
        check("SyntaxError", early("function f(...a = []) {}"))
        check("SyntaxError", early("function f(...[a] = []) {}"))
        check("SyntaxError", early("function f(a = 1) { 'use strict' }"))
        check("SyntaxError", early("function f({ a }) { 'use strict' }"))
        check("SyntaxError", early("function f(...a) { 'use strict' }"))
        check("SyntaxError", early("({ a }) => { 'use strict' }"))
        check("1", "(function (a, b) { 'use strict'; return 1 })()")
    }

    /** ZWNJ and ZWJ go on in identifiers, escaped or not, and format-control characters stay in the source (ECMAScript 5, 7.1). */
    @Test
    fun format_control_characters() {
        check("5", "var a\\u200d = 5; a\u200d")
        check("2", "var a\u200cb = 2; a\\u200cb")
        check("3", "class C { #a\\u200D = 3; m() { return this.#a\u200D } }; new C().m()")
        check("3", "'a\u200db'.length")
        check("3", "/a\u200db/.source.length")
        check("SyntaxError", early("var x = 1\u200e;"))
        check("SyntaxError", early("class C { #\\u0000; }"))
    }

    /** The operand of a yield is one AssignmentExpression, and a yield needs no parentheses in a list (ECMAScript 2015, 14.4). */
    @Test
    fun yield_operands() {
        check("1,2", "function* g() { yield 1, yield 2 } [...g()].join()")
        check("1", "function* g() { yield 1, 2 } [...g()].join()")
        check("1", "function* g() { return [yield 1] } [...g()].join()")
        check("ok", early("function* g() { f(yield 1) }"))
        check("1,2", "function* g() { var a = [yield, yield]; return a } var i = g(); i.next(); i.next(1); i.next(2).value.join()")
        check(",true", "function* g() { yield *\n h() } function* h() {} var r = g().next(); [r.value, r.done].join()")
        check("SyntaxError", early("function* g() { yield\n* 1 }"))
    }

    /** A let, a var and a function declared in a block clash across blocks (ECMAScript 2015, 13.2.1.1 and 13.15.1). */
    @Test
    fun declaration_clashes() {
        check("SyntaxError", early("{ var f; let f; }"))
        check("SyntaxError", early("{ { var f; } let f; }"))
        check("SyntaxError", early("let f; { var f; }"))
        check("SyntaxError", early("{ function f() {} var f; }"))
        check("SyntaxError", early("{ var f; function f() {} }"))
        check("SyntaxError", early("try {} catch (e) { let e; }"))
        check("SyntaxError", early("for (let x of []) { var x; }"))
        check("SyntaxError", early("function f(a) { let a; }"))
        // Annex B.3.3 lets two sloppy block functions, or a block function and a var of the
        // function body, share a name, and an `if` clause function sits in a block of its own.
        check("ok", early("{ function f() {} function f() {} }"))
        check("ok", early("{ function f() {} } var f;"))
        check("ok", early("try { throw null } catch (f) { if (true) function f() {} }"))
    }

    /** An anonymous function or class gets the name of what it initializes, in a pattern too (ECMAScript 2015, 12.14.5.2). */
    @Test
    fun named_evaluation_in_patterns() {
        check("f", "var [f = function () {}] = []; f.name")
        check("g", "var { g = () => 1 } = {}; g.name")
        check("C", "var { C = class {} } = {}; C.name")
        check("h", "var h; [h = function* () {}] = []; h.name")
        check("p", "function q(p = function () {}) { return p.name } q()")
        check("x", "var { y: x = function () {} } = {}; x.name")
        check("named", "var [k = function named() {}] = []; k.name")
    }

    /** A literal key names the property its string form spells, in a literal and in a pattern (ECMAScript 2020, 12.2.6.5). */
    @Test
    fun literal_keys() {
        check("true", "var o = { 9n: true }; o[9]")
        check("bar", "var o = { 1n() { return 'bar' } }; o['1']()")
        check("16", "Object.keys({ 0x10n: 1 }).join()")
        check("7", "var { 1.5: a } = { '1.5': 7, 1: 3 }; a")
        check("6", "var { 1n: a } = [5, 6]; a")
        check("5", "var { '0': a } = [5]; a")
    }

    /**
     * No name twice in a parameter list with a default, a pattern or a rest parameter, nor in a
     * method's or an arrow function's, in sloppy code too (ECMAScript 2015, 14.1.2, 14.2.1 and
     * 14.3.1); a plain list of a plain function may repeat one.
     */
    @Test
    fun duplicate_parameters() {
        check("SyntaxError", early("function f(x = 0, x) {}"))
        check("SyntaxError", early("function f(x, [x]) {}"))
        check("SyntaxError", early("function f(x, ...x) {}"))
        check("SyntaxError", early("function f({ a }, a) {}"))
        check("SyntaxError", early("({ m(a, a) {} })"))
        check("SyntaxError", early("({ *g(a, a) {} })"))
        check("SyntaxError", early("(a, [a]) => 1"))
        check("SyntaxError", early("({ a }, a) => 1"))
        check("ok", early("function f(a, a) {}"))
        check("ok", early("(function* (a, a) {})"))
    }

    /** A "use strict" body makes the function's name and parameters strict code too (ECMAScript 2015, 14.1.2). */
    @Test
    fun use_strict_reaches_back_to_the_parameters() {
        check("SyntaxError", early("function f(a, a) { 'use strict' }"))
        check("SyntaxError", early("function f(eval) { 'use strict' }"))
        check("SyntaxError", early("function eval() { 'use strict' }"))
        check("SyntaxError", early("(function arguments() { 'use strict' })"))
        check("SyntaxError", early("function static() { 'use strict' }"))
        check("SyntaxError", early("function f(implements) { 'use strict' }"))
        check("ok", early("({ eval() { 'use strict' } })"))
    }

    /**
     * Two functions declared in one block may share a name only when both are plain functions in
     * sloppy code (ECMAScript 2015, 13.2.1.1 and Annex B.3.3.4).
     */
    @Test
    fun block_function_redeclarations() {
        check("ok", early("{ function f() {} function f() {} }"))
        check("SyntaxError", early("{ function* f() {} function f() {} }"))
        check("SyntaxError", early("{ function f() {} function* f() {} }"))
        check("SyntaxError", early("{ async function f() {} function f() {} }"))
        check("SyntaxError", early("{ function f() {} async function f() {} }"))
        check("SyntaxError", early("'use strict'; { function f() {} function f() {} }"))
        check("SyntaxError", early("switch (0) { case 1: function* f() {} default: function f() {} }"))
        check("ok", early("{ function f() {} } { function* f() {} }"))
    }

    /** A let declaration ends the way any statement does (ECMAScript 2015, 11.9). */
    @Test
    fun let_declarations_end_like_statements() {
        check("SyntaxError", early("let x 0"))
        check("SyntaxError", early("let\nx 0"))
        check("ok", early("let x\n0"))
        check("ok", early("let x = 1, y"))
        check("SyntaxError", early("function f() { let\nawait 0 }"))
    }

    /**
     * A literal in parentheses is no pattern, while a name or property in parentheses inside an
     * assignment pattern is that name or property, and a property takes a default there too
     * (ECMAScript 2015, 12.14.1 and 12.14.5.1).
     */
    @Test
    fun assignment_targets() {
        check("SyntaxError", early("({}) = 1"))
        check("SyntaxError", early("([]) = 1"))
        check("SyntaxError", early("() => ({}) = 1"))
        check("SyntaxError", early("async () => ({}) = 1"))
        check("1", "var a; [(a)] = [1]; a")
        check("2", "var o = {}; ({ x: (o.y) } = { x: 2 }); o.y")
        check("5", "var o = {}; [o.x = 5] = []; o.x")
        check("6", "var o = {}; ({ a: o['k'] = 6 } = {}); o.k")
        check("", "var a; [(a) = function () {}] = []; a.name")
        check("SyntaxError", early("var a; [([a])] = [[1]]"))
        check("SyntaxError", early("var [(a)] = [1]"))
        check("SyntaxError", early("function f([(a)]) {}"))
    }

    /**
     * A generator's return value is computed where the return is, before its finally blocks run
     * and its block scopes close, even when a finally block yields (ECMAScript 2015, 13.10.1).
     */
    @Test
    fun generator_return_through_finally() {
        check(
            "expr fin true",
            "var out = []; function f() { out.push('expr'); return true } " +
                "function* g() { try { return f() } finally { out.push('fin') } } out.push(g().next().value); out.join(' ')",
        )
        check("3", "function* h() { { let x = 3; try { return x } finally {} } } h().next().value")
        check(
            "1 fin0 2 fin1 2,true",
            "var out = []; function* k() { for (let x of [1, 2]) { try { if (x == 1) continue; return x } finally { out.push('fin' + (yield x)) } } } " +
                "var it = k(); out.push(it.next().value); out.push(it.next(0).value); var r = it.next(1); out.push(r.value + ',' + r.done); out.join(' ')",
        )
    }

    /**
     * A return that a finally block's own `break` or caught throw abandons leaves the return
     * already pending in place, in a plain function as in a generator (ECMAScript 2015, 13.15.8).
     */
    @Test
    fun abandoned_return_in_finally() {
        check("42", "function f() { try { return 42 } finally { do try { return 43 } finally { break } while (0) } } f()")
        check("42", "function f() { try { return 41 } finally { try { return 42 } finally { L: try { return 43 } finally { break L } } } } f()")
        check("42", "function f() { try { return 42 } finally { try { try { return 43 } finally { throw 9 } } catch (e) {} } } f()")
        check("42", "function* g() { try { return 42 } finally { do try { return 43 } finally { continue } while (0) } } g().next().value")
        check("2", "function f() { try { return 1 } finally { return 2 } } f()")
        check("7", "eval('6; try { 7; } finally { 8; }')")
    }

    /** JavaScript 1.8's expression closures stay plain functions' own. */
    @Test
    fun expression_closures() {
        check("5", "function f() 5; f()")
        check("SyntaxError", early("function* g() 0"))
        check("SyntaxError", early("async function f() 0"))
        check("SyntaxError", early("void async function () 0"))
    }

    /** Built-in odds and ends class tests rely on. */
    @Test
    fun built_ins() {
        check("true", "ArrayBuffer[Symbol.species] === ArrayBuffer")
        check("true", "class B extends ArrayBuffer {}; new B(8).slice(0, 4) instanceof B")
        check("false", "function* g() {} g.prototype.hasOwnProperty('constructor')")
        check("5", "function f(a) {} Object.defineProperty(f, 'length', { value: 5 }); f.length")
        check("TypeError", "'use strict'; function f(a) {} try { f.length = 3; 'no' } catch (e) { e.name }")
    }
}
