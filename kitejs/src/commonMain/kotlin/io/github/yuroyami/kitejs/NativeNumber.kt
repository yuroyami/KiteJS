/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.dtoa.DecimalFormatter
import kotlin.math.floor

/** The JavaScript `Number` wrapper object. */
internal class NativeNumber internal constructor(private val doubleValue: Double) : ScriptableObject() {

    override val className: String
        get() = CLASS_NAME

    override fun toString(): String = ScriptRuntime.numberToString(doubleValue, 10)

    public companion object {
        public const val MAX_SAFE_INTEGER: Double = 9007199254740991.0
        private const val CLASS_NAME = "Number"
        private const val MAX_PRECISION = 100
        private const val MIN_SAFE_INTEGER = -MAX_SAFE_INTEGER
        private const val EPSILON = 2.220446049250313e-16

        internal fun init(scope: Scriptable, sealed: Boolean) {
            val constructor = LambdaConstructor(scope, CLASS_NAME, 1, ::js_constructorFunc, ::js_constructor)
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            constructor.setPrototypeScriptable(NativeNumber(0.0))
            val propAttr = DONTENUM or PERMANENT or READONLY
            constructor.defineProperty("NaN", ScriptRuntime.NaNobj, propAttr)
            constructor.defineProperty("POSITIVE_INFINITY", ScriptRuntime.wrapNumber(Double.POSITIVE_INFINITY), propAttr)
            constructor.defineProperty("NEGATIVE_INFINITY", ScriptRuntime.wrapNumber(Double.NEGATIVE_INFINITY), propAttr)
            constructor.defineProperty("MAX_VALUE", ScriptRuntime.wrapNumber(Double.MAX_VALUE), propAttr)
            constructor.defineProperty("MIN_VALUE", ScriptRuntime.wrapNumber(Double.MIN_VALUE), propAttr)
            constructor.defineProperty("MAX_SAFE_INTEGER", ScriptRuntime.wrapNumber(MAX_SAFE_INTEGER), propAttr)
            constructor.defineProperty("MIN_SAFE_INTEGER", ScriptRuntime.wrapNumber(MIN_SAFE_INTEGER), propAttr)
            constructor.defineProperty("EPSILON", ScriptRuntime.wrapNumber(EPSILON), propAttr)
            constructor.defineConstructorMethod(scope, "isFinite", 1, null, ::js_isFinite, DONTENUM, DONTENUM or READONLY)
            constructor.defineConstructorMethod(scope, "isNaN", 1, null, ::js_isNaN, DONTENUM, DONTENUM or READONLY)
            constructor.defineConstructorMethod(scope, "isInteger", 1, null, ::js_isInteger, DONTENUM, DONTENUM or READONLY)
            constructor.defineConstructorMethod(scope, "isSafeInteger", 1, null, ::js_isSafeInteger, DONTENUM, DONTENUM or READONLY)
            val parseFloat = ScriptRuntime.getTopLevelProp(constructor, "parseFloat")
            if (parseFloat is Function) {
                constructor.defineProperty("parseFloat", parseFloat, DONTENUM)
            }
            val parseInt = ScriptRuntime.getTopLevelProp(constructor, "parseInt")
            if (parseInt is Function) {
                constructor.defineProperty("parseInt", parseInt, DONTENUM)
            }
            constructor.definePrototypeMethod(scope, "toString", 1, ::js_toString)
            constructor.definePrototypeMethod(scope, "toLocaleString", 0, ::js_toString)
            constructor.definePrototypeMethod(scope, "toSource", 0, ::js_toSource)
            constructor.definePrototypeMethod(scope, "valueOf", 0, ::js_valueOf)
            constructor.definePrototypeMethod(scope, "toFixed", 1, ::js_toFixed)
            constructor.definePrototypeMethod(scope, "toExponential", 1, ::js_toExponential)
            constructor.definePrototypeMethod(scope, "toPrecision", 1, ::js_toPrecision)
            defineProperty(scope, CLASS_NAME, constructor, DONTENUM)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
        }

        private fun js_constructor(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
            val v = if (args.isNotEmpty()) ScriptRuntime.numericToDouble(ScriptRuntime.toNumeric(args[0])) else 0.0
            return NativeNumber(v)
        }

        private fun js_constructorFunc(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            if (args.isNotEmpty()) ScriptRuntime.numericToDouble(ScriptRuntime.toNumeric(args[0])) else 0.0

        private fun js_valueOf(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            toSelf(thisObj).doubleValue

        private fun js_toFixed(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val value = toSelf(thisObj).doubleValue
            val fractionDigits: Int
            if (args.isNotEmpty() && !Undefined.isUndefined(args[0])) {
                val p = ScriptRuntime.toInteger(args[0])
                // Before ES6 Rhino let toFixed take negative digit counts; the spec allows that.
                val precisionMin = if (cx.languageVersion < Context.VERSION_ES6) -20 else 0
                checkPrecision(p, precisionMin.toDouble(), args[0])
                fractionDigits = ScriptRuntime.toInt32(p)
            } else {
                fractionDigits = 0
            }
            if (!value.isFinite()) {
                return ScriptRuntime.toString(value)
            }
            return DecimalFormatter.toFixed(value, fractionDigits)
        }

        private fun js_toExponential(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val value = toSelf(thisObj).doubleValue
            val p: Double
            val wasUndefined: Boolean
            if (args.isNotEmpty() && !Undefined.isUndefined(args[0])) {
                wasUndefined = false
                p = ScriptRuntime.toInteger(args[0])
            } else {
                wasUndefined = true
                p = 0.0
            }
            if (!value.isFinite()) {
                return ScriptRuntime.toString(value)
            }
            checkPrecision(p, 0.0, if (args.isNotEmpty()) args[0] else Undefined.instance)
            val fractionDigits = if (wasUndefined) -1 else ScriptRuntime.toInt32(p)
            return DecimalFormatter.toExponential(value, fractionDigits)
        }

        private fun js_toPrecision(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val value = toSelf(thisObj).doubleValue
            if (args.isEmpty() || Undefined.isUndefined(args[0])) {
                return ScriptRuntime.toString(value)
            }
            val p = ScriptRuntime.toInteger(args[0])
            if (!value.isFinite()) {
                return ScriptRuntime.toString(value)
            }
            checkPrecision(p, 1.0, args[0])
            val precision = ScriptRuntime.toInt32(p)
            return DecimalFormatter.toPrecision(value, precision)
        }

        private fun checkPrecision(p: Double, min: Double, arg: Any?) {
            if (p < min || p > MAX_PRECISION) {
                val msg = ScriptRuntime.getMessageById("msg.bad.precision", ScriptRuntime.toString(arg))
                throw ScriptRuntime.rangeError(msg)
            }
        }

        private fun toSelf(thisObj: Scriptable?): NativeNumber =
            LambdaConstructor.convertThisObject<NativeNumber>(thisObj)

        private fun js_toString(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val base = if (args.isEmpty() || Undefined.isUndefined(args[0])) 10 else ScriptRuntime.toInt32(args[0])
            return ScriptRuntime.numberToString(toSelf(thisObj).doubleValue, base)
        }

        private fun js_toSource(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            "(new Number(" + ScriptRuntime.toString(toSelf(thisObj).doubleValue) + "))"

        private fun argToNumber(args: Array<Any?>): Number? {
            if (args.isNotEmpty()) {
                val first = args[0]
                if (first is Number) return first
            }
            return null
        }

        private fun js_isFinite(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val n = argToNumber(args)
            return if (n == null) false else isFinite(n)
        }

        internal fun isFinite(value: Any?): Any {
            val nd = ScriptRuntime.toNumber(value)
            return nd.isFinite()
        }

        private fun js_isNaN(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val v = argToNumber(args) ?: return false
            return v.toDouble().isNaN()
        }

        private fun js_isInteger(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val v = argToNumber(args) ?: return false
            return isDoubleInteger(v.toDouble())
        }

        private fun js_isSafeInteger(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val v = argToNumber(args) ?: return false
            return isDoubleSafeInteger(v.toDouble())
        }

        private fun isDoubleInteger(d: Double): Boolean = d.isFinite() && floor(d) == d

        private fun isDoubleSafeInteger(d: Double): Boolean =
            isDoubleInteger(d) && d <= MAX_SAFE_INTEGER && d >= MIN_SAFE_INTEGER
    }
}
