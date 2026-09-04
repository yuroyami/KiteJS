/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.Comment
import io.github.yuroyami.kitejs.ast.FunctionNode
import io.github.yuroyami.kitejs.ast.Jump
import io.github.yuroyami.kitejs.ast.Name
import io.github.yuroyami.kitejs.ast.NumberLiteral
import io.github.yuroyami.kitejs.ast.Scope
import io.github.yuroyami.kitejs.ast.ScriptNode

/**
 * Root of the intermediate representation: a weakly typed tree node holding a linked
 * list of children and a linked list of properties.
 *
 * KMP: upstream exposes `type`, `lineno` and `column` as protected fields alongside
 * public accessors, and subclasses write the raw field to bypass any accessor override.
 * The port keeps both halves: `typeField`/`linenoField`/`columnField` are the raw
 * storage, `type`/`lineno`/`column` are the (overridable) accessors.
 */
open class Node : Iterable<Node> {

    constructor(nodeType: Int) {
        typeField = nodeType
    }

    constructor(nodeType: Int, child: Node) {
        typeField = nodeType
        first = child
        last = child
        child.next = null
    }

    constructor(nodeType: Int, left: Node, right: Node) {
        typeField = nodeType
        first = left
        last = right
        left.next = right
        right.next = null
    }

    constructor(nodeType: Int, left: Node, mid: Node, right: Node) {
        typeField = nodeType
        first = left
        last = right
        left.next = mid
        mid.next = right
        right.next = null
    }

    constructor(nodeType: Int, line: Int, column: Int) : this(nodeType) {
        setLineColumnNumber(line, column)
    }

    constructor(nodeType: Int, child: Node, line: Int, column: Int) : this(nodeType, child) {
        setLineColumnNumber(line, column)
    }

    constructor(
        nodeType: Int,
        left: Node,
        right: Node,
        line: Int,
        column: Int,
    ) : this(nodeType, left, right) {
        setLineColumnNumber(line, column)
    }

    constructor(
        nodeType: Int,
        left: Node,
        mid: Node,
        right: Node,
        line: Int,
        column: Int,
    ) : this(nodeType, left, mid, right) {
        setLineColumnNumber(line, column)
    }

    /** Raw node type storage. Subclasses set it directly to skip any accessor override. */
    internal var typeField: Int = Token.ERROR
    internal var next: Node? = null
    internal var first: Node? = null
    internal var last: Node? = null
    internal var linenoField: Int = -1
    private var columnField: Int = -1

    /**
     * Linked list of properties. The vast majority of nodes hold no more than two, so a
     * linked list saves memory and stays fast to look up.
     */
    internal var propListHead: PropListItem? = null

    internal class PropListItem {
        var next: PropListItem? = null
        var type: Int = 0
        var intValue: Int = 0
        var objectValue: Any? = null
    }

    /** The node type, e.g. [Token.NAME]. */
    open var type: Int
        get() = typeField
        set(value) {
            typeField = value
        }

    /** The JsDoc comment string attached to this node, or null. */
    val jsDoc: String?
        get() = jsDocNode?.value

    /** The JsDoc [Comment] attached to this node, or null. */
    var jsDocNode: Comment?
        get() = getProp(JSDOC_PROP) as Comment?
        set(value) {
            putProp(JSDOC_PROP, value)
        }

    fun hasChildren(): Boolean = first != null

    val firstChild: Node?
        get() = first

    val lastChild: Node?
        get() = last

    fun getChildBefore(child: Node): Node? {
        if (child === first) return null
        var n = first
        while (n!!.next !== child) {
            n = n.next
            if (n == null) throw RuntimeException("node is not a child")
        }
        return n
    }

    val lastSibling: Node
        get() {
            var n: Node = this
            while (n.next != null) {
                n = n.next!!
            }
            return n
        }

    fun addChildToFront(child: Node) {
        child.next = first
        first = child
        if (last == null) {
            last = child
        }
    }

    fun addChildToBack(child: Node) {
        child.next = null
        if (last == null) {
            first = child
            last = child
            return
        }
        last!!.next = child
        last = child
    }

    fun addChildrenToFront(children: Node) {
        val lastSib = children.lastSibling
        lastSib.next = first
        first = children
        if (last == null) {
            last = lastSib
        }
    }

