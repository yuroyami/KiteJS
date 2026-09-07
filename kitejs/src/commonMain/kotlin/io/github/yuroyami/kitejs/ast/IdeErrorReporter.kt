/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.ErrorReporter

/**
 * Error reporting protocol for IDE-mode parsing. If the parser's error reporter
 * implements this interface, these offset/length overloads are called instead of the
 * line-based [ErrorReporter] versions.
 */
public interface IdeErrorReporter : ErrorReporter {

    /**
     * Report a warning.
     *
     * @param message a String describing the warning
     * @param sourceName the JavaScript source, typically a filename or URL
     * @param offset the warning's 0-indexed char position in the input stream
     * @param length the length of the region contributing to the warning
     */
    public fun warning(message: String, sourceName: String?, offset: Int, length: Int)

    /**
     * Report an error.
     *
     * @param message a String describing the error
     * @param sourceName the JavaScript source, typically a filename or URL
     * @param offset 0-indexed char position of the error in the input stream
     * @param length the length of the region contributing to the error
     */
    public fun error(message: String, sourceName: String?, offset: Int, length: Int)
}
