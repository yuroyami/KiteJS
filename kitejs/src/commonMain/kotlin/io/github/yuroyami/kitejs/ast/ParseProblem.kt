/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

/** One error or warning recorded by an [ErrorCollector] during a parse. */
class ParseProblem(
    var type: Type,
    var message: String?,
    var sourceName: String?,
    var fileOffset: Int,
    var length: Int,
) {

    enum class Type {
        Error,
        Warning,
    }

    override fun toString(): String {
        val sb = StringBuilder(200)
        sb.append(sourceName).append(":")
        sb.append("offset=").append(fileOffset).append(",")
        sb.append("length=").append(length).append(",")
        sb.append(if (type == Type.Error) "error: " else "warning: ")
        sb.append(message)
        return sb.toString()
    }
}