    fun addChildrenToBack(children: Node) {
        last?.next = children
        last = children.lastSibling
        if (first == null) {
            first = children
        }
    }

    /** Add [newChild] before [node]. */
    fun addChildBefore(newChild: Node, node: Node) {
        if (newChild.next != null) {
            throw RuntimeException("newChild had siblings in addChildBefore")
        }
        if (first === node) {
            newChild.next = first
            first = newChild
            return
        }
        val prev = getChildBefore(node)
        addChildAfter(newChild, prev!!)
    }

    /** Add [newChild] after [node]. */
    fun addChildAfter(newChild: Node, node: Node) {
        if (newChild.next != null) {
            throw RuntimeException("newChild had siblings in addChildAfter")
        }
        newChild.next = node.next
        node.next = newChild
        if (last === node) last = newChild
    }

    fun removeChild(child: Node) {
        val prev = getChildBefore(child)
        if (prev == null) first = first!!.next else prev.next = child.next
        if (child === last) last = prev
        child.next = null
    }

    fun replaceChild(child: Node, newChild: Node) {
        if (child === newChild) return
        newChild.next = child.next
        if (child === first) {
            first = newChild
        } else {
            getChildBefore(child)!!.next = newChild
        }
        if (child === last) last = newChild
        child.next = null
    }

    fun replaceChildAfter(prevChild: Node, newChild: Node) {
        val child = prevChild.next!!
        newChild.next = child.next
        prevChild.next = newChild
        if (child === last) last = newChild
        child.next = null
    }

    fun removeChildren() {
        first = null
        last = null
    }

    /**
     * Iterates over the children of this Node. Supports child removal. Not thread-safe: if
     * anyone changes the child list before the iterator finishes, the results are undefined
     * and probably bad.
     */
    inner class NodeIterator : MutableIterator<Node> {
        private var cursor: Node? = this@Node.first
        private var prev: Node? = NOT_SET
        private var prev2: Node? = null
        private var removed = false

        override fun hasNext(): Boolean = cursor != null

        override fun next(): Node {
            val current = cursor ?: throw NoSuchElementException()
            removed = false
            prev2 = prev
            prev = current
            cursor = current.next
            return current
        }

        override fun remove() {
            check(prev !== NOT_SET) { "next() has not been called" }
            check(!removed) { "remove() already called for current element" }
            when {
                prev === first -> first = prev!!.next
                prev === last -> {
                    prev2!!.next = null
                    last = prev2
                }
                else -> prev2!!.next = cursor
            }
        }
    }

    override fun iterator(): MutableIterator<Node> = NodeIterator()

    private fun lookupProperty(propType: Int): PropListItem? {
        var x = propListHead
        while (x != null && propType != x.type) {
            x = x.next
        }
        return x
    }

    private fun ensureProperty(propType: Int): PropListItem {
        var item = lookupProperty(propType)
        if (item == null) {
            item = PropListItem()
            item.type = propType
            item.next = propListHead
            propListHead = item
        }
        return item
    }

    fun removeProp(propType: Int) {
        var x = propListHead ?: return
        var prev: PropListItem? = null
        while (x.type != propType) {
            prev = x
            x = x.next ?: return
        }
        if (prev == null) propListHead = x.next else prev.next = x.next
    }

    fun getProp(propType: Int): Any? = lookupProperty(propType)?.objectValue

    fun getIntProp(propType: Int, defaultValue: Int): Int =
        lookupProperty(propType)?.intValue ?: defaultValue

    fun getExistingIntProp(propType: Int): Int =
        (lookupProperty(propType) ?: throw Kit.codeBug()).intValue

    fun putProp(propType: Int, prop: Any?) {
        if (prop == null) {
            removeProp(propType)
        } else {
            ensureProperty(propType).objectValue = prop
        }
    }

    fun putIntProp(propType: Int, prop: Int) {
        ensureProperty(propType).intValue = prop
    }

    /** The line number recorded for this node. */
    open val lineno: Int
        get() = linenoField

    /**
     * The column where this node is defined in source, one-based. -1 means it was never
     * initialized. May be overridden by subclasses.
     */
    open val column: Int
        get() = columnField

    fun setLineColumnNumber(lineno: Int, column: Int) {
        this.linenoField = lineno
        this.columnField = column
    }

    /** Only valid when `type == Token.NUMBER`. */
    var double: Double
        get() = (this as NumberLiteral).number
        set(value) {
            (this as NumberLiteral).number = value
        }

