/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** A host exception carried through script as a JavaScript error. */
class WrappedException(val wrappedException: Throwable) :
    EvaluatorException("Wrapped $wrappedException") {

    init {
        val linep = IntArray(1)
        val sourceName = Context.getSourcePositionFromStack(linep)
        if (sourceName != null) initSourceName(sourceName)
        if (linep[0] != 0) initLineNumber(linep[0])
    }

    override val cause: Throwable
        get() = wrappedException
}
