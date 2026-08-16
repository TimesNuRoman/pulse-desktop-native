// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — ScreenCapture unit tests. Exercise the Fake impl;
// RobotScreenCapture is integration-tested on a real desktop.
package com.pulseteam.desktop.data.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Rectangle

class ScreenCaptureTest {

    @Test
    fun `FakeScreenCapture screenSize returns configured dimensions`() {
        val cap = FakeScreenCapture(w = 1366, h = 768)
        val s = cap.screenSize()
        assertEquals(0, s.x)
        assertEquals(0, s.y)
        assertEquals(1366, s.width)
        assertEquals(768, s.height)
    }

    @Test
    fun `FakeScreenCapture captureFull returns a BufferedImage of the configured size`() {
        val cap = FakeScreenCapture(w = 1920, h = 1080)
        val img = cap.captureFull()
        assertEquals(1920, img.width)
        assertEquals(1080, img.height)
        assertNotNull(img.raster)
    }

    @Test
    fun `FakeScreenCapture captureRegion returns a BufferedImage of the requested size`() {
        val cap = FakeScreenCapture(w = 1920, h = 1080)
        val img = cap.captureRegion(Rectangle(100, 100, 400, 200))
        assertEquals(400, img.width)
        assertEquals(200, img.height)
    }

    @Test
    fun `FakeScreenCapture isAvailable is true`() {
        assertTrue(FakeScreenCapture().isAvailable())
    }
}
