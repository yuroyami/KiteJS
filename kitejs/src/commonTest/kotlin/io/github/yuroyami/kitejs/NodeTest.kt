/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.Name
import io.github.yuroyami.kitejs.ast.NumberLiteral
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Covers the raw IR node: the child linked list, the property list and line/column storage. */
class NodeTest {

    private fun node(type: Int = Token.EMPTY) = Node(type)

    @Test
    fun childListAddsToFrontAndBack() {
        val parent = node()
        val a = node()
        val b = node()
        val c = node()

        assertFalse(parent.hasChildren())
        parent.addChildToBack(a)
        parent.addChildToBack(b)
        parent.addChildToFront(c)

        assertTrue(parent.hasChildren())
        assertEquals(listOf(c, a, b), parent.toList())
        assertSame(c, parent.firstChild)
        assertSame(b, parent.lastChild)
    }

    @Test
    fun getChildBeforeWalksTheList() {
        val parent = node()
        val a = node()
        val b = node()
        parent.addChildToBack(a)
        parent.addChildToBack(b)

        assertNull(parent.getChildBefore(a))
        assertSame(a, parent.getChildBefore(b))
        assertFailsWith<RuntimeException> { parent.getChildBefore(node()) }
    }

    @Test
    fun addChildBeforeAndAfter() {
        val parent = node()
        val a = node()
        val b = node()
        parent.addChildToBack(a)
        parent.addChildToBack(b)

        val front = node()
        parent.addChildBefore(front, a)
        assertSame(front, parent.firstChild)

        val tail = node()
        parent.addChildAfter(tail, b)
        assertSame(tail, parent.lastChild)
        assertEquals(listOf(front, a, b, tail), parent.toList())
    }

    @Test
    fun removeAndReplaceChild() {
        val parent = node()
        val a = node()
        val b = node()
        val c = node()
        parent.addChildToBack(a)
        parent.addChildToBack(b)
        parent.addChildToBack(c)

        parent.removeChild(b)
        assertEquals(listOf(a, c), parent.toList())

        val d = node()
        parent.replaceChild(c, d)
        assertEquals(listOf(a, d), parent.toList())
        assertSame(d, parent.lastChild)

        parent.removeChildren()
        assertFalse(parent.hasChildren())
        assertNull(parent.firstChild)
        assertNull(parent.lastChild)
    }

    @Test
    fun addChildrenMovesWholeSiblingChains() {
        val parent = node()
        val existing = node()
        parent.addChildToBack(existing)

        val chainHead = node()
        val chainTail = node()
        val donor = node()
        donor.addChildToBack(chainHead)
        donor.addChildToBack(chainTail)

        parent.addChildrenToBack(chainHead)
        assertEquals(listOf(existing, chainHead, chainTail), parent.toList())
        assertSame(chainTail, parent.lastChild)
    }

    @Test
    fun iteratorSupportsRemoval() {
        val parent = node()
        val a = node()
        val b = node()
        val c = node()
        parent.addChildToBack(a)
        parent.addChildToBack(b)
        parent.addChildToBack(c)

        val it = parent.iterator()
        it.next()
        it.next() // b
        it.remove()
        assertEquals(listOf(a, c), parent.toList())
    }

    @Test
    fun propertyListStoresObjectsAndInts() {
        val n = node()
        assertNull(n.getProp(Node.NAME_PROP))
        assertEquals(7, n.getIntProp(Node.LABEL_ID_PROP, 7))

        n.putProp(Node.NAME_PROP, "hello")
        n.putIntProp(Node.LABEL_ID_PROP, 3)
        assertEquals("hello", n.getProp(Node.NAME_PROP))
        assertEquals(3, n.getIntProp(Node.LABEL_ID_PROP, 7))
        assertEquals(3, n.getExistingIntProp(Node.LABEL_ID_PROP))

        // Putting null removes the property, matching upstream.
        n.putProp(Node.NAME_PROP, null)
        assertNull(n.getProp(Node.NAME_PROP))

        n.removeProp(Node.LABEL_ID_PROP)
        assertEquals(7, n.getIntProp(Node.LABEL_ID_PROP, 7))
    }

    @Test
    fun lineAndColumnDefaultToMinusOne() {
        val n = node()
        assertEquals(-1, n.lineno)
        assertEquals(-1, n.column)

        n.setLineColumnNumber(12, 4)
        assertEquals(12, n.lineno)
        assertEquals(4, n.column)
    }

    @Test
    fun labelIdOnlyValidForTargetAndYield() {
        val target = Node.newTarget()
        target.labelId(9)
        assertEquals(9, target.labelId())

        assertFailsWith<IllegalStateException> { node(Token.NAME).labelId() }
    }

    @Test
    fun typedFactoriesProduceAstNodes() {
        val number = Node.newNumber(2.5)
        assertEquals(Token.NUMBER, number.type)
        assertEquals(2.5, number.double, 0.0)
        assertTrue(number is NumberLiteral)

        val str = Node.newString("abc")
        assertEquals(Token.STRING, str.type)
        assertEquals("abc", str.string)
        assertTrue(str is Name)
    }

    @Test
    fun hasSideEffectsFollowsTokenType() {
        assertTrue(node(Token.CALL).hasSideEffects())
        assertTrue(node(Token.THROW).hasSideEffects())
        assertFalse(node(Token.NAME).hasSideEffects())
    }
}
