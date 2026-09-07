/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.time.TimeSource

/**
 * The `console` object: `log`, `warn`, `error` and friends, plus the counters and timers. Where
 * the output goes is the embedder's choice, through a [ConsolePrinter].
 */
class NativeConsole private constructor(private val printer: ConsolePrinter) : ScriptableObject() {

    private val timers = HashMap<String, TimeSource.Monotonic.ValueTimeMark>()
    private val counters = HashMap<String, Int>()

    /** How loud a message is. */
    enum class Level { TRACE, DEBUG, INFO, WARN, ERROR }

    /** Where console output goes. [stack] is filled in only for `console.trace`. */
    fun interface ConsolePrinter {
        fun print(cx: Context, scope: Scriptable, level: Level, args: Array<Any?>, stack: Array<ScriptStackElement>?)
    }

    override val className: String get() = CLASS_NAME

    private fun print(cx: Context, scope: Scriptable, level: Level, message: String) {
        printer.print(cx, scope, level, arrayOf(message), null)
    }

    private fun jsTrace(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
        printer.print(cx, scope, Level.TRACE, args, EvaluatorException("[object Object]").scriptStack)
        return Undefined.instance
    }

    private fun jsAssert(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
        if (args.isNotEmpty() && ScriptRuntime.toBoolean(args[0])) return Undefined.instance
        if (args.size < 2) {
            printer.print(cx, scope, Level.ERROR, arrayOf("Assertion failed: console.assert"), null)
            return Undefined.instance
        }
        val first = args[1]
        val rest: Array<Any?> =
            if (first is CharSequence) {
                Array(args.size - 1) { if (it == 0) "Assertion failed: $first" else args[it + 1] }
            } else {
                Array(args.size) { if (it == 0) "Assertion failed:" else args[it] }
            }
        printer.print(cx, scope, Level.ERROR, rest, null)
        return Undefined.instance
    }

    private fun labelOf(args: Array<Any?>): String =
        if (args.isNotEmpty()) ScriptRuntime.toString(args[0]) else DEFAULT_LABEL

    private fun jsCount(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
        val label = labelOf(args)
        val count = (counters[label] ?: 0) + 1
        counters[label] = count
        print(cx, scope, Level.INFO, "$label: $count")
        return Undefined.instance
    }

    private fun jsCountReset(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
        val label = labelOf(args)
        if (counters.remove(label) == null) {
            print(cx, scope, Level.WARN, "Count for '$label' does not exist.")
        }
        return Undefined.instance
    }

    private fun jsTime(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
        val label = labelOf(args)
        if (timers.containsKey(label)) {
            print(cx, scope, Level.WARN, "Timer '$label' already exists.")
            return Undefined.instance
        }
        timers[label] = TimeSource.Monotonic.markNow()
        return Undefined.instance
    }

    private fun jsTimeEnd(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
        val label = labelOf(args)
        val start = timers.remove(label)
        if (start == null) {
            print(cx, scope, Level.WARN, "Timer '$label' does not exist.")
            return Undefined.instance
        }
        print(cx, scope, Level.INFO, "$label: ${elapsedMillis(start)}ms")
        return Undefined.instance
    }

    private fun jsTimeLog(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
        val label = labelOf(args)
        val start = timers[label]
        if (start == null) {
            print(cx, scope, Level.WARN, "Timer '$label' does not exist.")
            return Undefined.instance
        }
        val msg = StringBuilder("$label: ${elapsedMillis(start)}ms")
        for (i in 1 until args.size) msg.append(' ').append(ScriptRuntime.toString(args[i]))
        print(cx, scope, Level.INFO, msg.toString())
        return Undefined.instance
    }

    private fun elapsedMillis(start: TimeSource.Monotonic.ValueTimeMark): Double =
        start.elapsedNow().inWholeNanoseconds / 1_000_000.0

