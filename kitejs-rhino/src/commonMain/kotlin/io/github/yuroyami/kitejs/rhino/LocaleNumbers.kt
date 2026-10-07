/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import io.github.yuroyami.kitejs.api.KBigInt
import io.github.yuroyami.kitejs.api.format.EnUsNumberFormat
import io.github.yuroyami.kitejs.api.format.NumberFormatOptionError
import io.github.yuroyami.kitejs.api.format.NumberFormatOptionReader

/**
 * `toLocaleString` of Number and BigInt: what `Intl.NumberFormat("en-US", options)` prints, through
 * the formatter every engine shares (D-99). The locales argument is not read; en-US is the only
 * locale, as it is for dates.
 */
internal object LocaleNumbers {

    fun format(cx: Context, scope: Scriptable, args: Array<Any?>, value: Double): String = resolve(cx, scope, args).format(value)

    fun format(cx: Context, scope: Scriptable, args: Array<Any?>, value: KBigInt): String = resolve(cx, scope, args).format(value)

    private fun resolve(cx: Context, scope: Scriptable, args: Array<Any?>): EnUsNumberFormat {
        val options = if (args.size > 1) args[1] else Undefined.instance
        // CoerceOptionsToObject: undefined reads nothing, null is a TypeError.
        if (Undefined.isUndefined(options)) return EnUsNumberFormat.DEFAULT
        val obj = ScriptRuntime.toObject(cx, scope, options)
        try {
            return EnUsNumberFormat.resolve(Reader(obj))
        } catch (e: NumberFormatOptionError) {
            throw if (e.isTypeError) ScriptRuntime.typeError(e.message!!) else ScriptRuntime.rangeError(e.message!!)
        }
    }

    private class Reader(private val options: Scriptable) : NumberFormatOptionReader {
        override fun get(name: String): Any? = ScriptableObject.getProperty(options, name)
        override fun isUndefined(value: Any?): Boolean = value === Scriptable.NOT_FOUND || Undefined.isUndefined(value)
        override fun toNumber(value: Any?): Double = ScriptRuntime.toNumber(value)
        override fun toJsString(value: Any?): String = ScriptRuntime.toString(value)
        override fun toBoolean(value: Any?): Boolean = ScriptRuntime.toBoolean(value)
        override fun isTrue(value: Any?): Boolean = value == true
    }
}
