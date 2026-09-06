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
            val arg = args[index]
            out.append(if (arg is Number) JavaNumbers.messageFormat(arg) else arg?.toString() ?: "null")
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

        // The object model (phase 3.3).
        "msg.arg.not.object" to "Expected argument of type object, but instead had type {0}",
        "msg.bad.getter.parms" to "In order to define a property, getter {0} must have zero parameters or a single ScriptableObject parameter.",
        "msg.both.data.and.accessor.desc" to "Cannot be both a data and an accessor descriptor.",
        "msg.change.configurable.false.to.true" to "Cannot change the configurable attribute of \"{0}\" from false to true.",
        "msg.change.enumerable.with.configurable.false" to "Cannot change the enumerable attribute of \"{0}\" because configurable is false.",
        "msg.change.getter.with.configurable.false" to "Cannot change the get attribute of \"{0}\" because configurable is false.",
        "msg.change.property.data.to.accessor.with.configurable.false" to "Cannot change \"{0}\" from a data property to an accessor property because configurable is false.",
        "msg.change.setter.with.configurable.false" to "Cannot change the set attribute of \"{0}\" because configurable is false.",
        "msg.change.value.with.writable.false" to "Cannot change the value of attribute \"{0}\" because writable is false.",
        "msg.change.writable.false.to.true.with.configurable.false" to "Cannot change the writable attribute of \"{0}\" from false to true because configurable is false.",
        "msg.constructor.no.function" to "The constructor for {0} may not be invoked as a function",
        "msg.ctor.multiple.parms" to "Can''t define constructor or class {0} since more than one constructor has multiple parameters.",
        "msg.default.value" to "Cannot find default value for object.",
        "msg.delete.property.with.configurable.false" to "Cannot delete \"{0}\" property because configurable is false.",
        "msg.extend.scriptable" to "{0} must extend ScriptableObject in order to define property {1}.",
        "msg.incompat.call.details" to "Method \"{0}\" called on incompatible object ({1} is not an instance of {2}).",
        "msg.instanceof.bad.prototype" to "''prototype'' property of ''{0}'' is not an object.",
        "msg.instanceof.bad.target" to "Target of ``instanceof`` must be callable or have ``[Symbol.hasInstance]`` method.",
        "msg.method.not.found" to "Method \"{0}\" not found in \"{1}\".",
        "msg.modify.readonly" to "Cannot modify readonly property: {0}.",
        "msg.modify.sealed" to "Cannot modify a property of a sealed object: {0}.",
        "msg.no.new" to "{0} objects may not be constructed using \"new\"",
        "msg.not.ctor" to "\"{0}\" is not a constructor.",
        "msg.not.extensible" to "Cannot add properties to this object because extensible is false.",
        "msg.obj.getter.parms" to "Expected static or delegated getter {0} to take a ScriptableObject parameter.",
        "msg.object.not.symbolscriptable" to "Object {0} does not support Symbol keys",
        "msg.prop.not.found" to "Property {0} not found.",
        "msg.set.prop.no.setter" to "Cannot set property {0} that has only a getter to value ''{1}''.",
        "msg.setter.parms" to "Expected either one or two parameters for setter.",
        "msg.setter.return" to "Setter must have void return type: {0}",
        "msg.setter1.parms" to "Expected single parameter setter for {0}",
        "msg.setter2.expected" to "Expected static or delegated setter {0} to take two parameters.",
        "msg.setter2.parms" to "Two-parameter setter must take a ScriptableObject as its first parameter.",
        "msg.this.not.instance" to "\"this\" is not an instance of class {0}",
        "msg.varargs.ctor" to "Method or constructor \"{0}\" must be static with the signature \"(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)\" to define a variable arguments constructor.",
        "msg.varargs.fun" to "Method \"{0}\" must be static with the signature \"(Context cx, Scriptable thisObj, Object[] args, Function funObj)\" to define a variable arguments function.",
        "msg.zero.arg.ctor" to "Cannot load class \"{0}\" which has no zero-parameter constructor.",
        "msg.not.a.string" to "The object is not a string",
        "msg.bad.default.value" to "Object''s getDefaultValue() method returned an object.",
        "msg.cant.convert.to.primitive" to "Cannot convert object to primitive value.",
        "msg.invalid.type" to "Invalid JavaScript value of type {0}",
        "msg.function.not.found" to "Cannot find function {0}.",
        "msg.isnt.function" to "{0} is not a function, it is {1}.",
        "msg.ctor.not.found" to "Constructor for \"{0}\" not found.",
        "msg.primitive.expected" to "Primitive type expected (had {0} instead)",
        "msg.cant.convert.to.number" to "Cannot convert {0} to a number",
        "msg.not.a.number" to "The object is not a number",
        "msg.only.from.new" to "\"Constructor {0}\" may only be invoked from a \"new\" expression.",
        "msg.null.to.object" to "Cannot convert null to an object.",
        "msg.undef.to.object" to "Cannot convert undefined to an object.",
        "msg.arg.isnt.array" to "second argument to Function.prototype.apply must be an array",
        "msg.undef.method.call" to "Cannot call method \"{1}\" of {0}",
        "msg.deprec.ctor" to "The \"{0}\" constructor is deprecated.",
        "msg.op.not.allowed" to "This operation is not allowed.",
        "msg.no.regexp" to "Regular expressions are not available.",
        // The interpreter (phase 3.7).
        "msg.cant.call.indirect" to "Function \"{0}\" must be called directly, and not by way of a function of another name.",
        "msg.eval.nonstring" to "Calling eval() with anything other than a primitive string value will simply return the value. Is this what you intended?",
        "msg.eval.nonstring.strict" to "Calling eval() with anything other than a primitive string value is not allowed in strict mode.",
        "msg.invalid.iterator" to "Invalid iterator value",
        "msg.iterator.primitive" to "__iterator__ returned a primitive value",
        "msg.not.iterable" to "{0} is not iterable",
        "msg.assn.create.strict" to "Assignment to undeclared variable {0}",
        "msg.ref.undefined.prop" to "Reference to undefined property \"{0}\"",
        "msg.cant.convert.to.bigint" to "Cannot convert {0} to an BigInt.",
        "msg.cant.convert.to.bigint.isnt.integer" to "Cannot convert {0} to an BigInt. It isn't an integer.",
        "msg.bigint.bad.form" to "illegally formed BigInt syntax",
        "msg.cyclic.value" to "Cyclic {0} value not allowed.",
        "msg.is.not.defined" to "\"{0}\" is not defined.",
        "msg.undef.prop.read" to "Cannot read property \"{1}\" from {0}",
        "msg.undef.prop.write" to "Cannot set property \"{1}\" of {0} to \"{2}\"",
        "msg.undef.prop.delete" to "Cannot delete property \"{1}\" of {0}",
        "msg.undef.with" to "Cannot apply \"with\" to {0}",
        "msg.isnt.function.in" to "Cannot call property {0} in object {1}. It is not a function, it is \"{2}\".",
        "msg.function.not.found.in" to "Cannot find function {0} in object {1}.",
        "msg.no.ref.from.function" to "Function {0} can not be used as the left-hand side of assignment or as an operand of ++ or -- operator.",
        "msg.instanceof.not.object" to "Can''t use ''instanceof'' on a non-object.",
        "msg.in.not.object" to "Can''t use ''in'' on a non-object.",
        "msg.division.zero" to "Division by zero.",
        "msg.bigint.negative.exponent" to "BigInt negative exponent.",
        "msg.bigint.out.of.range.arithmetic" to "BigInt is too large.",
        "msg.yield.closing" to "Yield from closing generator",
        "msg.compare.symbol" to "Symbol objects may not be compared",
        "msg.bad.destruct.op" to "Invalid destructuring assignment operator",
        "msg.bad.esc.mask" to "invalid string escape mask",
        "msg.bad.for.in.destruct" to "Left hand side of for..in loop must be an array of length 2 to accept key/value pair.",
        "msg.bad.for.in.lhs" to "Invalid left-hand side of for..in loop.",
        "msg.bad.precision" to "Precision {0} out of range.",
        "msg.bad.uri" to "Malformed URI sequence.",
        "msg.called.null.or.undefined" to "{0}.prototype.{1} method called on null or undefined",
        "msg.iterable.expected" to "Expected the first argument to be iterable",
        "msg.method.missing.parameter" to "{0}: At least {1} arguments required, but only {2} passed",
        "msg.object.cyclic.prototype" to "Cyclic prototype \"{0}\" value not allowed.",
        "msg.script.is.not.constructor" to "Script objects are not constructors.",
        "msg.arraylength.bad" to "Inappropriate array length.",
        "msg.arraylength.too.big" to "Array length {0} exceeds supported capacity limit.",
        "msg.empty.array.reduce" to "Reduce of empty array with no initial value",
        "msg.map.function.not" to "Map function is not actually a function",
        "msg.typed.array.out.of.bounds" to "TypedArray is out of bounds",
        "msg.first.arg.not.regexp" to "First argument to {0}.prototype.{1} must not be a regular expression",
        "msg.json.cant.serialize" to "Do not know how to serialize a {0}",
        "msg.str.match.all.no.global.flag" to "String.prototype.matchAll called with a non-global RegExp argument",
        "msg.str.replace.all.no.global.flag" to "replaceAll must be called with a global RegExp",
        "msg.no.assign.symbol.strict" to "Symbol objects may not be assigned properties in strict mode",
        "msg.generator.executing" to "The generator is still executing from a previous invocation.",
        "msg.no.properties" to "{0} has no properties.",
        "msg.send.newborn" to "Attempt to send value to newborn generator",
        "msg.bad.backref" to "back-reference exceeds number of capturing parentheses.",
        "msg.bad.quant" to "Invalid quantifier {0}",
        "msg.bad.range" to "Invalid range in character class.",
        "msg.bad.regexp.compile" to "Only one argument may be specified if the first argument to RegExp.prototype.compile is a RegExp object.",
        "msg.duplicate.group.name" to "Duplicate capture group name \"{0}\"",
        "msg.incompat.call" to "Method \"{0}\" called on incompatible object.",
        "msg.invalid.class" to "Invalid character class",
        "msg.invalid.group.name" to "Invalid capture group name",
        "msg.invalid.named.backref" to "Invalid named capture referenced",
        "msg.invalid.property" to "Invalid property name",
        "msg.lone.quantifier.bracket" to "Lone quantifier brackets",
        "msg.max.lt.min" to "Invalid regular expression: The quantifier maximum ''{0}'' is less than the minimum ''{1}''.",
        "msg.overlarge.backref" to "Overly large back reference {0}",
        "msg.overlarge.max" to "Overly large maximum {0}",
        "msg.overlarge.min" to "Overly large minimum {0}",
        "msg.re.unmatched.right.paren" to "unmatched ) in regular expression.",
        "msg.trail.backslash" to "Trailing \\ in regular expression.",
        "msg.unterm.class" to "Unterminated character class {0}",
        "msg.unterm.paren" to "Unterminated parenthetical {0}",
    )
}
