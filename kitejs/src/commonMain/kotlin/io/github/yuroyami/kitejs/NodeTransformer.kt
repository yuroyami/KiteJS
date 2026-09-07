/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.FunctionNode
import io.github.yuroyami.kitejs.ast.Jump
import io.github.yuroyami.kitejs.ast.Name
import io.github.yuroyami.kitejs.ast.Scope
import io.github.yuroyami.kitejs.ast.ScriptNode

/**
 * Rewrites the IR into the lower-level form the code generator wants.
 *
 * This is the pass that resolves names to variable slots where it can, turns break and continue
 * into plain gotos with the right unwinding, threads finally blocks onto every return, and either
 * flattens block scopes into variable slots or turns them into `with` objects, depending on
 * whether the enclosing function needs an activation.
 */
public open class NodeTransformer {

    private var loops: ArrayDeque<Node> = ArrayDeque()
    private var loopEnds: ArrayDeque<Node> = ArrayDeque()
    private var hasFinally = false

    public fun transform(tree: ScriptNode, env: CompilerEnvirons) {
        transform(tree, false, env)
    }

    public fun transform(tree: ScriptNode, inStrictMode: Boolean, env: CompilerEnvirons) {
        var useStrictMode = inStrictMode
        // Strict mode inside a function is only supported from the ES6 language level up.
        // Below that it would break plenty of existing scripts.
        if (env.languageVersion >= Context.VERSION_ES6 && tree.isInStrictMode) {
            useStrictMode = true
        }
        transformCompilationUnit(tree, useStrictMode)
        for (i in 0 until tree.functionCount) {
            transform(tree.getFunctionNode(i), useStrictMode, env)
        }
    }

    private fun transformCompilationUnit(tree: ScriptNode, inStrictMode: Boolean) {
        loops = ArrayDeque()
        loopEnds = ArrayDeque()

        // Saves the up-checks when no finally block is used.
        hasFinally = false

        // Flatten everything only when block scopes are not becoming scope objects.
        val createScopeObjects =
            tree.type != Token.FUNCTION || (tree as FunctionNode).requiresActivation
        tree.flattenSymbolTable(!createScopeObjects)

        if (Token.printTrees) println(tree.toStringTree(tree))
        transformCompilationUnitR(tree, tree, tree, createScopeObjects, inStrictMode)
    }