    /** Only valid when `type == Token.BIGINT`. */
    open var bigInt: KBigInt
        get() = throw UnsupportedOperationException("Can only be called when Token.BIGINT")
        set(value) {
            throw UnsupportedOperationException("Can only be called when Token.BIGINT")
        }

    /** Only valid when the node has String context. */
    var string: String?
        get() = (this as Name).identifier
        set(value) {
            (this as Name).identifier = value
        }

    /** Only valid when the node has String context. */
    open var scope: Scope?
        get() = (this as Name).scope
        set(value) {
            if (value == null) Kit.codeBug()
            if (this !is Name) throw Kit.codeBug()
            this.scope = value
        }

    fun labelId(): Int {
        if (typeField != Token.TARGET &&
            typeField != Token.YIELD &&
            typeField != Token.YIELD_STAR
        ) {
            Kit.codeBug()
        }
        return getIntProp(LABEL_ID_PROP, -1)
    }

    fun labelId(labelId: Int) {
        if (typeField != Token.TARGET &&
            typeField != Token.YIELD &&
            typeField != Token.YIELD_STAR
        ) {
            Kit.codeBug()
        }
        putIntProp(LABEL_ID_PROP, labelId)
    }

    /**
     * Checks that every return usage in a function body is consistent with strict mode.
     * See the END_* flags for what the analysis reports.
     */
    fun hasConsistentReturnUsage(): Boolean {
        val n = endCheck()
        return (n and END_RETURNS_VALUE) == 0 ||
            (n and (END_DROPS_OFF or END_RETURNS or END_YIELDS)) == 0
    }

    /**
     * Returns in the then and else blocks must be consistent with each other. If there is
     * no else block, the return statement can fall through.
     */
    private fun endCheckIf(): Int {
        val th = next
        val el = (this as Jump).target

        var rv = th!!.endCheck()
        rv = if (el != null) rv or el.endCheck() else rv or END_DROPS_OFF
        return rv
    }

    /**
     * Consistency of return statements is checked between the case statements. Upstream
     * commented out the body when switches stopped being Jumps; kept for the same reason.
     */
    private fun endCheckSwitch(): Int = END_UNREACHED

    /**
     * If the block has a finally, return consistency is checked in the finally block.
     * Upstream commented out the body: a TryStatement is not a Jump any more.
     */
    private fun endCheckTry(): Int = END_UNREACHED

    /**
     * Return statement in the loop body must be consistent. Any loop is assumed to
     * terminate eventually, except one with a constant true condition.
     */
    private fun endCheckLoop(): Int {
        // The loop body hangs off the second-to-last node of the loop node, which is the
        // predicate; its target is the loop body for all four kinds of loop.
        var n = first!!
        while (n.next !== last) {
            n = n.next!!
        }
        if (n.typeField != Token.IFEQ) return END_DROPS_OFF

        var rv = (n as Jump).target!!.next!!.endCheck()

        // A constant-true condition means control never drops off the end.
        if (n.first!!.typeField == Token.TRUE) rv = rv and END_DROPS_OFF.inv()

        return rv or getIntProp(CONTROL_BLOCK_PROP, END_UNREACHED)
    }

    /**
     * A general block of code is examined statement by statement. If any statement returns
     * in all branches, subsequent statements are not examined.
     */
    private fun endCheckBlock(): Int {
        var rv = END_DROPS_OFF
        var n = first
        while ((rv and END_DROPS_OFF) != 0 && n != null) {
            rv = rv and END_DROPS_OFF.inv()
            rv = rv or n.endCheck()
            n = n.next
        }
        return rv
    }

    /**
     * A labelled statement implies there may be a break to the label, so the CONTROL_BLOCK_PROP
     * property is folded in after processing the statement.
     */
    private fun endCheckLabel(): Int =
        next!!.endCheck() or getIntProp(CONTROL_BLOCK_PROP, END_UNREACHED)

    /** A break annotates the statement being broken out of with CONTROL_BLOCK_PROP. */
    private fun endCheckBreak(): Int {
        (this as Jump).jumpStatement!!.putIntProp(CONTROL_BLOCK_PROP, END_DROPS_OFF)
        return END_UNREACHED
    }

