/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConvertersTest {
    private fun oldConversion(value: Any?): Any? = Converters.toEngine(
        value, scalar = { it }, array = { it }, obj = { it.toMap() }, bytes = { it },
    )

    @Test
    fun theOriginalCollectionCallbacksRejectCyclesAndExcessiveDepth() {
        val cycle = mutableListOf<Any?>()
        cycle.add(cycle)
        assertFailsWith<JsEngineError> { oldConversion(cycle) }
        var deep: Any? = 1
        repeat(200) { deep = listOf(deep) }
        assertFailsWith<JsEngineError> { oldConversion(deep) }
        assertEquals(listOf(1.0, mapOf("child" to listOf(2.0))), oldConversion(listOf(1, mapOf("child" to listOf(2)))))
    }
}
