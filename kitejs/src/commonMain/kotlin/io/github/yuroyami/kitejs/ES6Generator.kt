/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The ES6 generator object. Phase 4 ports it; the interpreter and the function objects only need
 * its tag and the `yield*` marker before then.
 */
object ES6Generator {
    /** The key `%GeneratorPrototype%` is cached under on the top scope. */
    internal val GENERATOR_TAG: Any = "Generator"

    /** Marks a value yielded by `yield*`, which the generator forwards rather than wraps. */
    class YieldStarResult(val result: Any?)
}
