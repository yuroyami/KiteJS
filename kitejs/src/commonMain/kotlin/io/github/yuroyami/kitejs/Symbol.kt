/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A JavaScript Symbol. This is an interface so that more than one implementation can exist.
 */
public interface Symbol {

    /** Whether the symbol is a regular one, a built-in, or a registered one. */
    public enum class Kind {
        /** Made with the Symbol constructor. */
        REGULAR,

        /** One of the properties of the Symbol constructor. */
        BUILT_IN,

        /** Made with Symbol.for. */
        REGISTERED,
    }

    /** The symbol's name, or the empty string for an anonymous symbol from `Symbol()`. */
    public val name: String

    public val kind: Kind
}
