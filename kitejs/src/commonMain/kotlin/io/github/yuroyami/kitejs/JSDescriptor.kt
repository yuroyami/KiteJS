/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Everything known about a compiled script or function that does not change per call: its code,
 * its names, its flags and its nested functions. Built once by the code generator through
 * [Builder], then shared by every function object made from it.
 *
 * Upstream also implements the debugger's `DebuggableScript` here. That interface is not ported;
 * the same members exist as plain methods.
 */
class JSDescriptor<T : ScriptOrFn<T>> private constructor(
    val code: JSCode<T>?,
    val constructor: JSCode<T>?,
    val parent: JSDescriptor<*>?,
    private val paramAndVarNames: Array<String>,
    private val paramIsConst: BooleanArray,
    private val flags: Int,
    val sourceName: String?,
    private val wholeSource: String?,
    private val rawSourceStart: Int,
    private val rawSourceEnd: Int,
    val name: String,
    val languageVersion: Int,
    val paramAndVarCount: Int,
    val paramCount: Int,
    val arity: Int,
    val securityDomain: Any?,
    val functionType: Int,
) {

    var nestedFunctions: List<JSDescriptor<JSFunction>>? = null
        private set

    val isStrict: Boolean get() = (flags and IS_STRICT_FLAG) != 0
    val isScript: Boolean get() = (flags and IS_SCRIPT_FLAG) != 0
    val isTopLevel: Boolean get() = (flags and IS_TOP_LEVEL_FLAG) != 0
    val isFunction: Boolean get() = functionType != 0
    val isES6Generator: Boolean get() = (flags and IS_ES6_GENERATOR_FLAG) != 0
    val isShorthand: Boolean get() = (flags and IS_SHORTHAND_FLAG) != 0
    val hasPrototype: Boolean get() = (flags and HAS_PROTOTYPE_FLAG) != 0
    val hasLexicalThis: Boolean get() = (flags and HAS_LEXICAL_THIS_FLAG) != 0
    val isEvalFunction: Boolean get() = (flags and IS_EVAL_FUNCTION_FLAG) != 0
    val hasRestArg: Boolean get() = (flags and HAS_REST_ARG_FLAG) != 0
    val hasDefaultParameters: Boolean get() = (flags and HAS_DEFAULT_PARAMETERS_FLAG) != 0
    val requiresActivationFrame: Boolean get() = (flags and REQUIRES_ACTIVATION_FRAME_FLAG) != 0
    val requiresArgumentObject: Boolean get() = (flags and REQUIRES_ARGUMENT_OBJECT_FLAG) != 0
    val declaredAsFunctionExpression: Boolean get() = (flags and DECLARED_AS_FUNCTION_EXPRESSION_FLAG) != 0

    val rawSource: String get() = wholeSource!!.substring(rawSourceStart, rawSourceEnd)

    fun getParamOrVarConst(index: Int): Boolean = paramIsConst[index]

    fun getParamOrVarName(index: Int): String = paramAndVarNames[index]

    /** False when a nested function declaration (not expression) is named [name]. */
    fun hasFunctionNamed(name: String): Boolean {
        for (f in 0 until functionCount) {
            val functionData = getFunction(f)
            if (!functionData.declaredAsFunctionExpression && name == functionData.name) return false
        }
        return true
    }

    val functionCount: Int get() = nestedFunctions?.size ?: 0

    fun getFunction(index: Int): JSDescriptor<JSFunction> = nestedFunctions!![index]

    val functionName: String
        get() = name

    val isGeneratedScript: Boolean
        get() = ScriptRuntime.isGeneratedScript(sourceName!!)

    val lineNumbers: IntArray get() = Interpreter.getLineNumbers(this)

    /** Collects what the code generator learns, then makes the descriptor. */
    class Builder<T : ScriptOrFn<T>> {
        var code: JSCode.Builder<T>? = null
        var constructor: JSCode.Builder<T>? = null
        var parent: Builder<*>? = null
        val nestedFunctions = ArrayList<Builder<JSFunction>>()
        var paramAndVarNames: Array<String>? = null
        var paramIsConst: BooleanArray? = null
        var isStrict = false
        var isScript = false
        var isTopLevel = false
        var isES6Generator = false
        var isShorthand = false
        var hasPrototype = false
        var hasLexicalThis = false
        var isEvalFunction = false
        var hasRestArg = false
        var sourceFile: String? = null
        var rawSource: String? = null
        var rawSourceStart = 0
        var rawSourceEnd = 0
        var name: String? = null
        var languageVersion = 0
        var paramAndVarCount = 0
        var paramCount = 0
        var arity = 0
        var hasDefaultParameters = false
        var requiresActivationFrame = false
        var requiresArgumentObject = false
        var declaredAsFunctionExpression = false
        var securityDomain: Any? = null
        var functionType = 0

        constructor()

        private constructor(parent: Builder<*>) {
            this.parent = parent
            languageVersion = parent.languageVersion
            rawSource = parent.rawSource
            sourceFile = parent.sourceFile
            isStrict = parent.isStrict
            securityDomain = parent.securityDomain
        }

        fun createChildBuilder(): Builder<JSFunction> {
            val child = Builder<JSFunction>(this)
            nestedFunctions.add(child)
            return child
        }

        /** Builds the whole tree. [consumer] sees each descriptor as it is made, parent first. */
        fun build(consumer: (JSDescriptor<*>) -> Unit): JSDescriptor<T> {
            check(parent == null)
            return build(null, consumer)
        }

        private fun build(parent: JSDescriptor<*>?, consumer: (JSDescriptor<*>) -> Unit): JSDescriptor<T> {
            var f = 0
            f = f or (if (isStrict) IS_STRICT_FLAG else 0)
            f = f or (if (isScript) IS_SCRIPT_FLAG else 0)
            f = f or (if (isTopLevel) IS_TOP_LEVEL_FLAG else 0)
            f = f or (if (isES6Generator) IS_ES6_GENERATOR_FLAG else 0)
            f = f or (if (isShorthand) IS_SHORTHAND_FLAG else 0)
            f = f or (if (hasPrototype) HAS_PROTOTYPE_FLAG else 0)
            f = f or (if (hasLexicalThis) HAS_LEXICAL_THIS_FLAG else 0)
            f = f or (if (isEvalFunction) IS_EVAL_FUNCTION_FLAG else 0)
            f = f or (if (hasRestArg) HAS_REST_ARG_FLAG else 0)
            f = f or (if (hasDefaultParameters) HAS_DEFAULT_PARAMETERS_FLAG else 0)
            f = f or (if (requiresActivationFrame) REQUIRES_ACTIVATION_FRAME_FLAG else 0)
            f = f or (if (requiresArgumentObject) REQUIRES_ARGUMENT_OBJECT_FLAG else 0)
            f = f or (if (declaredAsFunctionExpression) DECLARED_AS_FUNCTION_EXPRESSION_FLAG else 0)

            val result = JSDescriptor<T>(
                code!!.build(),
                constructor!!.build(),
                parent,
                paramAndVarNames!!,
                paramIsConst!!,
                f,
                sourceFile,
                rawSource,
                rawSourceStart,
                rawSourceEnd,
                name ?: "",
                languageVersion,
                paramAndVarCount,
                paramCount,
                arity,
                securityDomain,
                functionType,
            )
            consumer(result)
            result.nestedFunctions = nestedFunctions.map { it.build(result, consumer) }
            return result
        }
    }

    private companion object {
        const val IS_STRICT_FLAG = 1
        const val IS_SCRIPT_FLAG = 1 shl 1
        const val IS_TOP_LEVEL_FLAG = 1 shl 2
        const val IS_ES6_GENERATOR_FLAG = 1 shl 3
        const val IS_SHORTHAND_FLAG = 1 shl 4
        const val HAS_PROTOTYPE_FLAG = 1 shl 5
        const val HAS_LEXICAL_THIS_FLAG = 1 shl 6
        const val IS_EVAL_FUNCTION_FLAG = 1 shl 7
        const val HAS_REST_ARG_FLAG = 1 shl 8
        const val HAS_DEFAULT_PARAMETERS_FLAG = 1 shl 9
        const val REQUIRES_ACTIVATION_FRAME_FLAG = 1 shl 10
        const val REQUIRES_ARGUMENT_OBJECT_FLAG = 1 shl 11
        const val DECLARED_AS_FUNCTION_EXPRESSION_FLAG = 1 shl 12
    }
}
