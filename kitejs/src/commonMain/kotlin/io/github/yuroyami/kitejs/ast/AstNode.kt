/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Kit
import io.github.yuroyami.kitejs.Node
import io.github.yuroyami.kitejs.Token

/**
 * Base class for AST node types. The AST represents the physical source code, which makes
 * it useful for IDEs and pretty-printers; the parser must not rewrite the tree when
 * producing it.
 *
 * The hierarchy sits on top of the older [Node] class, which was designed for code
 * generation and keeps its children in a weakly typed linked list. [AstNode] is a strongly
 * typed facade over that list with named accessors, plus position, length and parent.
 *
 * All offset fields in subclasses are relative to the parent. During parsing the positions
 * are absolute; adding a node to its parent rewrites them to be relative, so by the time a
 * visitor sees the tree all offsets are relative.
 */
public abstract class AstNode(
    pos: Int = -1,
    len: Int = 1,
) : Node(Token.ERROR), Comparable<AstNode> {

    /** Relative position in the parent. */
    public var position: Int = pos

    /** Number of characters spanned by this node in the source text. */
    public var length: Int = len

    /**
     * KMP: upstream's protected `parent` field. [Scope.splitScope] rewires parent links
     * without the position fix-up that the [parent] accessor performs, so both halves stay.
     */
    internal var parentField: AstNode? = null

    /**
     * Comments on the same line as the statement, for example the trailing comment in
     * `if (x == 2) // note`.
     */
    public var inlineComment: AstNode? = null

    /** Sorts nodes by relative start position, so it only compares siblings. */
    public class PositionComparator : Comparator<AstNode> {
        override fun compare(a: AstNode, b: AstNode): Int = a.position - b.position
    }

    /**
     * The absolute document position, computed by adding this node's relative position to
     * the relative positions of all its parents.
     */
    public val absolutePosition: Int
        get() {
            var pos = position
            var p = parentField
            while (p != null) {
                pos += p.position
                p = p.parentField
            }
            return pos
        }

    /** Sets the start and end positions; the length becomes `end - position`. */
    public fun setBounds(position: Int, end: Int) {
        this.position = position
        this.length = end - position
    }

    /**
     * Makes this node's position relative to a parent. The current position is assumed to be
     * absolute and is decremented by [parentPosition].
     */
    public fun setRelative(parentPosition: Int) {
        this.position -= parentPosition
    }

    /**
     * The node parent, or null. Setting it adjusts this node's start position to be relative
     * to the new parent.
     */
    public var parent: AstNode?
        get() = parentField
        set(value) {
            if (value === parentField) return

            // Convert the position back to absolute.
            parentField?.let { setRelative(-it.absolutePosition) }

            parentField = value
            if (value != null) setRelative(value.absolutePosition)
        }

    /**
     * Adds a child to the end of the block. Sets the child's parent to this node, rewrites
     * the child position to be relative, and grows this node to include the child.
     */
    public fun addChild(kid: AstNode) {
        val end = kid.position + kid.length
        length = end - this.position
        addChildToBack(kid)
        kid.parent = this
    }

    /** The [AstRoot] at the top of this node's parent chain, or null if there is none. */
    public val astRoot: AstRoot?
        get() {
            var p: AstNode? = this // this node could be the AstRoot
            while (p != null && p !is AstRoot) {
                p = p.parent
            }
            return p
        }

    /**
     * Emits source code for this node. The caller recurses into children, incrementing
     * [depth] as appropriate.
     *
     * In error-recovery mode some nodes may have null children that are non-null in an
     * error-free tree; the behavior of toSource is undefined in that case.
     */
    public abstract fun toSource(depth: Int = 0): String

    /** Constructs an indentation string of [indent] steps. */
    public fun makeIndent(indent: Int): String = INDENTATIONS[indent.coerceIn(0, MAX_INDENT)]

    /** A short, descriptive name for the node, such as "ArrayComprehension". */
    public fun shortName(): String = this::class.simpleName ?: ""

    /**
     * Visits this node and its children in an arbitrary order.
     *
     * Each subclass decides the order for processing its children and which children are
     * passed to the visitor at all. Normally children are visited in lexical order.
     */
    public abstract fun visit(visitor: NodeVisitor)

    // Subclasses with potential side effects override this.
    override fun hasSideEffects(): Boolean = when (type) {
        Token.ASSIGN,
        Token.ASSIGN_ADD,
        Token.ASSIGN_BITAND,
        Token.ASSIGN_LOGICAL_AND,
        Token.ASSIGN_BITOR,
        Token.ASSIGN_LOGICAL_OR,
        Token.ASSIGN_BITXOR,
        Token.ASSIGN_DIV,
        Token.ASSIGN_LSH,
        Token.ASSIGN_MOD,
        Token.ASSIGN_MUL,
        Token.ASSIGN_RSH,
        Token.ASSIGN_SUB,
        Token.ASSIGN_URSH,
        Token.ASSIGN_EXP,
        Token.ASSIGN_NULLISH,
        Token.BLOCK,
        Token.BREAK,
        Token.CALL,
        Token.CATCH,
        Token.CATCH_SCOPE,
        Token.CONST,
        Token.CONTINUE,
        Token.DEC,
        Token.DELPROP,
        Token.DEL_REF,
        Token.DO,
        Token.ELSE,
        Token.ENTERWITH,
        Token.ERROR, // Avoid cascaded error messages
        Token.EXPORT,
        Token.EXPR_RESULT,
        Token.FINALLY,
        Token.FUNCTION,
        Token.FOR,
        Token.GOTO,
        Token.IF,
        Token.IFEQ,
        Token.IFNE,
        Token.IMPORT,
        Token.INC,
        Token.JSR,
        Token.LABEL,
        Token.LEAVEWITH,
        Token.LET,
        Token.LETEXPR,
        Token.LOCAL_BLOCK,
        Token.LOOP,
        Token.NEW,
        Token.REF_CALL,
        Token.RETHROW,
        Token.RETURN,
        Token.RETURN_RESULT,
        Token.SEMI,
        Token.SETELEM,
        Token.SETELEM_OP,
        Token.SETNAME,
        Token.SETPROP,
        Token.SETPROP_OP,
        Token.SETVAR,
        Token.SET_REF,
        Token.SET_REF_OP,
        Token.SWITCH,
        Token.TARGET,
        Token.THROW,
        Token.TRY,
        Token.VAR,
        Token.WHILE,
        Token.WITH,
        Token.WITHEXPR,
        Token.YIELD,
        Token.YIELD_STAR,
        -> true

        else -> false
    }

    /** Throws IllegalArgumentException if [arg] is null. */
    protected fun assertNotNull(arg: Any?) {
        if (arg == null) throw IllegalArgumentException("arg cannot be null")
    }

    /** Prints a comma-separated item list into [sb]. */
    protected fun printList(items: List<AstNode>, sb: StringBuilder) {
        val max = items.size
        var count = 0
        for (item in items) {
            sb.append(item.toSource(0))
            if (count++ < max - 1) {
                sb.append(", ")
            } else if (item is EmptyExpression) {
                sb.append(",")
            }
        }
    }

    /** The innermost enclosing function, or null. The search begins with the parent. */
    public val enclosingFunction: FunctionNode?
        get() {
            var p = this.parent
            while (p != null && p !is FunctionNode) {
                p = p.parent
            }
            return p
        }

    /**
     * The innermost enclosing [Scope], or null. The search begins with the parent. This is
     * not the same as the defining scope for a [Name].
     */
    public val enclosingScope: Scope?
        get() {
            var p = this.parent
            while (p != null && p !is Scope) {
                p = p.parent
            }
            return p
        }

    /**
     * Sorts by start position, then by length, then arbitrarily by hash code. Lets Comment
     * and error nodes be mixed into a sorted collection of other AST nodes.
     */
    override fun compareTo(other: AstNode): Int {
        if (this == other) return 0
        val abs1 = this.absolutePosition
        val abs2 = other.absolutePosition
        if (abs1 < abs2) return -1
        if (abs2 < abs1) return 1
        val len1 = this.length
        val len2 = other.length
        if (len1 < len2) return -1
        if (len2 < len1) return 1
        return this.hashCode() - other.hashCode()
    }

    /** The depth of this node. The root is depth 0, its children depth 1, and so on. */
    public fun depth(): Int = if (parentField == null) 0 else 1 + parentField!!.depth()

    protected class DebugPrintVisitor(private val buffer: StringBuilder) : NodeVisitor {

        override fun toString(): String = buffer.toString()

        override fun visit(node: AstNode): Boolean {
            val tt = node.type
            buffer.append(node.absolutePosition).append("\t")
            buffer.append(makeIndent(node.depth()))
            buffer.append(Token.typeToName(tt)).append(" ")
            buffer.append(node.position).append(" ")
            buffer.append(node.length)
            when {
                tt == Token.NAME -> buffer.append(" ").append((node as Name).identifier)
                tt == Token.STRING ->
                    buffer.append(" ").append((node as StringLiteral).getValue(true))
                tt == Token.FUNCTION ->
                    buffer.append(" functionType=").append((node as FunctionNode).functionType)
            }
            buffer.append("\n")
            return true // process kids
        }

        public companion object {
            private const val DEBUG_INDENT = 2

            private fun makeIndent(depth: Int): String = " ".repeat(DEBUG_INDENT * depth)
        }
    }

    /**
     * The line number recorded for this node. If none was recorded, searches the parent
     * chain and returns -1 when nothing is found.
     */
    override val lineno: Int
        get() {
            if (linenoField != -1) return linenoField
            return parentField?.lineno ?: -1
        }

    /**
     * A debugging representation of the parse tree starting at this node. Each line reads
     * `abs-pos name position length [identifier]`.
     */
    public open fun debugPrint(): String {
        val dpv = DebugPrintVisitor(StringBuilder(1000))
        visit(dpv)
        return dpv.toString()
    }

    public companion object {
        private const val MAX_INDENT = 42

        private val INDENTATIONS: Array<String> =
            Array(MAX_INDENT + 1) { i -> "  ".repeat(i) }

        private val operatorNames: Map<Int, String> = mapOf(
            Token.IN to "in",
            Token.TYPEOF to "typeof",
            Token.INSTANCEOF to "instanceof",
            Token.DELPROP to "delete",
            Token.COMMA to ",",
            Token.COLON to ":",
            Token.OR to "||",
            Token.NULLISH_COALESCING to "??",
            Token.QUESTION_DOT to "?.",
            Token.AND to "&&",
            Token.INC to "++",
            Token.DEC to "--",
            Token.BITOR to "|",
            Token.BITXOR to "^",
            Token.BITAND to "&",
            Token.EQ to "==",
            Token.NE to "!=",
            Token.LT to "<",
            Token.GT to ">",
            Token.LE to "<=",
            Token.GE to ">=",
            Token.LSH to "<<",
            Token.RSH to ">>",
            Token.URSH to ">>>",
            Token.ADD to "+",
            Token.SUB to "-",
            Token.MUL to "*",
            Token.DIV to "/",
            Token.MOD to "%",
            Token.EXP to "**",
            Token.NOT to "!",
            Token.BITNOT to "~",
            Token.POS to "+",
            Token.NEG to "-",
            Token.SHEQ to "===",
            Token.SHNE to "!==",
            Token.ASSIGN to "=",
            Token.ASSIGN_BITOR to "|=",
            Token.ASSIGN_LOGICAL_OR to "||=",
            Token.ASSIGN_BITAND to "&=",
            Token.ASSIGN_LOGICAL_AND to "&&=",
            Token.ASSIGN_LSH to "<<=",
            Token.ASSIGN_RSH to ">>=",
            Token.ASSIGN_URSH to ">>>=",
            Token.ASSIGN_ADD to "+=",
            Token.ASSIGN_SUB to "-=",
            Token.ASSIGN_MUL to "*=",
            Token.ASSIGN_DIV to "/=",
            Token.ASSIGN_MOD to "%=",
            Token.ASSIGN_BITXOR to "^=",
            Token.ASSIGN_EXP to "**=",
            Token.ASSIGN_NULLISH to "??=",
            Token.VOID to "void",
        )

        /** The source operator string for a token type, such as "+" or "typeof". */
        public fun operatorToString(op: Int): String =
            operatorNames[op] ?: throw IllegalArgumentException("Invalid operator: $op")

        public fun codeBug(): RuntimeException = throw Kit.codeBug()
    }
}
