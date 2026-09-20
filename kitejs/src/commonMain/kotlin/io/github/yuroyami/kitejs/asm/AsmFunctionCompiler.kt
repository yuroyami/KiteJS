/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.asm

import io.github.yuroyami.kitejs.Token
import io.github.yuroyami.kitejs.ast.Assignment
import io.github.yuroyami.kitejs.ast.AstNode
import io.github.yuroyami.kitejs.ast.BreakStatement
import io.github.yuroyami.kitejs.ast.ConditionalExpression
import io.github.yuroyami.kitejs.ast.ContinueStatement
import io.github.yuroyami.kitejs.ast.DoLoop
import io.github.yuroyami.kitejs.ast.ElementGet
import io.github.yuroyami.kitejs.ast.EmptyStatement
import io.github.yuroyami.kitejs.ast.ExpressionStatement
import io.github.yuroyami.kitejs.ast.ForLoop
import io.github.yuroyami.kitejs.ast.FunctionCall
import io.github.yuroyami.kitejs.ast.FunctionNode
import io.github.yuroyami.kitejs.ast.IfStatement
import io.github.yuroyami.kitejs.ast.InfixExpression
import io.github.yuroyami.kitejs.ast.LabeledStatement
import io.github.yuroyami.kitejs.ast.Name
import io.github.yuroyami.kitejs.ast.NumberLiteral
import io.github.yuroyami.kitejs.ast.ReturnStatement
import io.github.yuroyami.kitejs.ast.SwitchStatement
import io.github.yuroyami.kitejs.ast.UnaryExpression
import io.github.yuroyami.kitejs.ast.VariableDeclaration
import io.github.yuroyami.kitejs.ast.WhileLoop

/**
 * Compiles one function of an asm.js module to typed code.
 *
 * The function's shape is fixed: the parameters are coerced one by one, then the local variables
 * are declared with literal values, then the body. Reading the coercions is what gives every
 * parameter a type, and from there the type of every expression follows.
 *
 * Every `reject` here means the module is not asm.js and the engine should run it as ordinary
 * JavaScript instead.
 */
internal class AsmFunctionCompiler(private val owner: AsmCompiler, private val fn: FunctionNode) {

    private var code = IntArray(256)
    private var top = 0
    private val doubles = ArrayList<Double>()

    private val localType = HashMap<String, Int>()
    private val localSlot = HashMap<String, Int>()
    private var intLocals = 0
    private var dblLocals = 0

    private var intDepth = 0
    private var dblDepth = 0
    private var maxIntDepth = 0
    private var maxDblDepth = 0

    private var returnType = AsmType.VOID
    private val jumps = ArrayList<Jumps>()

    /** Where a `break` and a `continue` inside one loop or switch have to go. */
    private class Jumps(val labels: List<String>, val isLoop: Boolean) {
        val breaks = ArrayList<Int>()
        val continues = ArrayList<Int>()
    }

    // ---- Reading the signature, before any body is compiled -----------------------------------

    /**
     * The types this function takes and returns, read without emitting anything.
     *
     * Every function's signature has to be known before any body is compiled, because a body may
     * call a function declared after it.
     */
    fun readSignature(): Pair<IntArray, Int> {
        val statements = bodyStatements()
        val params = fn.params
        val paramTypes = IntArray(params.size)
        for (i in params.indices) {
            val name = (params[i] as? Name)?.identifier ?: reject("a parameter is not a plain name")
            paramTypes[i] = parameterCoercion(statements.getOrNull(i), name)
        }
        return paramTypes to readReturnType(statements)
    }

    /** The type the statement `x = x | 0` and its neighbours give to parameter [name]. */
    private fun parameterCoercion(statement: AstNode?, name: String): Int {
        val expression = (statement as? ExpressionStatement)?.expression
            ?: reject("the parameter $name is not coerced")
        val assign = unwrap(expression) as? Assignment ?: reject("the parameter $name is not coerced")
        if (assign.operator != Token.ASSIGN) reject("the parameter $name is not coerced")
        if ((unwrap(assign.left!!) as? Name)?.identifier != name) reject("the parameters are coerced out of order")
        val value = unwrap(assign.right!!)
        return when {
            value is InfixExpression && value.operator == Token.BITOR && isZero(value.right!!) &&
                (unwrap(value.left!!) as? Name)?.identifier == name -> AsmType.SIGNED
            value is UnaryExpression && value.operator == Token.POS &&
                (unwrap(value.operand!!) as? Name)?.identifier == name -> AsmType.DOUBLE
            value is FunctionCall && isFround(value) && value.arguments.size == 1 &&
                (unwrap(value.arguments[0]) as? Name)?.identifier == name -> AsmType.FLOAT
            else -> reject("the parameter $name is not coerced with | 0, + or fround")
        }
    }

    /** The one type every `return` in the function agrees on. */
    private fun readReturnType(statements: List<AstNode>): Int {
        var found = -1
        fun visit(node: AstNode?) {
            if (node == null || node is FunctionNode) return
            if (node is ReturnStatement) {
                val type = returnedType(node)
                if (found >= 0 && found != type) reject("the function returns two different types")
                found = type
            }
            var kid = node.firstChild
            while (kid != null) {
                visit(kid as? AstNode)
                kid = kid.next
            }
        }
        for (s in statements) visit(s)
        return if (found < 0) AsmType.VOID else found
    }

    private fun returnedType(node: ReturnStatement): Int {
        val value = node.returnValue?.let { unwrap(it) } ?: return AsmType.VOID
        return when {
            value is InfixExpression && value.operator == Token.BITOR && isZero(value.right!!) -> AsmType.SIGNED
            value is UnaryExpression && value.operator == Token.POS -> AsmType.DOUBLE
            value is FunctionCall && isFround(value) -> AsmType.FLOAT
            intLiteralOf(value) != null -> AsmType.SIGNED
            value is NumberLiteral -> AsmType.DOUBLE
            value is UnaryExpression && value.operator == Token.NEG && unwrap(value.operand!!) is NumberLiteral ->
                AsmType.DOUBLE
            else -> reject("a return value is not coerced with | 0, + or fround")
        }
    }

    // ---- Compiling the body -------------------------------------------------------------------

