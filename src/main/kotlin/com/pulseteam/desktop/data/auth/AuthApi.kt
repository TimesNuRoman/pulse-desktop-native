// SPDX-License-Identifier: Apache-2.0
// Pulse — Auth HTTP client. Talks to pulse-cf-worker at api.ownlocalml.com.
// All calls are suspend; the caller is responsible for moving them to Dispatchers.IO.
//
// Endpoints (per R264 spec):
//   POST /api/auth/register    { email, password } -> { token, user }
//   POST /api/auth/login       { email, password } -> { token, user }
//   GET  /api/auth/me          Bearer <token>      -> { user }
//   POST /api/auth/forgot      { email }           -> 200 (silent, anti-enumeration)
//   POST /api/auth/reset       { token, password } -> 200
//
// Token = JWT (HS256, opaque to client; we just store + forward).
// On 401, caller should clear session and route to AuthScreen.
package com.pulseteam.desktop.data.auth

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

data class User(val id: String, val email: String)

data class AuthResult(val token: String, val user: User)

class AuthApiException(val statusCode: Int, message: String) : IOException(message)

class AuthApi(
    private val baseUrl: String = System.getenv("PULSE_API_BASE_URL") ?: "https://api.ownlocalml.com",
) {
    private val endpoint = { path: String -> "$baseUrl$path" }

    suspend fun register(email: String, password: String): AuthResult =
        postForm("/api/auth/register", email, password)

    suspend fun login(email: String, password: String): AuthResult =
        postForm("/api/auth/login", email, password)

    suspend fun me(token: String): User {
        val (code, body) = http("GET", endpoint("/api/auth/me"), token = token)
        if (code == 401) throw AuthApiException(401, "unauthorized")
        if (code !in 200..299) throw AuthApiException(code, "me failed: $body")
        return parseUser(parseJson(body))
    }

    suspend fun forgot(email: String) {
        val (code, _) = http("POST", endpoint("/api/auth/forgot"), jsonBody = """{"email":${jsonStr(email)}}""")
        // Spec: silent 200 (anti-enumeration). Treat any 2xx as success.
        if (code !in 200..299) throw AuthApiException(code, "forgot failed")
    }

    suspend fun reset(token: String, newPassword: String) {
        val (code, body) = http(
            "POST", endpoint("/api/auth/reset"),
            jsonBody = """{"token":${jsonStr(token)},"password":${jsonStr(newPassword)}}""",
        )
        if (code == 400) throw AuthApiException(400, "invalid or expired token")
        if (code !in 200..299) throw AuthApiException(code, "reset failed: $body")
    }

    private fun postForm(path: String, email: String, password: String): AuthResult {
        val body = """{"email":${jsonStr(email)},"password":${jsonStr(password)}}"""
        val (code, resp) = http("POST", endpoint(path), jsonBody = body)
        return when (code) {
            in 200..299 -> {
                val obj = parseJson(resp)
                AuthResult(
                    token = obj["token"] as? String ?: throw AuthApiException(code, "missing token"),
                    user = parseUser(obj),
                )
            }
            401 -> throw AuthApiException(401, "invalid credentials")
            409 -> throw AuthApiException(409, "email already registered")
            else -> throw AuthApiException(code, "auth failed: $resp")
        }
    }

    // -- HTTP plumbing (blocking; caller dispatches to IO) ---------------------

    private fun http(
        method: String,
        url: String,
        token: String? = null,
        jsonBody: String? = null,
    ): Pair<Int, String> {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    // -- JSON parsing (very small, no library needed for our flat shape) ------

    private fun parseJson(s: String): Map<String, Any?> {
        val s = s.trim()
        if (s.isEmpty() || s == "null") return emptyMap()
        // Use a real parser for robustness; Jackson would be heavier, so
        // we hand-roll a flat object parser. We only ever get top-level
        // fields we know about: token, user{id,email}.
        val out = LinkedHashMap<String, Any?>()
        var i = 0
        i = skipWs(s, i); require(s[i] == '{') { "expected {" }
        i++
        while (i < s.length) {
            i = skipWs(s, i)
            if (s[i] == '}') break
            // key
            require(s[i] == '"') { "expected key" }
            val (key, ni) = readString(s, i); i = ni
            i = skipWs(s, i); require(s[i] == ':'); i++
            i = skipWs(s, i)
            // value: string | number | bool | null | object | array
            val (value, nj) = readValue(s, i); i = nj
            out[key] = value
            i = skipWs(s, i)
            if (i < s.length && s[i] == ',') i++
        }
        return out
    }

    private fun readValue(s: String, i: Int): Pair<Any?, Int> {
        val i = skipWs(s, i)
        return when (s[i]) {
            '"' -> { val (v, ni) = readString(s, i); v to ni }
            '{' -> {
                val end = matchBrace(s, i)
                val sub = s.substring(i + 1, end).trim().trimEnd(',')
                // recursive parse
                parseJson(s.substring(i, end + 1)) to (end + 1)
            }
            '[' -> {
                val end = matchBracket(s, i)
                s.substring(i + 1, end).split(",").map { it.trim() } to (end + 1)
            }
            't', 'f' -> (if (s.startsWith("true", i)) true else false) to (i + if (s.startsWith("true", i)) 4 else 5)
            'n' -> null to (i + 4)
            else -> {
                // number
                var j = i
                while (j < s.length && s[j] !in ",}] \n\t\r") j++
                val raw = s.substring(i, j)
                val n = raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
                n to j
            }
        }
    }

    private fun readString(s: String, i: Int): Pair<String, Int> {
        require(s[i] == '"')
        val sb = StringBuilder()
        var j = i + 1
        while (j < s.length) {
            val c = s[j]
            if (c == '"') return sb.toString() to (j + 1)
            if (c == '\\' && j + 1 < s.length) {
                when (s[j + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> if (j + 5 < s.length) {
                        val hex = s.substring(j + 2, j + 6)
                        sb.append(hex.toInt(16).toChar()); j += 4
                    }
                    else -> sb.append(s[j + 1])
                }
                j += 2
            } else { sb.append(c); j++ }
        }
        error("unterminated string")
    }

    private fun skipWs(s: String, i: Int): Int {
        var j = i
        while (j < s.length && s[j].isWhitespace()) j++
        return j
    }

    private fun matchBrace(s: String, i: Int): Int {
        var depth = 0; var j = i
        while (j < s.length) {
            when (s[j]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return j }
                '"' -> j = readString(s, j).second - 1
            }
            j++
        }
        error("unbalanced {")
    }

    private fun matchBracket(s: String, i: Int): Int {
        var depth = 0; var j = i
        while (j < s.length) {
            when (s[j]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return j }
                '"' -> j = readString(s, j).second - 1
            }
            j++
        }
        error("unbalanced [")
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
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun parseUser(obj: Map<String, Any?>): User {
        val u = obj["user"] as? Map<*, *> ?: throw AuthApiException(500, "missing user")
        return User(
            id = u["id"]?.toString() ?: "",
            email = u["email"]?.toString() ?: "",
        )
    }
}
