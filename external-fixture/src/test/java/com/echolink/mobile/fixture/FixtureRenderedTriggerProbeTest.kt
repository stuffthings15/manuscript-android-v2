package com.echolink.mobile.fixture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.roundToInt

class FixtureRenderedTriggerProbeTest {
    @Test fun officialOpaqueTriggerIsPresent() {
        assertPresent(renderTrigger(alpha = 1.0, scale = 1.0, includeIcon = true))
    }

    @Test fun officialMinimumPulseAlphaAndMaximumScaleIsPresent() {
        assertPresent(renderTrigger(alpha = 0.62, scale = 1.08, includeIcon = true))
    }

    @Test fun blankRegionIsAbsent() {
        assertFalse(probe(IntArray(SIZE * SIZE) { BACKGROUND }))
    }

    @Test fun darkFrameAndGoldRingWithoutOfficialIconIsRejected() {
        assertFalse(probe(renderTrigger(alpha = 1.0, scale = 1.0, includeIcon = false)))
    }

    @Test fun adversarialPaletteBackgroundIsRejected() {
        val pixels = IntArray(SIZE * SIZE) { index ->
            val x = index % SIZE
            val y = index / SIZE
            if (x < 8 || y < 8 || x >= SIZE - 8 || y >= SIZE - 8) BACKGROUND
            else when ((x / 4 + y / 4) % 3) {
                0 -> DARK
                1 -> GOLD
                else -> PARCHMENT
            }
        }
        assertFalse(probe(pixels))
    }

    private fun probe(pixels: IntArray): Boolean =
        FixtureRenderedTriggerProbe.evaluate(pixels, SIZE, SIZE).present

    private fun assertPresent(pixels: IntArray) {
        val result = FixtureRenderedTriggerProbe.evaluate(pixels, SIZE, SIZE)
        assertTrue(result.reasons.joinToString(), result.present)
    }

    private fun renderTrigger(alpha: Double, scale: Double, includeIcon: Boolean): IntArray {
        val center = (SIZE - 1) / 2.0
        return IntArray(SIZE * SIZE) { index ->
            val x = index % SIZE
            val y = index / SIZE
            val nx = (x - center) / (SIZE / 2.0)
            val ny = (y - center) / (SIZE / 2.0)
            val sourceRadius = hypot(nx, ny) / scale
            val foreground = when {
                sourceRadius > 0.98 -> null
                sourceRadius >= 0.90 -> GOLD
                includeIcon && sourceRadius <= 0.48 && inkStroke(nx / scale, ny / scale) -> INK
                includeIcon && sourceRadius <= 0.48 -> PARCHMENT
                else -> DARK
            }
            foreground?.let { blend(it, BACKGROUND, alpha) } ?: BACKGROUND
        }
    }

    private fun inkStroke(nx: Double, ny: Double): Boolean =
        nx in -0.28..0.22 && (
            ny in -0.22..-0.16 ||
                ny in -0.04..0.02 ||
                ny in 0.14..0.20
            )

    private fun blend(foreground: Int, background: Int, alpha: Double): Int {
        fun channel(color: Int, shift: Int): Int = color ushr shift and 0xff
        fun mixed(shift: Int): Int =
            (channel(foreground, shift) * alpha + channel(background, shift) * (1.0 - alpha)).roundToInt()
        return (0xff shl 24) or (mixed(16) shl 16) or (mixed(8) shl 8) or mixed(0)
    }

    companion object {
        const val SIZE = 96
        const val BACKGROUND = 0xfff7f7f7.toInt()
        const val DARK = 0xff17120d.toInt()
        const val GOLD = 0xffd6a85f.toInt()
        const val PARCHMENT = 0xfff2d6a2.toInt()
        const val INK = 0xff3b291c.toInt()
    }
}