    fun compile(): AsmFunction {
        val statements = bodyStatements()
        val params = fn.params
        val paramTypes = IntArray(params.size)

        // Parameters first, so integer ones take the lowest integer slots in order and double
        // ones the lowest double slots. A caller pushes its arguments in that same order, which
        // is what lets a call start without copying anything.
        for (i in params.indices) {
            val name = (params[i] as? Name)?.identifier!!
            val type = parameterCoercion(statements.getOrNull(i), name)
            paramTypes[i] = type
            declareLocal(name, type)
        }
        val intParams = intLocals
        val dblParams = dblLocals
        returnType = readReturnType(statements)

        var index = params.size
        // Then the local variables, which asm.js says must all be declared before anything runs.
        while (index < statements.size && statements[index] is VariableDeclaration) {
            for (v in (statements[index] as VariableDeclaration).variables) {
                val name = (v.target as? Name)?.identifier ?: reject("a local is not a plain name")
                val init = v.initializer ?: reject("the local $name has no value")
                declareLocal(name, localInitType(name, unwrap(init)))
                emitLocalInit(name, unwrap(init))
            }
            index++
        }
        while (index < statements.size) {
            statement(statements[index])
            index++
        }
        // Falling off the end of a function that returns a value cannot happen in valid asm.js,
        // but the code still needs an ending.
        when (returnType) {
            AsmType.VOID -> emit(AsmOp.RET_V)
            AsmType.DOUBLE, AsmType.FLOAT -> { emitDConst(0.0); emit(AsmOp.RET_D) }
            else -> { emitIConst(0); emit(AsmOp.RET_I) }
        }

        return AsmFunction(
            name = fn.name,
            code = AsmFuse.fuse(code.copyOf(top)),
            doubles = doubles.toDoubleArray(),
            paramTypes = paramTypes,
            returnType = returnType,
            intParams = intParams,
            dblParams = dblParams,
            intLocals = intLocals,
            dblLocals = dblLocals,
            intFrame = intLocals + maxIntDepth,
            dblFrame = dblLocals + maxDblDepth,
            signature = owner.signatureOf(returnType, paramTypes),
        )
    }

    private fun bodyStatements(): List<AstNode> {
        val body = fn.body ?: reject("the function ${fn.name} has no body")
        val all = childrenOf(body)
        if (fn.functionCount > 0) reject("the function ${fn.name} holds another function")
        return all
    }

    private fun declareLocal(name: String, type: Int) {
        if (localType.containsKey(name)) reject("the local $name is declared twice")
        localType[name] = type
        localSlot[name] = if (AsmType.isDbl(type)) dblLocals++ else intLocals++
    }

    private fun localInitType(name: String, init: AstNode): Int = when {
        intLiteralOf(init) != null -> AsmType.SIGNED
        init is NumberLiteral -> AsmType.DOUBLE
        init is UnaryExpression && init.operator == Token.NEG && unwrap(init.operand!!) is NumberLiteral ->
            AsmType.DOUBLE
        init is FunctionCall && isFround(init) -> AsmType.FLOAT
        else -> reject("the local $name starts from something that is not a number")
    }

    private fun emitLocalInit(name: String, init: AstNode) {
        val type = localType[name]!!
        val slot = localSlot[name]!!
        val value = constantOf(init)
        if (AsmType.isDbl(type)) {
            emitDConst(if (type == AsmType.FLOAT) froundOf(value) else value)
            popDbl(1)
            emit(AsmOp.D_STORE, slot)
        } else {
            emitIConst(value.toLong().toInt())
            popInt(1)
            emit(AsmOp.I_STORE, slot)
        }
    }

    private fun constantOf(node: AstNode): Double = when {
        node is NumberLiteral -> node.number
        node is UnaryExpression && node.operator == Token.NEG -> -constantOf(unwrap(node.operand!!))
        node is FunctionCall && isFround(node) && node.arguments.size == 1 ->
            froundOf(constantOf(unwrap(node.arguments[0])))
        else -> reject("a value that has to be constant is not")
    }

    // ---- Statements ---------------------------------------------------------------------------

    private fun statement(node: AstNode) {
        when (node) {
            is EmptyStatement -> Unit
            is ExpressionStatement -> discard(expr(unwrap(node.expression!!)))
            is ReturnStatement -> compileReturn(node)
            is IfStatement -> compileIf(node)
            is WhileLoop -> compileWhile(node)
            is DoLoop -> compileDo(node)
            is ForLoop -> compileFor(node)
            is SwitchStatement -> compileSwitch(node)
            is BreakStatement -> compileBreak(node)
            is ContinueStatement -> compileContinue(node)
            is LabeledStatement -> compileLabeled(node)
            is VariableDeclaration -> reject("a local is declared after the first statement")
            is FunctionNode -> reject("a function is declared inside a function of the module")
            else -> if (node.type == Token.BLOCK) {
                for (s in childrenOf(node)) statement(s)
            } else {
                reject("${Token.typeToName(node.type)} is not a statement asm.js allows")
            }
        }
    }

    /** Throws away whatever an expression statement left behind. */
    private fun discard(type: Int) {
        when {
            type == AsmType.VOID -> Unit
            // A foreign call leaves its answer on the double stack, coerced or not.
            AsmType.isDbl(type) || type == AsmType.EXTERN -> { popDbl(1); emit(AsmOp.D_DROP) }
            else -> { popInt(1); emit(AsmOp.I_DROP) }
        }
    }

    private fun compileReturn(node: ReturnStatement) {
        val value = node.returnValue
        if (value == null) {
            emit(AsmOp.RET_V)
            return
        }
        val type = expr(unwrap(value))
        if (AsmType.isDbl(type)) {
            popDbl(1)
            emit(AsmOp.RET_D)
        } else {
            popInt(1)
            emit(AsmOp.RET_I)
        }
    }

    private fun compileIf(node: IfStatement) {
        condition(unwrap(node.condition!!))
        val toElse = emitJump(AsmOp.JZ)
        statement(node.thenPart!!)
        val elsePart = node.elsePart
        if (elsePart == null) {
            patch(toElse, top)
        } else {
            val toEnd = emitJump(AsmOp.JMP)
            patch(toElse, top)
            statement(elsePart)
            patch(toEnd, top)
        }
    }

