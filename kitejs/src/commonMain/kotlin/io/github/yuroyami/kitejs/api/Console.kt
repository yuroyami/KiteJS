/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.NativeConsole

/** How loud a console message is. */
typealias ConsoleLevel = NativeConsole.Level

/** Where `console.log` and its neighbours end up. */
typealias ConsolePrinter = NativeConsole.ConsolePrinter

/** One console message, already formatted. */
data class ConsoleMessage(val level: ConsoleLevel, val text: String) {
    override fun toString(): String = "[${level.name.lowercase()}] $text"
}

/** Printers you probably want. */
object ConsolePrinters {

    /** Everything to standard output, one line per call, with the level in front. */
    val stdout: ConsolePrinter = ConsolePrinter { cx, scope, level, args, _ ->
        println("[${level.name.lowercase()}] " + NativeConsole.format(cx, scope, args))
    }

    /** Everything into [into], so a test can read back what a script printed. */
    fun collecting(into: MutableList<ConsoleMessage>): ConsolePrinter =
        ConsolePrinter { cx, scope, level, args, _ ->
            into.add(ConsoleMessage(level, NativeConsole.format(cx, scope, args)))
        }

    /** Everything to [sink], which decides what to do with it. */
    fun of(sink: (ConsoleLevel, String) -> Unit): ConsolePrinter =
        ConsolePrinter { cx, scope, level, args, _ -> sink(level, NativeConsole.format(cx, scope, args)) }
}
