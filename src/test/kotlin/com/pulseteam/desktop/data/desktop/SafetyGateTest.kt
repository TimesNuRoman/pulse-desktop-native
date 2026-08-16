// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — SafetyGate unit tests. Covers configure / request /
// confirm / cancel / disabled semantics.
package com.pulseteam.desktop.data.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SafetyGateTest {

    @Test
    fun `default state is enabled at AlwaysConfirm with no pending`() {
        val g = SafetyGate()
        val s = g.state.value
        assertTrue(s.enabled)
        assertEquals(SafetyLevel.AlwaysConfirm, s.level)
        assertNull(s.pending)
    }

    @Test
    fun `request at AlwaysConfirm returns false and populates pending`() {
        val g = SafetyGate()
        val allowed = g.request(DesktopAction.Click(10, 20), "click at (10,20)")
        assertFalse(allowed)
        val pending = g.state.value.pending
        assertNotNull(pending)
        assertEquals("click at (10,20)", pending!!.summary)
        assertTrue(pending.action is DesktopAction.Click)
    }

    @Test
    fun `request carries screenshot path`() {
        val g = SafetyGate()
        val png = File("preview.png")
        g.request(DesktopAction.Click(0, 0), "x", png)
        assertEquals(png, g.state.value.pending?.screenshotPath)
    }

    @Test
    fun `confirm clears pending`() {
        val g = SafetyGate()
        g.request(DesktopAction.Click(0, 0), "x")
        g.confirm()
        assertNull(g.state.value.pending)
    }

    @Test
    fun `cancel clears pending`() {
        val g = SafetyGate()
        g.request(DesktopAction.Click(0, 0), "x")
        g.cancel()
        assertNull(g.state.value.pending)
    }

    @Test
    fun `request when disabled returns true without populating pending`() {
        val g = SafetyGate()
        g.configure(enabled = false, level = SafetyLevel.AlwaysConfirm)
        val allowed = g.request(DesktopAction.Click(0, 0), "x")
        assertTrue(allowed, "when disabled, request should allow without asking")
        assertNull(g.state.value.pending)
    }

    @Test
    fun `configure updates enabled and level`() {
        val g = SafetyGate()
        g.configure(false, SafetyLevel.AlwaysConfirm)
        assertFalse(g.state.value.enabled)
        // We re-enable so subsequent tests / re-use of the gate behave sanely.
        g.configure(true, SafetyLevel.AlwaysConfirm)
        assertTrue(g.state.value.enabled)
    }

    @Test
    fun `Never level allows all actions without populating pending`() {
        val g = SafetyGate()
        g.configure(true, SafetyLevel.Never)
        val allowed = g.request(DesktopAction.Click(0, 0), "x")
        assertTrue(allowed)
        assertNull(g.state.value.pending)
        assertEquals("x", g.state.value.lastAutoApprovedSummary)
    }

    @Test
    fun `OncePerCommand allows same summary within window`() {
        val g = SafetyGate()
        g.configure(true, SafetyLevel.OncePerCommand)
        // First call: not seen before, so requires confirmation.
        assertFalse(g.request(DesktopAction.Click(0, 0), "Click at (0, 0)"))
        g.confirm()
        // Second call with the SAME summary: auto-approved (within 5 min).
        val second = g.request(DesktopAction.Click(0, 0), "Click at (0, 0)")
        assertTrue(second, "same summary within window should auto-approve")
    }

    @Test
    fun `OncePerCommand asks for new summary`() {
        val g = SafetyGate()
        g.configure(true, SafetyLevel.OncePerCommand)
        g.request(DesktopAction.Click(100, 200), "Click at (100, 200)")
        g.confirm()
        // Different summary → requires a new confirm.
        val second = g.request(DesktopAction.Click(300, 400), "Click at (300, 400)")
        assertFalse(second)
    }
}
