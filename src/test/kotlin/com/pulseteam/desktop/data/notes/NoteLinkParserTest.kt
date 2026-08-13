// SPDX-License-Identifier: Apache-2.0
// Pulse — NoteLinkParserTest. Confirms the [[Title]] and [[Title|body]]
// forms parse correctly, including the "first pipe" rule and edge cases.
package com.pulseteam.desktop.data.notes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoteLinkParserTest {

    @Test
    fun `single plain link`() {
        val links = NoteLinkParser.extract("See [[Sprint plan]] for details.")
        assertEquals(1, links.size)
        assertEquals("Sprint plan", links[0].title)
        assertNull(links[0].body)
    }

    @Test
    fun `link with body via pipe`() {
        val links = NoteLinkParser.extract("Read [[FTS search|fast lookups]] next.")
        assertEquals(1, links.size)
        assertEquals("FTS search", links[0].title)
        assertEquals("fast lookups", links[0].body)
    }

    @Test
    fun `link with extra pipes keeps them in body`() {
        val links = NoteLinkParser.extract("[[a | b | c]]")
        assertEquals(1, links.size)
        assertEquals("a", links[0].title)
        assertEquals("b | c", links[0].body)
    }

    @Test
    fun `multiple links in one text`() {
        val text = """
            Start with [[Project plan]].
            Then look at [[Code review checklist]] and [[Hygiene docs|tidy up]].
        """.trimIndent()
        val links = NoteLinkParser.extract(text)
        assertEquals(3, links.size)
        assertEquals(listOf("Project plan", "Code review checklist", "Hygiene docs"), links.map { it.title })
        assertEquals(listOf(null, null, "tidy up"), links.map { it.body })
    }

    @Test
    fun `empty title link is skipped`() {
        val links = NoteLinkParser.extract("Empty: [[]] link.")
        assertTrue(links.isEmpty(), "Empty [[]] should be skipped, got: $links")
    }

    @Test
    fun `whitespace is trimmed from title and body`() {
        val links = NoteLinkParser.extract("[[  spaced title  |  spaced body  ]]")
        assertEquals(1, links.size)
        assertEquals("spaced title", links[0].title)
        assertEquals("spaced body", links[0].body)
    }

    @Test
    fun `unmatched brackets are not links`() {
        // The regex requires closing ]]. A single [ is not a link.
        val links = NoteLinkParser.extract("Code [brackets] but not a link.")
        assertTrue(links.isEmpty(), "Unmatched single brackets should not match, got: $links")
    }

    @Test
    fun `no links yields empty list`() {
        val links = NoteLinkParser.extract("Just plain text, nothing to see here.")
        assertTrue(links.isEmpty())
    }
}
