/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The resumption protocol between a generator object and the interpreter. The generator objects
 * themselves land in phase 4; the interpreter only needs the operations before then.
 */
object NativeGenerator {
    const val GENERATOR_SEND = 0
    const val GENERATOR_THROW = 1
    const val GENERATOR_CLOSE = 2

    /** Thrown into a generator to run its `finally` blocks when it is closed early. */
    class GeneratorClosedException : RuntimeException()
}
