/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The `/*--- ... ---*/` block at the top of every test262 file, as described by the suite's own
 * INTERPRETING.md.
 *
 * Upstream parses it with snakeyaml. This reads the handful of keys that decide what runs, which
 * keeps the parser usable from common code later. A misreading here cannot fake a parity pass: the
 * same metadata drives both engines, so anything it gets wrong, it gets wrong for both.
 */
data class Test262FrontMatter(
    val negativeType: String?,
    val negativePhase: String?,
    val flags: Set<String>,
    val features: Set<String>,
    val includes: List<String>,
) {
    val isNegative: Boolean get() = negativeType != null

    /** A parse or resolution error has to happen before a line of the test runs. */
    val hasEarlyError: Boolean get() = negativePhase == "parse" || negativePhase == "early"

    fun hasFlag(flag: String): Boolean = flags.contains(flag)

    /** The harness files to run before the test, in order. */
    fun harnessFiles(): List<String> {
        // A "raw" test gets nothing, not even assert.js.
        if (hasFlag("raw")) return emptyList()
        return listOf("assert.js", "sta.js") + includes
    }

    companion object {
        fun parse(source: String): Test262FrontMatter {
            val start = source.indexOf("/*---")
            val end = source.indexOf("---*/")
            if (start < 0 || end < 0 || end < start) {
                return Test262FrontMatter(null, null, emptySet(), emptySet(), emptyList())
            }
            return parseBlock(source.substring(start + 5, end))
        }

        private fun parseBlock(block: String): Test262FrontMatter {
            var negativeType: String? = null
            var negativePhase: String? = null
            var flags = emptySet<String>()
            var features = emptySet<String>()
            var includes = emptyList<String>()

            val lines = block.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val indent = line.indentOf()
                // Only top-level keys matter; anything indented belongs to a key already read.
                if (line.isBlank() || indent > 0) {
                    i++
                    continue
                }
                val colon = line.indexOf(':')
                if (colon < 0) {
                    i++
                    continue
                }
                val key = line.substring(0, colon).trim()
                val inline = line.substring(colon + 1).trim()

                when (key) {
                    "negative" -> {
                        // A nested block: phase and type sit under it, indented.
                        var j = i + 1
                        while (j < lines.size && (lines[j].isBlank() || lines[j].indentOf() > 0)) {
                            val nested = lines[j].trim()
                            val c = nested.indexOf(':')
                            if (c > 0) {
                                val nk = nested.substring(0, c).trim()
                                val nv = nested.substring(c + 1).trim()
                                if (nk == "type") negativeType = nv
                                if (nk == "phase") negativePhase = nv
                            }
                            j++
                        }
                        i = j
                        continue
                    }
                    "flags" -> flags = readList(lines, i, inline).toSet()
                    "features" -> features = readList(lines, i, inline).toSet()
                    "includes" -> includes = readList(lines, i, inline)
                }
                i++
            }
            return Test262FrontMatter(negativeType, negativePhase, flags, features, includes)
        }

        /** A list is either inline as `[a, b]` or a block of `- a` lines under the key. */
        private fun readList(lines: List<String>, keyIndex: Int, inline: String): List<String> {
            if (inline.startsWith("[")) {
                return inline.removePrefix("[").substringBefore("]")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            val out = mutableListOf<String>()
            var j = keyIndex + 1
            while (j < lines.size) {
                val l = lines[j]
                if (l.isBlank()) { j++; continue }
                if (l.indentOf() == 0) break
                val item = l.trim()
                if (item.startsWith("-")) out.add(item.removePrefix("-").trim())
                j++
            }
            return out
        }

        private fun String.indentOf(): Int = length - trimStart().length
    }
}