    /**
     * Basic reachability analysis over a function body. The returned flags are the
     * pessimistic set of termination conditions: some paths flagged here may never be
     * taken at runtime.
     */
    private fun endCheck(): Int = when (typeField) {
        Token.BREAK -> endCheckBreak()

        Token.EXPR_VOID -> if (first != null) first!!.endCheck() else END_DROPS_OFF

        Token.YIELD, Token.YIELD_STAR -> END_YIELDS

        Token.CONTINUE, Token.THROW -> END_UNREACHED

        Token.RETURN -> if (first != null) END_RETURNS_VALUE else END_RETURNS

        Token.TARGET -> if (next != null) next!!.endCheck() else END_DROPS_OFF

        Token.LOOP -> endCheckLoop()

        Token.LOCAL_BLOCK, Token.BLOCK -> {
            // There are several special kinds of block.
            val head = first
            when {
                head == null -> END_DROPS_OFF
                head.typeField == Token.LABEL -> head.endCheckLabel()
                head.typeField == Token.IFNE -> head.endCheckIf()
                head.typeField == Token.SWITCH -> head.endCheckSwitch()
                head.typeField == Token.TRY -> head.endCheckTry()
                else -> endCheckBlock()
            }
        }

        else -> END_DROPS_OFF
    }

    open fun hasSideEffects(): Boolean = when (typeField) {
        Token.EXPR_VOID, Token.COMMA -> last?.hasSideEffects() ?: true

        Token.HOOK -> {
            if (first == null || first!!.next == null || first!!.next!!.next == null) Kit.codeBug()
            first!!.next!!.hasSideEffects() && first!!.next!!.next!!.hasSideEffects()
        }

        Token.AND, Token.OR -> {
            if (first == null || last == null) Kit.codeBug()
            first!!.hasSideEffects() || last!!.hasSideEffects()
        }

        Token.ERROR, // Avoid cascaded error messages
        Token.EXPR_RESULT,
        Token.ASSIGN,
        Token.ASSIGN_ADD,
        Token.ASSIGN_SUB,
        Token.ASSIGN_MUL,
        Token.ASSIGN_DIV,
        Token.ASSIGN_MOD,
        Token.ASSIGN_BITOR,
        Token.ASSIGN_LOGICAL_OR,
        Token.ASSIGN_BITXOR,
        Token.ASSIGN_BITAND,
        Token.ASSIGN_LOGICAL_AND,
        Token.ASSIGN_LSH,
        Token.ASSIGN_RSH,
        Token.ASSIGN_URSH,
        Token.ASSIGN_EXP,
        Token.ASSIGN_NULLISH,
        Token.ENTERWITH,
        Token.LEAVEWITH,
        Token.RETURN,
        Token.GOTO,
        Token.IFEQ,
        Token.IFNE,
        Token.NEW,
        Token.DELPROP,
        Token.SETNAME,
        Token.SETPROP,
        Token.SETELEM,
        Token.CALL,
        Token.THROW,
        Token.RETHROW,
        Token.SETVAR,
        Token.CATCH_SCOPE,
        Token.RETURN_RESULT,
        Token.SET_REF,
        Token.DEL_REF,
        Token.REF_CALL,
        Token.TRY,
        Token.SEMI,
        Token.INC,
        Token.DEC,
        Token.IF,
        Token.ELSE,
        Token.SWITCH,
        Token.WHILE,
        Token.DO,
        Token.FOR,
        Token.BREAK,
        Token.CONTINUE,
        Token.VAR,
        Token.CONST,
        Token.LET,
        Token.LETEXPR,
        Token.WITH,
        Token.WITHEXPR,
        Token.CATCH,
        Token.FINALLY,
        Token.BLOCK,
        Token.LABEL,
        Token.TARGET,
        Token.LOOP,
        Token.JSR,
        Token.SETPROP_OP,
        Token.SETELEM_OP,
        Token.LOCAL_BLOCK,
        Token.SET_REF_OP,
        Token.YIELD,
        Token.YIELD_STAR,
        -> true

        else -> false
    }

    /**
     * Recursively unlabel every TARGET or YIELD node in the tree. Used only for inlining
     * finally blocks where jsr instructions used to be.
     */
    fun resetTargets() {
        if (typeField == Token.FINALLY) {
            resetTargetsRecursive()
        } else {
            Kit.codeBug()
        }
    }

