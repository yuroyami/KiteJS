/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.facade

/** The file name the prelude runs under, which the stack frames a host sees leave out. */
internal const val PRELUDE_FILE = "kitejs:prelude"

/*
 * What the facade does in JavaScript rather than in C: property access with the semantics a
 * script's own `o[k]` has, the promise watching `onSettled` needs, the console, and sealing the
 * built-ins. It runs once, before any script, and keeps the built-ins it relies on, so a script
 * that later replaces `Object.defineProperty` or `Promise.prototype.then` changes nothing here.
 *
 * Its own frames are left out of the stacks the host sees.
 *
 * The console formats exactly as Rhino's does (NativeConsole.format there), so a host that
 * switches engines sees the same lines.
 */
internal const val PRELUDE: String = """
(function (print, monotonic, sealBuiltins) {
    var apply = Reflect.apply;
    var slice = Function.prototype.call.bind(Array.prototype.slice);
    var push = Function.prototype.call.bind(Array.prototype.push);
    var defineProperty = Object.defineProperty;
    var getOwnPropertyNames = Object.getOwnPropertyNames;
    var getOwnPropertyDescriptor = Object.getOwnPropertyDescriptor;
    var getPrototypeOf = Object.getPrototypeOf;
    var freeze = Object.freeze;
    var keys = Object.keys;
    var bindFn = Function.prototype.bind;
    var then = Promise.prototype.then;
    var Promise_ = Promise;
    var resolved = Promise.resolve(undefined);
    var rejectWith = function (reason) { return Promise_.reject(reason); };
    var BigInt_ = BigInt;
    var String_ = String;
    var Number_ = Number;
    var TypeError_ = TypeError;
    var Error_ = Error;
    var stringify = JSON.stringify;
    var isFinite_ = isFinite;
    var trunc = Math.trunc;
    var regexpExec = Function.prototype.call.bind(RegExp.prototype.exec);

    var helpers = {
        get: function (o, k) { return o[k]; },
        set: function (o, k, v) { o[k] = v; },
        has: function (o, k) { return k in o; },
        del: function (o, k) { delete o[k]; },
        keys: function (o) { return keys(o); },
        callMethod: function (o, k) {
            var f = o[k];
            if (typeof f !== 'function') throw new TypeError_(String_(k) + ' is not a function');
            return apply(f, o, slice(arguments, 2));
        },
        bind: function (f) { return apply(bindFn, f, slice(arguments, 1)); },
        toStringPrimitive: function (o) { return `${'$'}{o}`; },
        toNumberPrimitive: function (o) { return +o; },
        defineValue: function (o, k, v, w, e, c) {
            defineProperty(o, k, { value: v, writable: w, enumerable: e, configurable: c });
        },
        defineAccessor: function (o, k, g, s, e, c) {
            defineProperty(o, k, { get: g, set: s, enumerable: e, configurable: c });
        },
        thenable: function (o) { return typeof o.then === 'function'; },
        watch: function (v, ok, fail, isPromise) {
            if (isPromise) {
                apply(then, v, [ok, fail]);
                return true;
            }
            if (v === null || (typeof v !== 'object' && typeof v !== 'function')) return false;
            var t;
            try {
                t = v.then;
            } catch (e) {
                apply(then, rejectWith(e), [ok, fail]);
                return true;
            }
            if (typeof t !== 'function') return false;
            // As the Promise resolve functions do with a thenable: `then` is read once, here, and
            // called from a job, and what it resolves with is followed to the end.
            var p = new Promise_(function (res, rej) {
                apply(then, resolved, [function () {
                    try { apply(t, v, [res, rej]); } catch (e) { rej(e); }
                }]);
            });
            apply(then, p, [ok, fail]);
            return true;
        },
        bigint: function (s) { return BigInt_(s); },
        description: function (s) { return s.description; },
        stack: function (e) {
            try {
                var s = (e !== null && typeof e === 'object') ? e.stack : undefined;
                return typeof s === 'string' ? s : undefined;
            } catch (x) {
                return undefined;
            }
        },
        makeConstructor: function (name, arity, build) {
            var F = function () {
                if (new.target === undefined) throw new TypeError_('Constructor ' + name + " requires 'new'");
                apply(build, this, slice(arguments));
            };
            defineProperty(F, 'name', { value: name, configurable: true });
            defineProperty(F, 'length', { value: arity, configurable: true });
            return F;
        },
    };

    // ---- The console ------------------------------------------------------------------------

    var formatString = function (v) {
        if (typeof v === 'bigint') return String_(v) + 'n';
        if (typeof v === 'symbol') return v.toString();
        return String_(v);
    };
    var formatInt = function (v) {
        if (typeof v === 'bigint') return String_(v) + 'n';
        if (typeof v === 'symbol') return 'NaN';
        var n = Number_(v);
        if (!isFinite_(n)) return String_(n);
        // What a Kotlin Long holds, as Rhino's console gives it.
        var t = trunc(n);
        if (t >= 9223372036854775807) return '9223372036854775807';
        if (t <= -9223372036854775808) return '-9223372036854775808';
        return String_(t === 0 ? 0 : t);
    };
    var formatFloat = function (v) {
        if (typeof v === 'bigint' || typeof v === 'symbol') return 'NaN';
        return String_(Number_(v));
    };
    var replacer = function (k, value) {
        if (typeof value === 'function') return 'function ' + value.name + '() {...}';
        return value;
    };
    var formatObj = function (arg) {
        if (arg === null) return 'null';
        if (arg === undefined) return 'undefined';
        if (arg instanceof Error_) return String_(arg) + '\n' + arg.stack;
        if (typeof arg === 'bigint' || typeof arg === 'symbol') return formatString(arg);
        try {
            return String_(stringify(arg, replacer));
        } catch (e) {
            if (e instanceof TypeError_) return String_(arg);
            throw e;
        }
    };
    var format = function (args) {
        if (args.length === 0) return '';
        var out = '';
        var i = 0;
        if (typeof args[0] === 'string') {
            var msg = args[0];
            var spec = /%[sfdioOc%]/g;
            var last = 0;
            var m;
            i = 1;
            while ((m = regexpExec(spec, msg)) !== null) {
                out += msg.substring(last, m.index);
                last = m.index + 2;
                var p = m[0];
                if (p === '%%') {
                    out += '%';
                } else if (i >= args.length) {
                    out += p;
                    i++;
                } else {
                    var v = args[i++];
                    if (p === '%s') out += formatString(v);
                    else if (p === '%d' || p === '%i') out += formatInt(v);
                    else if (p === '%f') out += formatFloat(v);
                    else if (p === '%o' || p === '%O') out += formatObj(v);
                }
            }
            out += msg.substring(last);
        }
        for (; i < args.length; i++) {
            if (out.length > 0) out += ' ';
            var a = args[i];
            out += typeof a === 'string' ? formatString(a) : formatObj(a);
        }
        return out;
    };

    var TRACE = 0, DEBUG = 1, INFO = 2, WARN = 3, ERROR = 4;

    if (print !== undefined) {
        var counters = new Map();
        var timers = new Map();
        var mapGet = Function.prototype.call.bind(Map.prototype.get);
        var mapSet = Function.prototype.call.bind(Map.prototype.set);
        var mapHas = Function.prototype.call.bind(Map.prototype.has);
        var mapDelete = Function.prototype.call.bind(Map.prototype['delete']);
        var labelOf = function (args) { return args.length > 0 ? String_(args[0]) : 'default'; };
        var console = {};
        var method = function (name, length, body) {
            defineProperty(body, 'name', { value: name, configurable: true });
            defineProperty(body, 'length', { value: length, configurable: true });
            defineProperty(console, name, { value: body, writable: false, enumerable: false, configurable: true });
        };
        var level = function (name, lvl) {
            method(name, 1, function () { print(lvl, format(slice(arguments))); });
        };
        level('debug', DEBUG);
        level('log', INFO);
        level('info', INFO);
        level('warn', WARN);
        level('error', ERROR);
        method('trace', 1, function () { print(TRACE, format(slice(arguments))); });
        method('assert', 2, function () {
            var args = slice(arguments);
            if (args.length > 0 && args[0]) return;
            if (args.length < 2) {
                print(ERROR, format(['Assertion failed: console.assert']));
                return;
            }
            var first = args[1];
            var rest;
            if (typeof first === 'string') {
                rest = ['Assertion failed: ' + first];
                for (var i = 2; i < args.length; i++) push(rest, args[i]);
            } else {
                rest = ['Assertion failed:'];
                for (var j = 1; j < args.length; j++) push(rest, args[j]);
            }
            print(ERROR, format(rest));
        });
        method('count', 1, function () {
            var label = labelOf(arguments);
            var count = (mapGet(counters, label) || 0) + 1;
            mapSet(counters, label, count);
            print(INFO, label + ': ' + count);
        });
        method('countReset', 1, function () {
            var label = labelOf(arguments);
            if (!mapDelete(counters, label)) print(WARN, "Count for '" + label + "' does not exist.");
        });
        method('time', 1, function () {
            var label = labelOf(arguments);
            if (mapHas(timers, label)) {
                print(WARN, "Timer '" + label + "' already exists.");
                return;
            }
            mapSet(timers, label, monotonic());
        });
        method('timeEnd', 1, function () {
            var label = labelOf(arguments);
            if (!mapHas(timers, label)) {
                print(WARN, "Timer '" + label + "' does not exist.");
                return;
            }
            var start = mapGet(timers, label);
            mapDelete(timers, label);
            print(INFO, label + ': ' + (monotonic() - start) + 'ms');
        });
        method('timeLog', 2, function () {
            var label = labelOf(arguments);
            if (!mapHas(timers, label)) {
                print(WARN, "Timer '" + label + "' does not exist.");
                return;
            }
            var msg = label + ': ' + (monotonic() - mapGet(timers, label)) + 'ms';
            for (var i = 1; i < arguments.length; i++) msg += ' ' + String_(arguments[i]);
            print(INFO, msg);
        });
        if (sealBuiltins) freeze(console);
        defineProperty(globalThis, 'console', { value: console, writable: true, enumerable: false, configurable: true });
    }

    // ---- Sealing the built-ins --------------------------------------------------------------

    if (sealBuiltins) {
        // Freezing a prototype would make `obj.toString = f` fail silently on every object that
        // inherits it, the "override mistake". These few names are the ones scripts assign that
        // way, so on prototypes they become accessors: assigning through an heir defines the
        // heir's own property, and only assigning to the built-in itself is refused.
        var overridable = ['constructor', 'toString', 'valueOf', 'toLocaleString', 'toJSON', 'name', 'message',
            'hasOwnProperty', 'isPrototypeOf', 'propertyIsEnumerable'];
        var shield = function (holder, name) {
            var d = getOwnPropertyDescriptor(holder, name);
            if (!d || !('value' in d) || !d.configurable) return;
            var value = d.value;
            defineProperty(holder, name, {
                get: function () { return value; },
                set: function (v) {
                    if (this === holder) throw new TypeError_("Cannot assign to read only property '" + name + "'");
                    defineProperty(this, name, { value: v, writable: true, enumerable: true, configurable: true });
                },
                enumerable: d.enumerable,
                configurable: false,
            });
        };
        var seen = new Set();
        var seenHas = Function.prototype.call.bind(Set.prototype.has);
        var seenAdd = Function.prototype.call.bind(Set.prototype.add);
        var harden = function (o) {
            if (o === null || (typeof o !== 'object' && typeof o !== 'function') || o === globalThis) return;
            if (seenHas(seen, o)) return;
            seenAdd(seen, o);
            var names = getOwnPropertyNames(o);
            var isPrototype = (typeof o === 'object' || o === Function.prototype) && o !== Math && o !== JSON && o !== Reflect;
            for (var i = 0; i < names.length; i++) {
                var d = getOwnPropertyDescriptor(o, names[i]);
                if ('value' in d) {
                    harden(d.value);
                } else {
                    harden(d.get);
                    harden(d.set);
                }
            }
            if (isPrototype) {
                for (var j = 0; j < overridable.length; j++) shield(o, overridable[j]);
            }
            harden(getPrototypeOf(o));
            freeze(o);
        };
        var globals = getOwnPropertyNames(globalThis);
        for (var g = 0; g < globals.length; g++) {
            var gd = getOwnPropertyDescriptor(globalThis, globals[g]);
            if ('value' in gd) harden(gd.value);
        }
        // The prototypes no global names: the iterators, the generators, async functions.
        harden(getPrototypeOf([][Symbol.iterator]()));
        harden(getPrototypeOf(new Map()[Symbol.iterator]()));
        harden(getPrototypeOf(new Set()[Symbol.iterator]()));
        harden(getPrototypeOf(''[Symbol.iterator]()));
        harden(getPrototypeOf('x'.matchAll(/x/g)));
        harden(getPrototypeOf(function* () {}));
        harden(getPrototypeOf(async function () {}));
        harden(getPrototypeOf(async function* () {}));
    }

    // In the order the facade's Helper enum lists them.
    return [helpers.get, helpers.set, helpers.has, helpers.del, helpers.keys, helpers.callMethod, helpers.bind,
        helpers.toStringPrimitive, helpers.toNumberPrimitive, helpers.defineValue, helpers.defineAccessor,
        helpers.thenable, helpers.watch, helpers.bigint, helpers.description, helpers.stack, helpers.makeConstructor];
})
"""
