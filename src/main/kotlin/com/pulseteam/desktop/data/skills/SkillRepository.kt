// SPDX-License-Identifier: Apache-2.0
// Pulse — SkillRepository. CRUD on ~/.pulse/skills.json.
//
// We hand-roll a minimal JSON encoder/decoder instead of pulling in
// kotlinx-serialization. The file is small (one array of objects with
// stable fields) and the dep cost is not worth it for one feature.
package com.pulseteam.desktop.data.skills

import com.pulseteam.desktop.data.log.PulseLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class SkillRepository {
    private val file: Path = Path.of(System.getProperty("user.home"), ".pulse", "skills.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    init {
        scope.launch { load() }
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    fun upsert(skill: Skill) {
        val now = _skills.value.toMutableList()
        val idx = now.indexOfFirst { it.id == skill.id }
        if (idx >= 0) now[idx] = skill else now.add(skill)
        commit(now)
    }

    fun delete(id: String) {
        commit(_skills.value.filter { it.id != id })
    }

    fun recordUse(id: String, userMessage: String, autoTriggered: Boolean) {
        val now = _skills.value.toMutableList()
        val idx = now.indexOfFirst { it.id == id }
        if (idx < 0) return
        val s = now[idx]
        val activation = SkillActivation(
            timestamp = System.currentTimeMillis(),
            userMessage = userMessage.take(200),
            autoTriggered = autoTriggered,
        )
        val newHistory = (listOf(activation) + s.history).take(50)
        now[idx] = s.copy(uses = s.uses + 1, history = newHistory)
        commit(now)
    }

    fun recordAcceptance(id: String, accepted: Boolean) {
        val now = _skills.value.toMutableList()
        val idx = now.indexOfFirst { it.id == id }
        if (idx < 0) return
        val s = now[idx]
        val newAccepted = s.accepted + if (accepted) 1 else 0
        // Also flip the most recent history entry's accepted flag so the
        // user can see what they voted on.
        val lastIdx = s.history.indexOfFirst { it.accepted == null }
        val newHistory = if (lastIdx >= 0) {
            s.history.toMutableList().also { it[lastIdx] = it[lastIdx].copy(accepted = accepted) }
        } else s.history
        now[idx] = s.copy(accepted = newAccepted, history = newHistory)
        commit(now)
    }

    fun findById(id: String): Skill? = _skills.value.firstOrNull { it.id == id }

    /**
     * Return the skills whose triggers match the given message, in
     * order: pinned first, then by accept-rate desc, then by uses desc.
     */
    fun matching(message: String): List<Skill> {
        val lower = message.lowercase()
        return _skills.value
            .filter { s -> s.triggers.any { t -> triggerMatches(t, lower) } }
            .sortedWith(
                compareByDescending<Skill> { it.pinned }
                    .thenByDescending { it.acceptRate() ?: 0.0 }
                    .thenByDescending { it.uses }
            )
    }

    private fun triggerMatches(trigger: String, lowerMessage: String): Boolean {
        val t = trigger.trim()
        if (t.isEmpty()) return false
        return when {
            t.startsWith("/") && t.endsWith("/") && t.length > 2 -> {
                val pattern = t.substring(1, t.length - 1)
                runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(lowerMessage) }.getOrDefault(false)
            }
            t.startsWith("\"") && t.endsWith("\"") && t.length > 2 -> {
                val literal = t.substring(1, t.length - 1).lowercase()
                lowerMessage.contains(literal)
            }
            t.startsWith("!") -> {
                // tag-style, e.g. "!code" → match "tag:code" or "[code]"
                val tag = t.substring(1).lowercase()
                lowerMessage.contains("tag:$tag") || lowerMessage.contains("[$tag]")
            }
            else -> lowerMessage.contains(t.lowercase())
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private fun commit(newList: List<Skill>) {
        _skills.value = newList
        scope.launch { persist(newList) }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) {
            // Seed with two example skills on first run so the user sees
            // what they look like. They can edit or delete.
            val seeded = listOf(
                Skill(
                    name = "Code review",
                    description = "Reviews diffs and points out bugs, perf issues, and style nits.",
                    body = "You are a senior engineer doing a code review. Be terse. For each finding give: line, severity, fix.",
                    triggers = listOf("review", "code", "/class \\w+/"),
                    category = "Coding",
                    pinned = true,
                ),
                Skill(
                    name = "Summarize",
                    description = "TL;DRs long text into 3 bullets + 1 sentence.",
                    body = "Summarize the user's input in 3 bullet points and 1 sentence.",
                    triggers = listOf("summarize", "tl;dr", "tl dr"),
                    category = "Writing",
                ),
            )
            commit(seeded)
            return@withContext
        }
        runCatching {
            val text = Files.readString(file)
            _skills.value = decode(text)
        }.onFailure { t ->
            PulseLogger.error("SkillRepository: failed to load skills.json", t)
        }
    }

    private suspend fun persist(list: List<Skill>) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            // On Windows, two rapid commits can race when the first write's
            // ATOMIC_MOVE has not yet fully released the target file handle
            // (Windows Defender indexer / file lock from a parallel reader
            // can both hold it briefly). A unique temp filename + REPLACE_EXISTING
            // (no ATOMIC_MOVE) + a small retry loop makes this reliable in
            // practice. We try up to 3 times before giving up; each retry uses
            // a fresh temp name to avoid stale-file collisions.
            var attempt = 0
            var lastError: Throwable? = null
            while (attempt < 3) {
                attempt++
                val tmp = file.parent.resolve("skills.json.tmp.${System.nanoTime()}")
                try {
                    Files.createDirectories(file.parent)
                    Files.writeString(tmp, encode(list))
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                    // Clean up any leftover tmp files from a prior interrupted
                    // write. We do this AFTER the successful move so we never
                    // delete the current tmp mid-write.
                    runCatching {
                        Files.newDirectoryStream(file.parent, "skills.json.tmp.*").use { stream ->
                            stream.forEach { p -> if (p != tmp) runCatching { Files.deleteIfExists(p) } }
                        }
                    }
                    return@withContext
                } catch (t: AccessDeniedException) {
                    lastError = t
                    // Brief pause then retry with a fresh temp file.
                    runCatching { Files.deleteIfExists(tmp) }
                    Thread.sleep(50L * attempt)
                } catch (t: Throwable) {
                    lastError = t
                    runCatching { Files.deleteIfExists(tmp) }
                    // Non-retryable: don't loop.
                    break
                }
            }
            PulseLogger.error("SkillRepository: failed to persist skills.json after $attempt attempts",
                lastError, mapOf("file" to file.toString()))
        }
    }

    // ------------------------------------------------------------------
    // Hand-rolled JSON encoder/decoder. We don't need full RFC 8259,
    // we just need to round-trip our own Skill shape. Strings are
    // JSON-escaped; everything else is skipped/parsed.
    // ------------------------------------------------------------------

    private fun encode(list: List<Skill>): String {
        val sb = StringBuilder("[\n")
        list.forEachIndexed { i, s ->
            sb.append("  ").append(encodeSkill(s))
            if (i < list.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("]\n")
        return sb.toString()
    }

    private fun encodeSkill(s: Skill): String = buildString {
        append('{')
        append("\"id\":").append(jsonStr(s.id)).append(',')
        append("\"name\":").append(jsonStr(s.name)).append(',')
        append("\"description\":").append(jsonStr(s.description)).append(',')
        append("\"body\":").append(jsonStr(s.body)).append(',')
        append("\"triggers\":").append(jsonStrList(s.triggers)).append(',')
        append("\"category\":").append(jsonStr(s.category)).append(',')
        append("\"pinned\":").append(if (s.pinned) "true" else "false").append(',')
        append("\"uses\":").append(s.uses).append(',')
        append("\"accepted\":").append(s.accepted).append(',')
        append("\"history\":").append(encodeHistory(s.history)).append(',')
        append("\"createdAt\":").append(s.createdAt)
        append('}')
    }

    private fun encodeHistory(h: List<SkillActivation>): String = buildString {
        append('[')
        h.forEachIndexed { i, a ->
            append('{')
            append("\"timestamp\":").append(a.timestamp).append(',')
            append("\"userMessage\":").append(jsonStr(a.userMessage)).append(',')
            append("\"autoTriggered\":").append(if (a.autoTriggered) "true" else "false").append(',')
            when (a.accepted) {
                true -> append("\"accepted\":true")
                false -> append("\"accepted\":false")
                null -> append("\"accepted\":null")
            }
            append('}')
            if (i < h.size - 1) append(',')
        }
        append(']')
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder(s.length + 4)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun jsonStrList(xs: List<String>): String =
        xs.joinToString(prefix = "[", postfix = "]") { jsonStr(it) }

    // ---- decoder ----

    private fun decode(text: String): List<Skill> {
        val p = JsonParser(text)
        p.skipWs()
        p.expect('[')
        val out = mutableListOf<Skill>()
        p.skipWs()
        if (p.peek() == ']') { p.next(); return out }
        while (true) {
            p.skipWs()
            out.add(decodeSkill(p))
            p.skipWs()
            when (p.peek()) {
                ',' -> { p.next(); continue }
                ']' -> { p.next(); break }
                else -> error("Expected , or ] at pos ${p.pos}")
            }
        }
        return out
    }

    private fun decodeSkill(p: JsonParser): Skill {
        p.skipWs(); p.expect('{')
        val map = linkedMapOf<String, Any?>()
        p.skipWs()
        if (p.peek() == '}') { p.next(); return skillFromMap(map) }
        while (true) {
            p.skipWs()
            val key = p.readString()
            p.skipWs(); p.expect(':')
            p.skipWs()
            val v: Any? = when (p.peek()) {
                '"' -> p.readString()
                '[' -> p.readArray()
                't' -> { p.expectKeyword("true"); true }
                'f' -> { p.expectKeyword("false"); false }
                'n' -> { p.expectKeyword("null"); null }
                else -> p.readNumberOrWord()
            }
            map[key] = v
            p.skipWs()
            when (p.peek()) {
                ',' -> { p.next(); continue }
                '}' -> { p.next(); break }
                else -> error("Expected , or } at pos ${p.pos}")
            }
        }
        return skillFromMap(map)
    }

    private fun skillFromMap(map: Map<String, Any?>): Skill {
        fun s(key: String, default: String = ""): String = (map[key] as? String) ?: default
        fun i(key: String, default: Long = 0L): Long = (map[key] as? Long) ?: default
        fun b(key: String, default: Boolean = false): Boolean = (map[key] as? Boolean) ?: default
        fun l(key: String): List<String> = (map[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val rawHistory = (map["history"] as? List<*>) ?: emptyList<Any?>()
        val history = rawHistory.map { e ->
            val m = e as? Map<*, *> ?: return@map SkillActivation(0, "", false, null)
            SkillActivation(
                timestamp = (m["timestamp"] as? Long) ?: 0L,
                userMessage = (m["userMessage"] as? String) ?: "",
                autoTriggered = (m["autoTriggered"] as? Boolean) ?: false,
                accepted = when (m["accepted"]) {
                    true -> true
                    false -> false
                    else -> null
                },
            )
        }
        return Skill(
            id = s("id"),
            name = s("name", "Untitled"),
            description = s("description"),
            body = s("body"),
            triggers = l("triggers"),
            category = s("category", "General"),
            pinned = b("pinned"),
            uses = i("uses").toInt(),
            accepted = i("accepted").toInt(),
            history = history,
            createdAt = i("createdAt"),
        )
    }
}

/** Tiny JSON reader for our own shape. NOT a general-purpose parser. */
private class JsonParser(val src: String) {
    var pos = 0
    fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'
    fun next(): Char = src[pos++]
    fun skipWs() { while (pos < src.length && src[pos].isWhitespace()) pos++ }
    fun expect(c: Char) {
        if (peek() != c) error("Expected '$c' at pos $pos, got '${peek()}'")
        pos++
    }
    fun expectKeyword(kw: String) {
        if (!src.startsWith(kw, pos)) error("Expected '$kw' at pos $pos")
        pos += kw.length
    }
    fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = next()
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    when (val esc = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = src.substring(pos, pos + 4)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> sb.append(esc)
                    }
                }
                else -> sb.append(c)
            }
        }
    }
    fun readArray(): List<Any?> {
        expect('[')
        val out = mutableListOf<Any?>()
        skipWs()
        if (peek() == ']') { next(); return out }
        while (true) {
            skipWs()
            val v: Any? = when (peek()) {
                '"' -> readString()
                '[' -> readArray()
                't' -> { expectKeyword("true"); true }
                'f' -> { expectKeyword("false"); false }
                'n' -> { expectKeyword("null"); null }
                '{' -> error("Nested objects not supported (only the top-level skill shape is needed)")
                else -> readNumberOrWord()
            }
            out.add(v)
            skipWs()
            when (peek()) {
                ',' -> { next(); continue }
                ']' -> { next(); break }
                else -> error("Expected , or ] at pos $pos")
            }
        }
        return out
    }
    fun readNumberOrWord(): Any? {
        val start = pos
        while (pos < src.length && src[pos] !in ",]} \n\r\t") pos++
        val s = src.substring(start, pos)
        return s.toLongOrNull() ?: s
    }
}
