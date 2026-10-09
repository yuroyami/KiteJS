/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

class Test262ExecutionTest {

    // staging/sm/generators/delegating-yield-11.js throws the Error constructor itself.
    @Test
    fun aThrownFunctionGivesAOneLineOutcome() {
        val source = "throw Error;"
        val meta = Test262FrontMatter.parse(source)
        val outcome = Test262Execution.run("thrown-function.js", source, meta, strict = false, harness = emptyList())
        assertEquals("threw function Error() {\\u000a\\u0009[native code]\\u000a}\\u000a", outcome)
    }
}
