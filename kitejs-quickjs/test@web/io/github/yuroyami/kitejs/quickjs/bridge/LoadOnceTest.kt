/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.bridge

import io.github.yuroyami.kitejs.api.JsEngineError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LoadOnceTest {

    @Test
    fun twoLoadsAtOnceStartOneLoadAndBothEndWithIt() = runTest {
        var starts = 0
        var finish: ((Throwable?) -> Unit)? = null
        val load = LoadOnce { done ->
            starts++
            finish = done
        }
        val first = async { load.await() }
        val second = async { load.await() }
        runCurrent()
        assertEquals(1, starts)
        assertTrue(first.isActive && second.isActive)

        finish!!(null)
        first.await()
        second.await()
    }

    @Test
    fun aFailedLoadFailsEveryCallerAndTheNextCallStartsAgain() = runTest {
        var starts = 0
        var finish: ((Throwable?) -> Unit)? = null
        val load = LoadOnce { done ->
            starts++
            finish = done
        }
        val first = async { runCatching { load.await() } }
        val second = async { runCatching { load.await() } }
        runCurrent()
        finish!!(JsEngineError("no module"))
        assertTrue(first.await().exceptionOrNull() is JsEngineError)
        assertTrue(second.await().exceptionOrNull() is JsEngineError)

        val third = async { load.await() }
        runCurrent()
        assertEquals(2, starts)
        finish?.invoke(null)
        third.await()
    }

    @Test
    fun aLoadThatThrowsAtOnceLeavesNoLoadBehind() = runTest {
        var starts = 0
        val load = LoadOnce { done ->
            starts++
            if (starts == 1) throw JsEngineError("no glue") else done(null)
        }
        assertFailsWith<JsEngineError> { load.await() }
        load.await()
        assertEquals(2, starts)
    }
}