    private fun compileWhile(node: WhileLoop) {
        val frame = openJumps(emptyList(), isLoop = true)
        val start = top
        condition(unwrap(node.condition!!))
        val out = emitJump(AsmOp.JZ)
        statement(node.body!!)
        emitJumpTo(AsmOp.JMP, start)
        patch(out, top)
        closeJumps(frame, breakTo = top, continueTo = start)
    }

    private fun compileDo(node: DoLoop) {
        val frame = openJumps(emptyList(), isLoop = true)
        val start = top
        statement(node.body!!)
        val test = top
        condition(unwrap(node.condition!!))
        emitJumpTo(AsmOp.JNZ, start)
        closeJumps(frame, breakTo = top, continueTo = test)
    }

    private fun compileFor(node: ForLoop) {
        node.initializer?.let { init ->
            if (init is VariableDeclaration) reject("a for loop declares a local")
            if (init.type != Token.EMPTY) discard(expr(unwrap(init)))
        }
        val frame = openJumps(emptyList(), isLoop = true)
        val start = top
        var out = -1
        node.condition?.let { c ->
            if (c.type != Token.EMPTY) {
                condition(unwrap(c))
                out = emitJump(AsmOp.JZ)
            }
        }
        statement(node.body!!)
        val step = top
        node.increment?.let { inc ->
            if (inc.type != Token.EMPTY) discard(expr(unwrap(inc)))
        }
        emitJumpTo(AsmOp.JMP, start)
        if (out >= 0) patch(out, top)
        closeJumps(frame, breakTo = top, continueTo = step)
    }

    private fun compileSwitch(node: SwitchStatement) {
        val type = expr(unwrap(node.expression!!))
        if (!AsmType.isSigned(type)) reject("a switch is not over a signed value")
        popInt(1)
        val cases = node.cases

        // The dispatch table comes first and the bodies follow it, so every target in the table
        // is a position that is not known yet and gets filled in afterwards.
        emit(AsmOp.SWITCH)
        val defaultSlot = reserve()
        val countSlot = reserve()
        val targetSlots = ArrayList<Int>()
        for (c in cases) {
            val e = c.expression ?: continue
            val value = constantOf(unwrap(e))
            if (value != value.toLong().toDouble()) reject("a case value is not an integer")
            // Two separate statements: reserve can grow the array, so the slot has to be taken
            // before the array is indexed.
            val valueSlot = reserve()
            code[valueSlot] = value.toLong().toInt()
            targetSlots.add(reserve())
        }
        code[countSlot] = targetSlots.size

        val frame = openJumps(emptyList(), isLoop = false)
        var caseIndex = 0
        var defaultTarget = -1
        for (c in cases) {
            // The bodies run into each other, which is what makes a case without a break fall
            // through to the next one.
            if (c.expression == null) defaultTarget = top else patch(targetSlots[caseIndex++], top)
            c.statements?.forEach { statement(it) }
        }
        code[defaultSlot] = if (defaultTarget >= 0) defaultTarget else top
        closeJumps(frame, breakTo = top, continueTo = -1)
    }

    private fun compileBreak(node: BreakStatement) {
        val label = node.breakLabel?.identifier
        val frame = if (label == null) jumps.lastOrNull() else jumps.lastOrNull { it.labels.contains(label) }
        frame ?: reject("a break has nowhere to go")
        frame.breaks.add(emitJump(AsmOp.JMP))
    }

    private fun compileContinue(node: ContinueStatement) {
        val label = node.label?.identifier
        val frame = if (label == null) jumps.lastOrNull { it.isLoop } else jumps.lastOrNull { it.isLoop && it.labels.contains(label) }
        frame ?: reject("a continue has nowhere to go")
        frame.continues.add(emitJump(AsmOp.JMP))
    }

    private fun compileLabeled(node: LabeledStatement) {
        val names = node.labels.mapNotNull { it.name }
        val inner = node.statement!!
        when (inner) {
            is WhileLoop, is DoLoop, is ForLoop -> {
                pendingLabels = names
                statement(inner)
                pendingLabels = emptyList()
            }
            else -> {
                val frame = openJumps(names, isLoop = false)
                statement(inner)
                closeJumps(frame, breakTo = top, continueTo = -1)
            }
        }
    }

    private var pendingLabels: List<String> = emptyList()

    private fun openJumps(labels: List<String>, isLoop: Boolean): Jumps {
        val all = if (pendingLabels.isEmpty()) labels else labels + pendingLabels
        pendingLabels = emptyList()
        val frame = Jumps(all, isLoop)
        jumps.add(frame)
        return frame
    }

    private fun closeJumps(frame: Jumps, breakTo: Int, continueTo: Int) {
        jumps.removeAt(jumps.size - 1)
        for (at in frame.breaks) patch(at, breakTo)
        if (frame.continues.isNotEmpty()) {
            if (continueTo < 0) reject("a continue has nowhere to go")
            for (at in frame.continues) patch(at, continueTo)
        }
    }

    /** An expression used as a test: it has to be an integer, and zero means false. */
    private fun condition(node: AstNode) {
        val type = expr(node)
        if (!AsmType.isInt(type)) reject("a condition is not an integer")
        popInt(1)
    }

    // ---- Expressions --------------------------------------------------------------------------

    /** Compiles [node] so it leaves one value on the integer or the double stack. */
    private fun expr(node: AstNode): Int = when (node) {
        is NumberLiteral -> literal(node, negated = false)
        is Name -> readName(node)
        is UnaryExpression -> unary(node)
        is Assignment -> assignment(node)
        is InfixExpression -> infix(node)
        is ConditionalExpression -> conditional(node)
        is FunctionCall -> call(node)
        is ElementGet -> heapLoad(node)
        else -> reject("${Token.typeToName(node.type)} is not an expression asm.js allows")
    }

    /** Compiles a number, with [negated] set when a minus sign was written in front of it. */
    private fun literal(node: NumberLiteral, negated: Boolean): Int {
        val value = if (negated) -node.number else node.number
        val whole = if (node.value?.contains('.') != false) null else wholeInRange(value)
        if (whole == null) {
            emitDConst(value)
            return AsmType.DOUBLE
        }
        emitIConst(whole.toInt())
        // A small positive number fits both readings of a 32 bit word, so it can stand wherever
        // either is wanted. Anything else is one or the other.
        return when {
            whole >= 0 && whole < 2147483648L -> AsmType.FIXNUM
            whole >= 2147483648L -> AsmType.UNSIGNED
            else -> AsmType.SIGNED
        }
    }

