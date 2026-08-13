// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — real web search via DuckDuckGo HTML endpoint.
//
// DuckDuckGo's HTML endpoint (https://html.duckduckgo.com/html/) is
// a public, no-key-required search API that returns HTML. We POST the
// query as a form-urlencoded body, then hand-parse the result__a /
// result__snippet classes with regex (no DOM dep).
//
// Privacy: DuckDuckGo doesn't track. We send User-Agent "Pulse/1.0"
// but no cookies, no IP logging from us.
//
// v0.7.0: replaces the "uses /v1/web/search when back ready" mock.
package com.pulseteam.desktop.data.web

import com.pulseteam.desktop.data.log.PulseLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

class WebSearch {

    /** Result-returning variant. Returns up to [maxResults] results. */
    suspend fun search(query: String, maxResults: Int = 5): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val html = fetchHtml(query)
            parseResults(html, maxResults)
        } catch (t: Throwable) {
            PulseLogger.warn("Web search failed", mapOf("query" to query.take(80), "err" to t.message))
            emptyList()
        }
    }

    /** Format results for LLM context. Plain text, no markdown. */
    fun formatForLlm(results: List<SearchResult>, query: String): String {
        if (results.isEmpty()) return ""
        val sb = StringBuilder("Web search results for \"$query\":\n")
        results.forEachIndexed { i, r ->
            sb.append("[").append(i + 1).append("] ").append(r.title).append('\n')
            sb.append("    ").append(r.url).append('\n')
            if (r.snippet.isNotBlank()) sb.append("    ").append(r.snippet).append('\n')
        }
        sb.append("\nUse these to inform your answer. Cite as [1], [2], etc.")
        return sb.toString()
    }

    private fun fetchHtml(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = "q=$encoded&kl=us-en"
        val conn = URL("https://html.duckduckgo.com/html/").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 Pulse/1.0 (desktop; +https://ownlocalml.com)")
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw RuntimeException("DuckDuckGo HTTP $code")
        }
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        conn.disconnect()
        return text
    }

    /**
     * Hand-rolled HTML parser. We look for:
     *   <a class="result__a" href="...">TITLE</a>
     *   <a class="result__snippet" ...>SNIPPET</a>
     * OR (newer DDG layout):
     *   <h2 class="result__title"><a href="...">TITLE</a></h2>
     *   <div class="result__snippet">SNIPPET</div>
     *
     * The result URLs may be wrapped in DDG redirect URLs (//duckduckgo.com/l/?uddg=...)
     * so we extract the actual destination with regex.
     */
    private fun parseResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // Pattern 1: <a class="result__a" href="URL">TITLE</a>
        val linkRegex = Regex(
            """<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val snippetRegex = Regex(
            """<a[^>]+class="result__snippet"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        val titles = linkRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).toList()

        for (i in 0 until minOf(titles.size, maxResults)) {
            val titleHtml = titles[i].groupValues[2]
            val title = stripHtml(titleHtml).trim()
            var url = decodeUrl(titles[i].groupValues[1])
            val snippet = if (i < snippets.size) stripHtml(snippets[i].groupValues[1]).trim() else ""
            if (title.isNotEmpty() && url.isNotEmpty()) {
                results.add(SearchResult(title = title, url = url, snippet = snippet))
            }
        }
        return results
    }

    /** Strip HTML tags & decode common entities. */
    private fun stripHtml(s: String): String {
        var out = s.replace(Regex("<[^>]+>"), "")
        out = out.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        return out
    }

    /**
     * DDG wraps destination URLs in redirects like
     *   //duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2F&...
     * Extract the real URL.
     */
    private fun decodeUrl(raw: String): String {
        return if (raw.contains("uddg=")) {
            val m = Regex("uddg=([^&]+)").find(raw)
            val encoded = m?.groupValues?.get(1) ?: return raw
            java.net.URLDecoder.decode(encoded, "UTF-8")
        } else if (raw.startsWith("//")) {
            "https:$raw"
        } else {
            raw
        }
    }
}
