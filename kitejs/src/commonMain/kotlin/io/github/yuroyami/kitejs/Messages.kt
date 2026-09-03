/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * KMP replacement for the ResourceBundle + MessageFormat pipeline (ledger D-4).
 *
 * English only. Keys are added as the code that uses them gets ported; values are copied
 * verbatim from upstream Messages.properties. Formatting supports the two MessageFormat
 * features those values use: `{n}` argument slots and doubled quotes for a literal quote.
 */
internal object Messages {

    fun getMessageById(messageId: String, vararg args: Any?): String {
        val pattern = en[messageId]
            ?: throw RuntimeException("no message resource found for message property $messageId")
        var out = pattern
        args.forEachIndexed { i, arg -> out = out.replace("{$i}", arg?.toString() ?: "null") }
        return out.replace("''", "'")
    }

    private val en: Map<String, String> = mapOf(
        "msg.invalid.escape" to "invalid Unicode escape sequence",
        "msg.illegal.character" to "illegal character: {0}",
        "msg.caught.nfe" to "number format error",
        "msg.bad.octal.literal" to "illegal octal literal digit {0}; interpreting it as a decimal digit",
        "msg.missing.exponent" to "missing exponent",
        "msg.unterminated.string.lit" to "unterminated string literal",
        "msg.unterminated.comment" to "unterminated comment",
        "msg.unterminated.re.lit" to "unterminated regular expression literal",
        "msg.invalid.re.flag" to "invalid flag ''{0}'' after regular expression",
        "msg.unexpected.eof" to "Unexpected end of file",
        "msg.syntax" to "syntax error",
        "msg.got.syntax.errors" to "Compilation produced {0} syntax errors.",
    )
}
