/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.LanguageVersion
import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/** Contextual let grammar, with fixed Node 26.10 controls. */
class LetIdentifierTest {
    private val evaluated = listOf(
        "var let=1;let" to "1",
        "let=2;let" to "2",
        "function f(let){return let}f(3)" to "3",
        "(function(){var let=5;return let+1})()" to "6",
        "var let=1;let+=2;let++;let" to "4",
        "function let(){return 7}let()" to "7",
        "(function let(){return 8})()" to "8",
        "var [let]=[9];let" to "9",
        "var {x:let}={x:10};let" to "10",
        "try{throw 11}catch(let){let}" to "11",
        "var let=12;({let}).let" to "12",
        "var let=13;var o={let:14};o.let+let" to "27",
        "({m(let){return let}}).m(15)" to "15",
        "var o={};Object.defineProperty(o,\"x\",{set:function(let){this.y=let}});o.x=16;o.y" to "16",
        "var n=0;let:while(true){n++;break let}n" to "1",
        "var n=0;outer:let:while(true){n++;break outer}n" to "1",
        "var l\\u0065t=17;let" to "17",
        "var let=18;l\\u{65}t" to "18",
        "(let=>let+1)(18)" to "19",
        "var let=19;(function(){return let})()" to "19",
        "var let=20;function* f(){yield let}f().next().value" to "20",
        "var let=21;(function*(){function f(let){return let}yield f(let)})().next().value" to "21",
        "var let={x:22};with(let){x}" to "22",
        "typeof let" to "undefined",
        "var let=23;let in {23:true}" to "true",
        "function C(){}var let=new C;let instanceof C" to "true",
        "var let=[24];(let)[0]" to "24",
        "var let=function(x){return x+1};let(24)" to "25",
        "var let=26;let\nwhile(false){};let" to "26",
        "var let=27;let\nfunction f(){};let" to "27",
        "var let=28;let\ntrue" to "true",
        "var let=29;if(true)let\nx=30;x" to "30",
        "var let=31;label:let\n{};let" to "31",
        "let x=32;x" to "32",
        "let\nx=33;x" to "33",
        "let/*comment*/x=34;x" to "34",
        "let/*\n*/x=35;x" to "35",
        "let//comment\nx=36;x" to "36",
        "let\u2028x=37;x" to "37",
        "let\u2029x=38;x" to "38",
        "let<!--comment\nx=39;x" to "39",
        "let\n-->comment\nx=40;x" to "40",
        "let {x}={x:41};x" to "41",
        "let [x]=[42];x" to "42",
        "let\n{x}={x:43};x" to "43",
        "let\n[x]=[44];x" to "44",
        "let \\u0061=45;a" to "45",
        "let a\\u0062=46;ab" to "46",
        "let 变量=47;变量" to "47",
        "let 𝒜=48;𝒜" to "48",
        "let \$x=49;\$x" to "49",
        "let _x=50;_x" to "50",
        "(function(){let undefined=51;return undefined})()" to "51",
        "var let=52;let/*\n*/in {52:true}" to "true",
        "var out=[];for(let i=0;i<3;i++)out.push(i);out.join()" to "0,1,2",
        "var out=[];var let;for(let=0;let<3;let++)out.push(let);out.join()" to "0,1,2",
        "var out=[];var let;for(let in {a:1,b:2})out.push(let);out.join()" to "a,b",
        "var let={x:0};for(let.x in {a:1}){};let.x" to "a",
        "var let=[0];for((let)[0] in {b:1}){};(let)[0]" to "b",
        "var let={x:0};for(l\\u0065t.x of [53]){};let.x" to "53",
        "var let={x:0};for((let).x of [54]){};let.x" to "54",
        "var out=[];for(let of of [55])out.push(of);out.join()" to "55",
        "var out=[];for(let\ni of [56])out.push(i);out.join()" to "56",
        "var out=[];for(let/*\n*/[x] of [[57]])out.push(x);out.join()" to "57",
        "var out=[];for(let {x} of [{x:58}])out.push(x);out.join()" to "58",
        "'use strict';let x=59;x" to "59",
        "'use strict';({let:60}).let" to "60",
        "'use strict';({let(){return 61}}).let()" to "61",
        "class C{let(){return 62}}new C().let()" to "62",
        "var let=63;l\\u0065t\nx=64;x" to "64",
    )

