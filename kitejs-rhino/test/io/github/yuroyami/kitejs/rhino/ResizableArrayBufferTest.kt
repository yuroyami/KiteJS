/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Resizable buffers (ECMAScript 2024, 25.1): `resize`, `transfer` and `transferToFixedLength`,
 * and typed arrays and DataViews that track the length of their buffer (#114). The expected
 * string is Node 26.11 output.
 */
class ResizableArrayBufferTest {
    @Test
    fun viewsFollowTheBuffer() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ECMASCRIPT
            val result = cx.evaluateString(cx.initStandardObjects(), SOURCE, "resizable.js", 1, null)
            assertEquals(
                "true,16,4,false,8,8,2,3,7,0,1 2 3 4 0 0 0 0,1,0,0,0,0,TypeError,RangeError,1,2,4,0,8,2," +
                    "RangeError,TypeError,RangeError,true,true,16,9,0,false,3,1 0 0,3 2 3",
                ScriptRuntime.toString(result),
            )
        } finally {
            Context.exit()
        }
    }

    /**
     * After a shrink, an Array method skips the indexes past the end, while the `%TypedArray%`
     * method reads them as undefined. `map` makes its result through species before the first
     * callback, and a result too short is a TypeError. DataView reads and writes bigints. An
     * exhausted iterator stays done, and toLocaleString writes an element past the end as empty.
     */
    @Test
    fun shrinkingSpeciesAndBigInts() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ECMASCRIPT
            val result = cx.evaluateString(cx.initStandardObjects(), SHRINK_SOURCE, "shrink.js", 1, null)
            assertEquals(
                "1 2,1 2 undefined undefined,r 1 2,r 1 2 undefined undefined,TypeError,TypeError," +
                    "create 2 call 1 call 2,10 20 0 0,-2,18446744073709551614,-3,18446744073709551613,254," +
                    "TypeError,RangeError,1,2,true,\"0,0,,\"",
                ScriptRuntime.toString(result),
            )
        } finally {
            Context.exit()
        }
    }

    private companion object {
        val SOURCE = """
        var out = [];
        var rab = new ArrayBuffer(4, { maxByteLength: 16 });
        out.push(rab.resizable, rab.maxByteLength, rab.byteLength, new ArrayBuffer(8).resizable, new ArrayBuffer(8).maxByteLength);
        var tracking = new Uint8Array(rab);
        var fixed = new Uint8Array(rab, 0, 2);
        var tail = new Uint16Array(rab, 2);
        var view = new DataView(rab, 1);
        tracking.set([1, 2, 3, 4]);
        rab.resize(8);
        out.push(tracking.length, fixed.length, tail.length, view.byteLength, tracking[6], Array.from(tracking).join(' '));
        rab.resize(1);
        out.push(tracking.length, fixed.length, tail.length, tail.byteOffset, view.byteLength);
        try { fixed.at(0); } catch (e) { out.push(e.constructor.name); }
        try { view.getUint8(0); } catch (e) { out.push(e.constructor.name); }
        rab.resize(5);
        out.push(tail.length, fixed.length, tracking.subarray(1).length, view.getUint8(0));
        rab.resize(9);
        out.push(tracking.subarray(1).length, tracking.subarray(1, 3).length);
        try { rab.resize(17); } catch (e) { out.push(e.constructor.name); }
        try { new ArrayBuffer(8).resize(4); } catch (e) { out.push(e.constructor.name); }
        try { new ArrayBuffer(9, { maxByteLength: 8 }); } catch (e) { out.push(e.constructor.name); }
        var kept = rab.transfer();
        out.push(rab.detached, kept.resizable, kept.maxByteLength, kept.byteLength, tracking.length);
        var fixedCopy = kept.transferToFixedLength(3);
        out.push(fixedCopy.resizable, fixedCopy.byteLength, new Uint8Array(fixedCopy).join(' '));
        var shrink = new ArrayBuffer(4, { maxByteLength: 8 });
        var all = new Uint8Array(shrink);
        all.set([1, 2, 3, 4]);
        all.copyWithin(0, 2, { valueOf() { shrink.resize(3); return 4; } });
        out.push(Array.from(all).join(' '));
        out.join();
        """

        val SHRINK_SOURCE = """
        var out = [];
        function shrinking() {
            var rab = new ArrayBuffer(4, { maxByteLength: 8 });
            var ta = new Uint8Array(rab);
            ta.set([1, 2, 3, 4]);
            return ta;
        }
        var seen = [];
        var ta = shrinking();
        Array.prototype.forEach.call(ta, function (v, i) { seen.push(String(v)); if (i == 1) ta.buffer.resize(2); });
        out.push(seen.join(' '));
        seen = [];
        ta = shrinking();
        ta.forEach(function (v, i) { seen.push(String(v)); if (i == 1) ta.buffer.resize(2); });
        out.push(seen.join(' '));
        ta = shrinking();
        out.push(Array.prototype.reduce.call(ta, function (a, v, i) { if (i == 1) ta.buffer.resize(2); return a + ' ' + v; }, 'r'));
        ta = shrinking();
        out.push(ta.reduce(function (a, v, i) { if (i == 1) ta.buffer.resize(2); return a + ' ' + v; }, 'r'));
        var empty = new Uint8Array(new ArrayBuffer(4, { maxByteLength: 8 }));
        empty.buffer.resize(0);
        var source = new Uint8Array([5, 6]);
        source.constructor = { [Symbol.species]: function () { return empty; } };
        try { source.map(function (v) { return v; }); } catch (e) { out.push(e.constructor.name); }
        try { source.filter(function () { return true; }); } catch (e) { out.push(e.constructor.name); }
        var order = [];
        var big = new Uint8Array(4);
        var src = new Uint8Array([1, 2]);
        src.constructor = { [Symbol.species]: function (n) { order.push('create ' + n); return big; } };
        src.map(function (v) { order.push('call ' + v); return v * 10; });
        out.push(order.join(' '), big.join(' '));
        var dv = new DataView(new ArrayBuffer(16));
        dv.setBigInt64(0, -2n);
        dv.setBigUint64(8, 2n ** 64n - 3n, true);
        out.push(dv.getBigInt64(0), dv.getBigUint64(0), dv.getBigInt64(8, true), dv.getBigUint64(8, true), dv.getUint8(7));
        try { dv.setBigInt64(0, 1); } catch (e) { out.push(e.constructor.name); }
        try { dv.getBigInt64(9); } catch (e) { out.push(e.constructor.name); }
        out.push(DataView.prototype.getBigInt64.length, DataView.prototype.setBigUint64.length);
        var rab2 = new ArrayBuffer(3, { maxByteLength: 5 });
        var tail2 = new Int8Array(rab2, 1);
        var it = tail2.values();
        it.next(); it.next(); it.next();
        rab2.resize(0);
        out.push(it.next().done);
        var rab3 = new ArrayBuffer(4, { maxByteLength: 8 });
        var fixed3 = new Uint8Array(rab3, 0, 4);
        var calls = 0, saved = Number.prototype.toLocaleString;
        Number.prototype.toLocaleString = function () { if (++calls == 2) rab3.resize(2); return saved.call(this); };
        out.push(JSON.stringify(fixed3.toLocaleString()));
        Number.prototype.toLocaleString = saved;
        out.join();
        """
    }
}
