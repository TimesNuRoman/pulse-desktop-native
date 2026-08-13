// SPDX-License-Identifier: Apache-2.0
// Pulse — note link parser. Recognises [[Title]] and [[Title|body]] in AI
// responses, hands back a list of (title, body?) pairs that the ChatViewModel
// can turn into real notes via NoteRepository.
//
// Conventions:
//   [[Sprint plan]]              -> title="Sprint plan", body=null
//   [[FTS search|fast lookups]]  -> title="FTS search", body="fast lookups"
//   [[a | b | c]]                -> title="a", body="b | c"  (first '|' is the split)
//
// Whitespace around title and body is trimmed. Empty titles are skipped.
package com.pulseteam.desktop.data.notes

data class NoteLink(val title: String, val body: String?)

object NoteLinkParser {
    private val rx = Regex("""\[\[([^\[\]\n]+?)\]\]""")

    fun extract(text: String): List<NoteLink> {
        val out = mutableListOf<NoteLink>()
        for (m in rx.findAll(text)) {
            val raw = m.groupValues[1].trim()
            if (raw.isEmpty()) continue
            val pipe = raw.indexOf('|')
            if (pipe < 0) {
                out += NoteLink(raw, null)
            } else {
                val t = raw.substring(0, pipe).trim()
                val b = raw.substring(pipe + 1).trim()
                if (t.isNotEmpty()) out += NoteLink(t, b.ifEmpty { null })
            }
        }
        return out
    }
}
