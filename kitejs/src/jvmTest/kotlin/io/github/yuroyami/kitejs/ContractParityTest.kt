/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The interfaces the rest of the runtime is written against carry no behaviour, so the only thing
 * worth checking is that their member sets match upstream. Every ported contract is compared
 * against its upstream class by reflection: same method names, same arity, same parameter and
 * return shapes.
 *
 * Type names are normalised before comparison, because the two sides live in different packages and
 * a few types are deliberately different (see [expectedDifferences]). Anything not listed there is
 * a failure.
 */
class ContractParityTest {

    /** Members the port deliberately does not have, and why. Keyed by "Interface.member". */
    private val expectedDifferences: Map<String, String> = mapOf(
        // JSDescriptor is the code-generation descriptor layer, which lands later in this phase.
        "Script.getDescriptor()JSDescriptor" to "the descriptor layer is not ported yet",
        // The debug package is never ported beyond what the interpreter itself needs.
        "Evaluator.getDebuggableScript(Object)DebuggableScript" to "the debugger surface is dropped",
    )

    private val pairs: List<Pair<Class<*>, Class<*>>> = listOf(
        Scriptable::class.java to org.mozilla.javascript.Scriptable::class.java,
        SymbolScriptable::class.java to org.mozilla.javascript.SymbolScriptable::class.java,
        Callable::class.java to org.mozilla.javascript.Callable::class.java,
        Constructable::class.java to org.mozilla.javascript.Constructable::class.java,
        Function::class.java to org.mozilla.javascript.Function::class.java,
        Script::class.java to org.mozilla.javascript.Script::class.java,
        Evaluator::class.java to org.mozilla.javascript.Evaluator::class.java,
        Ref::class.java to org.mozilla.javascript.Ref::class.java,
        RefCallable::class.java to org.mozilla.javascript.RefCallable::class.java,
        ConstProperties::class.java to org.mozilla.javascript.ConstProperties::class.java,
        Symbol::class.java to org.mozilla.javascript.Symbol::class.java,
        Wrapper::class.java to org.mozilla.javascript.Wrapper::class.java,
    )

    /**
     * Reduces a type to a name both sides can agree on: the package goes away, arrays keep their
     * brackets, and the handful of types the port swaps out are mapped to the upstream name.
     */
    private fun shape(type: Class<*>): String {
        if (type.isArray) return shape(type.componentType) + "[]"
        val simple = type.simpleName
        return when (simple) {
            // D-20: the port takes a KClass where upstream takes a java.lang.Class.
            "KClass" -> "Class"
            else -> simple
        }
    }

    private fun signature(m: Method): String =
        m.name + m.parameterTypes.joinToString(",", "(", ")") { shape(it) } + shape(m.returnType)

    private fun members(type: Class<*>): Set<String> =
        type.declaredMethods
            .filterNot { it.isSynthetic || it.isBridge }
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { signature(it) }
            .toSet()

    @Test
    fun everyContractHasTheSameMembersAsUpstream() {
        val failures = mutableListOf<String>()
        for ((ported, upstream) in pairs) {
            val name = ported.simpleName
            val mine = members(ported)
            val theirs = members(upstream)

            val missing = (theirs - mine).filterNot { expectedDifferences.containsKey("$name.$it") }
            val extra = mine - theirs
            if (missing.isNotEmpty()) failures.add("$name is missing $missing")
            if (extra.isNotEmpty()) failures.add("$name has members upstream does not: $extra")
        }
        assertEquals(emptyList(), failures, "the ported contracts drifted from upstream")
    }

    @Test
    fun everyExpectedDifferenceIsRealAndStillNeeded() {
        // Guards the allowlist: if upstream drops one of these, or the port grows it, say so
        // instead of letting a stale entry hide a real difference.
        val stale = mutableListOf<String>()
        for ((key, reason) in expectedDifferences) {
            val (typeName, member) = key.split('.', limit = 2)
            val (ported, upstream) = pairs.first { it.first.simpleName == typeName }
            if (member !in members(upstream)) stale.add("$key: upstream no longer has it ($reason)")
            if (member in members(ported)) stale.add("$key: the port has it now, drop the entry")
        }
        assertEquals(emptyList(), stale, "the expected-difference list is out of date")
    }

    @Test
    fun contractsKeepTheirUpstreamHierarchy() {
        val failures = mutableListOf<String>()
        for ((ported, upstream) in pairs) {
            val mine = ported.interfaces.map { it.simpleName }.toSet()
            val theirs = upstream.interfaces.map { it.simpleName }.toSet() - "Serializable"
            if (mine != theirs) {
                failures.add("${ported.simpleName} extends $mine, upstream extends $theirs")
            }
        }
        assertEquals(emptyList(), failures, "contract hierarchies differ from upstream")
    }

    @Test
    fun scriptableSentinelMatchesUpstream() {
        // NOT_FOUND is a static field upstream and a companion value here, so it is checked apart
        // from the method comparison. Both sides have to agree that it is not any real value.
        assertTrue(Scriptable.NOT_FOUND === UniqueTag.NOT_FOUND)
        // toString carries the object identity, so only the tail after it is comparable.
        assertEquals(
            org.mozilla.javascript.Scriptable.NOT_FOUND.toString().substringAfter(": "),
            Scriptable.NOT_FOUND.toString().substringAfter(": "),
        )
    }

    @Test
    fun symbolKindsMatchUpstream() {
        assertEquals(
            org.mozilla.javascript.Symbol.Kind.entries.map { it.name },
            Symbol.Kind.entries.map { it.name },
        )
    }
}
