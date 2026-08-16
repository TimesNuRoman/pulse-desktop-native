// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — VisionEngine. Orchestrates screen capture + OCR + (optional)
// vision-capable LLM. The default path is OCR + text LLM (works today).
// Opt-in cloud VLM (OpenAI gpt-4o-mini) gives a real image-aware description
// when the user has configured an API key in Settings → Desktop.
//
// Also defines the small TextLlm + CloudVlm interfaces that the orchestrator
// depends on. Keeping them in this file (instead of separate files) avoids
// the subagent boundary problem of "where does the interface live?".
package com.pulseteam.desktop.data.desktop

import com.pulseteam.desktop.data.log.PulseLogger
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList

/** Result of "find <target> on screen" — where on the screen is the target word. */
data class ScreenMatch(
    val found: Boolean,
    val x: Int? = null,
    val y: Int? = null,
    val confidence: Int? = null,
    val matchedText: String? = null,
)

/** Result of "describe what's on screen" — a short text summary. */
data class ScreenDescription(
    val text: String,
    /** True if the image was sent to a vision-capable model. False = OCR + text LLM. */
    val usedVisionModel: Boolean,
)

/** Orchestrates capture + ocr + (cloud) VLM. Implementations must be safe to call from any thread. */
interface VisionEngine {
    /** Capture the current screen and find the center of [target] text. */
    suspend fun findOnScreen(target: String): ScreenMatch

    /** Capture the current screen and produce a short text description. */
    suspend fun describeScreen(): ScreenDescription
}

/** Text-only LLM. Wraps Pulse's existing local llama-server. */
interface TextLlm {
    suspend fun complete(prompt: String, maxTokens: Int = 256): String?
}

/** Opt-in cloud VLM. Sends image + prompt to OpenAI (or any compatible API). */
interface CloudVlm {
    fun isEnabled(): Boolean
    suspend fun describe(imagePngBytes: ByteArray, prompt: String): String?
}

/**
 * Real impl. Tries cloud VLM first when enabled; otherwise falls back to
 * OCR + text LLM. `findOnScreen` is always OCR-based (no model needed).
 *
 * Match priority: exact word match (case-insensitive) > starts-with > contains.
 * First hit wins.
 */