    companion object {
        private const val CLASS_NAME = "Console"
        private const val DEFAULT_LABEL = "default"

        /** The specifiers `format` understands, in the order it finds them. */
        private val FORMAT_SPECIFIER = Regex("%[sfdioOc%]")

        /** Builds a `console` and puts it into [scope]. */
        fun init(scope: Scriptable, sealed: Boolean, printer: ConsolePrinter) {
            val obj = NativeConsole(printer)
            obj.prototype = getObjectPrototype(scope)
            obj.parentScope = scope
            obj.defineProperty(scope, "toSource", 0, SerializableCallable { _, _, _, _ -> CLASS_NAME }, 0, DONTENUM or READONLY)
            obj.level(scope, "debug", Level.DEBUG)
            obj.level(scope, "log", Level.INFO)
            obj.level(scope, "info", Level.INFO)
            obj.level(scope, "warn", Level.WARN)
            obj.level(scope, "error", Level.ERROR)
            obj.builtin(scope, "trace", 1) { cx, s, args -> obj.jsTrace(cx, s, args) }
            obj.builtin(scope, "assert", 2) { cx, s, args -> obj.jsAssert(cx, s, args) }
            obj.builtin(scope, "count", 1) { cx, s, args -> obj.jsCount(cx, s, args) }
            obj.builtin(scope, "countReset", 1) { cx, s, args -> obj.jsCountReset(cx, s, args) }
            obj.builtin(scope, "time", 1) { cx, s, args -> obj.jsTime(cx, s, args) }
            obj.builtin(scope, "timeEnd", 1) { cx, s, args -> obj.jsTimeEnd(cx, s, args) }
            obj.builtin(scope, "timeLog", 2) { cx, s, args -> obj.jsTimeLog(cx, s, args) }
            if (sealed) obj.sealObject()
            defineProperty(scope, "console", obj, DONTENUM)
        }

        private fun NativeConsole.level(scope: Scriptable, name: String, level: Level) {
            builtin(scope, name, 1) { cx, s, args ->
                printer.print(cx, s, level, args, null)
                Undefined.instance
            }
        }

        private fun NativeConsole.builtin(
            scope: Scriptable,
            name: String,
            length: Int,
            body: (Context, Scriptable, Array<Any?>) -> Any,
        ) {
            defineBuiltinProperty(
                scope, name, length,
                SerializableCallable { cx, s, _, args -> body(cx, s, args) },
                0, DONTENUM or READONLY,
            )
        }

        /**
         * Turns the arguments into one line, the way a browser console does: the first argument
         * may carry `%s`, `%d`, `%i`, `%f`, `%o`, `%O`, `%c` and `%%`, and anything left over is
         * appended separated by spaces.
         */
        fun format(cx: Context, scope: Scriptable, args: Array<Any?>): String {
            if (args.isEmpty()) return ""
            val buffer = StringBuilder()
            var argIndex = 0
            val first = args[0]
            if (first is CharSequence) {
                val msg = first.toString()
                argIndex = 1
                var last = 0
                for (m in FORMAT_SPECIFIER.findAll(msg)) {
                    buffer.append(msg, last, m.range.first)
                    last = m.range.last + 1
                    val placeholder = m.value
                    buffer.append(
                        when {
                            placeholder == "%%" -> "%"
                            argIndex >= args.size -> placeholder.also { argIndex++ }
                            else -> {
                                val v = args[argIndex]
                                argIndex++
                                when (placeholder) {
                                    "%s" -> formatString(v)
                                    "%d", "%i" -> formatInt(v)
                                    "%f" -> formatFloat(v)
                                    "%o", "%O" -> formatObj(cx, scope, v)
                                    else -> ""
                                }
                            }
                        },
                    )
                }
                buffer.append(msg, last, msg.length)
            }
            for (i in argIndex until args.size) {
                if (buffer.isNotEmpty()) buffer.append(' ')
                val v = args[i]
                buffer.append(if (v is CharSequence) formatString(v) else formatObj(cx, scope, v))
            }
            return buffer.toString()
        }

        private fun formatString(value: Any?): String = when {
            value is KBigInt -> ScriptRuntime.toString(value) + "n"
            ScriptRuntime.isSymbol(value) -> value.toString()
            else -> ScriptRuntime.toString(value)
        }

        private fun formatInt(value: Any?): String = when {
            value is KBigInt -> ScriptRuntime.bigIntToString(value, 10) + "n"
            ScriptRuntime.isSymbol(value) -> ScriptRuntime.NaNobj.toString()
            else -> {
                val n = ScriptRuntime.toNumber(value)
                if (n.isInfinite() || n.isNaN()) ScriptRuntime.toString(n) else n.toLong().toString()
            }
        }

        private fun formatFloat(value: Any?): String =
            if (value is KBigInt || ScriptRuntime.isSymbol(value)) ScriptRuntime.NaNobj.toString()
            else ScriptRuntime.numberToString(ScriptRuntime.toNumber(value), 10)

        private fun formatObj(cx: Context, scope: Scriptable, arg: Any?): String {
            if (arg == null) return "null"
            if (Undefined.isUndefined(arg)) return Undefined.SCRIPTABLE_UNDEFINED.toString()
            if (arg is NativeError) return arg.toString() + "\n" + arg.get("stack")
            val replacer = SerializableCallable { _, _, _, callArgs ->
                var value = callArgs[1]
                while (value is Delegator) value = value.delegee
                when {
                    value is BaseFunction -> "function " + value.functionName + "() {...}"
                    value is Callable -> ScriptRuntime.toString(value)
                    arg is NativeError -> arg.toString()
                    else -> value
                }
            }
            return try {
                ScriptRuntime.toString(NativeJSON.stringify(cx, scope, arg, replacer, null))
            } catch (e: EcmaError) {
                if (e.name == "TypeError") ScriptRuntime.toString(arg) else throw e
            }
        }
    }
}
