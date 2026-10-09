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
    }
}
