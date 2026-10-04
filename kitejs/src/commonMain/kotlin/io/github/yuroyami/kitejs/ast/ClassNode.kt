/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A class declaration or expression. Node type is [Token.CLASS].
 *
 * ```
 * ClassDeclaration : class BindingIdentifier ClassTail
 * ClassExpression  : class BindingIdentifier? ClassTail
 * ClassTail        : ClassHeritage? { ClassBody? }
 * ```
 *
 * Every part of the class that runs code is a function of its own: the [constructor], which the
 * parser makes up when the class has none, each method, each field initializer and each static
 * block. The class itself only says how they hang together.
 */
public class ClassNode(pos: Int) : AstNode(pos) {

    init {
        typeField = Token.CLASS
    }

    /** The name the class binds inside its own body, or null for an anonymous class expression. */
    public var className: Name? = null
        set(value) {
            field = value
            value?.parent = this
        }

    /** The expression after `extends`, or null when the class has no heritage. */
    public var superClass: AstNode? = null
        set(value) {
            field = value
            value?.parent = this
        }

    /** The constructor, written in the class or made up for it. */
    public lateinit var constructor: FunctionNode

    /** The methods, accessors, fields and static blocks, in source order, without the constructor. */
    public val elements: MutableList<ClassElement> = ArrayList()

    /** The private names the class body declares, each with its `#`, in the order first declared. */
    public var privateNames: List<String> = emptyList()

    /** True for a declaration, which binds the name in the enclosing block as well. */
    public var isStatement: Boolean = false

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth)).append("class")
        className?.let { sb.append(' ').append(it.toSource(0)) }
        superClass?.let { sb.append(" extends ").append(it.toSource(0)) }
        sb.append(" {\n")
        sb.append(makeIndent(depth + 1)).append(constructor.toSource(0)).append('\n')
        for (e in elements) {
            sb.append(makeIndent(depth + 1))
            if (e.isStatic) sb.append("static ")
            when (e.kind) {
                ClassElement.GETTER -> sb.append("get ")
                ClassElement.SETTER -> sb.append("set ")
            }
            if (e.kind == ClassElement.STATIC_BLOCK) {
                sb.append(e.value!!.body!!.toSource(0))
            } else {
                sb.append(e.key!!.toSource(0))
                if (e.kind == ClassElement.FIELD) {
                    e.value?.let { sb.append(" = ").append(it.body!!.toSource(0)) }
                    sb.append(';')
                } else {
                    sb.append(e.value!!.toSource(0))
                }
            }
            sb.append('\n')
        }
        sb.append(makeIndent(depth)).append('}')
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            className?.visit(visitor)
            superClass?.visit(visitor)
            constructor.visit(visitor)
            for (e in elements) {
                e.key?.visit(visitor)
                e.value?.visit(visitor)
            }
        }
    }
}

/**
 * One element of a class body. A method, accessor or static block carries its function in
 * [value]; a field carries the function that computes its initial value, or null when it has no
 * initializer. [key] is a [Name], [StringLiteral], [NumberLiteral] or [ComputedPropertyKey], and
 * null for a static block.
 */
public class ClassElement(
    public val kind: Int,
    public val isStatic: Boolean,
    public val key: AstNode?,
    public val value: FunctionNode?,
) {
    public companion object {
        public const val METHOD: Int = 0
        public const val GETTER: Int = 1
        public const val SETTER: Int = 2
        public const val FIELD: Int = 3
        public const val STATIC_BLOCK: Int = 4
    }
}