    private fun resetTargetsRecursive() {
        if (typeField == Token.TARGET ||
            typeField == Token.YIELD ||
            typeField == Token.YIELD_STAR
        ) {
            labelId(-1)
        }
        var child = first
        while (child != null) {
            child.resetTargetsRecursive()
            child = child.next
        }
    }

    override fun toString(): String {
        if (!Token.printTrees) return typeField.toString()
        val sb = StringBuilder()
        toString(HashMap(), sb)
        return sb.toString()
    }

    private fun toString(printIds: MutableMap<Node, Int>, sb: StringBuilder) {
        if (!Token.printTrees) return

        sb.append(Token.name(typeField))
        when {
            this is Name -> {
                sb.append(' ')
                sb.append(string)
                val nameScope = scope
                if (nameScope != null) {
                    sb.append("[scope: ")
                    appendPrintId(nameScope, printIds, sb)
                    sb.append("]")
                }
            }
            this is Scope -> {
                if (this is ScriptNode) {
                    if (this is FunctionNode) {
                        sb.append(' ')
                        sb.append(name)
                    }
                    sb.append(" [source name: ")
                    sb.append(sourceName)
                    sb.append("] [raw source length: ")
                    sb.append(rawSourceEnd - rawSourceStart)
                    sb.append("] [base line: ")
                    sb.append(baseLineno)
                    sb.append("] [end line: ")
                    sb.append(endLineno)
                    sb.append(']')
                }
                if (symbolTable != null) {
                    sb.append(" [scope ")
                    appendPrintId(this, printIds, sb)
                    sb.append(": ")
                    for (s in symbolTable!!.keys) {
                        sb.append(s)
                        sb.append(" ")
                    }
                    sb.append("]")
                }
            }
            this is Jump -> {
                when {
                    typeField == Token.BREAK || typeField == Token.CONTINUE -> {
                        sb.append(" [label: ")
                        appendPrintId(jumpStatement, printIds, sb)
                        sb.append(']')
                    }
                    typeField == Token.TRY -> {
                        val catchNode = target
                        val finallyNode = finallyTarget
                        if (catchNode != null) {
                            sb.append(" [catch: ")
                            appendPrintId(catchNode, printIds, sb)
                            sb.append(']')
                        }
                        if (finallyNode != null) {
                            sb.append(" [finally: ")
                            appendPrintId(finallyNode, printIds, sb)
                            sb.append(']')
                        }
                    }
                    typeField == Token.LABEL ||
                        typeField == Token.LOOP ||
                        typeField == Token.SWITCH -> {
                        sb.append(" [break: ")
                        appendPrintId(target, printIds, sb)
                        sb.append(']')
                        if (typeField == Token.LOOP) {
                            sb.append(" [continue: ")
                            appendPrintId(continueTarget, printIds, sb)
                            sb.append(']')
                        }
                    }
                    else -> {
                        sb.append(" [target: ")
                        appendPrintId(target, printIds, sb)
                        sb.append(']')
                    }
                }
            }
            typeField == Token.NUMBER -> {
                sb.append(' ')
                sb.append(double)
            }
            typeField == Token.BIGINT -> {
                sb.append(' ')
                sb.append(bigInt.toString())
            }
            typeField == Token.TARGET -> {
                sb.append(' ')
                appendPrintId(this, printIds, sb)
            }
        }
        if (linenoField != -1) {
            sb.append(' ')
            sb.append(linenoField)
        }

        var x = propListHead
        while (x != null) {
            val propType = x.type
            sb.append(" [")
            sb.append(propToString(propType))
            sb.append(": ")
            when (propType) {
                // Cannot print the target block: it recurses.
                TARGETBLOCK_PROP -> sb.append("target block property")
                // Cannot print the last local block: it is dull.
                LOCAL_BLOCK_PROP -> sb.append("last local block")
                ISNUMBER_PROP -> when (x.intValue) {
                    BOTH -> sb.append("both")
                    RIGHT -> sb.append("right")
                    LEFT -> sb.append("left")
                    else -> throw Kit.codeBug()
                }
                SPECIALCALL_PROP -> when (x.intValue) {
                    SPECIALCALL_EVAL -> sb.append("eval")
                    SPECIALCALL_WITH -> sb.append("with")
                    // NON_SPECIALCALL should not be stored.
                    else -> throw Kit.codeBug()
                }
                OBJECT_IDS_PROP -> {
                    val a = x.objectValue as Array<*>
                    sb.append("[")
                    for (i in a.indices) {
                        if (a[i] != null) sb.append(a[i].toString())
                        if (i + 1 < a.size) sb.append(", ")
                    }
                    sb.append("]")
                }
                else -> {
                    val obj = x.objectValue
                    if (obj != null) sb.append(obj.toString()) else sb.append(x.intValue.toString())
                }
            }
            sb.append(']')
            x = x.next
        }
    }

