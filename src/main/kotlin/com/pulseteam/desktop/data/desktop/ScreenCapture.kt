// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — Screen capture. Real impl wraps java.awt.Robot
// (zero-dep JDK 9+ API, supports HiDPI via createMultiResolutionScreenCapture).
// Fake impl returns a solid-color BufferedImage for unit tests.
package com.pulseteam.desktop.data.desktop

import com.pulseteam.desktop.data.log.PulseLogger
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage

/** Screen capture abstraction. Implementations must be safe to call from any thread. */
interface ScreenCapture {
    /** Capture the full primary screen. Returns a logical-px BufferedImage. */
    fun captureFull(): BufferedImage
    /** Capture a sub-region (logical pixels). */
    fun captureRegion(rect: Rectangle): BufferedImage
    /** Logical screen size (for region validation + UI). */
    fun screenSize(): Rectangle
    /** True if capture actually works on this OS. False on Wayland / no permission. */
    fun isAvailable(): Boolean
}

/**
 * Real implementation backed by `java.awt.Robot`. Uses
 * `createMultiResolutionScreenCapture` to pick the native-resolution
 * variant on HiDPI displays. Falls back to `createScreenCapture` for
 * region captures (which is what JDK supports there).
 */
class RobotScreenCapture : ScreenCapture {
    private val robot: Robot? = try {
        Robot().also { r -> r.autoDelay = 20 }
    } catch (t: Throwable) {
        PulseLogger.warn("ScreenCapture: Robot unavailable", mapOf("error" to (t.message ?: t::class.java.simpleName)))
        null
    }

    override fun captureFull(): BufferedImage {
        val r = requireRobot()
        val screen = screenSize()
        // createMultiResolutionScreenCapture is JDK 9+; on single-res
        // monitors it returns a 1-element list with the only variant.
        val multi = r.createMultiResolutionScreenCapture(screen)
        // Pick the variant whose size matches the logical screen size
        // (which is what callers will be using as coordinates).
        val variants = multi.resolutionVariants
        val exact = variants.firstOrNull {
            it is java.awt.image.BufferedImage
                && it.width == screen.width
                && it.height == screen.height
        } as? java.awt.image.BufferedImage
        if (exact != null) return exact
        val largest = (variants.filterIsInstance<java.awt.image.BufferedImage>()
            .maxByOrNull { it.width * it.height })
        if (largest != null) return largest
        return r.createScreenCapture(screen)
    }

    override fun captureRegion(rect: Rectangle): BufferedImage {
        val r = requireRobot()
        return r.createScreenCapture(rect)
    }

    override fun screenSize(): Rectangle {
        val sz = Toolkit.getDefaultToolkit().screenSize
        return Rectangle(0, 0, sz.width, sz.height)
    }

    override fun isAvailable(): Boolean = robot != null

    private fun requireRobot(): Robot = robot
        ?: throw IllegalStateException("screen capture unavailable on this platform (no Robot, headless or no permission)")
}

/**
 * Test double. Returns a solid-color image of the requested size. The
 * [color] is the ARGB int fed to `BufferedImage` constructor; default
 * is a dim slate so test code can spot any "I forgot to swap the impl"
 * accidents visually.
 */
class FakeScreenCapture(
    private val w: Int = 1920,
    private val h: Int = 1080,
    private val color: Int = 0xFF202030.toInt(),
) : ScreenCapture {
    override fun captureFull(): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply {
        // Fill the entire image with the configured color.
        val g = createGraphics()
        try {
            g.color = java.awt.Color(color, true)
            g.fillRect(0, 0, w, h)
        } finally {
            g.dispose()
        }
    }

    override fun captureRegion(rect: Rectangle): BufferedImage = BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_ARGB).apply {
        val g = createGraphics()
        try {
            g.color = java.awt.Color(color, true)
            g.fillRect(0, 0, rect.width, rect.height)
        } finally {
            g.dispose()
        }
    }

    override fun screenSize(): Rectangle = Rectangle(0, 0, w, h)
    override fun isAvailable(): Boolean = true
}
