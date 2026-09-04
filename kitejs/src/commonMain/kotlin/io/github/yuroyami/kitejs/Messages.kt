/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * KMP replacement for the ResourceBundle + MessageFormat pipeline (ledger D-4).
 *
 * English only. Keys are added as the code that uses them gets ported; values are copied
 * verbatim from upstream Messages.properties.
 *
 * [format] implements the MessageFormat rules those values rely on: `{n}` argument slots, a
 * single-quoted section that passes its text through literally, and a doubled quote that stands
 * for one literal quote. A slot with no matching argument is left as written, which is what
 * MessageFormat does with a short or null argument array.
 */
internal object Messages {

    fun getMessageById(messageId: String, vararg args: Any?): String {
        val pattern = en[messageId]
            ?: throw RuntimeException("no message resource found for message property $messageId")
        return format(pattern, args)
    }

    private fun format(pattern: String, args: Array<out Any?>): String {
        val out = StringBuilder(pattern.length + 16)
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\'' -> i = appendQuoted(pattern, i, out)
                c == '{' -> i = appendArgument(pattern, i, args, out)
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Handles a doubled quote or a quoted section, and returns the index just past it. */
    private fun appendQuoted(pattern: String, start: Int, out: StringBuilder): Int {
        var i = start
        if (i + 1 < pattern.length && pattern[i + 1] == '\'') {
            out.append('\'')
            return i + 2
        }
        i++ // skip the opening quote
        while (i < pattern.length) {
            if (pattern[i] == '\'') {
                if (i + 1 < pattern.length && pattern[i + 1] == '\'') {
                    out.append('\'')
                    i += 2
                } else {
                    return i + 1 // closing quote
                }
            } else {
                out.append(pattern[i])
                i++
            }
        }
        return i
    }

    /** Handles a `{n}` slot, and returns the index just past it. */
    private fun appendArgument(
        pattern: String,
        start: Int,
        args: Array<out Any?>,
        out: StringBuilder,
    ): Int {
        var j = start + 1
        while (j < pattern.length && pattern[j].isDigit()) j++
        if (j == start + 1 || j >= pattern.length || pattern[j] != '}') {
            // Not an argument slot: emit the brace as written.
            out.append(pattern[start])
            return start + 1
        }
        val index = pattern.substring(start + 1, j).toInt()
        if (index < args.size) {
            out.append(args[index]?.toString() ?: "null")
        } else {
            // MessageFormat leaves a slot with no argument exactly as it was written.
            out.append(pattern, start, j + 1)
        }
        return j + 1
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

        // Phase 2 keys.
        "msg.bad.radix" to "illegal radix {0}.",

        // Parser keys (phase 1).
        "msg.XML.not.available" to "XML runtime not available",
        "msg.anon.generator.returns" to "anonymous generator function returns a value",
        "msg.anon.no.return.value" to "anonymous function does not always return a value",
        "msg.arrowfunction.generator" to "arrow function can not become generator",
        "msg.bad.assign.left" to "Invalid assignment left-hand side.",
        "msg.bad.break" to "unlabelled break must be inside loop or switch",
        "msg.bad.catchcond" to "invalid catch block condition",
        "msg.bad.computed.property.in.destruct" to "Unsupported computed property in destructuring.",
        "msg.bad.decr" to "Invalid decrement operand.",
        "msg.bad.id.strict" to "\"{0}\" is not a valid identifier for this use in strict mode.",
        "msg.bad.incr" to "Invalid increment operand.",
        "msg.bad.namespace" to "not a valid default namespace statement. Syntax is: default xml namespace = EXPRESSION;",
        "msg.bad.object.init" to "invalid object initializer",
        "msg.bad.prop" to "invalid property id",
        "msg.bad.return" to "invalid return",
        "msg.bad.switch" to "invalid switch statement",
        "msg.bad.throw.eol" to "Line terminator is not allowed between the throw keyword and throw expression.",
        "msg.bad.var" to "missing variable name",
        "msg.bad.yield" to "yield must be in a function.",
        "msg.catch.destructuring.requires.es6" to "Destructuring in catch blocks requires ES6 or later",
        "msg.catch.unreachable" to "any catch clauses following an unqualified catch are unreachable",
        "msg.const.redecl" to "redeclaration of const {0}.",
        "msg.continue.nonloop" to "continue can only use labeles of iteration statements",
        "msg.continue.outside" to "continue must be inside loop",
        "msg.default.args" to "Default values are only supported in version >= 200",
        "msg.default.args.use.strict" to "A function cannot have \"use strict\" directive with default arguments",
        "msg.destruct.assign.no.init" to "Missing = in destructuring declaration",
        "msg.double.switch.default" to "double default label in the switch statement",
        "msg.dup.label" to "duplicated label",
        "msg.dup.obj.lit.prop.strict" to "Property \"{0}\" already defined in this object literal.",
        "msg.dup.param.strict" to "Parameter \"{0}\" already declared in this function.",
        "msg.dup.parms" to "Duplicate parameter name \"{0}\".",
        "msg.equal.as.assign" to "Test for equality (==) mistyped as assignment (=)?",
        "msg.extra.trailing.comma" to "Trailing comma is not legal in an ECMA-262 object initializer",
        "msg.extra.trailing.semi" to "Extraneous trailing semicolon",
        "msg.fn.redecl" to "redeclaration of function {0}.",
        "msg.generator.returns" to "generator function {0} returns a value",
        "msg.in.after.for.name" to "missing in after for",
        "msg.invalid.for.each" to "invalid for each loop",
        "msg.let.decl.not.in.block" to "let declaration not directly within block",
        "msg.let.redecl" to "redeclaration of variable {0}.",
        "msg.missing.semi" to "missing ; after statement",
        "msg.mult.index" to "Only one variable allowed in for..in loop.",
        "msg.no.brace.after.body" to "missing } after function body",
        "msg.no.brace.block" to "missing } in compound statement",
        "msg.no.brace.body" to "missing '{' before function body",
        "msg.no.brace.catchblock" to "missing '{' before catch-block body",
        "msg.no.brace.prop" to "missing } after property list",
        "msg.no.brace.switch" to "missing '{' before switch body",
        "msg.no.brace.try" to "missing '{' before try block",
        "msg.no.bracket.arg" to "missing ] after element list",
        "msg.no.bracket.index" to "missing ] in index expression",
        "msg.no.colon.case" to "missing : after case expression",
        "msg.no.colon.cond" to "missing : in conditional expression",
        "msg.no.colon.prop" to "missing : after property id",
        "msg.no.curly.let" to "missing } after let statement",
        "msg.no.name.after.coloncolon" to "missing name after :: operator",
        "msg.no.name.after.dot" to "missing name after . operator",
        "msg.no.object.rest" to "object rest properties in destructuring are not supported",
        "msg.no.old.octal.bigint" to "Old octal numbers prohibited in BigInt.",
        "msg.no.old.octal.strict" to "Old octal numbers prohibited in strict mode.",
        "msg.no.paren" to "missing ) in parenthetical",
        "msg.no.paren.after.cond" to "missing ) after condition",
        "msg.no.paren.after.let" to "missing ( after let",
        "msg.no.paren.after.parms" to "missing ) after formal parameters",
        "msg.no.paren.after.switch" to "missing ) after switch expression",
        "msg.no.paren.after.with" to "missing ) after with-statement object",
        "msg.no.paren.arg" to "missing ) after argument list",
        "msg.no.paren.catch" to "missing ( before catch-block condition",
        "msg.no.paren.cond" to "missing ( before condition",
        "msg.no.paren.for" to "missing ( after for",
        "msg.no.paren.for.ctrl" to "missing ) after for-loop control",
        "msg.no.paren.let" to "missing ) after variable list",
        "msg.no.paren.parms" to "missing ( before function parameters.",
        "msg.no.paren.switch" to "missing ( before switch expression",
        "msg.no.paren.with" to "missing ( before with-statement object",
        "msg.no.parm" to "missing formal parameter",
        "msg.no.return.value" to "function {0} does not always return a value",
        "msg.no.semi.for" to "missing ; after for-loop initializer",
        "msg.no.semi.for.cond" to "missing ; after for-loop condition",
        "msg.no.semi.stmt" to "missing ; before statement",
        "msg.no.side.effects" to "Code has no side effects",
        "msg.no.unary.expr.on.left.exp" to "\"{0}\" is not allowed on the left-hand side of \"**\".",
        "msg.no.while.do" to "missing while after do-loop body",
        "msg.no.with.strict" to "with statements not allowed in strict mode",
        "msg.nullish.bad.token" to "Syntax Error: Unexpected token.",
        "msg.optional.super" to "super is not allowed in an optional chaining expression",
        "msg.parm.after.rest" to "parameter after rest parameter",
        "msg.parm.redecl" to "redeclaration of formal parameter {0}.",
        "msg.reserved.id" to "identifier is a reserved word: {0}",
        "msg.return.inconsistent" to "return statement is inconsistent with previous usage",
        "msg.super.shorthand.function" to "super should be inside a shorthand function",
        "msg.syntax.invalid.assignment.lhs" to "syntax error: Invalid left-hand side in assignment",
        "msg.too.many.constructor.args" to "Too many constructor arguments",
        "msg.too.many.function.args" to "Too many function arguments",
        "msg.try.no.catchfinally" to "''try'' without ''catch'' or ''finally''",
        "msg.undef.label" to "undefined label",
        "msg.var.hides.arg" to "Variable {0} hides argument",
        "msg.var.redecl" to "redeclaration of var {0}.",
        "msg.yield.parenthesized" to "yield expression must be parenthesized.",
    )
}
