/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A scripted EPUB, bound through the facade and nothing else. The reading system in KitePDF binds
 * a real document; this proves the facade is enough to do it, and that nothing in the shape of the
 * binding needs the engine's own classes.
 */
class EpubScriptingDemoTest {

    /** One element of the fake document. */
    private class Element(val id: String, val tag: String) {
        var textContent: String = ""
        var className: String = ""
        val style = HashMap<String, String>()
        val listeners = HashMap<String, MutableList<JsFunction>>()
    }

    /** Just enough document to run a chapter's script. */
    private class FakeDocument {
        val elements = LinkedHashMap<String, Element>()
        var readyState = "loading"
        val fired = mutableListOf<String>()

        fun add(id: String, tag: String, text: String = ""): Element =
            Element(id, tag).also { it.textContent = text; elements[id] = it }
    }

    private fun bind(js: KiteJs, doc: FakeDocument) {
        val documentListeners = HashMap<String, MutableList<JsFunction>>()

        fun bindElement(el: Element): JsObject = js.newObject().apply {
            constant("id", el.id)
            constant("tagName", el.tag.uppercase())
            accessor("textContent", read = { el.textContent }, write = { v -> el.textContent = v.asString() })
            accessor("className", read = { el.className }, write = { v -> el.className = v.asString() })
            property("style", js.newObject().apply {
                function("setProperty", 2) { args ->
                    el.style[args[0].asString()] = args[1].asString()
                }
                function("getPropertyValue", 1) { args -> el.style[args[0].asString()] ?: "" }
            })
            function("addEventListener", 2) { args ->
                el.listeners.getOrPut(args[0].asString()) { mutableListOf() }.add(args[1].asFunction())
            }
        }

        js.global.obj("document") {
            getter("readyState") { doc.readyState }
            function("getElementById", 1) { args ->
                doc.elements[args[0].asString()]?.let { bindElement(it) }
            }
            function("querySelector", 1) { args ->
                val sel = args[0].asString()
                val match = when {
                    sel.startsWith("#") -> doc.elements[sel.drop(1)]
                    sel.startsWith(".") -> doc.elements.values.firstOrNull { it.className == sel.drop(1) }
                    else -> doc.elements.values.firstOrNull { it.tag.equals(sel, ignoreCase = true) }
                }
                match?.let { bindElement(it) }
            }
            function("querySelectorAll", 1) { args ->
                val tag = args[0].asString()
                doc.elements.values.filter { it.tag.equals(tag, ignoreCase = true) }.map { bindElement(it).value }
            }
            function("addEventListener", 2) { args ->
                documentListeners.getOrPut(args[0].asString()) { mutableListOf() }.add(args[1].asFunction())
            }
        }

        js.global.obj("navigator") {
            obj("epubReadingSystem") {
                constant("name", "KiteJS Reader")
                constant("version", "0.1")
                function("hasFeature", 2) { args ->
                    args[0].asString() in setOf("dom-manipulation", "mouse-events", "touch-events")
                }
            }
        }

        // What the reading system calls once the chapter is in the DOM.
        js.global.function("__fireReady") { _ ->
            doc.readyState = "complete"
            documentListeners["DOMContentLoaded"]?.forEach { it() }
            doc.fired.add("DOMContentLoaded")
        }
        js.global.method("__click") { _, args ->
            val el = doc.elements[args[0].asString()] ?: return@method false
            el.listeners["click"]?.forEach { it() }
            true
        }
    }

    /** The kind of script a scripted EPUB actually ships. */
    private val chapterScript = """
        (function () {
            var supported = navigator.epubReadingSystem.hasFeature('dom-manipulation');
            document.addEventListener('DOMContentLoaded', function () {
                var title = document.getElementById('chapter-title');
                title.textContent = 'Chapter One: ' + title.textContent;
                title.style.setProperty('font-weight', 'bold');

                var note = document.querySelector('.sidenote');
                note.textContent = supported ? 'interactive' : 'static';

                var count = document.querySelectorAll('p').length;
                document.getElementById('counter').textContent = count + ' paragraphs';

                var toggle = document.getElementById('toggle');
                var open = false;
                toggle.addEventListener('click', function () {
                    open = !open;
                    toggle.textContent = open ? 'Hide' : 'Show';
                    document.getElementById('answer').style.setProperty('display', open ? 'block' : 'none');
                });
            });
        })();
    """.trimIndent()

    @Test
    fun aScriptedChapterDrivesTheDocumentItWasGiven() {
        val doc = FakeDocument()
        doc.add("chapter-title", "h1", "The Arrival")
        doc.add("counter", "span")
        doc.add("toggle", "button", "Show")
        doc.add("answer", "div", "42")
        doc.add("body-1", "p", "First.")
        doc.add("body-2", "p", "Second.")
        doc.add("aside-1", "aside").className = "sidenote"

        KiteJs().use { js ->
            bind(js, doc)
            js.evaluate(chapterScript, "chapter1.js")

            // Nothing has run yet: the script only registered a listener.
            assertEquals("The Arrival", doc.elements["chapter-title"]!!.textContent)

            js.evaluate("__fireReady()")

            assertEquals("Chapter One: The Arrival", doc.elements["chapter-title"]!!.textContent)
            assertEquals("bold", doc.elements["chapter-title"]!!.style["font-weight"])
            assertEquals("interactive", doc.elements["aside-1"]!!.textContent)
            assertEquals("2 paragraphs", doc.elements["counter"]!!.textContent)
            assertEquals(listOf("DOMContentLoaded"), doc.fired)

            // The click handler the script installed still closes over its own `open` flag.
            js.evaluate("__click('toggle')")
            assertEquals("Hide", doc.elements["toggle"]!!.textContent)
            assertEquals("block", doc.elements["answer"]!!.style["display"])

            js.evaluate("__click('toggle')")
            assertEquals("Show", doc.elements["toggle"]!!.textContent)
            assertEquals("none", doc.elements["answer"]!!.style["display"])
        }
    }

    @Test
    fun theReadingSystemAnswersWhatTheSpecSaysItShould() {
        KiteJs().use { js ->
            bind(js, FakeDocument())
            assertEquals("KiteJS Reader", js.evaluate("navigator.epubReadingSystem.name").asString())
            assertEquals("0.1", js.evaluate("navigator.epubReadingSystem.version").asString())
            assertTrue(js.evaluate("navigator.epubReadingSystem.hasFeature('dom-manipulation')").asBoolean())
            assertTrue(!js.evaluate("navigator.epubReadingSystem.hasFeature('layout-changes')").asBoolean())
        }
    }

    @Test
    fun aChapterScriptThatThrowsDoesNotTakeTheReaderDown() {
        val doc = FakeDocument()
        doc.add("chapter-title", "h1", "Ok")
        KiteJs().use { js ->
            bind(js, doc)
            val e = kotlin.test.assertFailsWith<JsError> {
                js.evaluate("document.getElementById('missing').textContent = 'x'", "bad.js")
            }
            assertEquals("TypeError", e.name)
            // The engine is still usable afterwards.
            js.evaluate("document.getElementById('chapter-title').textContent = 'Recovered'")
            assertEquals("Recovered", doc.elements["chapter-title"]!!.textContent)
        }
    }

    @Test
    fun aChapterScriptCannotRunForever() {
        val doc = FakeDocument()
        KiteJs { instructionBudget = 200_000 }.use { js ->
            bind(js, doc)
            kotlin.test.assertFailsWith<JsEngineError> { js.evaluate("for (;;) {}", "runaway.js") }
        }
    }
}