class OcrFallbackVisionEngine(
    private val screen: ScreenCapture,
    private val ocr: OcrEngine,
    private val textLlm: TextLlm,
    private val cloudVlm: CloudVlm?,
) : VisionEngine {

    override suspend fun findOnScreen(target: String): ScreenMatch {
        if (!screen.isAvailable() || !ocr.isAvailable()) {
            return ScreenMatch(found = false)
        }
        val img = screen.captureFull()
        val result = ocr.ocr(img)
        val t = target.trim()
        if (t.isEmpty()) return ScreenMatch(found = false)

        // Multi-word target: split on whitespace, find the run of adjacent words
        // on the same line, within a small vertical tolerance and a sane horizontal
        // gap. "Open File" → find "Open", then check if "File" follows within reach.
        val parts = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val multi = findMultiWord(result.words, parts)
            if (multi != null) return multi
            // Fall through to single-word logic on the first part, so a partial
            // match still helps the user (e.g. target "Open File", screen has "Open").
        }

        // Single-word path: exact > starts-with > contains.
        val firstPart = parts.first()
        val exact = result.words.firstOrNull { it.text.equals(firstPart, ignoreCase = true) }
        if (exact != null) return exact.toMatch()
        val starts = result.words.firstOrNull { it.text.startsWith(firstPart, ignoreCase = true) }
        if (starts != null) return starts.toMatch()
        val contains = result.words.firstOrNull { it.text.contains(firstPart, ignoreCase = true) }
        if (contains != null) return contains.toMatch()
        return ScreenMatch(found = false)
    }

    /**
     * Search [words] for a contiguous run of [parts] (case-insensitive) where
     * each consecutive word sits within [maxVerticalGapPx] vertically of the
     * previous one. Returns the centroid of the whole run, or null.
     *
     * "Adjacent" in OCR = roughly the same line, maybe with 1-2 px of vertical
     * drift between lines that wrapped. We allow up to 8 px vertical gap and
     * 250 px horizontal gap (so a wide button with the label centered is OK).
     */
    private fun findMultiWord(words: List<OcrWord>, parts: List<String>): ScreenMatch? {
        if (parts.isEmpty() || words.isEmpty()) return null
        val maxVGap = 8
        val maxHGap = 250
        for (i in words.indices) {
            if (!words[i].text.equals(parts[0], ignoreCase = true)) continue
            val run = mutableListOf(words[i])
            var cursor = i
            for (p in 1 until parts.size) {
                val prev = words[cursor]
                val next = words.drop(cursor + 1).firstOrNull { w ->
                    w.text.equals(parts[p], ignoreCase = true) &&
                        kotlin.math.abs(w.top - prev.top) <= maxVGap &&
                        (w.left - (prev.left + prev.width)) <= maxHGap &&
                        (w.left - (prev.left + prev.width)) >= -8  // slight overlap is OK
                } ?: break
                run += next
                cursor = words.indexOf(next)
            }
            if (run.size == parts.size) {
                val minLeft = run.minOf { it.left }
                val maxRight = run.maxOf { it.left + it.width }
                val minTop = run.minOf { it.top }
                val maxBottom = run.maxOf { it.top + it.height }
                return ScreenMatch(
                    found = true,
                    x = (minLeft + maxRight) / 2,
                    y = (minTop + maxBottom) / 2,
                    confidence = run.minOf { it.conf },
                    matchedText = run.joinToString(" ") { it.text },
                )
            }
        }
        return null
    }

    override suspend fun describeScreen(): ScreenDescription {
        if (!screen.isAvailable() || !ocr.isAvailable()) {
            return ScreenDescription("(screen capture or OCR unavailable)", usedVisionModel = false)
        }
        val img = screen.captureFull()
        val text = ocr.ocr(img).text
        // Cloud VLM path: only if user opted in AND provided a key.
        if (cloudVlm != null && cloudVlm.isEnabled()) {
            val pngBytes = bufferedImageToPng(img)
            val cloud = cloudVlm.describe(pngBytes, "Describe what you see on this screen in 1-2 sentences.")
            if (cloud != null) return ScreenDescription(cloud, usedVisionModel = true)
            // Fall through to local path on cloud failure (logged inside cloudVlm).
        }
        // Local OCR + text LLM path.
        if (text.isBlank()) {
            return ScreenDescription("(no readable text on screen)", usedVisionModel = false)
        }
        val prompt = "OCR text from screen:\n<text>\n$text\n</text>\n\nSummarize what's on screen in 1-2 sentences."
        val response = textLlm.complete(prompt, maxTokens = 200)
        return ScreenDescription(
            text = response ?: "(local LLM unavailable — install a model in Settings → Models)",
            usedVisionModel = false,
        )
    }

    private fun OcrWord.toMatch(): ScreenMatch = ScreenMatch(
        found = true,
        x = left + width / 2,
        y = top + height / 2,
        confidence = conf,
        matchedText = text,
    )
}

/** Test impl. Returns canned values. */
class FakeVisionEngine(
    private val match: ScreenMatch = ScreenMatch(found = false),
    private val description: ScreenDescription = ScreenDescription("(fake)", usedVisionModel = false),
) : VisionEngine {
    override suspend fun findOnScreen(target: String): ScreenMatch = match
    override suspend fun describeScreen(): ScreenDescription = description
}

/**
 * Production [TextLlm] wrapping Pulse's [com.pulseteam.desktop.data.ai.LlamaClient].
 * Uses the active model from AppSettingsStore.
 */