    private fun transformCompilationUnitR(
        tree: ScriptNode,
        parent: Node,
        scope: Scope,
        createScopeObjects: Boolean,
        inStrictMode: Boolean,
    ) {
        var node: Node? = null
        siblingLoop@ while (true) {
            var previous: Node? = null
            if (node == null) {
                node = parent.firstChild
            } else {
                previous = node
                node = node.next
            }
            val current = node ?: break

            var type = current.type
            if (createScopeObjects &&
                (type == Token.BLOCK || type == Token.LOOP || type == Token.ARRAYCOMP) &&
                current is Scope
            ) {
                val symbolTable = current.symbolTable
                if (symbolTable != null) {
                    // Turn it into a let statement, so a "with" gets created to hold the scoped
                    // let variables.
                    val let = Node(if (type == Token.ARRAYCOMP) Token.LETEXPR else Token.LET)
                    val innerLet = Node(Token.LET)
                    let.addChildToBack(innerLet)
                    for (name in symbolTable.keys) {
                        innerLet.addChildToBack(Node.newString(Token.NAME, name))
                    }
                    current.symbolTable = null // so this does not run again
                    node = replaceCurrent(parent, previous, current, let)
                    type = node.type
                    let.addChildToBack(current)
                }
            }

            var n = node

            when (type) {
                Token.LABEL, Token.SWITCH, Token.LOOP -> {
                    loops.addFirst(n)
                    loopEnds.addFirst((n as Jump).target!!)
                }

                Token.WITH -> {
                    loops.addFirst(n)
                    val leave = n.next
                    if (leave == null || leave.type != Token.LEAVEWITH) {
                        Kit.codeBug()
                    }
                    loopEnds.addFirst(leave!!)
                }

                Token.TRY -> {
                    val finallyTarget = (n as Jump).finallyTarget
                    if (finallyTarget != null) {
                        hasFinally = true
                        loops.addFirst(n)
                        loopEnds.addFirst(finallyTarget)
                    }
                }

                Token.TARGET, Token.LEAVEWITH -> {
                    if (loopEnds.isNotEmpty() && loopEnds.first() === n) {
                        loopEnds.removeFirst()
                        loops.removeFirst()
                    }
                }

                Token.YIELD, Token.YIELD_STAR -> (tree as FunctionNode).addResumptionPoint(n)

                Token.RETURN -> {
                    val isGenerator =
                        tree.type == Token.FUNCTION && (tree as FunctionNode).isGenerator
                    if (isGenerator) {
                        n.putIntProp(Node.GENERATOR_END_PROP, 1)
                    }
                    /*
                     * Without try/finally the LEAVEWITH nodes here would be unnecessary. But a
                     * series of JSR FINALLY nodes has to run before each RETURN, and every finally
                     * block needs the right scope, which can mean some LEAVEWITH nodes.
                     */
                    if (hasFinally) {
                        var unwindBlock: Node? = null
                        // Walk from the top of the stack, most recently pushed first.
                        for (loopNode in loops) {
                            val elemtype = loopNode.type
                            if (elemtype == Token.TRY || elemtype == Token.WITH) {
                                val unwind: Node
                                if (elemtype == Token.TRY) {
                                    val jsrnode = Jump(Token.JSR)
                                    jsrnode.target = (loopNode as Jump).finallyTarget
                                    unwind = jsrnode
                                } else {
                                    unwind = Node(Token.LEAVEWITH)
                                }
                                if (unwindBlock == null) {
                                    unwindBlock = Node(Token.BLOCK)
                                    unwind.setLineColumnNumber(n.lineno, n.column)
                                }
                                unwindBlock.addChildToBack(unwind)
                            }
                        }
                        if (unwindBlock != null) {
                            var returnNode = n
                            val returnExpr = returnNode.firstChild
                            node = replaceCurrent(parent, previous, n, unwindBlock)
                            if (returnExpr == null || isGenerator) {
                                unwindBlock.addChildToBack(returnNode)
                            } else {
                                val store = Node(Token.EXPR_RESULT, returnExpr)
                                unwindBlock.addChildToFront(store)
                                returnNode = Node(Token.RETURN_RESULT)
                                unwindBlock.addChildToBack(returnNode)
                                // Transform the return expression.
                                transformCompilationUnitR(
                                    tree,
                                    store,
                                    scope,
                                    createScopeObjects,
                                    inStrictMode,
                                )
                            }
                            // Skip the recursive call below, to avoid an infinite loop.
                            continue@siblingLoop
                        }
                    }
                }

                Token.BREAK, Token.CONTINUE -> {
                    val jump = n as Jump
                    val jumpStatement = jump.jumpStatement
                    if (jumpStatement == null) Kit.codeBug()

                    if (loops.isEmpty()) {
                        // The parser and IR factory guarantee that break and continue always carry
                        // a jump statement, so it should have been found.
                        throw Kit.codeBug()
                    }
                    // Walk from the top of the stack, most recently pushed first.
                    for (loopNode in loops) {
                        if (loopNode === jumpStatement) {
                            break
                        }

                        val elemtype = loopNode.type
                        if (elemtype == Token.WITH) {
                            val leave = Node(Token.LEAVEWITH)
                            previous = addBeforeCurrent(parent, previous, n, leave)
                        } else if (elemtype == Token.TRY) {
                            val jsrFinally = Jump(Token.JSR)
                            jsrFinally.target = (loopNode as Jump).finallyTarget
                            previous = addBeforeCurrent(parent, previous, n, jsrFinally)
                        }
                    }

                    if (type == Token.BREAK) {
                        jump.target = jumpStatement!!.target
                    } else {
                        jump.target = jumpStatement!!.continueTarget
                    }
                    jump.type = Token.GOTO
                }

                Token.CALL -> visitCall(n, tree)

                Token.NEW -> visitNew(n, tree)

                Token.LETEXPR, Token.LET, Token.CONST, Token.VAR -> {
                    var handled = false
                    if (type == Token.LETEXPR || type == Token.LET) {
                        val child = n.firstChild!!
                        if (child.type == Token.LET) {
                            // A let statement or expression, rather than a let declaration.
                            val createWith =
                                tree.type != Token.FUNCTION ||
                                    (tree as FunctionNode).requiresActivation
                            node = visitLet(createWith, parent, previous, n)
                            n = node
                            handled = true
                        }
                        // Otherwise fall through and process it as a let declaration.
                    }
                    if (!handled) {
                        val result = Node(Token.BLOCK)
                        var cursor = n.firstChild
                        while (cursor != null) {
                            // Move the cursor on before createAssignment can change n.next.
                            var v = cursor
                            cursor = cursor.next
                            if (v.type == Token.NAME) {
                                if (!v.hasChildren()) continue
                                val init = v.firstChild!!
                                v.removeChild(init)
                                v.type = Token.BINDNAME
                                v = Node(
                                    if (type == Token.CONST) Token.SETCONST else Token.SETNAME,
                                    v,
                                    init,
                                )
                            } else {
                                // May be a destructuring assignment already turned into a LETEXPR.
                                if (v.type != Token.LETEXPR) throw Kit.codeBug()
                            }
                            val pop = Node(Token.EXPR_VOID, v)
                            pop.setLineColumnNumber(n.lineno, n.column)
                            result.addChildToBack(pop)
                        }
                        node = replaceCurrent(parent, previous, n, result)
                        n = node
                    }
                }

                Token.TYPEOFNAME -> {
                    val defining = scope.getDefiningScope(n.string!!)
                    if (defining != null) {
                        n.scope = defining
                    }
                }

                Token.TYPEOF, Token.IFNE -> {
                    /*
                     * Suppress the undefined-property warning for o.p in these shapes:
                     * typeof o.p, if (o.p), if (!o.p), if (o.p == undefined), if (undefined == o.p)
                     */
                    var child = n.firstChild!!
                    if (type == Token.IFNE) {
                        while (child.type == Token.NOT) {
                            child = child.firstChild!!
                        }
                        if (child.type == Token.EQ || child.type == Token.NE) {
                            val first = child.firstChild!!
                            val last = child.lastChild!!
                            if (first.type == Token.UNDEFINED) {
                                child = last
                            } else if (last.type == Token.UNDEFINED) {
                                child = first
                            }
                        }
                    }
                    if (child.type == Token.GETPROP) {
                        child.type = Token.GETPROPNOWARN
                    }
                }

                Token.SETNAME, Token.NAME, Token.SETCONST, Token.DELPROP -> {
                    if (type == Token.SETNAME && inStrictMode) {
                        n.type = Token.STRICT_SETNAME
                        if (n.firstChild!!.type == Token.BINDNAME) {
                            val name = n.firstChild!!
                            if (name is Name && "eval" == name.identifier) {
                                // Assigning to eval is not allowed in strict mode.
                                Context.reportError("syntax error")
                            }
                        }
                    }

                    // Turn the name into a variable slot for faster access when possible.
                    if (!createScopeObjects) {
                        val nameSource: Node?
                        if (type == Token.NAME) {
                            nameSource = n
                        } else {
                            val first = n.firstChild!!
                            if (first.type != Token.BINDNAME) {
                                if (type == Token.DELPROP) {
                                    nameSource = null
                                } else {
                                    throw Kit.codeBug()
                                }
                            } else {
                                nameSource = first
                            }
                        }
                        if (nameSource != null && nameSource.scope == null) {
                            val name = nameSource.string!!
                            val defining = scope.getDefiningScope(name)
                            if (defining != null) {
                                nameSource.scope = defining
                                when (type) {
                                    Token.NAME -> n.type = Token.GETVAR
                                    Token.SETNAME -> {
                                        n.type = Token.SETVAR
                                        nameSource.type = Token.STRING
                                    }
                                    Token.SETCONST -> {
                                        n.type = Token.SETCONSTVAR
                                        nameSource.type = Token.STRING
                                    }
                                    Token.DELPROP -> {
                                        // A local variable is permanent by definition.
                                        node = replaceCurrent(
                                            parent,
                                            previous,
                                            n,
                                            Node(Token.FALSE),
                                        )
                                        n = node
                                    }
                                    else -> throw Kit.codeBug()
                                }
                            }
                        }
                    }
                }

                Token.OBJECTLIT -> {
                    val propertyIds = n.getProp(Node.OBJECT_IDS_PROP) as Array<*>?
                    propertyIds?.forEach { propertyId ->
                        if (propertyId is Node) {
                            transformCompilationUnitR(
                                tree,
                                propertyId,
                                if (n is Scope) n else scope,
                                createScopeObjects,
                                inStrictMode,
                            )
                        }
                    }
                }
            }

            transformCompilationUnitR(
                tree,
                n,
                if (n is Scope) n else scope,
                createScopeObjects,
                inStrictMode,
            )
        }
    }

