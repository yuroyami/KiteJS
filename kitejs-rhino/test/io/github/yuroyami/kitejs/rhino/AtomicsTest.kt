/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `SharedArrayBuffer` and `Atomics` (#72). The expected string is Node 26.11 output, except
 * `Atomics.wait`: Node's main thread can block, so it answers "timed-out" where this engine
 * throws a TypeError.
 */
class AtomicsTest {
    @Test
    fun atomicsMatchNode() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ECMASCRIPT
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, SOURCE, "atomics.js", 1, null)
            cx.processMicrotasks()
            val joined = cx.evaluateString(scope, "out.join('|')", "join.js", 1, null)
            assertEquals(EXPECTED, ScriptRuntime.toString(joined))
        } finally {
            Context.exit()
        }
    }

    private companion object {
        const val EXPECTED =
            "[object SharedArrayBuffer]|16,true,32|24|RangeError|RangeError|TypeError|" +
            "TypeError|TypeError|4,true|300,44|44,-112|0,4294967295|4294967295,240|240,255|" +
            "255,0|0,4294967295|-112,-112|-112,-112|4294967295|0|true|RangeError|TypeError|" +
            "TypeError|TypeError|0,5|5,-5|TypeError|0|truetruefalsetruefalsetruefalse|" +
            "TypeError|TypeError|TypeError|0|0|{\"async\":false,\"value\":\"not-equal\"}|" +
            "{\"async\":false,\"value\":\"timed-out\"}|true,true|1|" +
            "undefined,undefined,undefined|TypeError|TypeError|[object Atomics]|3,4,4,3,0|6|" +
            "r:ok|r3:timed-out|r2:timed-out"

        val SOURCE = """
        var out = [];
        function t(f) { try { out.push(String(f())); } catch (e) { out.push(e.constructor.name); } }
        var sab = new SharedArrayBuffer(16, { maxByteLength: 32 });
        t(() => Object.prototype.toString.call(sab));
        t(() => sab.byteLength + "," + sab.growable + "," + sab.maxByteLength);
        t(() => { sab.grow(24); return sab.byteLength; });
        t(() => sab.grow(8));
        t(() => sab.grow(40));
        t(() => new SharedArrayBuffer(4).grow(4));
        t(() => ArrayBuffer.prototype.slice.call(sab, 0));
        t(() => Object.getOwnPropertyDescriptor(ArrayBuffer.prototype, "byteLength").get.call(sab));
        t(() => sab.slice(2, 6).byteLength + "," + (sab.slice(0) instanceof SharedArrayBuffer));
        var i8 = new Int8Array(sab);
        var u32 = new Uint32Array(sab);
        t(() => Atomics.store(i8, 0, 300) + "," + i8[0]);
        t(() => Atomics.add(i8, 0, 100) + "," + i8[0]);
        t(() => Atomics.sub(u32, 1, 1) + "," + u32[1]);
        t(() => Atomics.and(u32, 1, 0xF0) + "," + u32[1]);
        t(() => Atomics.or(u32, 1, 0x0F) + "," + u32[1]);
        t(() => Atomics.xor(u32, 1, 0xFF) + "," + u32[1]);
        t(() => Atomics.exchange(u32, 1, -1) + "," + u32[1]);
        t(() => Atomics.compareExchange(i8, 0, 300, 5) + "," + i8[0]);
        t(() => Atomics.compareExchange(i8, 0, 44, 5) + "," + i8[0]);
        t(() => Atomics.load(u32, 1));
        t(() => Atomics.store(u32, 1, -0));
        t(() => Object.is(Atomics.store(u32, 1, -0), 0));
        t(() => Atomics.load(u32, 100));
        t(() => Atomics.load(new Float32Array(4), 0));
        t(() => Atomics.load(new Uint8ClampedArray(4), 0));
        t(() => Atomics.load({}, 0));
        var b64 = new BigInt64Array(new SharedArrayBuffer(16));
        t(() => Atomics.add(b64, 0, 5n) + "," + b64[0]);
        t(() => Atomics.sub(b64, 0, 10n) + "," + b64[0]);
        t(() => Atomics.add(b64, 0, 1));
        t(() => Atomics.add(new BigUint64Array(4), 0, 3n) );
        t(() => [1,2,3,4,5,8,16].map(n => Atomics.isLockFree(n)).join(""));
        var i32 = new Int32Array(new SharedArrayBuffer(16));
        t(() => Atomics.wait(i32, 0, 0, 0));
        t(() => Atomics.wait(new Int32Array(4), 0, 0, 0));
        t(() => Atomics.wait(new Int16Array(new SharedArrayBuffer(8)), 0, 0, 0));
        t(() => Atomics.notify(new Int32Array(4), 0));
        t(() => Atomics.notify(i32, 0));
        t(() => JSON.stringify(Atomics.waitAsync(i32, 0, 1)));
        t(() => JSON.stringify(Atomics.waitAsync(i32, 0, 0, 0)));
        var r = Atomics.waitAsync(i32, 1, 0);
        t(() => r.async + "," + (r.value instanceof Promise));
        var r2 = Atomics.waitAsync(i32, 1, 0, 50);
        var r3 = Atomics.waitAsync(i32, 2, 0, 10);
        t(() => Atomics.notify(i32, 1, 1));
        r.value.then(v => { out.push("r:" + v); });
        r2.value.then(v => { out.push("r2:" + v); });
        r3.value.then(v => { out.push("r3:" + v); });
        t(() => Atomics.pause() + "," + Atomics.pause(3) + "," + Atomics.pause(-0));
        t(() => Atomics.pause(1.5));
        t(() => Atomics.pause("1"));
        t(() => Object.prototype.toString.call(Atomics));
        t(() => [Atomics.add.length, Atomics.compareExchange.length, Atomics.wait.length, Atomics.notify.length, Atomics.pause.length].join());
        t(() => new Int32Array(sab).length);
        """.trimIndent()
    }
}
