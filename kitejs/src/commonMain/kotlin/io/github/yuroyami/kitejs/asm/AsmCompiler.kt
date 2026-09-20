/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.asm

import io.github.yuroyami.kitejs.Token
import io.github.yuroyami.kitejs.ast.ArrayLiteral
import io.github.yuroyami.kitejs.ast.AstRoot
import io.github.yuroyami.kitejs.ast.AstNode
import io.github.yuroyami.kitejs.ast.ExpressionStatement
import io.github.yuroyami.kitejs.ast.FunctionNode
import io.github.yuroyami.kitejs.ast.Name
import io.github.yuroyami.kitejs.ast.NewExpression
import io.github.yuroyami.kitejs.ast.NumberLiteral
import io.github.yuroyami.kitejs.ast.ObjectLiteral
import io.github.yuroyami.kitejs.ast.ObjectProperty
import io.github.yuroyami.kitejs.ast.ParenthesizedExpression
import io.github.yuroyami.kitejs.ast.PropertyGet
import io.github.yuroyami.kitejs.ast.ReturnStatement
import io.github.yuroyami.kitejs.ast.StringLiteral
import io.github.yuroyami.kitejs.ast.UnaryExpression
import io.github.yuroyami.kitejs.ast.VariableDeclaration
import io.github.yuroyami.kitejs.ast.VariableInitializer

/**
 * Turns a `"use asm"` function into typed code, or decides it is not asm.js and leaves it alone.
 *
 * asm.js is a subset of JavaScript that an engine can compile ahead of time, because every value
 * in it has a type that can be worked out without running anything. A module says so with the
 * directive `"use asm"` at the top of a function. The function takes up to three arguments, the
 * standard library, an object of foreign imports and an `ArrayBuffer` to use as memory, and it
 * returns the functions it wants the outside world to call.
 *
 * This compiler reads the source tree rather than the engine's own lowered form, because the
 * lowered form has already thrown away the one thing asm.js needs most: whether a number was
 * written `0` or `0.0`. The first declares an integer, the second a double.
 *
 * Every rule here is a rule about rejection. Anything this compiler does not recognise makes the
 * whole module fall back to ordinary JavaScript, which is what a browser does too, so a mistake
 * in validation can make a module slow but cannot make it wrong.
 */