    private fun readName(node: Name): Int {
        val name = node.identifier ?: reject("a name has no text")
        val local = localType[name]
        if (local != null) {
            val slot = localSlot[name]!!
            if (AsmType.isDbl(local)) {
                emit(AsmOp.D_LOAD, slot)
                pushDbl()
            } else {
                emit(AsmOp.I_LOAD, slot)
                pushInt()
            }
            return local
        }
        return when (val global = owner.globalNamed(name)) {
            is AsmGlobal.IntVar -> { emit(AsmOp.GI_LOAD, global.slot); pushInt(); AsmType.SIGNED }
            is AsmGlobal.ImportedInt -> { emit(AsmOp.GI_LOAD, global.slot); pushInt(); AsmType.SIGNED }
            is AsmGlobal.DblVar -> { emit(AsmOp.GD_LOAD, global.slot); pushDbl(); if (global.isFloat) AsmType.FLOAT else AsmType.DOUBLE }
            is AsmGlobal.ImportedDbl -> { emit(AsmOp.GD_LOAD, global.slot); pushDbl(); if (global.isFloat) AsmType.FLOAT else AsmType.DOUBLE }
            is AsmGlobal.MathConst -> { emitDConst(global.value); AsmType.DOUBLE }
            null -> reject("$name is not declared")
            else -> reject("$name is not a value")
        }
    }

    private fun unary(node: UnaryExpression): Int {
        val operand = unwrap(node.operand!!)
        return when (node.operator) {
            Token.NEG -> {
                if (operand is NumberLiteral) return literal(operand, negated = true)
                val type = expr(operand)
                when {
                    type == AsmType.SIGNED || type == AsmType.UNSIGNED || type == AsmType.FIXNUM -> {
                        emit(AsmOp.I_NEG); AsmType.INTISH
                    }
                    type == AsmType.DOUBLE -> { emit(AsmOp.D_NEG); AsmType.DOUBLE }
                    type == AsmType.FLOAT -> { emit(AsmOp.D_NEG); AsmType.FLOATISH }
                    else -> reject("unary minus is applied to ${AsmType.name(type)}")
                }
            }
            Token.POS -> {
                val type = expr(operand)
                when {
                    type == AsmType.SIGNED || type == AsmType.FIXNUM -> { convertIntToDouble(signed = true); AsmType.DOUBLE }
                    type == AsmType.UNSIGNED -> { convertIntToDouble(signed = false); AsmType.DOUBLE }
                    type == AsmType.DOUBLE || type == AsmType.FLOAT -> AsmType.DOUBLE
                    type == AsmType.EXTERN -> AsmType.DOUBLE
                    else -> reject("unary plus is applied to ${AsmType.name(type)}")
                }
            }
            Token.BITNOT -> {
                // `~~x` is how asm.js turns a double into a signed integer.
                if (operand is UnaryExpression && operand.operator == Token.BITNOT) {
                    val inner = expr(unwrap(operand.operand!!))
                    if (AsmType.isDbl(inner)) {
                        popDbl(1)
                        emit(AsmOp.D2I)
                        pushInt()
                        return AsmType.SIGNED
                    }
                    if (!AsmType.isInt(inner)) reject("~~ is applied to ${AsmType.name(inner)}")
                    emit(AsmOp.I_NOT)
                    emit(AsmOp.I_NOT)
                    return AsmType.SIGNED
                }
                val type = expr(operand)
                if (!AsmType.isInt(type)) reject("~ is applied to ${AsmType.name(type)}")
                emit(AsmOp.I_NOT)
                AsmType.SIGNED
            }
            Token.NOT -> {
                val type = expr(operand)
                if (!AsmType.isInt(type)) reject("! is applied to ${AsmType.name(type)}")
                emit(AsmOp.I_EQZ)
                AsmType.SIGNED
            }
            else -> reject("${Token.typeToName(node.operator)} is not a unary operator asm.js allows")
        }
    }

    private fun convertIntToDouble(signed: Boolean) {
        popInt(1)
        emit(if (signed) AsmOp.I2D_S else AsmOp.I2D_U)
        pushDbl()
    }

    private fun infix(node: InfixExpression): Int {
        val operator = node.operator
        if (operator == Token.COMMA) {
            discard(expr(unwrap(node.left!!)))
            return expr(unwrap(node.right!!))
        }
        val left = unwrap(node.left!!)
        val right = unwrap(node.right!!)

        // `x | 0` is the coercion that turns anything integral into a signed value. It comes
        // first because it is the one shape that accepts an intish, and the one that ends a
        // chain of additions.
        if (operator == Token.BITOR && isZero(right)) {
            val type = exprAllowingIntish(left)
            if (type == AsmType.EXTERN) {
                // A foreign call left a double behind, and `| 0` is what makes it an integer.
                popDbl(1)
                emit(AsmOp.D2I)
                pushInt()
                return AsmType.SIGNED
            }
            if (!AsmType.isInt(type)) reject("| 0 is applied to ${AsmType.name(type)}")
            return AsmType.SIGNED
        }

        return when (operator) {
            Token.BITOR, Token.BITAND, Token.BITXOR, Token.LSH, Token.RSH, Token.URSH ->
                bitwise(operator, left, right)
            Token.ADD, Token.SUB -> additive(operator, left, right)
            Token.MUL -> multiplicative(left, right)
            Token.DIV, Token.MOD -> divisive(operator, left, right)
            Token.LT, Token.LE, Token.GT, Token.GE, Token.EQ, Token.NE -> comparison(operator, left, right)
            else -> reject("${Token.typeToName(operator)} is not an operator asm.js allows")
        }
    }

    private fun bitwise(operator: Int, left: AstNode, right: AstNode): Int {
        val lt = exprAllowingIntish(left)
        if (!AsmType.isInt(lt)) reject("a bitwise operator is applied to ${AsmType.name(lt)}")
        val rt = exprAllowingIntish(right)
        if (!AsmType.isInt(rt)) reject("a bitwise operator is applied to ${AsmType.name(rt)}")
        popInt(1)
        emit(
            when (operator) {
                Token.BITOR -> AsmOp.I_OR
                Token.BITAND -> AsmOp.I_AND
                Token.BITXOR -> AsmOp.I_XOR
                Token.LSH -> AsmOp.I_SHL
                Token.RSH -> AsmOp.I_SHR
                else -> AsmOp.I_USHR
            },
        )
        return if (operator == Token.URSH) AsmType.UNSIGNED else AsmType.SIGNED
    }

