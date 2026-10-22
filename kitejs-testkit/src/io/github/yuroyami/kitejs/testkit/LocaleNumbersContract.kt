/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.testkit

import io.github.yuroyami.kitejs.api.JsEngine
import io.github.yuroyami.kitejs.api.KiteJsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * `toLocaleString` on numbers and BigInts prints what `Intl.NumberFormat("en-US", options)` does,
 * on every engine, and ignores the locale. Neither engine has `Intl`.
 */
public abstract class LocaleNumbersContract<C : KiteJsConfig>(engine: JsEngine<C>) : EngineContract<C>(engine) {

    @Test
    public fun aLocaleArgumentIsAcceptedAndEnUsIsWhatPrints(): TestResult = withEngine { js ->
        assertEquals("2,046,430", js.evaluate("(2046430).toLocaleString('en-US')").asString())
        assertEquals("1,234.5", js.evaluate("(1234.5).toLocaleString('de-DE')").asString())
        assertEquals("2,046,430", js.evaluate("(2046430).toLocaleString()").asString())
        assertEquals("1,234.568", js.evaluate("(1234.56789).toLocaleString()").asString())
        assertEquals("255", js.evaluate("(255).toLocaleString(16)").asString())
        assertEquals("123", js.evaluate("(123n).toLocaleString('en-US')").asString())
        assertEquals("12,345,678,901,234,567,890", js.evaluate("(12345678901234567890n).toLocaleString()").asString())
        assertEquals("1,234,567,2", js.evaluate("[1234567, 2].toLocaleString('en-US')").asString())
        assertEquals("-0", js.evaluate("(-0).toLocaleString()").asString())
        assertEquals("∞", js.evaluate("Infinity.toLocaleString()").asString())
        assertEquals("undefined", js.evaluate("typeof Intl").asString())
        assertEquals(
            "toLocaleString 0 true",
            js.evaluate(
                "var f = Number.prototype.toLocaleString; [f.name, f.length, /\\[native code\\]/.test(String(f))].join(' ')",
            ).asString(),
        )
        assertEquals("true", js.evaluate("String(/\\[native code\\]/.test(String(BigInt.prototype.toLocaleString)))").asString())
    }

    @Test
    public fun theOptionsOfIntlNumberFormatApply(): TestResult = withEngine { js ->
        assertEquals("50%", js.evaluate("(0.5).toLocaleString('en-US', { style: 'percent' })").asString())
        assertEquals("$1,234.50", js.evaluate("(1234.5).toLocaleString(undefined, { style: 'currency', currency: 'USD' })").asString())
        assertEquals("($5.00)", js.evaluate("(-5).toLocaleString('en', { style: 'currency', currency: 'usd', currencySign: 'accounting' })").asString())
        assertEquals("1.00 euros", js.evaluate("(1).toLocaleString('fr', { style: 'currency', currency: 'EUR', currencyDisplay: 'name' })").asString())
        assertEquals("5 km/h", js.evaluate("(5).toLocaleString('en', { style: 'unit', unit: 'kilometer-per-hour' })").asString())
        assertEquals("1.2K", js.evaluate("(1234).toLocaleString('en', { notation: 'compact' })").asString())
        assertEquals("1.23E3", js.evaluate("(1234).toLocaleString('en', { notation: 'scientific', maximumFractionDigits: 2 })").asString())
        assertEquals("3.14", js.evaluate("Math.PI.toLocaleString('en', { maximumFractionDigits: 2 })").asString())
        assertEquals("1.001", js.evaluate("(1.0005).toLocaleString()").asString())
        assertEquals("+12,000", js.evaluate("(12345n).toLocaleString('en', { maximumSignificantDigits: 2, signDisplay: 'always' })").asString())
    }

    @Test
    public fun badOptionsThrowCatchableErrorsAndAreReadInOrder(): TestResult = withEngine { js ->
        assertEquals("RangeError", js.evaluate("try { (1).toLocaleString('en', { style: 'bogus' }) } catch (e) { e.name }").asString())
        assertEquals("TypeError", js.evaluate("try { (1).toLocaleString('en', { style: 'currency' }) } catch (e) { e.name }").asString())
        assertEquals("RangeError", js.evaluate("try { (1n).toLocaleString('en', { maximumFractionDigits: 101 }) } catch (e) { e.name }").asString())
        assertEquals("TypeError", js.evaluate("try { (1).toLocaleString('en', null) } catch (e) { e.name }").asString())
        assertEquals("boom", js.evaluate("try { (1).toLocaleString('en', { get style() { throw 'boom' } }) } catch (e) { e }").asString())
        assertEquals(
            "localeMatcher,numberingSystem,style,currency,currencyDisplay,currencySign,unit,unitDisplay,notation," +
                "minimumIntegerDigits,minimumFractionDigits,maximumFractionDigits,minimumSignificantDigits," +
                "maximumSignificantDigits,roundingIncrement,roundingMode,roundingPriority,trailingZeroDisplay," +
                "compactDisplay,useGrouping,signDisplay",
            js.evaluate(
                "var seen = []; (1).toLocaleString('en', new Proxy({}, { get: function (t, k) { seen.push(k); } })); seen.join()",
            ).asString(),
        )
        assertEquals("TypeError", js.evaluate("try { Number.prototype.toLocaleString.call('1') } catch (e) { e.name }").asString())
    }
}
