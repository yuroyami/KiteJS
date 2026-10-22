/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The own keys of an object are the keys of its properties (ECMAScript 2015, 9.1.12): a property
 * named by a symbol is listed as that symbol, so each listed key has a descriptor and each
 * property is listed, the symbol methods of RegExp.prototype and Date.prototype too, and a
 * definition of one of those changes it where it is (#76).
 */
class OwnSymbolKeysTest {

    private fun eval(source: String): String = KiteJs(Rhino).use { js -> js.evaluate(source).asString() }

    @Test
    fun every_built_in_lists_its_own_keys_as_the_keys_of_its_properties() {
        // Each object a global of ECMAScript reaches in two steps, by a property and then its prototype.
        assertEquals(
            "",
            eval(
                """
                var seen = [], out = [];
                function visit(name, o) {
                    if (o === null || (typeof o !== 'object' && typeof o !== 'function') || seen.indexOf(o) >= 0) return;
                    seen.push(o);
                    var keys = Reflect.ownKeys(o);
                    keys.forEach(function (k) {
                        if (Object.getOwnPropertyDescriptor(o, k) === undefined) out.push(name + ' lists ' + String(k));
                    });
                    Object.getOwnPropertySymbols(o).forEach(function (k) {
                        if (keys.indexOf(k) < 0) out.push(name + ' hides ' + String(k));
                    });
                    [Symbol.iterator, Symbol.asyncIterator, Symbol.match, Symbol.matchAll, Symbol.replace, Symbol.search,
                     Symbol.split, Symbol.toPrimitive, Symbol.toStringTag, Symbol.species, Symbol.hasInstance,
                     Symbol.isConcatSpreadable, Symbol.unscopables].forEach(function (k) {
                        if (k !== undefined && Object.prototype.hasOwnProperty.call(o, k) && keys.indexOf(k) < 0) out.push(name + ' hides ' + String(k));
                    });
                }
                ['Object', 'Function', 'Array', 'String', 'Number', 'Boolean', 'Symbol', 'BigInt', 'Math', 'JSON', 'Reflect', 'Date',
                 'RegExp', 'Error', 'TypeError', 'RangeError', 'SyntaxError', 'ReferenceError', 'EvalError', 'URIError', 'Map', 'Set',
                 'WeakMap', 'WeakSet', 'Promise', 'Proxy', 'ArrayBuffer', 'DataView', 'Int8Array', 'Uint8Array', 'Uint8ClampedArray',
                 'Int16Array', 'Uint16Array', 'Int32Array', 'Uint32Array', 'Float32Array', 'Float64Array', 'BigInt64Array',
                 'BigUint64Array', 'globalThis'].forEach(function (name) {
                    var v = this[name];
                    visit(name, v);
                    if (v !== null && (typeof v === 'object' || typeof v === 'function')) {
                        visit(name + '.prototype', v.prototype);
                        visit(name + ' proto', Object.getPrototypeOf(v));
                    }
                }, this);
                var gp = Object.getPrototypeOf;
                visit('%ArrayIteratorPrototype%', gp([][Symbol.iterator]()));
                visit('%MapIteratorPrototype%', gp(new Map().entries()));
                visit('%SetIteratorPrototype%', gp(new Set().values()));
                visit('%StringIteratorPrototype%', gp(''[Symbol.iterator]()));
                visit('%GeneratorPrototype%', gp(gp((function* () {})())));
                visit('%TypedArray%.prototype', gp(Uint8Array.prototype));
                out.join(', ');
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun the_symbol_methods_of_regexp_and_date_are_listed_as_symbols() {
        assertEquals(
            "Symbol(Symbol.match),Symbol(Symbol.matchAll),Symbol(Symbol.replace),Symbol(Symbol.search),Symbol(Symbol.split)|" +
                "Symbol(Symbol.toPrimitive)|symbol|true",
            eval(
                "function names(o) { return Object.getOwnPropertySymbols(o).map(String).sort().join(); }" +
                    " [names(RegExp.prototype), names(Date.prototype)," +
                    " typeof Reflect.ownKeys(RegExp.prototype).filter(function (k) { return String(k) === 'Symbol(Symbol.replace)'; })[0]," +
                    " Reflect.ownKeys(Date.prototype).indexOf(Symbol.toPrimitive) >= 0].join('|')",
            ),
        )
    }

    @Test
    fun a_copy_by_descriptors_and_a_freeze_reach_the_symbol_methods() {
        assertEquals(
            "function|false|false|false",
            eval(
                "var d = Object.getOwnPropertyDescriptors(RegExp.prototype);" +
                    " Object.freeze(Date.prototype);" +
                    " [typeof d[Symbol.split].value," +
                    " Object.getOwnPropertyDescriptor(Date.prototype, Symbol.toPrimitive).configurable," +
                    " Date.prototype.hasOwnProperty('Symbol(Symbol.toPrimitive)')," +
                    " Object.prototype.hasOwnProperty.call(d, 'Symbol(Symbol.split)')].join('|')",
            ),
        )
    }

    @Test
    fun a_symbol_method_is_redefined_in_place() {
        // A definition changes the property the prototype has, extensible or not, and adds no second one.
        assertEquals(
            "false|false|1|function|true",
            eval(
                "Object.defineProperty(RegExp.prototype, Symbol.split, { enumerable: false, writable: false });" +
                    " var d = Object.getOwnPropertyDescriptor(RegExp.prototype, Symbol.split);" +
                    " Object.preventExtensions(Date.prototype);" +
                    " Object.defineProperty(Date.prototype, Symbol.toPrimitive, { configurable: false });" +
                    " [d.writable, Object.getOwnPropertyDescriptor(Date.prototype, Symbol.toPrimitive).configurable," +
                    " Reflect.ownKeys(RegExp.prototype).filter(function (k) { return k === Symbol.split; }).length," +
                    " typeof Date.prototype[Symbol.toPrimitive], Object.isFrozen(Object.freeze(RegExp.prototype))].join('|')",
            ),
        )
    }
}
