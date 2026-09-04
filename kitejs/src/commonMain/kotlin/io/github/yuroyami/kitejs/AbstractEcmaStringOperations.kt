/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** GetSubstitution: the `$1`, `$&`, `$<name>` patterns a replacement string can carry. */
object AbstractEcmaStringOperations {

    /** Compiles a replacement template into the pieces [getSubstitution] stitches together. */
    fun buildReplacementList(replacementTemplate: String): List<ReplacementOperation> {
        val ops = ArrayList<ReplacementOperation>()
        var position = 0
        var start = 0
        while (position < replacementTemplate.length) {
            if (replacementTemplate[position] == '$') {
                if (position < replacementTemplate.length - 1) {
                    if (start != position) {
                        ops.add(LiteralReplacement(replacementTemplate.substring(start, position)))
                    }
                    var ref = replacementTemplate.substring(position, position + 1)
                    when (val c = replacementTemplate[position + 1]) {
                        '$' -> {
                            ref = "$$"
                            ops.add(LiteralReplacement("$"))
                        }
                        '`' -> {
                            ref = "$`"
                            ops.add(FromStartToMatchReplacement())
                        }
                        '&' -> {
                            ref = "$&"
                            ops.add(MatchedReplacement())
                        }
                        '\'' -> {
                            ref = "$'"
                            ops.add(FromMatchToEndReplacement())
                        }
                        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                            var digitCount = 1
                            if (replacementTemplate.length > position + 2) {
                                val c2 = replacementTemplate[position + 2]
                                if (isAsciiDigit(c2)) digitCount = 2
                            }
                            val digits = replacementTemplate.substring(position + 1, position + 1 + digitCount)
                            ref = replacementTemplate.substring(position, position + 1 + digitCount)
                            val index = digits.toInt()
                            if (digits.length == 1) {
                                ops.add(OneDigitCaptureReplacement(index))
                            } else {
                                ops.add(TwoDigitCaptureReplacement(index))
                            }
                        }
                        '<' -> {
                            val gtPos = replacementTemplate.indexOf('>', position + 2)
                            if (gtPos == -1) {
                                ref = "$<"
                                ops.add(LiteralReplacement(ref))
                            } else {
                                ref = replacementTemplate.substring(position, gtPos + 1)
                                val groupName = replacementTemplate.substring(position + 2, gtPos)
                                ops.add(NamedCaptureReplacement(groupName))
                            }
                        }
                        else -> {
                            ops.add(LiteralReplacement(ref))
                        }
                    }
                    position += ref.length
                    start = position
                } else {
                    position++
                }
            } else {
                position++
            }
        }
        if (start != position) {
            ops.add(LiteralReplacement(replacementTemplate.substring(start, position)))
        }
        return ops
    }

    fun <T> getSubstitution(
        cx: Context,
        scope: Scriptable,
        matched: String,
        str: String,
        position: Int,
        capturesList: List<T>,
        namedCaptures: Any?,
        replacementTemplate: List<ReplacementOperation>,
    ): String {
        if (position > str.length) throw Kit.codeBug()
        val result = StringBuilder()
        for (op in replacementTemplate) {
            result.append(op.replacement(cx, scope, matched, str, position, capturesList, namedCaptures))
        }
        return result.toString()
    }

    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

    abstract class ReplacementOperation {
        abstract fun <T> replacement(
            cx: Context,
            scope: Scriptable,
            matched: String,
            str: String,
            position: Int,
            captures: List<T>,
            namedCaptures: Any?,
        ): String
    }

    private class LiteralReplacement(private val replacement: String) : ReplacementOperation() {
        override fun <T> replacement(cx: Context, scope: Scriptable, matched: String, str: String, position: Int, captures: List<T>, namedCaptures: Any?): String =
            replacement
    }

    private class OneDigitCaptureReplacement(private val capture: Int) : ReplacementOperation() {
        override fun <T> replacement(cx: Context, scope: Scriptable, matched: String, str: String, position: Int, captures: List<T>, namedCaptures: Any?): String {
            if (capture >= 1 && capture <= captures.size) {
                val v = captures[capture - 1]
                return if (v == null || v === Undefined.instance) "" else v.toString()
            }
            return "$" + capture.toString()
        }
    }

    private class TwoDigitCaptureReplacement(private val capture: Int) : ReplacementOperation() {
        override fun <T> replacement(cx: Context, scope: Scriptable, matched: String, str: String, position: Int, captures: List<T>, namedCaptures: Any?): String {
            var i = capture
            if (i > 9 && i > captures.size && i / 10 <= captures.size) {
                i /= 10 // Only the first digit names a capture; the second is literal.
                val v = captures[i - 1]
                return if (v == null || v === Undefined.instance) {
                    "" + (capture % 10).toString()
                } else {
                    v.toString() + (capture % 10).toString()
                }
            } else if (i >= 1 && i <= captures.size) {
                val v = captures[i - 1]
                return if (v == null || v === Undefined.instance) "" else v.toString()
            }
            return (if (capture >= 10) "$" else "$0") + capture.toString()
        }
    }

    private class FromStartToMatchReplacement : ReplacementOperation() {
        override fun <T> replacement(cx: Context, scope: Scriptable, matched: String, str: String, position: Int, captures: List<T>, namedCaptures: Any?): String =
            str.substring(0, position)
    }

    private class MatchedReplacement : ReplacementOperation() {
        override fun <T> replacement(cx: Context, scope: Scriptable, matched: String, str: String, position: Int, captures: List<T>, namedCaptures: Any?): String =
            matched
    }

    private class FromMatchToEndReplacement : ReplacementOperation() {
        override fun <T> replacement(cx: Context, scope: Scriptable, matched: String, str: String, position: Int, captures: List<T>, namedCaptures: Any?): String {
            val matchLength = matched.length
            val tailPos = position + matchLength
            return str.substring(minOf(str.length, tailPos))
        }
    }

    private class NamedCaptureReplacement(val groupName: String) : ReplacementOperation() {
        override fun <T> replacement(cx: Context, scope: Scriptable, matched: String, str: String, position: Int, captures: List<T>, namedCaptures: Any?): String {
            if (Undefined.isUndefined(namedCaptures)) {
                val ops = buildReplacementList(groupName)
                return "$<" + getSubstitution(cx, scope, matched, str, position, captures, namedCaptures, ops) + ">"
            }
            val capture = ScriptRuntime.getObjectProp(namedCaptures, groupName, cx, scope)
            return if (Undefined.isUndefined(capture)) "" else ScriptRuntime.toString(capture)
        }
    }
}