    fun toStringTree(treeTop: ScriptNode): String? {
        if (!Token.printTrees) return null
        val sb = StringBuilder()
        toStringTreeHelper(treeTop, this, null, 0, sb)
        return sb.toString()
    }

    companion object {
        const val FUNCTION_PROP = 1
        const val LOCAL_PROP = 2
        const val LOCAL_BLOCK_PROP = 3
        const val REGEXP_PROP = 4
        const val CASEARRAY_PROP = 5

        // The following properties are defined and manipulated by the optimizer:
        // TARGETBLOCK_PROP - the block referenced by a branch node
        // VARIABLE_PROP - the variable referenced by a BIND or NAME node
        // ISNUMBER_PROP - this node generates code on Number children and delivers a
        //                 Number result (as opposed to Objects)
        // DIRECTCALL_PROP - this call node should emit code to test the function object
        //                   against the known class and call direct if it matches.
        const val TARGETBLOCK_PROP = 6
        const val VARIABLE_PROP = 7
        const val ISNUMBER_PROP = 8
        const val DIRECTCALL_PROP = 9
        const val SPECIALCALL_PROP = 10
        const val SKIP_INDEXES_PROP = 11 // array of skipped indexes of array literal
        const val OBJECT_IDS_PROP = 12 // array of properties for object literal
        const val INCRDECR_PROP = 13 // pre or post type of increment/decrement
        const val CATCH_SCOPE_PROP = 14 // index of catch scope block in catch
        const val LABEL_ID_PROP = 15 // label id: code generation uses it
        const val MEMBER_TYPE_PROP = 16 // type of element access operation
        const val NAME_PROP = 17 // property name
        const val CONTROL_BLOCK_PROP = 18 // flags a control block that can drop off
        const val PARENTHESIZED_PROP = 19 // expression is parenthesized
        const val GENERATOR_END_PROP = 20
        const val DESTRUCTURING_ARRAY_LENGTH = 21
        const val DESTRUCTURING_NAMES = 22
        const val DESTRUCTURING_PARAMS = 23
        const val JSDOC_PROP = 24
        const val EXPRESSION_CLOSURE_PROP = 25 // JS 1.8 expression closure pseudo-return
        const val ARROW_FUNCTION_PROP = 26
        const val TEMPLATE_LITERAL_PROP = 27
        const val TRAILING_COMMA = 28
        const val OBJECT_LITERAL_DESTRUCTURING = 29
        const val OPTIONAL_CHAINING = 30
        const val SUPER_PROPERTY_ACCESS = 31
        const val NUMBER_OF_SPREAD = 32
        const val LAST_PROP = NUMBER_OF_SPREAD
        const val FIRST_PROP = FUNCTION_PROP

        // Values of ISNUMBER_PROP: which of the children are Number types.
        const val BOTH = 0
        const val LEFT = 1
        const val RIGHT = 2

        // Values for SPECIALCALL_PROP.
        const val NON_SPECIALCALL = 0
        const val SPECIALCALL_EVAL = 1
        const val SPECIALCALL_WITH = 2

        // Flags for INCRDECR_PROP.
        const val DECR_FLAG = 0x1
        const val POST_FLAG = 0x2

        // Flags for MEMBER_TYPE_PROP.
        const val PROPERTY_FLAG = 0x1 // property access: element is valid name
        const val ATTRIBUTE_FLAG = 0x2 // x.@y or x..@y
        const val DESCENDANTS_FLAG = 0x4 // x..y or x..@i

        /**
         * These flags enumerate the ways a statement or function can terminate. END_UNREACHED
         * is reserved for code paths assumed to always execute (throw, continue).
         * END_DROPS_OFF means the statement can transfer control to the next one.
         * END_RETURNS means it can return without arguments, END_RETURNS_VALUE with one.
         */
        const val END_UNREACHED = 0
        const val END_DROPS_OFF = 1
        const val END_RETURNS = 2
        const val END_RETURNS_VALUE = 4
        const val END_YIELDS = 8

        private val NOT_SET = Node(Token.ERROR)

        fun newNumber(number: Double): Node {
            val n = NumberLiteral()
            n.number = number
            return n
        }

        fun newString(str: String): Node = newString(Token.STRING, str)

        fun newString(type: Int, str: String): Node {
            val name = Name()
            name.identifier = str
            name.type = type
            return name
        }

        fun newTarget(): Node = Node(Token.TARGET)

        private fun propToString(propType: Int): String? =
            // When Token.printTrees is false the compiler can drop all these strings.
            if (!Token.printTrees) null else propName(propType)

        internal fun propName(propType: Int): String = when (propType) {
            FUNCTION_PROP -> "function"
            LOCAL_PROP -> "local"
            LOCAL_BLOCK_PROP -> "local_block"
            REGEXP_PROP -> "regexp"
            CASEARRAY_PROP -> "casearray"
            TARGETBLOCK_PROP -> "targetblock"
            VARIABLE_PROP -> "variable"
            ISNUMBER_PROP -> "isnumber"
            DIRECTCALL_PROP -> "directcall"
            SPECIALCALL_PROP -> "specialcall"
            SKIP_INDEXES_PROP -> "skip_indexes"
            OBJECT_IDS_PROP -> "object_ids_prop"
            INCRDECR_PROP -> "incrdecr_prop"
            CATCH_SCOPE_PROP -> "catch_scope_prop"
            LABEL_ID_PROP -> "label_id_prop"
            MEMBER_TYPE_PROP -> "member_type_prop"
            NAME_PROP -> "name_prop"
            CONTROL_BLOCK_PROP -> "control_block_prop"
            PARENTHESIZED_PROP -> "parenthesized_prop"
            GENERATOR_END_PROP -> "generator_end"
            DESTRUCTURING_ARRAY_LENGTH -> "destructuring_array_length"
            DESTRUCTURING_NAMES -> "destructuring_names"
            DESTRUCTURING_PARAMS -> "destructuring_params"
            JSDOC_PROP -> "jsdoc"
            EXPRESSION_CLOSURE_PROP -> "expression_closure_prop"
            ARROW_FUNCTION_PROP -> "arrow_function"
            TEMPLATE_LITERAL_PROP -> "template_literal"
            TRAILING_COMMA -> "trailing comma"
            OBJECT_LITERAL_DESTRUCTURING -> "object_literal_destructuring"
            OPTIONAL_CHAINING -> "optional_chaining"
            SUPER_PROPERTY_ACCESS -> "super_property_access"
            NUMBER_OF_SPREAD -> "number_of_spread"
            else -> throw Kit.codeBug()
        }

        private fun toStringTreeHelper(
            treeTop: ScriptNode,
            n: Node,
            printIds: MutableMap<Node, Int>?,
            level: Int,
            sb: StringBuilder,
        ) {
            if (!Token.printTrees) return
            var ids = printIds
            if (ids == null) {
                ids = HashMap()
                generatePrintIds(treeTop, ids)
            }
            repeat(level) { sb.append("    ") }
            n.toString(ids, sb)
            sb.append('\n')
            var cursor = n.firstChild
            while (cursor != null) {
                if (cursor.type == Token.FUNCTION) {
                    val fnIndex = cursor.getExistingIntProp(FUNCTION_PROP)
                    val fn = treeTop.getFunctionNode(fnIndex)
                    toStringTreeHelper(fn, fn, null, level + 1, sb)
                } else {
                    toStringTreeHelper(treeTop, cursor, ids, level + 1, sb)
                }
                cursor = cursor.next
            }
        }

        private fun generatePrintIds(n: Node, map: MutableMap<Node, Int>) {
            if (!Token.printTrees) return
            map[n] = map.size
            var cursor = n.firstChild
            while (cursor != null) {
                generatePrintIds(cursor, map)
                cursor = cursor.next
            }
        }

        private fun appendPrintId(n: Node?, printIds: Map<Node, Int>, sb: StringBuilder) {
            if (!Token.printTrees) return
            if (n != null) {
                val id = printIds[n] ?: -1
                sb.append('#')
                if (id != -1) sb.append(id + 1) else sb.append("<not_available>")
            }
        }
    }
}