    private fun additive(operator: Int, left: AstNode, right: AstNode): Int {
        val lt = exprAllowingIntish(left)
        val rt = exprAllowingIntish(right)
        if (AsmType.isInt(lt) && AsmType.isInt(rt)) {
            popInt(1)
            emit(if (operator == Token.ADD) AsmOp.I_ADD else AsmOp.I_SUB)
            // The sum of two 32 bit integers can be one bit wider than either. asm.js allows a
            // chain of up to a million of them before the running total has to be coerced back,
            // which is what keeps the wrapping this instruction does out of sight.
            additiveTerms += 1
            if (additiveTerms > MAX_ADDITIVE_TERMS) reject("an addition chain is too long to stay exact")
            return AsmType.INTISH
        }
        popDbl(1)
        emit(if (operator == Token.ADD) AsmOp.D_ADD else AsmOp.D_SUB)
        return doubleResult(lt, rt, "an addition")
    }

    private fun multiplicative(left: AstNode, right: AstNode): Int {
        val lt = exprAllowingIntish(left)
        val rt = exprAllowingIntish(right)
        if (AsmType.isInt(lt) && AsmType.isInt(rt)) {
            // Two full integers multiplied can be 62 bits wide, which a double still holds but a
            // wrapping multiply does not. asm.js only allows it when one side is a small literal,
            // and asks for `Math.imul` otherwise.
            val small = smallLiteral(left) ?: smallLiteral(right)
                ?: reject("a multiplication of two integers needs Math.imul")
            if (small >= (1 shl 20) || small <= -(1 shl 20)) reject("a multiplication uses too large a literal")
            popInt(1)
            emit(AsmOp.I_MUL)
            return AsmType.INTISH
        }
        popDbl(1)
        emit(AsmOp.D_MUL)
        return doubleResult(lt, rt, "a multiplication")
    }

    private fun divisive(operator: Int, left: AstNode, right: AstNode): Int {
        val lt = exprAllowingIntish(left)
        val rt = exprAllowingIntish(right)
        if (AsmType.isInt(lt) && AsmType.isInt(rt)) {
            val signed = AsmType.isSigned(lt) && AsmType.isSigned(rt)
            val unsigned = AsmType.isUnsigned(lt) && AsmType.isUnsigned(rt)
            if (!signed && !unsigned) reject("a division mixes signed and unsigned")
            popInt(1)
            emit(
                when {
                    operator == Token.DIV && signed -> AsmOp.I_DIV_S
                    operator == Token.DIV -> AsmOp.I_DIV_U
                    signed -> AsmOp.I_REM_S
                    else -> AsmOp.I_REM_U
                },
            )
            return AsmType.INTISH
        }
        if (operator == Token.MOD && (lt == AsmType.FLOAT || rt == AsmType.FLOAT)) {
            reject("a float remainder is not allowed")
        }
        popDbl(1)
        emit(if (operator == Token.DIV) AsmOp.D_DIV else AsmOp.D_REM)
        return doubleResult(lt, rt, "a division")
    }

    /** The type of an arithmetic result whose operands were not both integers. */
    private fun doubleResult(lt: Int, rt: Int, what: String): Int = when {
        lt == AsmType.DOUBLE && rt == AsmType.DOUBLE -> AsmType.DOUBLE
        lt == AsmType.FLOAT && rt == AsmType.FLOAT -> AsmType.FLOATISH
        else -> reject("$what mixes ${AsmType.name(lt)} and ${AsmType.name(rt)}")
    }

    private fun comparison(operator: Int, left: AstNode, right: AstNode): Int {
        val lt = expr(left)
        val rt = expr(right)
        if (AsmType.isInt(lt) && AsmType.isInt(rt)) {
            if (lt == AsmType.INTISH || rt == AsmType.INTISH) reject("a comparison reads an uncoerced value")
            val signed = AsmType.isSigned(lt) && AsmType.isSigned(rt)
            val unsigned = AsmType.isUnsigned(lt) && AsmType.isUnsigned(rt)
            if (!signed && !unsigned) reject("a comparison mixes signed and unsigned")
            popInt(1)
            emit(
                when (operator) {
                    Token.LT -> if (signed) AsmOp.I_LT_S else AsmOp.I_LT_U
                    Token.LE -> if (signed) AsmOp.I_LE_S else AsmOp.I_LE_U
                    Token.GT -> if (signed) AsmOp.I_GT_S else AsmOp.I_GT_U
                    Token.GE -> if (signed) AsmOp.I_GE_S else AsmOp.I_GE_U
                    Token.EQ -> AsmOp.I_EQ
                    else -> AsmOp.I_NE
                },
            )
            return AsmType.SIGNED
        }
        if (!AsmType.isDbl(lt) || !AsmType.isDbl(rt)) {
            reject("a comparison mixes ${AsmType.name(lt)} and ${AsmType.name(rt)}")
        }
        if (lt == AsmType.FLOATISH || rt == AsmType.FLOATISH) reject("a comparison reads an uncoerced float")
        if ((lt == AsmType.FLOAT) != (rt == AsmType.FLOAT)) reject("a comparison mixes float and double")
        popDbl(2)
        emit(
            when (operator) {
                Token.LT -> AsmOp.D_LT
                Token.LE -> AsmOp.D_LE
                Token.GT -> AsmOp.D_GT
                Token.GE -> AsmOp.D_GE
                Token.EQ -> AsmOp.D_EQ
                else -> AsmOp.D_NE
            },
        )
        pushInt()
        return AsmType.SIGNED
    }

    private fun conditional(node: ConditionalExpression): Int {
        condition(unwrap(node.testExpression!!))
        val toElse = emitJump(AsmOp.JZ)
        val savedInt = intDepth
        val savedDbl = dblDepth
        val thenType = expr(unwrap(node.trueExpression!!))
        val toEnd = emitJump(AsmOp.JMP)
        patch(toElse, top)
        intDepth = savedInt
        dblDepth = savedDbl
        val elseType = expr(unwrap(node.falseExpression!!))
        patch(toEnd, top)
        if (AsmType.isInt(thenType) && AsmType.isInt(elseType)) {
            if (thenType == AsmType.INTISH || elseType == AsmType.INTISH) {
                reject("a conditional gives an uncoerced value")
            }
            return if (AsmType.isSigned(thenType) && AsmType.isSigned(elseType)) AsmType.SIGNED else AsmType.UNSIGNED
        }
        if (thenType == AsmType.DOUBLE && elseType == AsmType.DOUBLE) return AsmType.DOUBLE
        if (thenType == AsmType.FLOAT && elseType == AsmType.FLOAT) return AsmType.FLOAT
        reject("a conditional gives ${AsmType.name(thenType)} on one side and ${AsmType.name(elseType)} on the other")
    }

