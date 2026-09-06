/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import java.io.File

/**
 * Upstream's `test262.properties`, read rather than rewritten. The copy in test resources is
 * upstream's file byte for byte, so the same tests are skipped and the same ones are expected to
 * fail.
 *
 * Format, in upstream's own words: an unindented line names a top level folder, optionally prefixed
 * with `~` to skip the whole thing. Indented lines under it name files that are expected to fail,
 * or subfolders whose files all are. `!` or `#` starts a comment. A trailing `12/34 (56%)`, a mode
 * word, or a `{unsupported: [...]}` note is annotation and carries no meaning here.
 */
class Test262Properties private constructor(
    /** Folder paths, relative to `test/`, that upstream does not run at all. */
    val skippedFolders: List<String>,
    /** File paths, relative to `test/`, that upstream expects to fail. */
    val expectedToFail: Set<String>,
) {
    fun isSkipped(relativePath: String): Boolean =
        skippedFolders.any { relativePath == it || relativePath.startsWith("$it/") }

    companion object {
        // Upstream's own splitter: prefix, path, optional annotation, trailing comment.
        private val LINE = Regex(
            "(~|(?:\\s*)(?:!|#)(?:\\s*)|\\s+)?(\\S+)(?:[^\\S\\r\\n]+" +
                "(?:strict|non-strict|compiled-strict|compiled-non-strict|interpreted-strict|" +
                "interpreted-non-strict|compiled|interpreted|" +
                "\\d+/\\d+ \\(\\d+(?:\\.\\d+)?%\\)|\\{(?:non-strict|strict|unsupported): \\[.*\\],?\\}))?" +
                "[^\\S\\r\\n]*(.*)",
        )

        fun load(propertiesFile: File, testRoot: File): Test262Properties {
            val skipped = mutableListOf<String>()
            val failing = mutableSetOf<String>()
            var topLevel: String? = null

            for (raw in propertiesFile.readLines()) {
                val m = LINE.matchEntire(raw) ?: continue
                val prefix = m.groupValues[1]
                val path = m.groupValues[2]
                if (path.isEmpty()) continue

                val isTopLevel = m.groups[1] == null || prefix == "~"
                if (isTopLevel) {
                    topLevel = path
                    if (prefix == "~") skipped.add(path)
                    continue
                }
                // A comment marker rather than plain indentation.
                if (prefix.isNotBlank()) continue
                val parent = topLevel ?: continue

                if (path.endsWith(".js")) {
                    failing.add("$parent/$path")
                } else {
                    // A subfolder stands for every .js file directly inside it.
                    val dir = File(testRoot, "$parent/$path")
                    dir.listFiles { f -> f.isFile && f.name.endsWith(".js") }
                        ?.forEach { failing.add("$parent/$path/${it.name}") }
                }
            }
            return Test262Properties(skipped, failing)
        }
    }
}
