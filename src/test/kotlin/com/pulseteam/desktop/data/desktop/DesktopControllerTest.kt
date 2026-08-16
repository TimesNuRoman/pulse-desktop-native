// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — DesktopController unit tests. Use Fakes for every
// dependency so we can verify the orchestration + safety gate end-to-end
// without a real display / tesseract / Robot.
package com.pulseteam.desktop.data.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class DesktopControllerTest {

    private class CannedVision(
        private val match: ScreenMatch,
    ) : VisionEngine {
        var lastFindTarget: String? = null
        override suspend fun findOnScreen(target: String): ScreenMatch {
            lastFindTarget = target
            return match
        }
        override suspend fun describeScreen(): ScreenDescription =
            ScreenDescription("(canned)", usedVisionModel = false)
    }

    private fun controller(
        match: ScreenMatch,
        vision: CannedVision = CannedVision(match),
        tempDir: Path,
    ): Triple<DesktopController, SafetyGate, FakePcController> {
        val screen = FakeScreenCapture(w = 1920, h = 1080)
        val ocr = FakeOcrEngine(canned = listOf(OcrWord("ok", 0, 0, 20, 20, 90)))
        val pc = FakePcController()
        val safety = SafetyGate()
        safety.configure(enabled = true, level = SafetyLevel.AlwaysConfirm)
        val ctrl = DesktopController(
            screen = screen,
            ocr = ocr,
            pc = pc,
            vision = vision,
            safety = safety,
            capturesDir = tempDir.toFile(),
        )
        return Triple(ctrl, safety, pc)
    }

    @Test
    fun `proposeClickOnText with a match populates pending and does not click yet`(@TempDir tmp: Path) {
        val match = ScreenMatch(found = true, x = 150, y = 250, confidence = 95, matchedText = "Submit")
        val (ctrl, safety, pc) = controller(match = match, tempDir = tmp)
        val r = runBlocking { ctrl.proposeClickOnText("Submit") }
        assertTrue(r is ProposeResult.NeedsConfirmation, "expected NeedsConfirmation, got $r")
        val nc = r as ProposeResult.NeedsConfirmation
        assertEquals(150, nc.x)
        assertEquals(250, nc.y)
        assertTrue(nc.summary.contains("Submit"))
        assertNotNull(nc.previewPath)
        // Safety gate has a pending action
        assertNotNull(safety.state.value.pending)
        // Nothing was clicked yet
        assertTrue(pc.calls.isEmpty(), "no click should happen until confirm")
    }

    @Test
    fun `executeApproved after NeedsConfirmation fires the click and clears pending`(@TempDir tmp: Path) {
        val match = ScreenMatch(found = true, x = 100, y = 200, confidence = 90, matchedText = "OK")
        val (ctrl, safety, pc) = controller(match = match, tempDir = tmp)
        runBlocking { ctrl.proposeClickOnText("OK") }
        val executed = runBlocking { ctrl.executeApproved() }
        assertTrue(executed, "executeApproved should return true after NeedsConfirmation")
        // SafetyGate is cleared
        assertNull(safety.state.value.pending)
        // FakePcController recorded the click
        assertEquals(1, pc.calls.size)
        val c = pc.calls[0]
        assertEquals("click", c.op)
        assertEquals(100, c.args["x"])
        assertEquals(200, c.args["y"])
    }

    @Test
    fun `proposeClickOnText with no match returns NotFound`(@TempDir tmp: Path) {
        val (ctrl, safety, pc) = controller(match = ScreenMatch(found = false), tempDir = tmp)
        val r = runBlocking { ctrl.proposeClickOnText("missing") }
        assertTrue(r is ProposeResult.NotFound, "expected NotFound, got $r")
        assertEquals("missing", (r as ProposeResult.NotFound).target)
        assertNull(safety.state.value.pending)
        assertTrue(pc.calls.isEmpty())
    }

    @Test
    fun `proposeClickOnText with safety disabled executes immediately`(@TempDir tmp: Path) {
        val match = ScreenMatch(found = true, x = 50, y = 75, confidence = 80, matchedText = "x")
        val screen = FakeScreenCapture()
        val ocr = FakeOcrEngine()
        val pc = FakePcController()
        val safety = SafetyGate()
        safety.configure(enabled = false, level = SafetyLevel.AlwaysConfirm)
        val ctrl = DesktopController(
            screen = screen, ocr = ocr, pc = pc,
            vision = CannedVision(match), safety = safety,
            capturesDir = tmp.toFile(),
        )
        val r = runBlocking { ctrl.proposeClickOnText("x") }
        assertTrue(r is ProposeResult.Executed)
        assertEquals(1, pc.calls.size)
    }

    @Test
    fun `cancelPending clears the safety gate`(@TempDir tmp: Path) {
        val match = ScreenMatch(found = true, x = 10, y = 20, confidence = 90, matchedText = "x")
        val (ctrl, safety, pc) = controller(match = match, tempDir = tmp)
        runBlocking { ctrl.proposeClickOnText("x") }
        assertNotNull(safety.state.value.pending)
        ctrl.cancelPending()
        assertNull(safety.state.value.pending)
        // No click fired because we cancelled
        assertTrue(pc.calls.isEmpty())
    }

    @Test
    fun `takeScreenshot writes a PNG into capturesDir`(@TempDir tmp: Path) {
        val (ctrl, _, _) = controller(match = ScreenMatch(found = false), tempDir = tmp)
        val file = runBlocking { ctrl.takeScreenshot() }
        assertTrue(file.exists(), "screenshot file should exist")
        assertTrue(file.length() > 0, "screenshot file should not be empty")
        assertTrue(file.name.endsWith(".png"))
    }

    @Test
    fun `readScreenText returns OCR text`(@TempDir tmp: Path) {
        val (ctrl, _, _) = controller(match = ScreenMatch(found = false), tempDir = tmp)
        val text = runBlocking { ctrl.readScreenText() }
        assertEquals("ok", text)
    }

    @Test
    fun `executeApproved without pending returns false`(@TempDir tmp: Path) {
        val (ctrl, _, _) = controller(match = ScreenMatch(found = false), tempDir = tmp)
        val executed = runBlocking { ctrl.executeApproved() }
        assertEquals(false, executed)
    }

    @Test
    fun `proposeTypeText populates pending with the typed text summary`(@TempDir tmp: Path) {
        val (ctrl, safety, pc) = controller(match = ScreenMatch(found = false), tempDir = tmp)
        val r = runBlocking { ctrl.proposeTypeText("hello world") }
        assertTrue(r is ProposeResult.NeedsConfirmation, "expected NeedsConfirmation, got $r")
        val nc = r as ProposeResult.NeedsConfirmation
        assertTrue(nc.summary.contains("hello world"))
        assertTrue(nc.summary.contains("11 character"))
        // SafetyGate has a pending action with the text
        val pending = safety.state.value.pending
        assertNotNull(pending)
        assertTrue(pending!!.action is DesktopAction.Type)
        assertEquals("hello world", (pending.action as DesktopAction.Type).text)
        // Nothing was typed yet
        assertTrue(pc.calls.isEmpty(), "no input should fire before confirm")
    }

    @Test
    fun `proposeTypeText with empty input returns Unavailable`(@TempDir tmp: Path) {
        val (ctrl, safety, _) = controller(match = ScreenMatch(found = false), tempDir = tmp)
        val r = runBlocking { ctrl.proposeTypeText("") }
        assertTrue(r is ProposeResult.Unavailable)
        assertNull(safety.state.value.pending)
    }

    @Test
    fun `proposePressHotkey populates pending with the parsed chord`(@TempDir tmp: Path) {
        val (ctrl, safety, pc) = controller(match = ScreenMatch(found = false), tempDir = tmp)
        val r = runBlocking { ctrl.proposePressHotkey("Ctrl+Shift+S") }
        assertTrue(r is ProposeResult.NeedsConfirmation, "expected NeedsConfirmation, got $r")
        val nc = r as ProposeResult.NeedsConfirmation
        assertTrue(nc.summary.contains("Ctrl+Shift+S"))
        val pending = safety.state.value.pending
        assertNotNull(pending)
        assertTrue(pending!!.action is DesktopAction.Hotkey)
        assertEquals(
            listOf(java.awt.event.KeyEvent.VK_CONTROL, java.awt.event.KeyEvent.VK_SHIFT, java.awt.event.KeyEvent.VK_S),
            (pending.action as DesktopAction.Hotkey).keyCodes,
        )
        // Nothing was pressed yet
        assertTrue(pc.calls.isEmpty())
    }

    @Test
    fun `proposePressHotkey with invalid combo returns Unavailable`(@TempDir tmp: Path) {
        val (ctrl, safety, _) = controller(match = ScreenMatch(found = false), tempDir = tmp)
        val r = runBlocking { ctrl.proposePressHotkey("Ctrl+Bogus") }
        assertTrue(r is ProposeResult.Unavailable, "expected Unavailable, got $r")
        assertTrue((r as ProposeResult.Unavailable).reason.contains("Ctrl+Bogus"))
        assertNull(safety.state.value.pending)
    }

    @Test
    fun `executeApproved for Type fires typeText calls on the FakePcController`(@TempDir tmp: Path) {
        val (ctrl, safety, pc) = controller(match = ScreenMatch(found = false), tempDir = tmp)
        runBlocking { ctrl.proposeTypeText("hi") }
        val executed = runBlocking { ctrl.executeApproved() }
        assertTrue(executed)
        // typeText records press+release per char → 2 calls for "h" + 2 for "i" = 4.
        assertEquals(4, pc.calls.size)
        // Both characters' press events are present.
        val pressedChars = pc.calls.filter { it.op == "press" }.mapNotNull { it.args["char"] as? String }
        assertEquals(listOf("h", "i"), pressedChars)
        assertNull(safety.state.value.pending)
    }
}