    // ---- Assignment ---------------------------------------------------------------------------

    private fun assignment(node: Assignment): Int {
        if (node.operator != Token.ASSIGN) reject("${Token.typeToName(node.operator)} is not an assignment asm.js allows")
        val target = unwrap(node.left!!)
        val value = unwrap(node.right!!)
        if (target is ElementGet) return heapStore(target, value)
        val name = (target as? Name)?.identifier ?: reject("an assignment writes to an expression")
        val type = expr(value)
        val local = localType[name]
        if (local != null) {
            checkAssignable(local, type, name)
            val slot = localSlot[name]!!
            emit(if (AsmType.isDbl(local)) AsmOp.D_STORE_KEEP else AsmOp.I_STORE_KEEP, slot)
            return local
        }
        return when (val global = owner.globalNamed(name)) {
            is AsmGlobal.IntVar -> { checkAssignable(AsmType.SIGNED, type, name); emit(AsmOp.GI_STORE_KEEP, global.slot); AsmType.SIGNED }
            is AsmGlobal.ImportedInt -> { checkAssignable(AsmType.SIGNED, type, name); emit(AsmOp.GI_STORE_KEEP, global.slot); AsmType.SIGNED }
            is AsmGlobal.DblVar -> {
                val want = if (global.isFloat) AsmType.FLOAT else AsmType.DOUBLE
                checkAssignable(want, type, name)
                emit(AsmOp.GD_STORE_KEEP, global.slot)
                want
            }
            is AsmGlobal.ImportedDbl -> {
                val want = if (global.isFloat) AsmType.FLOAT else AsmType.DOUBLE
                checkAssignable(want, type, name)
                emit(AsmOp.GD_STORE_KEEP, global.slot)
                want
            }
            null -> reject("$name is not declared")
            else -> reject("$name cannot be assigned to")
        }
    }

    private fun checkAssignable(want: Int, got: Int, name: String) {
        val ok = when (want) {
            AsmType.SIGNED -> got == AsmType.SIGNED || got == AsmType.UNSIGNED || got == AsmType.FIXNUM
            AsmType.DOUBLE -> got == AsmType.DOUBLE
            AsmType.FLOAT -> got == AsmType.FLOAT
            else -> false
        }
        if (!ok) reject("$name holds ${AsmType.name(want)} and is given ${AsmType.name(got)}")
    }

    // ---- The heap -----------------------------------------------------------------------------

    private fun viewOf(node: ElementGet): Int {
        val name = (unwrap(node.target!!) as? Name)?.identifier ?: reject("a heap read is not through a view")
        val global = owner.globalNamed(name) ?: reject("$name is not declared")
        return (global as? AsmGlobal.View)?.view ?: reject("$name is not a heap view")
    }

    /** Compiles the index of a heap access into a byte address. */
    private fun heapAddress(node: ElementGet, view: Int) {
        val shift = AsmView.shift(view)
        val index = unwrap(node.element!!)
        if (shift == 0) {
            val type = exprAllowingIntish(index)
            if (!AsmType.isInt(type)) reject("a heap index is ${AsmType.name(type)}")
            return
        }
        // A wide view is written `H32[p >> 2]`, so the value before the shift is already a byte
        // address. Masking off the low bits is the same as shifting right and back again, and it
        // saves an instruction.
        if (index is InfixExpression && index.operator == Token.RSH) {
            val amount = constantIntOf(unwrap(index.right!!))
            if (amount == shift) {
                val type = exprAllowingIntish(unwrap(index.left!!))
                if (!AsmType.isInt(type)) reject("a heap index is ${AsmType.name(type)}")
                emitIConst(((1 shl shift) - 1).inv())
                popInt(1)
                emit(AsmOp.I_AND)
                return
            }
        }
        val constant = constantIntOrNull(index)
        if (constant != null) {
            emitIConst(constant shl shift)
            return
        }
        reject("a heap index is not shifted by ${shift}")
    }

    private fun heapLoad(node: ElementGet): Int {
        val view = viewOf(node)
        heapAddress(node, view)
        popInt(1)
        emit(
            when (view) {
                AsmView.I8 -> AsmOp.H_LOAD_I8
                AsmView.U8 -> AsmOp.H_LOAD_U8
                AsmView.I16 -> AsmOp.H_LOAD_I16
                AsmView.U16 -> AsmOp.H_LOAD_U16
                AsmView.I32, AsmView.U32 -> AsmOp.H_LOAD_I32
                AsmView.F32 -> AsmOp.H_LOAD_F32
                else -> AsmOp.H_LOAD_F64
            },
        )
        return when (view) {
            AsmView.F32 -> { pushDbl(); AsmType.FLOAT }
            AsmView.F64 -> { pushDbl(); AsmType.DOUBLE }
            else -> { pushInt(); AsmType.INTISH }
        }
    }

    private fun heapStore(target: ElementGet, value: AstNode): Int {
        val view = viewOf(target)
        heapAddress(target, view)
        val type = exprAllowingIntish(value)
        if (AsmView.isFloating(view)) {
            if (!AsmType.isDbl(type)) reject("a float heap slot is given ${AsmType.name(type)}")
            popDbl(1)
            popInt(1)
            emit(if (view == AsmView.F32) AsmOp.H_STORE_F32 else AsmOp.H_STORE_F64)
        } else {
            if (!AsmType.isInt(type)) reject("an integer heap slot is given ${AsmType.name(type)}")
            popInt(2)
            emit(
                when (view) {
                    AsmView.I8, AsmView.U8 -> AsmOp.H_STORE_I8
                    AsmView.I16, AsmView.U16 -> AsmOp.H_STORE_I16
                    else -> AsmOp.H_STORE_I32
                },
            )
        }
        return AsmType.VOID
    }

