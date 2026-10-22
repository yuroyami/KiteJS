/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

class TypedArrayIndexTest {
    private fun check(expected: String, source: String) {
        KiteJs(Rhino).use { js ->
            assertEquals(expected, js.evaluate(source, "typed-array-index.js").asString())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun invalidPrimitiveLengthsAreRejectedBeforeNarrowing() = check("true", """
        var types = [Int8Array, Uint8Array, Uint8ClampedArray, Int16Array, Uint16Array,
          Int32Array, Uint32Array, Float32Array, Float64Array, BigInt64Array, BigUint64Array];
        var lengths = [Infinity, -Infinity, -1, -4294967296, -4294967295, 4294967296, 9007199254740992];
        String(types.every(function (C) { return lengths.every(function (length) {
          try { new C(length); return false; } catch (e) { return e instanceof RangeError; }
        }); }));
    """.trimIndent())

    @Test
    fun primitiveLengthConversionsPreserveSmallValidLengths() = check("0,0,0,0,1,3,3|TypeError,TypeError", """
        var lengths = [undefined, null, NaN, -0.5, true, '3', 3.9];
        var result = lengths.map(function (length) { return new Uint8Array(length).length; });
        var errors = [Symbol('length'), 3n].map(function (length) {
          try { new Uint8Array(length); return 'missing'; } catch (e) { return e.name; }
        });
        result.join() + '|' + errors.join();
    """.trimIndent())

    @Test
    fun subarrayClampsInfiniteAndLargeEndpoints() = check("0:2:0:2:0:2:2:0", """
        var a = new Uint8Array([1, 2]);
        [a.subarray(Infinity).length, a.subarray(0, Infinity).length,
         a.subarray(4294967296).length, a.subarray(-Infinity).length,
         a.subarray(0, -Infinity).length, a.subarray(-4294967296).length,
         a.subarray(0, 4294967296).length, a.subarray(2147483648).length].join(':');
    """.trimIndent())

    @Test
    fun subarrayStillConvertsEndpointsOnceAndSharesItsBytes() = check("2|begin,end|9|0|3", """
        var events = [], a = new Uint16Array([1, 2, 3]);
        var begin = { valueOf: function () { events.push('begin'); return -2.9; } };
        var end = { valueOf: function () { events.push('end'); return Infinity; } };
        var b = a.subarray(begin, end); b[0] = 9;
        [b.length, events.join(), a[1], a.subarray(0, null).length, a.subarray(0, undefined).length].join('|');
    """.trimIndent())

    @Test
    fun hugeWindowsOnSmallBuffersCannotOverflowByteBounds() = check("true", """
        var buffer = new ArrayBuffer(8);
        var cases = [
          function () { return new Uint8Array(buffer, 4294967296, 0); },
          function () { return new Uint16Array(buffer, 0, 2147483648); },
          function () { return new Uint32Array(buffer, 0, 1073741824); },
          function () { return new Float64Array(buffer, 0, 536870912); },
          function () { return new Uint16Array(buffer, 2, 2147483647); }
        ];
        String(cases.every(function (make) {
          try { make(); return false; } catch (e) { return e instanceof RangeError; }
        }));
    """.trimIndent())

    @Test
    fun ordinaryBufferWindowsStillShareAndEnforceAlignment() = check("2|6|RangeError,RangeError,RangeError", """
        var buffer = new ArrayBuffer(8), whole = new Uint16Array(buffer), part = new Uint16Array(buffer, 2, 2);
        part[0] = 6;
        var errors = [
          function () { return new Uint16Array(buffer, 1); },
          function () { return new Uint16Array(buffer, 10); },
          function () { return new Uint16Array(buffer, 6, 2); }
        ].map(function (make) { try { make(); return 'missing'; } catch (e) { return e.name; } });
        [part.length, whole[1], errors.join()].join('|');
    """.trimIndent())

    @Test
    fun largeOffsetsPreserveAlignmentAndLengthConversionOrder() = check("offset,length,RangeError|offset,RangeError", """
        function run(value) {
          var events = [], buffer = new ArrayBuffer(8);
          var offset = { valueOf: function () { events.push('offset'); return value; } };
          var length = { valueOf: function () { events.push('length'); return 0; } };
          try { new Uint16Array(buffer, offset, length); events.push('missing'); }
          catch (e) { events.push(e.name); }
          return events.join();
        }
        run(4294967296) + '|' + run(4294967297);
    """.trimIndent())

    @Test
    fun conversionErrorsPrecedeTheDetachmentCheck() = check("offset,length,TypeError|offset,length,RangeError", """
        function run(value) {
          var events = [], buffer = new ArrayBuffer(8);
          var offset = { valueOf: function () { events.push('offset'); buffer.transfer(); return 0; } };
          var length = { valueOf: function () { events.push('length'); return value; } };
          try { new Uint16Array(buffer, offset, length); events.push('missing'); }
          catch (e) { events.push(e.name); }
          return events.join();
        }
        run(0) + '|' + run(-1);
    """.trimIndent())

    @Test
    fun subarraySpeciesReceivesClampedIndicesAndTheSharedBuffer() = check("0|true|2|9|species,3:0,species,1:2", """
        var a = new Uint8Array([1, 2, 3]), events = [], constructor = {};
        Object.defineProperty(constructor, Symbol.species, { get: function () {
          events.push('species');
          return function (buffer, offset, length) {
            events.push(offset + ':' + length);
            return new Uint8Array(buffer, offset, length);
          };
        } });
        a.constructor = constructor;
        var empty = a.subarray(Infinity), tail = a.subarray(-2, Infinity);
        tail[0] = 9;
        [empty.length, empty.buffer === a.buffer, tail.length, a[1], events.join()].join('|');
    """.trimIndent())
}
