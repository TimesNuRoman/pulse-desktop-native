// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — llama-server OpenAI-compat HTTP client.
//
// llama-server exposes:
//   GET  /v1/models
//   POST /v1/chat/completions   (with stream:true → SSE "data: {...}\n\n" frames)
//
// We hand-roll SSE parsing with java.net.HttpURLConnection — no extra
// gradle deps. 4 KB streaming chunks, "data: [DONE]" terminates the stream.
package com.pulseteam.desktop.data.ai

import com.pulseteam.desktop.data.log.PulseLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(val role: String, val content: String)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val maxTokens: Int = 2048,
    val topP: Double = 0.9,
    val stream: Boolean = true,
)

data class ModelInfo(val id: String)

class LlamaClient(private val baseUrl: String = "http://127.0.0.1:11435") {

    /** List installed models (one per GGUF file loaded by llama-server). */
    fun listModels(): List<ModelInfo> {
        return try {
            val conn = URL("$baseUrl/v1/models").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                // Hand-rolled parse: "data":[{"id":"..."}]
                val regex = "\"id\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                regex.findAll(body).map { ModelInfo(it.groupValues[1]) }.toList()
            } else {
                emptyList()
            }
        } catch (t: Throwable) {
            PulseLogger.warn("listModels failed", mapOf("err" to t.message))
            emptyList()
        }
    }

    /**
     * Stream chat completion. Emits content deltas as they arrive from the
     * server. Throws on non-2xx response. Cancellable via the consuming
     * coroutine.
     */
    fun chatStream(req: ChatRequest): Flow<String> = flow {
        val conn = URL("$baseUrl/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30_000
        conn.readTimeout = 0  // no read timeout for streaming
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("User-Agent", "Pulse/1.0 (desktop; +https://ownlocalml.com)")

        val body = buildRequestBody(req)
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val errBody = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: ""
            throw RuntimeException("llama-server $code: $errBody")
        }

        val reader = BufferedReader(conn.inputStream.reader(Charsets.UTF_8))
        try {
            var currentEvent: String? = null
            val dataBuf = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (dataBuf.isNotEmpty()) {
                            val data = dataBuf.toString()
                            dataBuf.setLength(0)
                            if (data == "[DONE]") break
                            val delta = parseDelta(data)
                            if (delta != null) emit(delta)
                        }
                        currentEvent = null
                    }
                    line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> dataBuf.append(line.removePrefix("data:").trim())
                    // ignore other lines (id:, retry:, comments)
                }
            }
        } finally {
            try { reader.close() } catch (_: Throwable) {}
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(req: ChatRequest): String {
        val msgs = req.messages.joinToString(",") { msg ->
            """{"role":${jsonStr(msg.role)},"content":${jsonStr(msg.content)}}"""
        }
        return """
            {
              "model": ${jsonStr(req.model)},
              "messages": [$msgs],
              "temperature": ${req.temperature},
              "max_tokens": ${req.maxTokens},
              "top_p": ${req.topP},
              "stream": ${req.stream}
            }
        """.trimIndent()
    }

    /** Extract content delta from a `data:` frame. */
    private fun parseDelta(data: String): String? {
        // Find "content":"..." in the "delta" object
        // Frame shape: {"choices":[{"delta":{"content":"..."},"index":0}]}
        val deltaStart = data.indexOf("\"delta\"")
        if (deltaStart < 0) return null
        val contentIdx = data.indexOf("\"content\"", deltaStart)
        if (contentIdx < 0) return null
        val colon = data.indexOf(':', contentIdx)
        if (colon < 0) return null
        val firstQuote = data.indexOf('"', colon + 1)
        if (firstQuote < 0) return null
        val sb = StringBuilder()
        var i = firstQuote + 1
        while (i < data.length) {
            val c = data[i]
            if (c == '\\' && i + 1 < data.length) {
                sb.append(when (data[i + 1]) {
                    'n' -> '\n'
                    't' -> '\t'
                    'r' -> '\r'
                    '"' -> '"'
                    '\\' -> '\\'
                    '/' -> '/'
                    else -> data[i + 1]
                })
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun jsonStr(s: String): String {
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
