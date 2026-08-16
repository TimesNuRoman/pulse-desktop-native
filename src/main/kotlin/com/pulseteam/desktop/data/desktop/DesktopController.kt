// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — DesktopController. Single entry point for the UI.
// Wires together ScreenCapture + OcrEngine + VisionEngine + PcController +
// SafetyGate. UI calls these high-level methods; the controller handles
// capture, OCR, safety approval, and execution.
package com.pulseteam.desktop.data.desktop

import com.pulseteam.desktop.data.log.PulseLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/** What the user proposed, and how the controller resolved it. */
sealed class ProposeResult {
    /** Action needs user confirmation; UI should show the dialog. */
    data class NeedsConfirmation(
        val summary: String,
        val previewPath: File,
        val x: Int,
        val y: Int,
    ) : ProposeResult()
    /** Vision couldn't find the target on screen. */
    data class NotFound(val target: String) : ProposeResult()
    /** One of the engines is unavailable (no tesseract / no display / no Robot). */
    data class Unavailable(val reason: String) : ProposeResult()
    /** Operation completed (used by Screenshot / readScreenText). */
    data class Executed(val message: String) : ProposeResult()
}

/**
 * Orchestrator that the UI calls. Wires together:
 * - capture + OCR (always)
 * - vision model (opt-in cloud; default = text LLM fallback)
 * - PC controller (mouse + keyboard)
 * - safety gate (always-confirm in MVP)
 */
class DesktopController(
    private val screen: ScreenCapture,
    private val ocr: OcrEngine,
    private val pc: PcController,
    private val vision: VisionEngine,
    private val safety: SafetyGate,
    private val capturesDir: File = File(System.getProperty("user.home"), ".pulse/captures"),
) {
    /** Public re-export of the SafetyGate state so the UI can render the confirm dialog. */
    val safetyGateState get() = safety.state

    /** True if screen + ocr + pc all report available. */
    fun isReady(): Boolean = screen.isAvailable() && ocr.isAvailable() && pc.isAvailable()

    // Per-engine status accessors (for the Settings panel's status rows).
    val ocrStatus: String get() = ocr.statusMessage()
    val ocrAvailable: Boolean get() = ocr.isAvailable()
    val pcAvailable: Boolean get() = pc.isAvailable()
    val screenAvailable: Boolean get() = screen.isAvailable()

    /** Capture screen + save to ~/.pulse/captures/yyyy-MM-dd-HHmmss.png. */
    suspend fun takeScreenshot(): File = withContext(Dispatchers.IO) {
        if (!screen.isAvailable()) {
            PulseLogger.warn("DesktopController.takeScreenshot: screen capture unavailable")
            throw IllegalStateException("screen capture unavailable on this platform")
        }
        capturesDir.mkdirs()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
        val dest = File(capturesDir, "$stamp.png")
        val img = screen.captureFull()
        ImageIO.write(img, "png", dest)
        PulseLogger.info("Screenshot saved", mapOf("path" to dest.absolutePath, "bytes" to dest.length()))
        dest
    }

    /** Capture + OCR. Returns the recognised text. Passive (no safety gate). */
    suspend fun readScreenText(): String = withContext(Dispatchers.IO) {
        if (!screen.isAvailable() || !ocr.isAvailable()) {
            PulseLogger.warn("DesktopController.readScreenText: screen or OCR unavailable")
            return@withContext ""
        }
        val img = screen.captureFull()
        ocr.ocr(img).text
    }

    /**
     * Find [target] on screen, then ask the SafetyGate to authorise a click.
     * The UI watches `safetyGateState` and renders the confirm dialog.
     * When the user clicks Confirm, the UI calls [executeApproved].
     */
    suspend fun proposeClickOnText(target: String): ProposeResult = withContext(Dispatchers.IO) {
        if (!isReady()) {
            return@withContext ProposeResult.Unavailable(
                when {
                    !screen.isAvailable() -> "screen capture unavailable"
                    !ocr.isAvailable() -> ocr.statusMessage()
                    !pc.isAvailable() -> "PC interaction unavailable (no Robot)"
                    else -> "unknown"
                }
            )
        }
        val match = vision.findOnScreen(target)
        if (!match.found) {
            return@withContext ProposeResult.NotFound(target)
        }
        // Save a preview screenshot for the dialog
        val img = screen.captureFull()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
        val preview = File(capturesDir, "preview-$stamp.png").also {
            capturesDir.mkdirs()
            ImageIO.write(img, "png", it)
        }
        val x = match.x ?: 0
        val y = match.y ?: 0
        val summary = "Click at ($x, $y) — \"${match.matchedText ?: target}\""
        val click = DesktopAction.Click(x, y)
        val allowed = safety.request(click, summary, preview)
        if (allowed) {
            // Shouldn't happen at AlwaysConfirm, but if it does (e.g. safety disabled), execute immediately.
            executeAction(click)
            ProposeResult.Executed(summary)
        } else {
            ProposeResult.NeedsConfirmation(summary, preview, x, y)
        }
    }

    /**
     * Run whatever is currently in `safety.state.value.pending`. Called by
     * the UI when the user clicks Confirm in the dialog. Returns true if
     * there was a pending action and we executed it.
     */
    suspend fun executeApproved(): Boolean = withContext(Dispatchers.IO) {
        val pending = safety.state.value.pending ?: return@withContext false
        try {
            executeAction(pending.action)
            PulseLogger.info("DesktopController: executed approved action", mapOf("summary" to pending.summary))
        } catch (t: Throwable) {
            PulseLogger.error("DesktopController: approved action failed", t)
        } finally {
            safety.confirm()
        }
        true
    }

    /** Drop the pending action without executing. UI calls this on Cancel. */
    fun cancelPending() {
        safety.cancel()
    }

    private fun executeAction(action: DesktopAction) {
        when (action) {
            is DesktopAction.Click -> pc.click(action.x, action.y, action.button)
            is DesktopAction.Type -> pc.typeText(action.text)
            is DesktopAction.Key -> pc.pressKey(action.keyCode)
            is DesktopAction.Hotkey -> pc.pressHotkey(action.keyCodes)
            is DesktopAction.Screenshot -> {
                // Should never reach the executor (Screenshot is passive),
                // but handle it gracefully just in case.
                PulseLogger.warn("DesktopController.executeAction: Screenshot routed through safety (unexpected)")
            }
        }
    }
}