internal class AsmCompiler private constructor(
    private val module: FunctionNode,
    private val diagnostic: AsmDiagnostic,
) {

    private val globals = LinkedHashMap<String, AsmGlobal>()
    private var globalInts = 0
    private var globalDbls = 0
    private val ffiNames = ArrayList<String>()
    private val tables = ArrayList<AsmTable>()
    private val fnIndex = LinkedHashMap<String, Int>()
    private val fnNodes = ArrayList<FunctionNode>()
    private val signatures = ArrayList<IntArray>()
    private val fnParamTypes = ArrayList<IntArray>()
    private val fnReturnType = ArrayList<Int>()

    private var stdlibParam: String? = null
    private var foreignParam: String? = null
    private var heapParam: String? = null

    companion object {
        /** The directive that marks a module. */
        const val DIRECTIVE: String = "use asm"

        /**
         * Compiles every `"use asm"` function in [root] and hangs the result on its node.
         *
         * Called once, right after parsing and before the tree is lowered, because lowering
         * destroys the source tree in place.
         */
        fun compileAll(root: AstRoot, reports: MutableList<AsmDiagnostic>) {
            // A visitor rather than a walk over the child list: a function's body and parameters
            // hang off fields of their own and are not children, so a plain walk never reaches
            // them. The list of functions on a script node is not filled in until the tree is
            // lowered, which is after this runs.
            root.visit { node ->
                if (node !is FunctionNode || !hasDirective(node)) return@visit true
                val diagnostic = AsmDiagnostic(node.name)
                reports.add(diagnostic)
                node.asmModule = try {
                    AsmCompiler(node, diagnostic).compileModule().also { diagnostic.compiled = true }
                } catch (e: AsmReject) {
                    diagnostic.compileReason = e.reason
                    node.asmRejection = e.reason
                    null
                }
                // A module's own functions cannot be modules, so there is nothing to look for
                // inside one that compiled. One that did not is ordinary JavaScript and may
                // hold any.
                node.asmModule == null
            }
        }

        /** True when [fn]'s body opens with the `"use asm"` directive. */
        fun hasDirective(fn: FunctionNode): Boolean {
            val body = fn.body ?: return false
            val first = body.firstChild as? AstNode ?: return false
            val expr = (first as? ExpressionStatement)?.expression ?: return false
            return (expr as? StringLiteral)?.value == DIRECTIVE
        }

        /** The Math members a module may import, and how many arguments each one takes. */
        private val MATH_FUNCTIONS = mapOf(
            "acos" to 1, "asin" to 1, "atan" to 1, "cos" to 1, "sin" to 1, "tan" to 1,
            "ceil" to 1, "floor" to 1, "exp" to 1, "log" to 1, "sqrt" to 1, "abs" to 1,
            "atan2" to 2, "pow" to 2, "imul" to 2, "fround" to 1, "min" to -1, "max" to -1,
            "clz32" to 1,
        )

        /** The Math constants a module may import. */
        private val MATH_CONSTANTS = mapOf(
            "E" to kotlin.math.E,
            "LN10" to kotlin.math.ln(10.0),
            "LN2" to kotlin.math.ln(2.0),
            "LOG2E" to 1.0 / kotlin.math.ln(2.0),
            "LOG10E" to 1.0 / kotlin.math.ln(10.0),
            "PI" to kotlin.math.PI,
            "SQRT1_2" to kotlin.math.sqrt(0.5),
            "SQRT2" to kotlin.math.sqrt(2.0),
        )

        /** True when [name] is a Math member a module may import. */
        fun isMathFunction(name: String): Boolean = MATH_FUNCTIONS.containsKey(name)
    }

    // ---- The module ---------------------------------------------------------------------------

    private fun compileModule(): AsmModule {
        readParameters()
        val body = module.body ?: reject("the module has no body")
        val statements = childrenOf(body)

        // Pass one: every function of the module gets an index, so a call can name a function
        // that is declared further down.
        for (s in statements) {
            if (s is FunctionNode) {
                val name = s.name
                if (name.isEmpty()) reject("a function of the module has no name")
                if (fnIndex.containsKey(name)) reject("two functions are called $name")
                fnIndex[name] = fnNodes.size
                fnNodes.add(s)
            }
        }
        for ((name, index) in fnIndex) declare(name, AsmGlobal.Fn(index))

        // Pass two: the declarations, in order, because a later one may name an earlier one.
        var exports: Array<AsmExport>? = null
        var singleExport = false
        for ((position, s) in statements.withIndex()) {
            when {
                position == 0 -> Unit // the directive
                s is FunctionNode -> Unit // already indexed
                s is VariableDeclaration -> for (v in s.variables) declareGlobal(v)
                s is ReturnStatement -> {
                    if (exports != null) reject("the module returns twice")
                    val returned = readExports(s.returnValue ?: reject("the module returns nothing"))
                    exports = returned.first
                    singleExport = returned.second
                }
                s is ExpressionStatement -> reject("a statement of the module is not a declaration")
                else -> reject("a statement of the module is not a declaration: ${Token.typeToName(s.type)}")
            }
        }
        val exportList = exports ?: reject("the module returns nothing")
        if (exportList.isEmpty()) reject("the module exports nothing")

        // Pass three: every function's signature, read without emitting anything, so that a body
        // can call a function declared after it.
        for (node in fnNodes) {
            val signature = AsmFunctionCompiler(this, node).readSignature()
            fnParamTypes.add(signature.first)
            fnReturnType.add(signature.second)
        }

        // Pass four: the bodies. Every module level name is known by now.
        val compiled = arrayOfNulls<AsmFunction>(fnNodes.size)
        for (i in fnNodes.indices) {
            compiled[i] = AsmFunctionCompiler(this, fnNodes[i]).compile()
        }
        val functions = Array(compiled.size) { compiled[it]!! }

        // A table's entries must all take and return the same things, or an indirect call could
        // not be compiled at all. This is where that is checked, once every signature is known.
        for (t in tables) {
            val first = functions[t.entries[0]].signature
            for (e in t.entries) {
                if (functions[e].signature != first) reject("a table mixes functions of two shapes")
            }
        }
        val fixedTables = Array(tables.size) { AsmTable(tables[it].entries, functions[tables[it].entries[0]].signature) }

        return AsmModule(
            name = module.name,
            stdlibParam = stdlibParam,
            foreignParam = foreignParam,
            heapParam = heapParam,
            functions = functions,
            globals = globals,
            globalIntCount = globalInts,
            globalDblCount = globalDbls,
            tables = fixedTables,
            ffiNames = ffiNames.toTypedArray(),
            exports = exportList,
            singleExport = singleExport,
            signatures = signatures.toTypedArray(),
            diagnostic = diagnostic,
        )
    }

    private fun readParameters() {
        val params = module.params
        if (params.size > 3) reject("a module takes at most three arguments")
        val names = params.map { (it as? Name)?.identifier ?: reject("a module argument is not a plain name") }
        stdlibParam = names.getOrNull(0)
        foreignParam = names.getOrNull(1)
        heapParam = names.getOrNull(2)
        if (names.distinct().size != names.size) reject("two module arguments share a name")
    }

    // ---- Module level declarations ------------------------------------------------------------

    private fun declareGlobal(v: VariableInitializer) {
        val name = (v.target as? Name)?.identifier ?: reject("a module variable is not a plain name")
        val init = v.initializer ?: reject("the module variable $name has no value")
        declare(name, globalFor(name, unwrap(init)))
    }

    private fun declare(name: String, global: AsmGlobal) {
        if (globals.containsKey(name) && globals[name] !is AsmGlobal.Fn) {
            reject("the module declares $name twice")
        }
        globals[name] = global
    }

    private fun globalFor(name: String, init: AstNode): AsmGlobal = when {
        // var x = 0;  var x = 1.5;  var x = -1;  var x = -1.5;
        init is NumberLiteral ||
            (init is UnaryExpression && init.operator == Token.NEG && unwrap(init.operand!!) is NumberLiteral) ->
            numericGlobal(init)

        // var H32 = new stdlib.Int32Array(heap);
        init is NewExpression -> viewGlobal(init)

        // var imul = stdlib.Math.imul;  or  var pi = stdlib.Math.PI;  or  var log = foreign.log;
        init is PropertyGet -> importedGlobal(init)

        // var x = foreign.f | 0;
        init is io.github.yuroyami.kitejs.ast.InfixExpression && init.operator == Token.BITOR ->
            importedIntGlobal(init)

        // var x = +foreign.f;
        init is UnaryExpression && init.operator == Token.POS ->
            importedDblGlobal(unwrap(init.operand!!), isFloat = false)

        // var x = fround(foreign.f);  or  var x = fround(0);
        init is io.github.yuroyami.kitejs.ast.FunctionCall -> froundGlobal(init)

        // var TBL = [f, g];
        init is ArrayLiteral -> tableGlobal(init)

        else -> reject("the module variable $name is not one of the shapes asm.js allows")
    }

    private fun numericGlobal(init: AstNode): AsmGlobal {
        val whole = intLiteralOf(init)
        if (whole != null) return AsmGlobal.IntVar(globalInts++, whole.toInt())
        val literal = unwrap(init)
        val value = when {
            literal is NumberLiteral -> literal.number
            else -> -((unwrap((literal as UnaryExpression).operand!!)) as NumberLiteral).number
        }
        return AsmGlobal.DblVar(globalDbls++, value, isFloat = false)
    }

    private fun viewGlobal(init: NewExpression): AsmGlobal {
        val target = unwrap(init.target ?: reject("a view has no constructor"))
        val get = target as? PropertyGet ?: reject("a view constructor is not read from the standard library")
        if ((unwrap(get.target!!) as? Name)?.identifier != stdlibParam) {
            reject("a view constructor is not read from the standard library")
        }
        val view = AsmView.ofConstructor(get.property?.identifier ?: "")
        if (view < 0) reject("${get.property?.identifier} is not a typed array")
        val args = init.arguments
        if (args.size != 1 || (unwrap(args[0]) as? Name)?.identifier != heapParam) {
            reject("a view is not built over the module's heap")
        }
        return AsmGlobal.View(view)
    }

    private fun importedGlobal(get: PropertyGet): AsmGlobal {
        val field = get.property?.identifier ?: reject("a module import has no name")
        val owner = unwrap(get.target!!)
        // stdlib.Math.imul
        if (owner is PropertyGet) {
            if ((unwrap(owner.target!!) as? Name)?.identifier != stdlibParam || owner.property?.identifier != "Math") {
                reject("$field is read from something that is not the standard library")
            }
            if (isMathFunction(field)) return AsmGlobal.MathFn(field)
            val constant = MATH_CONSTANTS[field] ?: reject("Math.$field is not something asm.js allows")
            return AsmGlobal.MathConst(constant, field)
        }
        val ownerName = (owner as? Name)?.identifier ?: reject("$field is read from an expression")
        if (ownerName == stdlibParam) {
            return when (field) {
                "Infinity" -> AsmGlobal.MathConst(Double.POSITIVE_INFINITY, field)
                "NaN" -> AsmGlobal.MathConst(Double.NaN, field)
                else -> reject("the standard library has no $field for asm.js")
            }
        }
        if (ownerName != foreignParam) reject("$field is read from $ownerName, which is not an import object")
        val index = ffiNames.size
        ffiNames.add(field)
        return AsmGlobal.Ffi(index, field)
    }

    private fun importedIntGlobal(init: io.github.yuroyami.kitejs.ast.InfixExpression): AsmGlobal {
        val right = unwrap(init.right!!)
        if (!(right is NumberLiteral && right.number == 0.0)) reject("an integer import is not coerced with | 0")
        val field = foreignField(unwrap(init.left!!))
        return AsmGlobal.ImportedInt(globalInts++, field)
    }

    private fun importedDblGlobal(operand: AstNode, isFloat: Boolean): AsmGlobal {
        val field = foreignField(operand)
        return AsmGlobal.ImportedDbl(globalDbls++, field, isFloat)
    }

    private fun froundGlobal(call: io.github.yuroyami.kitejs.ast.FunctionCall): AsmGlobal {
        val callee = (unwrap(call.target!!) as? Name)?.identifier ?: reject("a global is built by an unknown call")
        val known = globals[callee]
        if (known !is AsmGlobal.MathFn || known.field != "fround") {
            reject("a global is built by $callee, which is not fround")
        }
        if (call.arguments.size != 1) reject("fround takes one argument")
        val arg = unwrap(call.arguments[0])
        // fround(0) and fround(0.0) declare a float global; fround(foreign.f) imports one.
        if (arg is NumberLiteral) return AsmGlobal.DblVar(globalDbls++, froundOf(arg.number), isFloat = true)
        if (arg is UnaryExpression && arg.operator == Token.NEG && unwrap(arg.operand!!) is NumberLiteral) {
            val n = (unwrap(arg.operand!!) as NumberLiteral).number
            return AsmGlobal.DblVar(globalDbls++, froundOf(-n), isFloat = true)
        }
        return importedDblGlobal(arg, isFloat = true)
    }

    private fun tableGlobal(array: ArrayLiteral): AsmGlobal {
        val elements = array.elements
        if (elements.isEmpty()) reject("a function table is empty")
        if (elements.size and (elements.size - 1) != 0) reject("a function table's length is not a power of two")
        val entries = IntArray(elements.size) { i ->
            val name = (unwrap(elements[i]) as? Name)?.identifier ?: reject("a function table holds an expression")
            fnIndex[name] ?: reject("a function table names $name, which is not a function of the module")
        }
        val index = tables.size
        // The signature is filled in once every function is compiled.
        tables.add(AsmTable(entries, -1))
        return AsmGlobal.Table(index)
    }

    private fun foreignField(node: AstNode): String {
        val get = node as? PropertyGet ?: reject("an import is not read from the import object")
        if ((unwrap(get.target!!) as? Name)?.identifier != foreignParam) {
            reject("an import is not read from the import object")
        }
        return get.property?.identifier ?: reject("an import has no name")
    }

    private fun readExports(returned: AstNode): Pair<Array<AsmExport>, Boolean> {
        val value = unwrap(returned)
        if (value is Name) {
            val index = fnIndex[value.identifier] ?: reject("the module returns ${value.identifier}, which is not one of its functions")
            return arrayOf(AsmExport(value.identifier!!, index)) to true
        }
        val literal = value as? ObjectLiteral ?: reject("the module returns something other than a function or an object")
        val out = ArrayList<AsmExport>()
        for (element in literal.elements) {
            val property = element as? ObjectProperty ?: reject("an export is not a plain property")
            if (property.type != Token.COLON) reject("an export is a getter, setter or method")
            val key = when (val k = property.key) {
                is Name -> k.identifier
                is StringLiteral -> k.value
                else -> reject("an export has a computed name")
            } ?: reject("an export has no name")
            val fnName = (unwrap(property.value ?: reject("the export $key has no value")) as? Name)?.identifier
                ?: reject("the export $key is not one of the module's functions")
            val index = fnIndex[fnName] ?: reject("the export $key names $fnName, which is not a function of the module")
            out.add(AsmExport(key, index))
        }
        return out.toTypedArray() to false
    }

    // ---- What the function compiler needs -----------------------------------------------------

    fun globalNamed(name: String): AsmGlobal? = globals[name]

    fun functionAt(index: Int): FunctionNode = fnNodes[index]

    fun paramTypesOf(index: Int): IntArray = fnParamTypes[index]

    fun returnTypeOf(index: Int): Int = fnReturnType[index]

    fun tableAt(index: Int): AsmTable = tables[index]

    /** The index of a signature, adding it when this shape has not been seen before. */
    fun signatureOf(returnType: Int, paramTypes: IntArray): Int {
        val shape = IntArray(paramTypes.size + 1)
        shape[0] = returnType
        paramTypes.copyInto(shape, 1)
        for (i in signatures.indices) if (signatures[i].contentEquals(shape)) return i
        signatures.add(shape)
        return signatures.size - 1
    }

    fun reject(reason: String): Nothing = throw AsmReject(reason)
}

