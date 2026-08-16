// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — OcrEngine unit tests. Exercise the Fake engine
// (real Tesseract isn't installed in CI). Also cover the TesseractCliOcr
// TSV parser as a pure function so a regression in column indexes is
// caught even without a real tesseract binary.
package com.pulseteam.desktop.data.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path

class OcrEngineTest {

    @Test
    fun `FakeOcrEngine returns canned words`() {
        val words = listOf(
            OcrWord("hello", 0, 0, 50, 20, 90),
            OcrWord("world", 60, 0, 50, 20, 95),
        )
        val ocr = FakeOcrEngine(canned = words)
        val img = BufferedImage(100, 50, BufferedImage.TYPE_INT_ARGB)
        val res = runBlocking { ocr.ocr(img) }
        assertEquals(2, res.words.size)
        assertEquals("hello world", res.text)
    }

    @Test
    fun `FakeOcrEngine with empty canned returns empty result`() {
        val ocr = FakeOcrEngine()
        val res = runBlocking { ocr.ocr(BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)) }
        assertTrue(res.words.isEmpty())
        assertEquals("", res.text)
    }

    @Test
    fun `FakeOcrEngine isAvailable and status reflect config`() {
        val ok = FakeOcrEngine(available = true, status = "tesseract 5.4.1")
        assertTrue(ok.isAvailable())
        assertEquals("tesseract 5.4.1", ok.statusMessage())
        val missing = FakeOcrEngine(available = false, status = "tesseract not found")
        assertFalse(missing.isAvailable())
        assertEquals("tesseract not found", missing.statusMessage())
    }

    @Test
    fun `TesseractCliOcr parseTsv returns empty for missing file`() {
        val words = TesseractCliOcr.parseTsv(File("does-not-exist.tsv"))
        assertTrue(words.isEmpty())
    }

    @Test
    fun `TesseractCliOcr parseTsv drops header and low-confidence rows`(@TempDir tmp: Path) {
        // tesseract TSV header + 3 rows. Conf 90, 50, 70. Expected: rows 1 and 3 (90 and 70).
        // Columns separated by real \t characters (NOT spaces).
        val tsv = tmp.resolve("out.tsv").toFile()
        tsv.writeText(
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n" +
                "5\t1\t1\t1\t1\t1\t0\t0\t50\t20\t90\thello\n" +
                "5\t1\t1\t1\t1\t2\t60\t0\t50\t20\t50\tlow\n" +
                "5\t1\t1\t1\t1\t3\t120\t0\t50\t20\t70\tworld\n"
        )
        val words = TesseractCliOcr.parseTsv(tsv)
        assertEquals(2, words.size)
        assertEquals("hello", words[0].text)
        assertEquals(0, words[0].left)
        assertEquals(90, words[0].conf)
        assertEquals("world", words[1].text)
        assertEquals(70, words[1].conf)
    }

    @Test
    fun `TesseractCliOcr parseTsv drops rows with blank text`(@TempDir tmp: Path) {
        val tsv = tmp.resolve("out.tsv").toFile()
        tsv.writeText(
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n" +
                "5\t1\t1\t1\t1\t1\t0\t0\t50\t20\t90\tok\n" +
                "5\t1\t1\t1\t1\t2\t60\t0\t50\t20\t99\t\n"
        )
        val words = TesseractCliOcr.parseTsv(tsv)
        assertEquals(1, words.size)
        assertEquals("ok", words[0].text)
    }

    @Test
    fun `TesseractCliOcr refresh re-probes the binary on PATH`() {
        // Create a real impl and call refresh. The result depends on the
        // host: if tesseract is installed, isAvailable stays true and
        // statusMessage contains "tesseract". If not, statusMessage
        // contains the install hint. Either way, the call must not throw.
        val ocr = TesseractCliOcr()
        val before = ocr.isAvailable()
        val msgBefore = ocr.statusMessage()
        // Just call refresh — it re-probes and updates the cache.
        ocr.refresh()
        val after = ocr.isAvailable()
        val msgAfter = ocr.statusMessage()
        // The values should be stable across refresh() on the same machine.
        assertEquals(before, after)
        assertEquals(msgBefore, msgAfter)
    }
}
