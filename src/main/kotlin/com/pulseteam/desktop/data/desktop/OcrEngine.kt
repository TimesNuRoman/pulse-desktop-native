// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — OCR. Real impl shells out to `tesseract` (matches the
// Whisper-CLI pattern; no new Java dep). User must install Tesseract
// system-wide (brew/apt/dnf/UB-Mannheim installer). TSV output gives
// word-level bounding boxes which the controller uses for "click on [X]".
// Fake impl returns canned words for unit tests.
package com.pulseteam.desktop.data.desktop

import com.pulseteam.desktop.data.log.PulseLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * A single recognised word, with its bounding box in image-local coords
 * (0,0 = top-left of the source image). Confidence is 0-100, or -1 if
 * the underlying engine didn't report it. Words with conf < 60 are
 * filtered out before they reach the caller.
 */
data class OcrWord(
    val text: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val conf: Int,
)

/** Result of running OCR on an image. */
data class OcrResult(
    /** All retained words concatenated with single spaces. May be empty. */
    val text: String,
    /** Word-level boxes (filtered to conf >= 60). */
    val words: List<OcrWord>,
)

/** OCR abstraction. Implementations should be safe to call from any thread. */
interface OcrEngine {
    /** Run OCR on the given image. Default language = English. */
    suspend fun ocr(image: BufferedImage, lang: String = "eng"): OcrResult
    /** True if the tesseract binary is on PATH and runnable. */
    fun isAvailable(): Boolean
    /** Short human-readable status for the Settings panel. */
    fun statusMessage(): String
}

/**
 * Real impl: writes the image to a temp PNG, shells out to
 * `tesseract <input> <outputBase> -l <lang> tsv`, parses the TSV.
 * Logs stdout/stderr to `~/.pulse/logs/tesseract.log`. 10s timeout.
 *
 * Does NOT download tesseract or tessdata. If the binary is missing,
 * `isAvailable()` returns false and `statusMessage()` returns a clear
 * install hint per platform.
 */
class TesseractCliOcr : OcrEngine {
    private val logDir = File(System.getProperty("user.home"), ".pulse/logs")
    private val logFile = File(logDir, "tesseract.log")

    // Detect tesseract once on construction. `which` is the POSIX way; on
    // Windows we fall back to `where`. Both write a path or nothing to
    // stdout; non-zero exit means "not found".
    private val detected: Boolean by lazy { probeBinary() != null }
    private val detectedVersion: String? by lazy { probeBinary()?.let { runVersionProbe() } }