/** Steps past the parentheses a reader wrote but the language does not care about. */
internal fun unwrap(node: AstNode): AstNode {
    var n = node
    while (n is ParenthesizedExpression) n = n.expression ?: return n
    return n
}

/**
 * The value of an integer literal, with an optional minus sign in front, or null when the number
 * is a double.
 *
 * This is the one thing asm.js needs that the engine's lowered tree has already thrown away:
 * `var x = 0` declares an integer and `var x = 0.0` declares a double, and by then both are the
 * number zero. The source token says which.
 *
 * A literal counts as an integer when it has no decimal point and its value is a whole number
 * inside the 32 bit range. That includes `1e3`, which Emscripten writes for 1000, and it is what
 * a browser's own validator does. Anything else is a double.
 */
internal fun intLiteralOf(node: AstNode): Long? {
    var inner = unwrap(node)
    var sign = 1
    if (inner is UnaryExpression && inner.operator == Token.NEG) {
        sign = -1
        inner = unwrap(inner.operand ?: return null)
    }
    val literal = inner as? NumberLiteral ?: return null
    if (literal.value?.contains('.') != false) return null
    val value = literal.number * sign
    if (value != value.toLong().toDouble()) return null
    if (value < -2147483648.0 || value >= 4294967296.0) return null
    return value.toLong()
}

/** Rounds to the nearest value a 32 bit float can hold, which is what `Math.fround` answers. */
internal fun froundOf(value: Double): Double = value.toFloat().toDouble()

/** The value when it is a whole number a 32 bit slot can hold either way, and null otherwise. */
internal fun wholeInRange(value: Double): Long? {
    if (value != value.toLong().toDouble()) return null
    if (value < -2147483648.0 || value >= 4294967296.0) return null
    return value.toLong()
}
