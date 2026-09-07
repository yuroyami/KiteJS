/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.AstRoot
import io.github.yuroyami.kitejs.ast.Block
import io.github.yuroyami.kitejs.ast.FunctionNode
import io.github.yuroyami.kitejs.ast.Scope
import io.github.yuroyami.kitejs.ast.ScriptNode

/** Fills a [JSDescriptor.Builder] from the IR tree. Shared by every code generator. */
public object CodeGenUtils {

    public fun fillInForNestedFunction(builder: JSDescriptor.Builder<JSFunction>, parent: JSDescriptor.Builder<*>, fn: FunctionNode) {
        val fnParent = fn.parent
        if (!(fnParent is AstRoot || fnParent is Scope || fnParent is Block)) {
            builder.declaredAsFunctionExpression = true
            val isArrow = fn.functionType == FunctionNode.ARROW_FUNCTION
            builder.hasLexicalThis = isArrow
            builder.hasPrototype = !isArrow
            builder.constructor = if (!isArrow) builder.code else JSCode.NullBuilder()
        } else {
            builder.hasLexicalThis = false
            builder.hasPrototype = true
            builder.constructor = builder.code
        }
        fillInForFunction(builder, fn)
    }

    private fun fillInForFunction(builder: JSDescriptor.Builder<*>, fn: FunctionNode) {
        builder.functionType = fn.functionType
        builder.requiresActivationFrame = fn.requiresActivation
        builder.requiresArgumentObject = fn.requiresArgumentObject
        if (fn.functionName != null) builder.name = fn.name
        if (fn.isInStrictMode) builder.isStrict = true
        if (fn.isES6Generator) builder.isES6Generator = true
        if (fn.isShorthand) builder.isShorthand = true
        fillInCommon(builder, fn)
    }

    public fun fillInForTopLevelFunction(builder: JSDescriptor.Builder<*>, fn: FunctionNode, rawSource: String?, compilerEnv: CompilerEnvirons) {
        builder.hasPrototype = true
        fillInTopLevelCommon(builder, fn, rawSource, compilerEnv)
        fillInForFunction(builder, fn)
    }

    public fun fillInForScript(builder: JSDescriptor.Builder<*>, scriptOrFn: ScriptNode, rawSource: String?, compilerEnv: CompilerEnvirons) {
        builder.hasPrototype = false
        fillInTopLevelCommon(builder, scriptOrFn, rawSource, compilerEnv)
        fillInCommon(builder, scriptOrFn)
    }

    private fun fillInTopLevelCommon(builder: JSDescriptor.Builder<*>, scriptOrFn: ScriptNode, rawSource: String?, compilerEnv: CompilerEnvirons) {
        builder.sourceFile = scriptOrFn.sourceName
        builder.rawSource = rawSource
        builder.isTopLevel = true
        builder.isScript = true
        builder.isEvalFunction = compilerEnv.inEval
        builder.isStrict = scriptOrFn.isInStrictMode
        builder.hasLexicalThis = false
        builder.securityDomain = null
    }

    private fun fillInCommon(builder: JSDescriptor.Builder<*>, scriptOrFn: ScriptNode) {
        builder.paramAndVarNames = disambiguateNames(scriptOrFn.paramAndVarNames, scriptOrFn.paramCount)
        builder.paramCount = scriptOrFn.paramCount
        builder.paramIsConst = scriptOrFn.paramAndVarConst
        builder.paramAndVarCount = scriptOrFn.paramAndVarCount
        builder.hasRestArg = scriptOrFn.hasRestParameter
        builder.hasDefaultParameters = scriptOrFn.defaultParams != null
        builder.arity = FunctionNode.calculateFunctionArity(scriptOrFn)
        builder.rawSourceStart = scriptOrFn.rawSourceStart
        builder.rawSourceEnd = scriptOrFn.rawSourceEnd
    }

    public fun <T : ScriptOrFn<T>> setConstructor(builder: JSDescriptor.Builder<T>, scriptOrFn: ScriptNode) {
        if (scriptOrFn is FunctionNode) {
            val isArrow = scriptOrFn.functionType == FunctionNode.ARROW_FUNCTION
            builder.constructor =
                if (isArrow || scriptOrFn.isMethodDefinition || scriptOrFn.isGenerator) JSCode.NullBuilder()
                else builder.code
        } else {
            builder.constructor = JSCode.NullBuilder()
        }
    }

    /** A variable that shares a parameter's name gets a numbered suffix so the two stay apart. */
    private fun disambiguateNames(names: Array<String>, paramCount: Int): Array<String> {
        val checkMap = HashMap<String, Int>()
        return Array(names.size) { i ->
            val count = checkMap[names[i]] ?: 0
            checkMap[names[i]] = count + 1
            if (i >= paramCount && count > 0) "${names[i]}($count)" else names[i]
        }
    }
}
