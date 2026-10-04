/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Class syntax: ECMAScript 2015 classes, `super` and new.target, and the ECMAScript 2022 public
 * and private fields, private methods and accessors, `#x in o` and static blocks (#2, D-95).
 * Every expected value here is what V8 answers, error messages included, and each source runs in
 * a fresh engine, since a class declaration may not be repeated in one scope.
 */
class ClassSyntaxTest {

    private fun check(expected: String, source: String) {
        assertEquals(expected, KiteJs().use { js -> js.evaluate(source).asString() }, source)
    }

    /** The three checks the issue asks for. */
    @Test
    fun the_examples_of_the_issue() {
        check("6", "class A { m() { return 6; } } new A().m()")
        check("7", "class A { m() { return 6; } }; class B extends A { m() { return super.m() + 1; } } new B().m()")
        check("TypeError", "class A {}; try { A(); 'no' } catch (e) { e.name }")
    }

    /** The constructor owns a read-only `prototype`, methods are non-enumerable, and a class is never callable. */
    @Test
    fun constructors_and_prototypes() {
        check("function", "class A {}; typeof A")
        check("false,false,false", "class A {}; var d = Object.getOwnPropertyDescriptor(A, 'prototype'); [d.writable, d.enumerable, d.configurable].join()")
        check("true,false,true", "class A { m() {} }; var d = Object.getOwnPropertyDescriptor(A.prototype, 'm'); [d.writable, d.enumerable, d.configurable].join()")
        check("0", "class A { m() {} }; Object.keys(A.prototype).length")
        check("true,true,false,true", "class A {}; var d = Object.getOwnPropertyDescriptor(A.prototype, 'constructor'); [d.value === A, d.writable, d.enumerable, d.configurable].join()")
        check("TypeError", "class A { m() {} }; try { new A.prototype.m(); 'no' } catch (e) { e.name }")
        check("false", "class A { m() {} }; A.prototype.m.hasOwnProperty('prototype')")
        check("2", "class A { constructor(a, b) {} }; A.length")
        check("true", "class A { constructor() { this.a = 1 } }; String(new A().constructor === A)")
        check("1", "class A { 'constructor'() { this.q = 1; } }; new A().q")
        check("false", "class A { ['constructor']() { return 'm'; } }; new A().constructor === A")
        check("TypeError", "class A {}; class B extends A {}; try { B.call({}); 'no' } catch (e) { e.name }")
        check("x", "class A { x = 1 }; Object.getOwnPropertyNames(new A()).join()")
        check("length,name,prototype,m,x", "class A { static x = 1; static m() {} }; Object.getOwnPropertyNames(A).join()")
        check("constructor,m,g", "class A { m(){} get g(){return 1} static s(){} }; Object.getOwnPropertyNames(A.prototype).join()")
    }

    /** extends sets both prototype chains, super() binds `this` once, and a derived constructor's result is checked (ECMAScript 2015, 9.2.2). */
    @Test
    fun extends_and_super() {
        check("true,true", "class A {}; class B extends A {}; [Object.getPrototypeOf(B) === A, Object.getPrototypeOf(B.prototype) === A.prototype].join()")
        check("5,6,true,true", "class A { constructor(x) { this.x = x; } }; class B extends A { constructor() { super(5); this.y = this.x + 1; } }; var b = new B(); [b.x, b.y, b instanceof A, b instanceof B].join()")
        check("9", "class A { constructor(x) { this.x = x; } }; class B extends A {}; new B(9).x")
        check("ReferenceError", "class B extends Object { constructor() { try { this; } catch (e) { return {r: e.name}; } } }; new B().r")
        check("ReferenceError", "class A {}; class B extends A { constructor() { super(); super(); } }; try { new B(); 'no' } catch (e) { e.name }")
        check("ReferenceError", "class A {}; class B extends A { constructor() { } }; try { new B(); 'no' } catch (e) { e.name }")
        check("TypeError", "class A {}; class B extends A { constructor() { return 1; } }; try { new B(); 'no' } catch (e) { e.name }")
        check("1", "class A {}; class B extends A { constructor() { return {z: 1}; } }; new B().z")
        check("true", "class A {}; class B extends A { constructor() { super(); return undefined; } }; new B() instanceof B")
        check("true", "class A { constructor() { return 1; } }; new A() instanceof A")
        check("TypeError", "class B extends null {}; try { new B(); 'no' } catch (e) { e.name }")
        check("null", "class B extends null {}; Object.getPrototypeOf(B.prototype)")
        check("TypeError", "try { class B extends 5 {}; 'no' } catch (e) { e.name }")
        check("TypeError", "function F() {}; F.prototype = 3; try { class B extends F {}; 'no' } catch (e) { e.name }")
        check("ST", "class A { static s() { return 'S'; } }; class B extends A { static t() { return super.s() + 'T'; } }; B.t()")
        check("true", "class A { static create() { return new this(); } }; class B extends A {}; B.create() instanceof B")
        check("po", "var o = { __proto__: { m() { return 'p'; } }, m() { return super.m() + 'o'; } }; o.m()")
        check("true", "class A {}; Reflect.construct(A, [], Object).constructor === Object")
        check("true", "class A { constructor() { this.nt = new.target; } }; function F() {}; Reflect.construct(A, [], F).nt === F")
        check("true", "class A {}; class B extends A { constructor() { super(); } }; Reflect.construct(B, [], Array) instanceof Array")
        check("3", "class A {}; class B extends A { constructor(...args) { super(...args); this.n = args.length; } }; new B(1, 2, 3).n")
        check("1,2", "class A { constructor(...a) { this.a = a.join(); } }; class B extends A {}; new B(1, 2).a")
        check("4,5,6", "class A { constructor(...a) { this.a = a.join(); } }; class B extends A { constructor() { super(...[4, 5], 6); } }; new B().a")
    }

    /** Accessors, computed and literal keys, generators, and the names SetFunctionName gives. */
    @Test
    fun methods_accessors_and_names() {
        check("1,5", "class A { get x() { return 1; } set x(v) { this._x = v; } }; var a = new A(); a.x = 5; [a.x, a._x].join()")
        check("2", "class A { static get x() { return 2; } }; A.x")
        check("3", "var k = 'foo'; class A { [k + 'bar']() { return 3; } }; new A().foobar()")
        check("1,2", "class A { [Symbol.iterator]() { return [1,2][Symbol.iterator](); } }; [...new A()].join()")
        check("1,2", "class A { *gen() { yield 1; yield 2; } }; [...new A().gen()].join()")
        check("m", "class A { m() {} }; A.prototype.m.name")
        check("get x", "class A { get x() {} }; Object.getOwnPropertyDescriptor(A.prototype, 'x').get.name")
        check("[Symbol.iterator]", "class A { [Symbol.iterator]() {} }; A.prototype[Symbol.iterator].name")
        check("[d]", "class A { static [Symbol('d')]() {} }; Object.getOwnPropertySymbols(A).map(s => A[s].name).join()")
        check("A", "class A {}; A.name")
        check("C", "var C = class {}; C.name")
        check("D", "var C = class D {}; C.name")
        check("true", "var C = class D { m() { return D; } }; new C().m() === C")
        check("1,2,3,4", "class A { static async = 1; static get = 2; static set = 3; static static = 4; }; [A.async, A.get, A.set, A.static].join()")
        check("true,true,true", "class A { get; set; static; }; var a = new A(); ['get' in a, 'set' in a, 'static' in a].join()")
        check("3", "class A { m() { return 1 } ; ; n() { return 2 } }; new A().m() + new A().n()")
        check("function", "class A { static name() { return 'n'; } }; typeof A.name")
        check("1", "(class { static m() { return 1; } }).m()")
        check("one", "class A { 1() { return 'one'; } }; new A()[1]()")
        check("ab", "class A { 'a b'() { return 'ab'; } }; new A()['a b']()")
    }

    /** Function.prototype.toString gives a method its own source, and a class the whole class. */
    @Test
    fun source_text() {
        check("m() {}", "class A { m() {} }; A.prototype.m.toString()")
        check("m() {}", "class A { static m() {} }; A.m.toString()")
        check("get x() { return 1; }", "class A { get x() { return 1; } }; Object.getOwnPropertyDescriptor(A.prototype, 'x').get.toString()")
        check("class A {}", "class A {}; A.toString()")
        // An object literal's methods keep their whole definition too, and a strict function
        // has no own `arguments`; upstream gets both wrong for plain functions as well.
        check("m() { return 1; }", "({ m() { return 1; } }).m.toString()")
        check("get x() { return 1; }", "Object.getOwnPropertyDescriptor({ get x() { return 1; } }, 'x').get.toString()")
        check("false", "(function () { 'use strict'; return function () {} })().hasOwnProperty('arguments')")
    }

    /** Public fields are defined, not assigned, in order, with `this` the new object or the class (ECMAScript 2022). */
    @Test
    fun fields_and_static_blocks() {
        check("1,2", "class A { x = 1; y = this.x + 1; }; var a = new A(); [a.x, a.y].join()")
        check("true,", "class A { x; }; var a = new A(); ['x' in a, a.x].join()")
        check("5,10", "class A { static x = 5; static y = A.x * 2; }; [A.x, A.y].join()")
        check("7", "class A { static { this.z = 7; } }; A.z")
        check("a,b,c", "var log = []; class A { static a = log.push('a'); static { log.push('b'); } static c = log.push('c'); }; log.join()")
        check("1,2", "class A { x = 1; }; class B extends A { y = this.x + 1; }; var b = new B(); [b.x, b.y].join()")
        check("1,2,3", "class A { x = 1; }; class B extends A { y = 2; constructor() { super(); this.z = this.y + 1; } }; var b = new B(); [b.x, b.y, b.z].join()")
        check("1,2,3", "class A { 'quoted' = 1; 42 = 2; ['comp' + 'uted'] = 3; }; var a = new A(); [a.quoted, a[42], a.computed].join()")
        check("f,g,h", "class A { f = function() {}; g = () => {}; static h = class {}; }; var a = new A(); [a.f.name, a.g.name, A.h.name].join()")
        check("1,2,3,4", "var r = []; class A { [(r.push(1), 'a')]() {} static [(r.push(2), 'b')] = r.push(4); [(r.push(3), 'c')] = 0; }; r.join()")
        check("true", "class A { static x = this; }; A.x === A")
        check("true", "class A { static x = () => this; }; A.x() === A")
        check("true", "class A { static { var t = this; this.f = () => t; } }; A.f() === A")
        check("true", "let C = class { static m() { return C; } }; C.m() === C")
    }

    /** new.target, and arrow functions and direct eval sharing the constructor's `this` binding. */
    @Test
    fun new_target_arrows_and_eval() {
        check("true", "var A = class { constructor() { this.t = new.target === A; } }; new A().t")
        check("true,true", "function f() { return new.target; }; [f() === undefined, new f() === f].join()")
        check("true", "class A { constructor() { this.nt = new.target; } }; class B extends A {}; new B().nt === B")
        check("true", "class A { x = () => this; }; var a = new A(); a.x() === a")
        check("1", "class A {}; class B extends A { constructor() { var f = () => super(); f(); this.ok = 1; } }; new B().ok")
        check("ReferenceError,true", "class A {}; class B extends A { constructor() { var f = () => this; try { f(); } catch (e) { var r = e.name; } super(); this.r = r; this.same = f() === this; } }; var b = new B(); [b.r, b.same].join()")
        check("AB", "class A { m() { return 'A'; } }; class B extends A { m() { var f = () => super.m(); return f() + 'B'; } }; new B().m()")
        check("1", "class A { constructor() { this.v = 1; } }; class B extends A { constructor() { eval('super()'); } }; new B().v")
        check("true", "class A {}; class B extends A { constructor() { super(); this.t = eval('this') === this; } }; new B().t")
        check("true", "class A { static m() { return this; } }; A.m() === A")
    }

    /** Built-in constructors make the object, with the subclass's prototype. */
    @Test
    fun subclassing_built_ins() {
        check("2,true,true", "class A extends Array {}; var a = new A(); a.push(1, 2); [a.length, a instanceof A, Array.isArray(a)].join()")
        check("boom,true,true,E: boom", "class E extends Error { constructor(m) { super(m); this.name = 'E'; } }; var e = new E('boom'); [e.message, e instanceof Error, e instanceof E, String(e)].join()")
        check("2,true", "class M extends Map {}; var m = new M([[1, 2]]); [m.get(1), m instanceof M].join()")
        check("true", "class P extends Promise {}; P.resolve(1) instanceof P")
        check("function", "class A { constructor() { this.x = 1; } }; var b = Object.create(A.prototype); typeof A.prototype.constructor")
        check("1", "\"use strict\"; class A { m() { return 1 } }; new A().m()")
    }

    /** Early errors of ECMAScript 2022, 15.7.1. */
    @Test
    fun early_errors() {
        check("SyntaxError", "try { eval('class A { constructor() {} constructor() {} }'); 'no' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('class A { get constructor() {} }'); 'no' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('class A { static prototype() {} }'); 'no' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('class A { constructor = 1 }'); 'no' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('class A { m() { super(); } }'); 'no' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('function f() { super.x; }'); 'no' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('new.target'); 'no' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('class A { x = arguments; }'); 'no' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('class A { static { return; } }'); 'no' } catch (e) { e.name }")
    }

    /** Private fields: read, written, updated, destructured into, and brand-checked (ECMAScript 2022, 7.3.26 to 7.3.32). */
    @Test
    fun private_fields() {
        check("1", "class A { #x = 1; get() { return this.#x; } }; new A().get()")
        check("3", "class A { #x = 1; inc() { return ++this.#x; } }; var a = new A(); a.inc(); a.inc()")
        check("6", "class A { #x = 1; add() { this.#x += 5; return this.#x; } }; new A().add()")
        check("9", "class A { #x; set(v) { this.#x = v; return this.#x; } }; new A().set(9)")
        check("TypeError: Cannot read private member #x from an object whose class did not declare it", "class A { #x = 1; static read(o) { return o.#x; } }; try { A.read({}); 'no' } catch (e) { e.name + ': ' + e.message }")
        check("TypeError: Cannot write private member #x to an object whose class did not declare it", "class A { #x = 1; static write(o) { o.#x = 2; } }; try { A.write({}); 'no' } catch (e) { e.name + ': ' + e.message }")
        check("1", "class A { #x = 1; m() { return (() => this.#x)(); } }; new A().m()")
        check("1", "class A { #x = 1; m() { return eval('this.#x'); } }; new A().m()")
        check(",1", "class A { #x = 1; m(o) { return o?.#x; } }; [new A().m(null), new A().m(new A())].join()")
        check("TypeError", "function make() { return class { #x = 1; static get(o) { return o.#x; } }; }; var C1 = make(), C2 = make(); try { C1.get(new C2()); 'no' } catch (e) { e.name }")
        check("stamped", "class Base { constructor(o) { return o; } }; class Stamp extends Base { #x = 'stamped'; static get(o) { return o.#x; } }; var o = {}; new Stamp(o); Stamp.get(o)")
        check("TypeError", "class Base { constructor(o) { return o; } }; class Stamp extends Base { #x = 1; }; var o = {}; new Stamp(o); try { new Stamp(o); 'no' } catch (e) { e.name }")
        check("8", "class A { #x = 1; m() { [this.#x] = [8]; return this.#x; } }; new A().m()")
        check("3", "class A { #x = 0; m() { for (this.#x of [1, 2, 3]); return this.#x; } }; new A().m()")
        check("7", "class A { #x = 1; m() { this.#x ??= 5; this.#x ||= 6; this.#x &&= 7; return this.#x; } }; new A().m()")
        check("3", "class A { #x = 1; m() { return this.#x++ + this.#x--; } }; new A().m()")
        check("SyntaxError", "try { eval('class A { #x; m() { 1 + #x in {}; } }'); 'no' } catch (e) { e.name }")
        check("true", "class A { #x = 1; m() { return #x in this && #x in this; } }; new A().m()")
        check("3", "class A { #if = 1; #class = 2; m() { return this.#if + this.#class; } }; new A().m()")
        check("late", "class A { m() { return this.#y; } #y = 'late'; }; new A().m()")
        check("1", "class A { #a = 1; m() { return this.#a; } }; new A().m()")
        check("0", "class A { #x = 1; m() { return Object.keys(this).length + Object.getOwnPropertyNames(this).length; } }; new A().m()")
        check("{}", "class A { #x = 1; m() { return JSON.stringify(this); } }; new A().m()")
        check("TypeError", "class A { #x = 42; static m() { var p = new Proxy(new A(), {}); try { return p.#x; } catch (e) { return e.name; } } }; A.m()")
        check("1", "class A { #x = 1; m() { return Object.freeze(this) && this.#x; } }; new A().m()")
        check("true", "class A { #m() { return this; } static t() { var a = new A(); return a.#m() === a; } }; A.t()")
        check("2", "class A { static #x = 1; static #y = A.#x + 1; static g() { return A.#y; } }; A.g()")
        check("B", "class A { #x = this.constructor.name; g() { return this.#x; } }; class B extends A {}; new B().g()")
    }

    /** Private methods and accessors are shared by every instance, and cannot be written, or read without a getter. */
    @Test
    fun private_methods_and_accessors() {
        check("m", "class A { #m() { return 'm'; } call() { return this.#m(); } }; new A().call()")
        check("TypeError: Private method '#m' is not writable", "class A { #m() {} write() { this.#m = 1; } }; try { new A().write(); 'no' } catch (e) { e.name + ': ' + e.message }")
        check("#m", "class A { #m() {} name() { return this.#m.name; } }; new A().name()")
        check("g", "class A { get #g() { return 'g'; } read() { return this.#g; } }; new A().read()")
        check("TypeError: '#g' was defined without a setter", "class A { get #g() { return 'g'; } write() { this.#g = 1; } }; try { new A().write(); 'no' } catch (e) { e.name + ': ' + e.message }")
        check("3", "class A { set #s(v) { this.v = v; } write() { this.#s = 3; return this.v; } }; new A().write()")
        check("TypeError: '#s' was defined without a getter", "class A { set #s(v) {} read() { return this.#s; } }; try { new A().read(); 'no' } catch (e) { e.name + ': ' + e.message }")
        check("8", "class A { get #a() { return this._a; } set #a(v) { this._a = v * 2; } t() { this.#a = 4; return this.#a; } }; new A().t()")
        check("S", "class A { static #s = 'S'; static read() { return A.#s; } }; A.read()")
        check("sm", "class A { static #sm() { return 'sm'; } static call() { return this.#sm(); } }; A.call()")
        check("TypeError", "class A { static #sm() {} static call() { return this.#sm(); } }; class B extends A {}; try { B.call(); 'no' } catch (e) { e.name }")
        check(",1", "class A { #m() { return 1; } c(o) { return o?.#m(); } }; [new A().c(undefined), new A().c(new A())].join()")
        check("#f,#g", "class A { #f = function() {}; #g = () => {}; n() { return [this.#f.name, this.#g.name].join(); } }; new A().n()")
        check("#f", "class A { static #f = class {}; static n() { return A.#f.name; } }; A.n()")
        check("TypeError", "class A { #x = 1; static m() { var o = Object.preventExtensions({}); try { o.#x; } catch (e) { return e.name } } }; A.m()")
        check("2", "class A { #a = 1; #b = this.#a + 1; g() { return this.#b; } }; new A().g()")
        check("field,function", "var s = []; class A { #m() {} constructor() { s.push(typeof this.#m); } #x = s.push('field'); }; new A(); s.join()")
        check("constructor,m", "class A { get #x() { return 1; } m() { return Object.getOwnPropertyNames(A.prototype).join(); } }; new A().m()")
        check("TypeError", "class A { #x = 1; m() { try { this.#x(); } catch (e) { return e.name; } } }; new A().m()")
    }

    /** `#x in o` is a brand check that throws for a primitive. */
    @Test
    fun private_in() {
        check("true,false", "class A { #x = 1; static has(o) { return #x in o; } }; [A.has(new A()), A.has({})].join()")
        check("true,false", "class A { #m() {} static has(o) { return #m in o; } }; [A.has(new A()), A.has({})].join()")
        check("TypeError", "class A { #x; static has(o) { return #x in o; } }; try { A.has(1); 'no' } catch (e) { e.name }")
    }

    /** Each class evaluation makes fresh private names, nested classes shadow outer ones, and the early errors. */
    @Test
    fun private_name_scopes() {
        check("outerinner", "class Outer { #x = 'outer'; m() { class Inner { #y = 'inner'; f(o, i) { return o.#x + i.#y; } } return new Inner().f(this, new Inner()); } }; new Outer().m()")
        check("i", "class Outer { #x = 'o'; m() { class Inner { #x = 'i'; f() { return this.#x; } } return new Inner().f(); } }; new Outer().m()")
        check("SyntaxError", "try { eval('class A { m() { this.#y; } }'); 'no' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('this.#x'); 'no' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('class A { #x; #x; }'); 'no' } catch (e) { e.name }")
        check("ok", "try { eval('class A { get #x() {} set #x(v) {} }'); 'ok' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('class A { get #x() {} static set #x(v) {} }'); 'no' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('class A { #constructor() {} }'); 'no' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('class A { #x; m() { delete this.#x; } }'); 'no' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('class A extends Object { #x; m() { super.#x; } }'); 'no' } catch (e) { e.name }")
        check("SyntaxError", "try { eval('class A { #x; m() { #x; } }'); 'no' } catch (e) { e.name }")
    }
}
