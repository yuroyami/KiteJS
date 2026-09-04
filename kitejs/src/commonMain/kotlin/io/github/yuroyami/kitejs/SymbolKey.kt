/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.Symbol.Kind.BUILT_IN

/**
 * One of the two implementations of [Symbol]. This one exists so native code can use the well-known
 * symbols as plain map keys. A `NativeSymbol` built from a key compares equal to that key.
 */
class SymbolKey(private val nameOrNull: String?, override val kind: Symbol.Kind) : Symbol {

    /** Empty for an anonymous symbol, the kind `Symbol()` makes. */
    override val name: String
        get() = nameOrNull ?: ""

    /** The description as script sees it: `undefined` for an anonymous symbol. */
    val description: Any
        get() = nameOrNull ?: Undefined.instance

    // hashCode is deliberately not overridden. Upstream returns the identity hash, which is what
    // the default gives, and equals stays asymmetric with NativeSymbol exactly as upstream has it.
    override fun equals(other: Any?): Boolean {
        if (other is SymbolKey) return other === this
        // TODO(P3.4): NativeSymbol does not exist yet. Upstream also returns true when other is a
        // NativeSymbol whose key is this one.
        return false
    }

    override fun hashCode(): Int = super.hashCode()

    override fun toString(): String = if (nameOrNull == null) "Symbol()" else "Symbol($nameOrNull)"

    companion object {
        // The well-known symbols from the spec.
        val ITERATOR: SymbolKey = SymbolKey("Symbol.iterator", BUILT_IN)
        val TO_STRING_TAG: SymbolKey = SymbolKey("Symbol.toStringTag", BUILT_IN)
        val SPECIES: SymbolKey = SymbolKey("Symbol.species", BUILT_IN)
        val HAS_INSTANCE: SymbolKey = SymbolKey("Symbol.hasInstance", BUILT_IN)
        val IS_CONCAT_SPREADABLE: SymbolKey = SymbolKey("Symbol.isConcatSpreadable", BUILT_IN)
        val IS_REGEXP: SymbolKey = SymbolKey("Symbol.isRegExp", BUILT_IN)
        val TO_PRIMITIVE: SymbolKey = SymbolKey("Symbol.toPrimitive", BUILT_IN)
        val MATCH: SymbolKey = SymbolKey("Symbol.match", BUILT_IN)
        val MATCH_ALL: SymbolKey = SymbolKey("Symbol.matchAll", BUILT_IN)
        val REPLACE: SymbolKey = SymbolKey("Symbol.replace", BUILT_IN)
        val SEARCH: SymbolKey = SymbolKey("Symbol.search", BUILT_IN)
        val SPLIT: SymbolKey = SymbolKey("Symbol.split", BUILT_IN)
        val UNSCOPABLES: SymbolKey = SymbolKey("Symbol.unscopables", BUILT_IN)
    }
}
