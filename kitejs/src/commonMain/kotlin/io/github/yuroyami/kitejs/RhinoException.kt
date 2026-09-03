/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The class of exceptions thrown by the JavaScript engine.
 *
 * Phase 0 shell: source-position plumbing and message composition only. Phase 3 ports
 * the full class (script stacks, interpreter integration).
 */
abstract class RhinoException : RuntimeException {

    private val detailsMessage: String?

    constructor() : super() {
        detailsMessage = null
    }

    constructor(details: String) : super() {
        detailsMessage = details
    }

    var sourceName: String? = null
        private set

    var lineNumber: Int = 0
        private set

    var lineSource: String? = null
        private set

    var columnNumber: Int = 0
        private set

    open fun details(): String = detailsMessage ?: ""

    fun initSourceName(sourceName: String) {
        this.sourceName = sourceName
    }

    fun initLineNumber(lineNumber: Int) {
        this.lineNumber = lineNumber
    }

    fun initLineSource(lineSource: String) {
        this.lineSource = lineSource
    }

    fun initColumnNumber(columnNumber: Int) {
        this.columnNumber = columnNumber
    }

    final override val message: String
        get() {
            val details = details()
            val name = sourceName ?: return details
            if (lineNumber <= 0) return details
            return "$details ($name#$lineNumber)"
        }
}
