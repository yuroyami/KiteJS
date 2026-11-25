/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Array.prototype.sort` sorts with the port's own merge sort on every target. Upstream sorted
 * with the JVM's TimSort, which throws on an inconsistent comparator, and then left the array as
 * it was, so a shuffle by a random comparator did nothing in about one run in twelve on the JVM.
 */
class ArraySortTest {
    private fun run(source: String): String {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ECMASCRIPT
            val scope = cx.initStandardObjects()
            return ScriptRuntime.toString(cx.evaluateString(scope, source, "sort.js", 1, null))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun anInconsistentComparatorStillMovesTheElements() {
        // A seeded random comparator, so each run sees the same answers. Each seed shuffles 1000
        // elements and counts the shuffles that left the array in order, or lost an element.
        val script = """
            var untouched = 0, broken = 0;
            for (var seed = 1; seed <= 60; seed++) {
                var k = seed, a = [];
                for (var i = 0; i < 1000; i++) a.push(i);
                a.sort(function () { k = (k * 1103515245 + 12345) % 2147483648; return (k % 3) - 1 });
                if (a.every(function (v, i) { return v === i })) untouched++;
                if (a.slice().sort(function (x, y) { return x - y }).join() !== a.map(function (v, i) { return i }).join()) broken++;
            }
            untouched + ':' + broken
        """.trimIndent()
        assertEquals("0:0", run(script))
    }

    @Test
    fun aComparatorThatNeverAnswersBelowZeroMovesNothing() {
        // Node 26.11 answers 3,1,2 too: V8 moves an element only when comparefn(later, earlier) < 0.
        assertEquals("3,1,2", run("[3, 1, 2].sort(function (a, b) { return a > b }).join()"))
    }

    @Test
    fun theSortIsStable() {
        val script = """
            var a = [];
            for (var i = 0; i < 200; i++) a.push({ key: i % 3, at: i });
            a.sort(function (x, y) { return x.key - y.key });
            a.every(function (v, i) { return i === 0 || a[i - 1].key < v.key || a[i - 1].at < v.at })
        """.trimIndent()
        assertEquals("true", run(script))
    }
}
