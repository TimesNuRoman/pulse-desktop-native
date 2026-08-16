// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — VisionEngine unit tests. Exercise OcrFallbackVisionEngine
// with Fakes for screen + ocr + text-llm. Cloud VLM path is verified
// via the `usedVisionModel` flag on ScreenDescription.
package com.pulseteam.desktop.data.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisionEngineTest {

    private class CannedTextLlm(private val response: String?) : TextLlm {
        var lastPrompt: String? = null
        override suspend fun complete(prompt: String, maxTokens: Int): String? {
            lastPrompt = prompt
            return response
        }
    }

    private class FixedCloudVlm(
        private val enabled: Boolean,
        private val response: String? = null,
    ) : CloudVlm {
        var lastCalled: Boolean = false
        override fun isEnabled(): Boolean = enabled
        override suspend fun describe(imagePngBytes: ByteArray, prompt: String): String? {
            lastCalled = true
            return response
        }
    }

    @Test
    fun `findOnScreen returns first exact case-insensitive match`() = runBlocking {
        val ocr = FakeOcrEngine(
            canned = listOf(
                OcrWord("hello", 0, 0, 50, 20, 90),
                OcrWord("HELLO", 60, 0, 50, 20, 95),
            ),
        )
        val engine = OcrFallbackVisionEngine(
            screen = FakeScreenCapture(),
            ocr = ocr,
            textLlm = CannedTextLlm(null),
            cloudVlm = null,
        )
        val m = engine.findOnScreen("hello")
        assertTrue(m.found)
        // First word in the list is "hello" (lowercase) at (0,0,50,20) → center (25,10).
        assertEquals(25, m.x)
        assertEquals(10, m.y)
        assertEquals("hello", m.matchedText)
    }

    @Test
    fun `findOnScreen falls back to starts-with and contains`() = runBlocking {
        val ocr = FakeOcrEngine(
            canned = listOf(
                OcrWord("wonderful", 0, 0, 80, 20, 90),
                OcrWord("wonder", 100, 0, 60, 20, 92),
            ),
        )
        val engine = OcrFallbackVisionEngine(
            screen = FakeScreenCapture(),
            ocr = ocr,
            textLlm = CannedTextLlm(null),
            cloudVlm = null,
        )
        // Exact "wond" is not present. starts-with "wond" matches "wonderful" first.
        val m = engine.findOnScreen("wond")
        assertTrue(m.found)
        assertEquals("wonderful", m.matchedText)
    }

    @Test
    fun `findOnScreen returns not found when no word matches`() = runBlocking {
        val ocr = FakeOcrEngine(
            canned = listOf(OcrWord("hello", 0, 0, 50, 20, 90)),
        )
        val engine = OcrFallbackVisionEngine(
            screen = FakeScreenCapture(),
            ocr = ocr,
            textLlm = CannedTextLlm(null),
            cloudVlm = null,
        )
        val m = engine.findOnScreen("missing")
        assertFalse(m.found)
    }

    @Test
    fun `findOnScreen matches multi-word target across adjacent OCR words`() = runBlocking {
        // Simulate a button labelled "Open File" with two words on the same line.
        val ocr = FakeOcrEngine(
            canned = listOf(
                OcrWord("Open", 100, 200, 60, 24, 95),
                OcrWord("File", 170, 200, 50, 24, 92),
            ),
        )
        val engine = OcrFallbackVisionEngine(
            screen = FakeScreenCapture(),
            ocr = ocr,
            textLlm = CannedTextLlm(null),
            cloudVlm = null,
        )
        val m = engine.findOnScreen("Open File")
        assertTrue(m.found)
        assertEquals("Open File", m.matchedText)
        // Centroid of (100, 170, 200, 224) → x = (100 + 220) / 2 = 160, y = 212.
        assertEquals(160, m.x)
        assertEquals(212, m.y)
    }

    @Test
    fun `findOnScreen multi-word fails when words are not adjacent`() = runBlocking {
        // "Open" at y=100, "File" at y=400 — different vertical zones. Should
        // fall back to single-word match for "Open".
        val ocr = FakeOcrEngine(
            canned = listOf(
                OcrWord("Open", 100, 100, 60, 24, 95),
                OcrWord("File", 170, 400, 50, 24, 92),
            ),
        )
        val engine = OcrFallbackVisionEngine(
            screen = FakeScreenCapture(),
            ocr = ocr,
            textLlm = CannedTextLlm(null),
            cloudVlm = null,
        )
        val m = engine.findOnScreen("Open File")
        // Multi-word fails; falls back to single-word match for "Open".
        assertTrue(m.found)
        assertEquals("Open", m.matchedText)
    }

    @Test
    fun `findOnScreen multi-word returns null when no part matches`() = runBlocking {
        val ocr = FakeOcrEngine(
            canned = listOf(OcrWord("Submit", 0, 0, 60, 20, 90)),
        )
        val engine = OcrFallbackVisionEngine(
            screen = FakeScreenCapture(),
            ocr = ocr,
            textLlm = CannedTextLlm(null),
            cloudVlm = null,
        )
        val m = engine.findOnScreen("Send Mail")
        assertFalse(m.found)
    }

    @Test
    fun `describeScreen uses cloud VLM when enabled`() = runBlocking {
        val ocr = FakeOcrEngine(canned = listOf(OcrWord("hi", 0, 0, 20, 20, 90)))
        val cloud = FixedCloudVlm(enabled = true, response = "A blue button labelled hi")
        val textLlm = CannedTextLlm("should not be used")
        val engine = OcrFallbackVisionEngine(
            screen = FakeScreenCapture(),
            ocr = ocr,
            textLlm = textLlm,
            cloudVlm = cloud,
        )
        val d = engine.describeScreen()
        assertEquals("A blue button labelled hi", d.text)
        assertTrue(d.usedVisionModel)
        assertTrue(cloud.lastCalled)
    }

    @Test
    fun `describeScreen falls back to local text LLM when cloud is disabled`() = runBlocking {
        val ocr = FakeOcrEngine(canned = listOf(OcrWord("Submit", 0, 0, 60, 20, 90)))
        val cloud = FixedCloudVlm(enabled = false, response = "should not be used")
        val textLlm = CannedTextLlm("A submit button")
        val engine = OcrFallbackVisionEngine(
            screen = FakeScreenCapture(),
            ocr = ocr,
            textLlm = textLlm,
            cloudVlm = cloud,
        )
        val d = engine.describeScreen()
        assertEquals("A submit button", d.text)
        assertFalse(d.usedVisionModel)
        // The text LLM got a prompt that contains the OCR text
        assertTrue(textLlm.lastPrompt!!.contains("Submit"))
    }

    @Test
    fun `describeScreen falls back to local text LLM when cloud returns null`() = runBlocking {
        val ocr = FakeOcrEngine(canned = listOf(OcrWord("Login", 0, 0, 40, 20, 90)))
        val cloud = FixedCloudVlm(enabled = true, response = null)
        val textLlm = CannedTextLlm("A login form")
        val engine = OcrFallbackVisionEngine(
            screen = FakeScreenCapture(),
            ocr = ocr,
            textLlm = textLlm,
            cloudVlm = cloud,
        )
        val d = engine.describeScreen()
        assertEquals("A login form", d.text)
        assertFalse(d.usedVisionModel)
    }

    @Test
    fun `FakeVisionEngine returns canned values`() = runBlocking {
        val match = ScreenMatch(found = true, x = 100, y = 200, confidence = 95, matchedText = "X")
        val desc = ScreenDescription("desc", usedVisionModel = true)
        val v = FakeVisionEngine(match = match, description = desc)
        assertEquals(match, v.findOnScreen("X"))
        assertEquals(desc, v.describeScreen())
    }
}