    // ---- Calls --------------------------------------------------------------------------------

    private fun call(node: FunctionCall): Int {
        val target = unwrap(node.target!!)
        if (target is ElementGet) return indirectCall(node, target)
        val name = (target as? Name)?.identifier ?: reject("a call is not to a name")
        val global = owner.globalNamed(name) ?: reject("$name is not declared")
        return when (global) {
            is AsmGlobal.MathFn -> mathCall(global.field, node)
            is AsmGlobal.Fn -> directCall(global.index, node)
            is AsmGlobal.Ffi -> foreignCall(global.index, node)
            else -> reject("$name is not a function")
        }
    }

    private fun pushArguments(node: FunctionCall, paramTypes: IntArray) {
        val args = node.arguments
        if (args.size != paramTypes.size) reject("a call passes ${args.size} arguments where ${paramTypes.size} are wanted")
        for (i in args.indices) {
            val got = expr(unwrap(args[i]))
            checkAssignable(paramTypes[i], got, "argument ${i + 1}")
        }
    }

    private fun directCall(index: Int, node: FunctionCall): Int {
        val paramTypes = owner.paramTypesOf(index)
        pushArguments(node, paramTypes)
        var ints = 0
        var dbls = 0
        for (t in paramTypes) if (AsmType.isDbl(t)) dbls++ else ints++
        popInt(ints)
        popDbl(dbls)
        emit(AsmOp.CALL_DIRECT, index)
        return finishCall(owner.returnTypeOf(index))
    }

    private fun indirectCall(node: FunctionCall, target: ElementGet): Int {
        val name = (unwrap(target.target!!) as? Name)?.identifier ?: reject("an indirect call is not through a table")
        val table = (owner.globalNamed(name) as? AsmGlobal.Table) ?: reject("$name is not a function table")
        val entries = owner.tableAt(table.index).entries
        // asm.js asks for `TBL[i & 7](x)` so the index cannot leave the table, which is what
        // makes an indirect call safe without a check at run time.
        val index = unwrap(target.element!!)
        if (index !is InfixExpression || index.operator != Token.BITAND) {
            reject("an indirect call does not mask its index")
        }
        val mask = constantIntOf(unwrap(index.right!!))
        if (mask != entries.size - 1) reject("an indirect call masks with $mask where the table holds ${entries.size}")
        val indexType = exprAllowingIntish(unwrap(index.left!!))
        if (!AsmType.isInt(indexType)) reject("an indirect call's index is ${AsmType.name(indexType)}")
        emitIConst(mask)
        popInt(1)
        emit(AsmOp.I_AND)

        val first = entries[0]
        val paramTypes = owner.paramTypesOf(first)
        pushArguments(node, paramTypes)
        var ints = 0
        var dbls = 0
        for (t in paramTypes) if (AsmType.isDbl(t)) dbls++ else ints++
        popInt(ints + 1)
        popDbl(dbls)
        emit(AsmOp.CALL_INDIRECT, table.index)
        return finishCall(owner.returnTypeOf(first))
    }

    private fun foreignCall(index: Int, node: FunctionCall): Int {
        val args = node.arguments
        val types = IntArray(args.size)
        for (i in args.indices) {
            val type = expr(unwrap(args[i]))
            types[i] = when {
                AsmType.isInt(type) && type != AsmType.INTISH -> AsmType.SIGNED
                type == AsmType.DOUBLE || type == AsmType.FLOAT -> AsmType.DOUBLE
                else -> reject("a foreign call is passed ${AsmType.name(type)}")
            }
        }
        var ints = 0
        var dbls = 0
        for (t in types) if (AsmType.isDbl(t)) dbls++ else ints++
        popInt(ints)
        popDbl(dbls)
        emit(AsmOp.CALL_FFI, index, encodeArgs(types))
        // What comes back is an ordinary JavaScript value, so it arrives as a double: `ToNumber`
        // of the answer. That is exactly what the coercion around the call would have read, since
        // `x | 0` is `ToInt32(ToNumber(x))`.
        pushDbl()
        return AsmType.EXTERN
    }

    /** Packs the argument shapes of a foreign call into one operand: two bits each. */
    private fun encodeArgs(types: IntArray): Int {
        if (types.size > 12) reject("a foreign call takes too many arguments")
        var packed = types.size
        for (i in types.indices) {
            if (AsmType.isDbl(types[i])) packed = packed or (1 shl (8 + i))
        }
        return packed
    }

    private fun finishCall(returnType: Int): Int {
        when {
            returnType == AsmType.VOID -> Unit
            AsmType.isDbl(returnType) -> pushDbl()
            else -> pushInt()
        }
        return returnType
    }

    private fun mathCall(field: String, node: FunctionCall): Int {
        val args = node.arguments
        if (field == "fround") {
            if (args.size != 1) reject("fround takes one argument")
            val type = exprAllowingIntish(unwrap(args[0]))
            when {
                type == AsmType.SIGNED || type == AsmType.FIXNUM -> convertIntToDouble(signed = true)
                type == AsmType.UNSIGNED -> convertIntToDouble(signed = false)
                AsmType.isDbl(type) || type == AsmType.EXTERN -> Unit
                else -> reject("fround is applied to ${AsmType.name(type)}")
            }
            emit(AsmOp.D_FROUND)
            return AsmType.FLOAT
        }
        return when (field) {
            "imul" -> {
                if (args.size != 2) reject("imul takes two arguments")
                requireInt(exprAllowingIntish(unwrap(args[0])), "imul")
                requireInt(exprAllowingIntish(unwrap(args[1])), "imul")
                popInt(1)
                emit(AsmOp.M_IMUL)
                AsmType.SIGNED
            }
            "clz32" -> {
                if (args.size != 1) reject("clz32 takes one argument")
                requireInt(exprAllowingIntish(unwrap(args[0])), "clz32")
                emit(AsmOp.M_CLZ32)
                AsmType.FIXNUM
            }
            "abs" -> {
                if (args.size != 1) reject("abs takes one argument")
                when (val type = exprAllowingIntish(unwrap(args[0]))) {
                    AsmType.SIGNED, AsmType.FIXNUM -> { emit(AsmOp.M_ABS_I); AsmType.UNSIGNED }
                    AsmType.DOUBLE -> { emit(AsmOp.M_ABS_D); AsmType.DOUBLE }
                    AsmType.FLOAT, AsmType.FLOATISH -> { emit(AsmOp.M_ABS_D); AsmType.FLOATISH }
                    else -> reject("abs is applied to ${AsmType.name(type)}")
                }
            }
            "min", "max" -> minOrMax(field, node)
            "atan2", "pow" -> {
                if (args.size != 2) reject("$field takes two arguments")
                requireDouble(expr(unwrap(args[0])), field)
                requireDouble(expr(unwrap(args[1])), field)
                popDbl(1)
                emit(if (field == "atan2") AsmOp.M_ATAN2 else AsmOp.M_POW)
                AsmType.DOUBLE
            }
            else -> {
                if (args.size != 1) reject("$field takes one argument")
                requireDouble(expr(unwrap(args[0])), field)
                emit(
                    when (field) {
                        "floor" -> AsmOp.M_FLOOR
                        "ceil" -> AsmOp.M_CEIL
                        "sqrt" -> AsmOp.M_SQRT
                        "sin" -> AsmOp.M_SIN
                        "cos" -> AsmOp.M_COS
                        "tan" -> AsmOp.M_TAN
                        "asin" -> AsmOp.M_ASIN
                        "acos" -> AsmOp.M_ACOS
                        "atan" -> AsmOp.M_ATAN
                        "exp" -> AsmOp.M_EXP
                        "log" -> AsmOp.M_LOG
                        else -> reject("Math.$field is not something asm.js allows")
                    },
                )
                AsmType.DOUBLE
            }
        }
    }