class LocalTextLlm(
    private val llamaClient: com.pulseteam.desktop.data.ai.LlamaClient,
    private val modelIdProvider: () -> String,
) : TextLlm {
    override suspend fun complete(prompt: String, maxTokens: Int): String? {
        val model = modelIdProvider()
        if (model.isBlank()) {
            PulseLogger.warn("LocalTextLlm: no active model configured")
            return null
        }
        return try {
            val req = com.pulseteam.desktop.data.ai.ChatRequest(
                model = model,
                messages = listOf(com.pulseteam.desktop.data.ai.ChatMessage("user", prompt)),
                maxTokens = maxTokens,
                stream = true,
            )
            // Collect the streaming deltas into a single String. The
            // Pulse llama-server streams via SSE; we glue the pieces.
            val deltas = llamaClient.chatStream(req).toList()
            val joined = deltas.joinToString("").trim()
            joined.ifBlank { null }
        } catch (t: Throwable) {
            PulseLogger.warn("LocalTextLlm.complete failed", mapOf("err" to (t.message ?: t::class.java.simpleName)))
            null
        }
    }
}

/**
 * Opt-in [CloudVlm] that POSTs to OpenAI's `/v1/chat/completions` with
 * `gpt-4o-mini` (vision-capable, cheap, ~$0.15/1M input tokens).
 * Sends the image as a `data:image/png;base64,...` URL in the user
 * message content. No new gradle deps — uses HttpURLConnection + org.json.
 */
class OpenAiCloudVlm(
    private val apiKeyProvider: () -> String,
    private val model: String = "gpt-4o-mini",
) : CloudVlm {
    override fun isEnabled(): Boolean = apiKeyProvider().isNotBlank()

    override suspend fun describe(imagePngBytes: ByteArray, prompt: String): String? {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) return null
        return try {
            val base64 = java.util.Base64.getEncoder().encodeToString(imagePngBytes)
            val body = buildRequestBody(prompt, base64)
            val conn = java.net.URL("https://api.openai.com/v1/chat/completions").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("User-Agent", "Pulse/1.0 (desktop; +https://ownlocalml.com)")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = runCatching { conn.errorStream?.bufferedReader()?.use { r -> r.readText() } }.getOrNull() ?: ""
                PulseLogger.warn("OpenAiCloudVlm: HTTP $code", mapOf("body" to errBody.take(500)))
                conn.disconnect()
                return null
            }
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            parseContent(resp)
        } catch (t: Throwable) {
            PulseLogger.warn("OpenAiCloudVlm.describe failed", mapOf("err" to (t.message ?: t::class.java.simpleName)))
            null
        }
    }

    private fun buildRequestBody(prompt: String, base64: String): String {
        // Hand-rolled JSON. We need to escape the prompt's special chars
        // (backslashes, quotes, newlines, tabs, control chars).
        val escPrompt = jsonEscape(prompt)
        return """
            {
              "model": ${jsonEscape(model)},
              "max_tokens": 300,
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "text", "text": $escPrompt},
                    {"type": "image_url", "image_url": {"url": "data:image/png;base64,$base64"}}
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    private fun parseContent(json: String): String? {
        // Hand-rolled extract of `choices[0].message.content`. Avoid
        // pulling in a JSON parser dep for a single field.
        val marker = "\"content\""
        val idx = json.indexOf(marker)
        if (idx < 0) return null
        val colon = json.indexOf(':', idx + marker.length)
        if (colon < 0) return null
        val firstQuote = json.indexOf('"', colon + 1)
        if (firstQuote < 0) return null
        val sb = StringBuilder()
        var i = firstQuote + 1
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                sb.append(when (json[i + 1]) {
                    'n' -> '\n'
                    't' -> '\t'
                    'r' -> '\r'
                    '"' -> '"'
                    '\\' -> '\\'
                    '/' -> '/'
                    else -> json[i + 1]
                })
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString().trim().ifBlank { null }
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder("\"")
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
}

/** Encode a BufferedImage as PNG bytes. Used by OcrFallbackVisionEngine. */
internal fun bufferedImageToPng(img: java.awt.image.BufferedImage): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(img, "png", out)
    return out.toByteArray()
}