    private val parsed = listOf(
        "'use strict';var let=1" to "throws SyntaxError",
        "'use strict';let=1" to "throws SyntaxError",
        "'use strict'\nlet=1" to "throws SyntaxError",
        "'use strict'\nlet" to "throws SyntaxError",
        "function f(){'use strict'\nlet}" to "throws SyntaxError",
        "'use strict';function f(let){}" to "throws SyntaxError",
        "function f(let){'use strict'}" to "throws SyntaxError",
        "function let(){'use strict'}" to "throws SyntaxError",
        "(let)=>{'use strict'}" to "throws SyntaxError",
        "function f({x:let}){'use strict'}" to "throws SyntaxError",
        "function f([let]){'use strict'}" to "throws SyntaxError",
        "'use strict';var l\\u0065t=1" to "throws SyntaxError",
        "'use strict';l\\u0065t=1" to "throws SyntaxError",
        "'use strict';try{}catch(let){}" to "throws SyntaxError",
        "'use strict';let:while(false){}" to "throws SyntaxError",
        "'use strict';({let})" to "throws SyntaxError",
        "class C{m(let){}}" to "throws SyntaxError",
        "class C{static{let=1}}" to "throws SyntaxError",
        "let let=1" to "throws SyntaxError",
        "const let=1" to "throws SyntaxError",
        "let l\\u0065t=1" to "throws SyntaxError",
        "const l\\u0065t=1" to "throws SyntaxError",
        "let [let]=[]" to "throws SyntaxError",
        "let {x:let}={}" to "throws SyntaxError",
        "const {let}= {}" to "throws SyntaxError",
        "for(let let in {}){}" to "throws SyntaxError",
        "for(let let of []){}" to "throws SyntaxError",
        "for(const let of []){}" to "throws SyntaxError",
        "var let;for(let of []){}" to "throws SyntaxError",
        "var let={};for(let.x of []){}" to "throws SyntaxError",
        "var let=[];for(let[0] in {}){}" to "throws SyntaxError",
        "var let=[];for(let[0] of []){}" to "throws SyntaxError",
        "let [0]" to "throws SyntaxError",
        "var let=[];let[0]" to "throws SyntaxError",
        "var let=[];let\n[0]" to "throws SyntaxError",
        "label:let[0]" to "throws SyntaxError",
        "if(false)let[0]" to "throws SyntaxError",
        "if(false)let x=1" to "throws SyntaxError",
        "if(false)let [x]=[]" to "throws SyntaxError",
        "while(false)let x=1" to "throws SyntaxError",
        "label:let x=1" to "throws SyntaxError",
        "with({})let x=1" to "throws SyntaxError",
        "var let=1;let\n{}" to "throws SyntaxError",
        "var let=1;let\nlet=2" to "throws SyntaxError",
        "function* f(){let\nyield 1}" to "throws SyntaxError",
        "var x=let(a=1)a" to "throws SyntaxError",
        "'use strict';let (x=1){}" to "throws SyntaxError",
        "l\\u0065t x=1" to "throws SyntaxError",
        "let \\u0069f=1" to "throws SyntaxError",
        "function f(let){}" to "parses",
        "function let(){}" to "parses",
        "let x=1" to "parses",
        "var let;for(let in {}){}" to "parses",
        "for(let of of []){}" to "parses",
        "var let={};for(l\\u0065t.x of []){}" to "parses",
        "var let={};for((let).x of []){}" to "parses",
        "var let;label:let\n{}" to "parses",
        "var let;if(false)let\nx=1" to "parses",
        "let\nwhile(false){}" to "parses",
        "let\nfunction f(){}" to "parses",
        "let\ntrue" to "parses",
        "let\nimplements=1" to "parses",
        "let/*\n*/x=1" to "parses",
        "let//comment\nx=1" to "parses",
        "let \\u0061=1" to "parses",
    )

    private fun check(cases: List<Pair<String, String>>, parseOnly: Boolean) {
        val failures = mutableListOf<String>()
        for ((source, expected) in cases) KiteJs(Rhino).use { js ->
            val actual = try {
                if (parseOnly) { js.compile(source); "parses" } else js.evaluate(source).asString()
            } catch (e: JsSyntaxError) {
                "throws SyntaxError"
            } catch (e: JsError) {
                "throws " + e.name
            }
            if (actual != expected) failures += "$source: expected $expected, was $actual"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun sloppyNamesAndLexicalDeclarationsMatchV8() = check(evaluated, false)

    @Test
    fun strictAndAmbiguousGrammarMatchesV8() = check(parsed, true)

    @Test
    fun legacyModeRetainsLetExpressionsAndBlocks() {
        KiteJs(Rhino) { languageVersion = LanguageVersion.ES5 }.use { js ->
            assertEquals("3", js.evaluate("let (x=2) x+1").asString())
            assertEquals("4", js.evaluate("let (x=3) { x+1 }").asString())
        }
    }
}
