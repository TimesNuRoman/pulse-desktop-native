// SPDX-License-Identifier: Apache-2.0
// Pulse — WebSearch.formatForLlm unit test. The HTTP path needs network,
// so we only test the pure formatter (and the regex parser via a small
// HTML fixture if we add one).
package com.pulseteam.desktop.data.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebSearchFormatTest {

    @Test
    fun `formatForLlm with empty results returns empty string`() {
        val out = WebSearch().formatForLlm(emptyList(), "anything")
        assertEquals("", out)
    }

    @Test
    fun `formatForLlm includes the query and numbered citations`() {
        val results = listOf(
            SearchResult(title = "Kotlin", url = "https://kotlinlang.org", snippet = "Statically typed"),
            SearchResult(title = "Compose", url = "https://developer.android.com/jetpack/compose", snippet = "UI toolkit"),
        )
        val out = WebSearch().formatForLlm(results, "kotlin")
        assertTrue(out.contains("kotlin"), "Query must appear in the prefix")
        assertTrue(out.contains("[1] Kotlin"), "First citation must be numbered [1]")
        assertTrue(out.contains("[2] Compose"), "Second citation must be numbered [2]")
        assertTrue(out.contains("https://kotlinlang.org"))
        assertTrue(out.contains("Statically typed"))
        assertTrue(out.contains("Cite as [1]"))
    }
}
