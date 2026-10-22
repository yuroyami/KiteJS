/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

/** How loud a console message is. */
public enum class ConsoleLevel { TRACE, DEBUG, INFO, WARN, ERROR }

/**
 * Where `console.log` and its neighbours end up. Every engine formats the arguments the same way
 * before they get here: separated by spaces, with `%s`, `%d`, `%i`, `%f`, `%o`, `%O` and `%c` in a
 * leading string filled in, as a browser console does.
 */
public fun interface ConsolePrinter {
    public fun print(level: ConsoleLevel, text: String)
}

/** One console message, already formatted. */
public data class ConsoleMessage(val level: ConsoleLevel, val text: String) {
    override fun toString(): String = "[${level.name.lowercase()}] $text"
}

/** Printers you probably want. */
public object ConsolePrinters {

    /** Everything to standard output, one line per call, with the level in front. */
    public val stdout: ConsolePrinter = ConsolePrinter { level, text ->
        println("[${level.name.lowercase()}] $text")
    }

    /** Everything into [into], so a test can read back what a script printed. */
    public fun collecting(into: MutableList<ConsoleMessage>): ConsolePrinter =
        ConsolePrinter { level, text -> into.add(ConsoleMessage(level, text)) }

    /** Everything to [sink], which decides what to do with it. */
    public fun of(sink: (ConsoleLevel, String) -> Unit): ConsolePrinter = ConsolePrinter(sink)
}
