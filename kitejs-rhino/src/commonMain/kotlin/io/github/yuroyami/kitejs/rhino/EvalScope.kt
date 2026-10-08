/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import io.github.yuroyami.kitejs.rhino.ast.FunctionNode

/** PerformEval's declarative environment and the variable environment it declares into. */
internal class EvalScope(outer: Scriptable, private val strictEval: Boolean) : NativeObject() {
    internal val variableScope: Scriptable = if (strictEval) this else variableEnvironment(outer)
    private val lexicalNames = mutableSetOf<String>()

    init {
        parentScope = outer
        prototype = null
    }

    internal fun instantiate(cx: Context, desc: JSDescriptor<*>) {
        val varNames = (0 until desc.paramAndVarCount).filter { !desc.isLexicalDeclaration(it) }
            .map { desc.getParamOrVarName(it) }.toSet()
        if (!strictEval) {
            for (name in varNames) checkDeclarationConflict(parentScope!!, variableScope, name)
            if (variableScope.parentScope == null) {
                val global = variableScope as? ScriptableObject
                for (name in varNames) {
                    if (global != null && !global.has(name, global) && !global.isExtensible) globalDeclarationError(name)
                }
                for (i in 0 until desc.functionCount) {
                    val fn = desc.getFunction(i)
                    if (fn.functionType != FunctionNode.FUNCTION_STATEMENT) continue
                    val current = global?.getOwnPropertyDescriptor(cx, fn.functionName)
                    if (current != null && !ScriptableObject.isTrue(current.configurable) &&
                        (current.isAccessorDescriptor || !ScriptableObject.isTrue(current.writable) ||
                            !ScriptableObject.isTrue(current.enumerable))) globalDeclarationError(fn.functionName)
                }
            }
        }
        // Validate every declaration before installing any of them.
        for (name in varNames) {
            if (!variableScope.has(name, variableScope)) {
                ScriptableObject.defineProperty(variableScope, name, Undefined.instance, if (strictEval) PERMANENT else 0)
            }
        }
        for (i in 0 until desc.paramAndVarCount) {
            if (!desc.isLexicalDeclaration(i)) continue
            val name = desc.getParamOrVarName(i)
            lexicalNames.add(name)
            if (desc.getParamOrVarConst(i)) ScriptableObject.defineConstProperty(this, name)
            else defineProperty(name, Undefined.instance, PERMANENT)
        }
    }

    internal fun initializeFunction(cx: Context, name: String, function: JSFunction) {
        val global = variableScope as? ScriptableObject
        if (!strictEval && global != null && global.parentScope == null) {
            val current = global.getOwnPropertyDescriptor(cx, name)
            val configurable = current == null || ScriptableObject.isTrue(current.configurable)
            if (!AbstractEcmaObjectOperations.defineOwnPropertyOrFalse(cx, global, name,
                    ScriptableObject.DescriptorInfo(true, true, configurable, function))) globalDeclarationError(name)
        } else variableScope.put(name, variableScope, function)
    }

    internal companion object {
        private val GLOBAL_LEXICAL_NAMES = Any()
        private class GlobalLexicalNames(val names: MutableSet<String> = mutableSetOf())

        private fun variableEnvironment(scope: Scriptable): Scriptable = when (scope) {
            is EvalScope -> scope.variableScope
            is NativeWith -> variableEnvironment(scope.parentScope!!)
            is NativeCall -> scope.parameterVariableScope ?: scope
            else -> scope
        }

        private fun checkDeclarationConflict(start: Scriptable, end: Scriptable, name: String) {
            var scope: Scriptable? = start
            while (scope != null) {
                val conflicts = when (scope) {
                    is EvalScope -> name in scope.lexicalNames
                    is NativeWith -> !scope.isObjectEnvironment && scope.prototype!!.has(name, scope.prototype!!)
                    is NativeCall -> if (scope.isFormalParameterEnvironment) {
                        scope.function!!.descriptor.let { it.hasParameterExpressions && name in it.parameterBindingNames }
                    } else !scope.isParameterEnvironment && scope.function?.descriptor?.hasLexicalDeclaration(name) == true
                    is ScriptableObject -> (scope.getAssociatedValue(GLOBAL_LEXICAL_NAMES) as? GlobalLexicalNames)?.names?.contains(name) == true
                    else -> false
                }
                if (conflicts) declarationError(name)
                if (scope === end) break
                scope = scope.parentScope
            }
        }

        private fun declarationError(name: String): Nothing =
            throw ScriptRuntime.constructError("SyntaxError", "Identifier '$name' has already been declared or cannot be declared")

        private fun globalDeclarationError(name: String): Nothing =
            throw ScriptRuntime.typeError("Cannot declare global binding '$name'")

        internal fun recordGlobalLexicalNames(scope: Scriptable, desc: JSDescriptor<*>) {
            if (scope.parentScope != null || scope !is ScriptableObject) return
            val names = (0 until desc.paramAndVarCount).filter { desc.isLexicalDeclaration(it) }
                .map { desc.getParamOrVarName(it) }
            if (names.isEmpty()) return
            val registry = scope.associateValue(GLOBAL_LEXICAL_NAMES, GlobalLexicalNames()) as GlobalLexicalNames
            registry.names.addAll(names)
        }
    }
}