    private fun minOrMax(field: String, node: FunctionCall): Int {
        val args = node.arguments
        if (args.size < 2) reject("$field takes at least two arguments")
        val first = exprAllowingIntish(unwrap(args[0]))
        val integral = AsmType.isSigned(first)
        if (!integral) requireDouble(first, field)
        for (i in 1 until args.size) {
            val type = exprAllowingIntish(unwrap(args[i]))
            if (integral) {
                if (!AsmType.isSigned(type)) reject("$field mixes signed and ${AsmType.name(type)}")
                popInt(1)
                emit(if (field == "min") AsmOp.M_MIN_I else AsmOp.M_MAX_I)
            } else {
                requireDouble(type, field)
                popDbl(1)
                emit(if (field == "min") AsmOp.M_MIN_D else AsmOp.M_MAX_D)
            }
        }
        return if (integral) AsmType.SIGNED else AsmType.DOUBLE
    }

    private fun requireInt(type: Int, what: String) {
        if (!AsmType.isInt(type)) reject("$what is applied to ${AsmType.name(type)}")
    }

    private fun requireDouble(type: Int, what: String) {
        if (type != AsmType.DOUBLE && type != AsmType.FLOAT) reject("$what is applied to ${AsmType.name(type)}")
    }

    // ---- Small helpers ------------------------------------------------------------------------

    /**
     * The same as [expr], and named apart so the places that accept an uncoerced value are the
     * ones that say so. Every other caller of [expr] rejects [AsmType.INTISH] itself.
     */
    private fun exprAllowingIntish(node: AstNode): Int = expr(node)

    private fun isZero(node: AstNode): Boolean = intLiteralOf(node) == 0L

    private fun isFround(call: FunctionCall): Boolean {
        val name = (unwrap(call.target ?: return false) as? Name)?.identifier ?: return false
        val global = owner.globalNamed(name)
        return global is AsmGlobal.MathFn && global.field == "fround"
    }

    private fun smallLiteral(node: AstNode): Int? = intLiteralOf(node)?.toInt()

    private fun constantIntOf(node: AstNode): Int =
        constantIntOrNull(node) ?: reject("a value that has to be a constant integer is not")

    private fun constantIntOrNull(node: AstNode): Int? = intLiteralOf(node)?.toInt()

    private var additiveTerms = 0

    private fun reject(reason: String): Nothing = owner.reject("${fn.name}: $reason")

    // ---- Emitting -----------------------------------------------------------------------------

    private fun room(n: Int) {
        if (top + n > code.size) code = code.copyOf(maxOf(code.size * 2, top + n))
    }

    private fun emit(op: Int) {
        room(1)
        code[top++] = op
    }

    private fun emit(op: Int, a: Int) {
        room(2)
        code[top++] = op
        code[top++] = a
    }

    private fun emit(op: Int, a: Int, b: Int) {
        room(3)
        code[top++] = op
        code[top++] = a
        code[top++] = b
    }

    private fun reserve(): Int {
        room(1)
        return top++
    }

    private fun emitJump(op: Int): Int {
        room(2)
        code[top++] = op
        return top++
    }

    private fun emitJumpTo(op: Int, target: Int) {
        room(2)
        code[top++] = op
        code[top++] = target
    }

    private fun patch(at: Int, target: Int) {
        code[at] = target
    }

    private fun emitIConst(value: Int) {
        emit(AsmOp.I_CONST, value)
        pushInt()
    }

    private fun emitDConst(value: Double) {
        var index = -1
        for (i in doubles.indices) {
            if (doubles[i].toRawBits() == value.toRawBits()) {
                index = i
                break
            }
        }
        if (index < 0) {
            index = doubles.size
            doubles.add(value)
        }
        emit(AsmOp.D_CONST, index)
        pushDbl()
    }

    private fun pushInt() {
        intDepth++
        if (intDepth > maxIntDepth) maxIntDepth = intDepth
    }

    private fun pushDbl() {
        dblDepth++
        if (dblDepth > maxDblDepth) maxDblDepth = dblDepth
    }

    private fun popInt(n: Int) {
        intDepth -= n
        if (intDepth < 0) reject("the integer stack went empty")
    }

    private fun popDbl(n: Int) {
        dblDepth -= n
        if (dblDepth < 0) reject("the double stack went empty")
    }

    private companion object {
        /** How many integers asm.js lets a program add before the total has to be coerced. */
        const val MAX_ADDITIVE_TERMS = 1 shl 20
    }
}

/** The children of a node, as a list, because the tree is a linked list of siblings. */
internal fun childrenOf(node: AstNode): List<AstNode> {
    val out = ArrayList<AstNode>()
    var kid = node.firstChild
    while (kid != null) {
        (kid as? AstNode)?.let { out.add(it) }
        kid = kid.next
    }
    return out
}
