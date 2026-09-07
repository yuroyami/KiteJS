/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.Symbol.Kind.BUILT_IN

/**
 * One of the two implementations of [Symbol]. This one exists so native code can use the well-known
 * symbols as plain map keys. A `NativeSymbol` built from a key compares equal to that key.
 */
public class SymbolKey(private val nameOrNull: String?, override val kind: Symbol.Kind) : Symbol {

    /** Empty for an anonymous symbol, the kind `Symbol()` makes. */
    override val name: String
        get() = nameOrNull ?: ""

    /** The description as script sees it: `undefined` for an anonymous symbol. */
    public val description: Any
        get() = nameOrNull ?: Undefined.instance

    // hashCode is deliberately not overridden. Upstream returns the identity hash, which is what
    // the default gives, and equals stays asymmetric with NativeSymbol exactly as upstream has it.
    override fun equals(other: Any?): Boolean {
        if (other is SymbolKey) return other === this
        if (other is NativeSymbol) return other.key === this
        return false
    }

    override fun hashCode(): Int = super.hashCode()

    override fun toString(): String = if (nameOrNull == null) "Symbol()" else "Symbol($nameOrNull)"

    public companion object {
        // The well-known symbols from the spec.
        public val ITERATOR: SymbolKey = SymbolKey("Symbol.iterator", BUILT_IN)
        public val TO_STRING_TAG: SymbolKey = SymbolKey("Symbol.toStringTag", BUILT_IN)
        public val SPECIES: SymbolKey = SymbolKey("Symbol.species", BUILT_IN)
        public val HAS_INSTANCE: SymbolKey = SymbolKey("Symbol.hasInstance", BUILT_IN)
        public val IS_CONCAT_SPREADABLE: SymbolKey = SymbolKey("Symbol.isConcatSpreadable", BUILT_IN)
        public val IS_REGEXP: SymbolKey = SymbolKey("Symbol.isRegExp", BUILT_IN)
        public val TO_PRIMITIVE: SymbolKey = SymbolKey("Symbol.toPrimitive", BUILT_IN)
        public val MATCH: SymbolKey = SymbolKey("Symbol.match", BUILT_IN)
        public val MATCH_ALL: SymbolKey = SymbolKey("Symbol.matchAll", BUILT_IN)
        public val REPLACE: SymbolKey = SymbolKey("Symbol.replace", BUILT_IN)
        public val SEARCH: SymbolKey = SymbolKey("Symbol.search", BUILT_IN)
        public val SPLIT: SymbolKey = SymbolKey("Symbol.split", BUILT_IN)
        public val UNSCOPABLES: SymbolKey = SymbolKey("Symbol.unscopables", BUILT_IN)
    }
}