    protected open fun visitNew(node: Node, tree: ScriptNode) {}

    protected open fun visitCall(node: Node, tree: ScriptNode) {}

    protected open fun visitLet(
        createWith: Boolean,
        parent: Node,
        previous: Node?,
        scopeNode: Node,
    ): Node {
        val vars = scopeNode.firstChild!!
        var body = vars.next!!
        scopeNode.removeChild(vars)
        scopeNode.removeChild(body)
        val isExpression = scopeNode.type == Token.LETEXPR
        var result: Node
        val newVars: Node
        if (createWith) {
            result = Node(if (isExpression) Token.WITHEXPR else Token.BLOCK)
            result = replaceCurrent(parent, previous, scopeNode, result)
            val list = mutableListOf<Any?>()
            val objectLiteral = Node(Token.OBJECTLIT)
            var v = vars.firstChild
            while (v != null) {
                var currentVar = v
                if (currentVar.type == Token.LETEXPR) {
                    // Destructuring in a let expression, as in let ([x, y] = [3, 4]) {}
                    val destructuringNames = currentVar.getProp(Node.DESTRUCTURING_NAMES) as List<*>?
                    val c = currentVar.firstChild!!
                    if (c.type != Token.LET) throw Kit.codeBug()
                    // Put the initialization code at the front of the body.
                    body = if (isExpression) {
                        Node(Token.COMMA, c.next!!, body)
                    } else {
                        Node(Token.BLOCK, Node(Token.EXPR_VOID, c.next!!), body)
                    }
                    // Update the list and the object literal for the variables the destructuring
                    // assignment defines.
                    if (destructuringNames != null) {
                        list.addAll(destructuringNames)
                        for (i in destructuringNames.indices) {
                            objectLiteral.addChildToBack(Node(Token.VOID, Node.newNumber(0.0)))
                        }
                    }
                    currentVar = c.firstChild!! // should be a NAME, checked below
                }
                if (currentVar.type != Token.NAME) throw Kit.codeBug()
                list.add(ScriptRuntime.getIndexObject(currentVar.string!!))
                val init = currentVar.firstChild ?: Node(Token.VOID, Node.newNumber(0.0))
                objectLiteral.addChildToBack(init)
                v = v.next
            }
            objectLiteral.putProp(Node.OBJECT_IDS_PROP, list.toTypedArray())
            newVars = Node(Token.ENTERWITH, objectLiteral)
            result.addChildToBack(newVars)
            result.addChildToBack(Node(Token.WITH, body))
            result.addChildToBack(Node(Token.LEAVEWITH))
        } else {
            result = Node(if (isExpression) Token.COMMA else Token.BLOCK)
            result = replaceCurrent(parent, previous, scopeNode, result)
            newVars = Node(Token.COMMA)
            var v = vars.firstChild
            while (v != null) {
                var currentVar = v
                if (currentVar.type == Token.LETEXPR) {
                    // Destructuring in a let expression, as in let ([x, y] = [3, 4]) {}
                    val c = currentVar.firstChild!!
                    if (c.type != Token.LET) throw Kit.codeBug()
                    // Put the initialization code at the front of the body.
                    body = if (isExpression) {
                        Node(Token.COMMA, c.next!!, body)
                    } else {
                        Node(Token.BLOCK, Node(Token.EXPR_VOID, c.next!!), body)
                    }
                    // The LETEXPR goes away, so move its symbols.
                    Scope.joinScopes(currentVar as Scope, scopeNode as Scope)
                    currentVar = c.firstChild!! // should be a NAME, checked below
                }
                if (currentVar.type != Token.NAME) throw Kit.codeBug()
                val stringNode = Node.newString(currentVar.string!!)
                stringNode.scope = scopeNode as Scope
                val init = currentVar.firstChild ?: Node(Token.VOID, Node.newNumber(0.0))
                newVars.addChildToBack(Node(Token.SETVAR, stringNode, init))
                v = v.next
            }
            if (isExpression) {
                result.addChildToBack(newVars)
                scopeNode.type = Token.COMMA
                result.addChildToBack(scopeNode)
                scopeNode.addChildToBack(body)
                if (body is Scope) {
                    val scopeParent = body.parentScope
                    body.parentScope = scopeNode as Scope
                    scopeNode.parentScope = scopeParent
                }
            } else {
                result.addChildToBack(Node(Token.EXPR_VOID, newVars))
                scopeNode.type = Token.BLOCK
                result.addChildToBack(scopeNode)
                scopeNode.addChildrenToBack(body)
                if (body is Scope) {
                    val scopeParent = body.parentScope
                    body.parentScope = scopeNode as Scope
                    scopeNode.parentScope = scopeParent
                }
            }
        }
        return result
    }

    public companion object {
        private fun addBeforeCurrent(
            parent: Node,
            previous: Node?,
            current: Node,
            toAdd: Node,
        ): Node {
            if (previous == null) {
                if (current !== parent.firstChild) Kit.codeBug()
                parent.addChildToFront(toAdd)
            } else {
                if (current !== previous.next) Kit.codeBug()
                parent.addChildAfter(toAdd, previous)
            }
            return toAdd
        }

        private fun replaceCurrent(
            parent: Node,
            previous: Node?,
            current: Node,
            replacement: Node,
        ): Node {
            if (previous == null) {
                if (current !== parent.firstChild) Kit.codeBug()
                parent.replaceChild(current, replacement)
            } else if (previous.next === current) {
                // The check is needed because the tree may have been mutated.
                parent.replaceChildAfter(previous, replacement)
            } else {
                parent.replaceChild(current, replacement)
            }
            return replacement
        }
    }
}