    override suspend fun ocr(image: BufferedImage, lang: String): OcrResult = withContext(Dispatchers.IO) {
        if (!detected) {
            PulseLogger.warn("OcrEngine: tesseract not found on PATH")
            return@withContext OcrResult("", emptyList())
        }
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "pulse-ocr")
        tmpDir.mkdirs()
        val tmpPng = File.createTempFile("pulse-ocr-", ".png", tmpDir)
        try {
            ImageIO.write(image, "png", tmpPng)
            val outBase = File(tmpDir, tmpPng.nameWithoutExtension)
            val outTsv = File(outBase.absolutePath + ".tsv")
            // `tesseract <input> <outputBase> -l <lang> tsv` writes <outputBase>.tsv
            // We pass outputBase WITHOUT the .tsv suffix — tesseract adds it.
            val proc = ProcessBuilder(
                "tesseract", tmpPng.absolutePath, outBase.absolutePath,
                "-l", lang, "tsv",
            ).redirectErrorStream(true).start()
            val stdout = StringBuilder()
            proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { stdout.appendLine(it) }
            }
            val finished = proc.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                PulseLogger.error("OcrEngine: tesseract timed out after 10s", null)
                return@withContext OcrResult("", emptyList())
            }
            if (proc.exitValue() != 0) {
                appendLog("ocr failed: exit=${proc.exitValue()}\n${stdout}\n")
                PulseLogger.error("OcrEngine: non-zero exit", null, mapOf("exit" to proc.exitValue()))
                return@withContext OcrResult("", emptyList())
            }
            if (!outTsv.exists()) {
                appendLog("ocr produced no tsv file: ${outTsv.absolutePath}\nstdout: ${stdout}\n")
                return@withContext OcrResult("", emptyList())
            }
            val words = parseTsv(outTsv)
            OcrResult(words.joinToString(" ") { it.text }, words)
        } catch (t: Throwable) {
            PulseLogger.error("OcrEngine: failed", t)
            OcrResult("", emptyList())
        } finally {
            // Clean up temp files; never leave a .png in the user's temp dir.
            tmpPng.delete()
            File(tmpPng.absolutePath.replaceAfterLast('.', "tsv")).delete()
        }
    }

    override fun isAvailable(): Boolean = detected

    override fun statusMessage(): String = when {
        detected -> detectedVersion ?: "tesseract (unknown version)"
        isWindows() -> "tesseract not found — install UB-Mannheim Tesseract: https://github.com/UB-Mannheim/tesseract/wiki"
        isMac() -> "tesseract not found — run: brew install tesseract"
        else -> "tesseract not found — run: sudo apt install tesseract-ocr"
    }

    // ----- internals -----

    private fun probeBinary(): String? = try {
        val cmd = if (isWindows()) arrayOf("where", "tesseract") else arrayOf("which", "tesseract")
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.firstOrNull { it.isNotBlank() }?.trim()
        }?.also { proc.waitFor(2, TimeUnit.SECONDS) }
    } catch (t: Throwable) {
        null
    }

    private fun runVersionProbe(): String? = try {
        val proc = ProcessBuilder("tesseract", "--version").redirectErrorStream(true).start()
        val first = proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.firstOrNull { it.isNotBlank() }?.trim()
        }
        proc.waitFor(2, TimeUnit.SECONDS)
        first
    } catch (t: Throwable) {
        null
    }

    private fun isWindows(): Boolean = System.getProperty("os.name")?.lowercase()?.contains("win") == true
    private fun isMac(): Boolean = System.getProperty("os.name")?.lowercase()?.contains("mac") == true

    private fun appendLog(line: String) {
        try {
            logDir.mkdirs()
            logFile.appendText(line)
        } catch (_: Throwable) {
            // Best-effort logging; never fail the OCR call because we couldn't write a log.
        }
    }

    companion object {
        /**
         * Parse a tesseract TSV file. Columns (1-indexed): level(1) page_num(2)
         * block_num(3) par_num(4) line_num(5) word_num(6) left(7) top(8) width(9)
         * height(10) conf(11) text(12). We only care about conf, text, and the
         * box coords. We drop the header row and rows with blank text or conf<60.
         */
        internal fun parseTsv(file: File): List<OcrWord> {
            if (!file.exists()) return emptyList()
            val out = mutableListOf<OcrWord>()
            file.useLines { lines ->
                lines.drop(1).forEach { line ->
                    val cols = line.split('\t')
                    if (cols.size < 12) return@forEach
                    val text = cols[11].trim()
                    if (text.isEmpty()) return@forEach
                    val conf = cols[10].toFloatOrNull()?.toInt() ?: -1
                    if (conf in 0..100 && conf < 60) return@forEach
                    out += OcrWord(
                        text = text,
                        left = cols[6].toIntOrNull() ?: 0,
                        top = cols[7].toIntOrNull() ?: 0,
                        width = cols[8].toIntOrNull() ?: 0,
                        height = cols[9].toIntOrNull() ?: 0,
                        conf = conf,
                    )
                }
            }
            return out
        }
    }
}

/**
 * Test impl. Returns the canned [words] for every call. Useful for
 * driving the vision/orchestrator tests without a real Tesseract install.
 */
class FakeOcrEngine(
    private val canned: List<OcrWord> = emptyList(),
    private val available: Boolean = true,
    private val status: String = "fake",
) : OcrEngine {
    override suspend fun ocr(image: BufferedImage, lang: String): OcrResult =
        OcrResult(canned.joinToString(" ") { it.text }, canned)

    override fun isAvailable(): Boolean = available
    override fun statusMessage(): String = status
}
