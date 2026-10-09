/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The regexp compiler must not run out of native stack on a deep pattern. The Windows test
 * executable has a 1 MB main thread, so these cases fail there first.
 */
class RegExpDepthTest {
    // test262 built-ins/RegExp/S15.10.2.8_A3_T15.js nests 200 groups.
    @Test
    fun twoHundredNestedGroupsCompileAndMatch() {
        assertEquals("201,true", run(
            """
            var re = new RegExp('('.repeat(200) + 'hello' + ')'.repeat(200));
            var m = re.exec('hello');
            m.length + ',' + m.every(function (s) { return s === 'hello'; });
            """,
        ))
    }

    // A word list joined with '|' makes one alternation per word.
    @Test
    fun twentyThousandAlternativesCompileAndMatch() {
        assertEquals("true,k19999,false", run(
            """
            var words = [];
            for (var i = 0; i < 20000; i++) words.push('k' + i);
            var re = new RegExp('^(?:' + words.join('|') + ')$');
            re.test('k0') + ',' + re.exec('k19999')[0] + ',' + re.test('k20000');
            """,
        ))
    }

    @Test
    fun singleCharacterAlternativesKeepTheirPrerequisites() {
        assertEquals("c,C,null", run(
            """
            var chars = 'abcdefghijklmnopqrstuvwxyz'.split('');
            var big = [];
            for (var i = 0; i < 4000; i++) big.push(chars[i % 26]);
            var re = new RegExp(big.join('|'));
            var rei = new RegExp(big.join('|'), 'i');
            re.exec('1c')[0] + ',' + rei.exec('1C')[0] + ',' + re.exec('123');
            """,
        ))
    }

    @Test
    fun aNamedGroupInsideAnUnnamedGroupIsFound() {
        assertEquals("x,y", run(
            """
            var m = /((?<a>x))|(?<b>y)/.exec('x');
            var n = /((?<a>x))|(?<b>y)/.exec('y');
            m.groups.a + ',' + n.groups.b;
            """,
        ))
    }

    @Test
    fun manyAlternativesWithNamedGroupsCompile() {
        assertEquals("v4999", run(
            """
            var parts = [];
            for (var i = 0; i < 5000; i++) parts.push('(?<g' + i + '>v' + i + ')');
            var m = new RegExp('^(?:' + parts.join('|') + ')$').exec('v4999');
            m.groups.g4999;
            """,
        ))
    }

    private fun run(source: String): String {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ECMASCRIPT
            return ScriptRuntime.toString(cx.evaluateString(cx.initStandardObjects(), source, "depth.js", 1, null))
        } finally {
            Context.exit()
        }
    }
}
