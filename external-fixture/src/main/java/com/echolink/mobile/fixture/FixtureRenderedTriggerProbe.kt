package com.echolink.mobile.fixture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

object FixtureRenderedTriggerProbe {
    data class Result(val present: Boolean, val reasons: List<String>)

    fun evaluate(pixels: IntArray, width: Int, height: Int): Result {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val background = cornerMedian(pixels, width, height)
        val goldBySector = BooleanArray(SECTOR_COUNT)
        val darkBySector = BooleanArray(SECTOR_COUNT)
        var outerCount = 0
        var outerGold = 0
        var darkBandCount = 0
        var darkBandHits = 0
        var centerCount = 0
        var centerParchment = 0
        var centerDark = 0
        val centerX = (width - 1) / 2.0
        val centerY = (height - 1) / 2.0
        val halfWidth = width / 2.0
        val halfHeight = height / 2.0

        pixels.forEachIndexed { index, color ->
            val x = index % width
            val y = index / width
            val nx = (x - centerX) / halfWidth
            val ny = (y - centerY) / halfHeight
            val radius = hypot(nx, ny)
            val sector = sector(nx, ny)
            val gold = matchesAnyComposite(color, GOLD_COLORS, background)
            val dark = matchesAnyComposite(color, DARK_COLORS, background)
            val parchment = matchesAnyComposite(color, PARCHMENT_COLORS, background)
            if (radius in OUTER_RING_MIN..OUTER_RING_MAX) {
                outerCount++
                if (gold) { outerGold++; goldBySector[sector] = true }
            }
            if (radius in DARK_BAND_MIN..DARK_BAND_MAX) {
                darkBandCount++
                if (dark) { darkBandHits++; darkBySector[sector] = true }
            }
            if (radius <= CENTER_MAX) {
                centerCount++
                if (parchment) centerParchment++
                if (dark) centerDark++
            }
        }

        val reasons = mutableListOf<String>()
        if (goldBySector.count { it } < MIN_SECTORS) reasons += "gold ring geometry missing"
        if (darkBySector.count { it } < MIN_SECTORS) reasons += "dark frame geometry missing"
        if (ratio(outerGold, outerCount) !in MIN_OUTER_GOLD..MAX_OUTER_GOLD) reasons += "gold ring ratio invalid"
        if (ratio(darkBandHits, darkBandCount) < MIN_DARK_BAND) reasons += "dark frame ratio invalid"
        if (ratio(centerParchment, centerCount) < MIN_CENTER_PARCHMENT) reasons += "center parchment missing"
        if (ratio(centerDark, centerCount) < MIN_CENTER_DARK) reasons += "center ink missing"
        return Result(reasons.isEmpty(), reasons)
    }

    private fun matchesAnyComposite(color: Int, targets: IntArray, background: Int): Boolean =
        targets.any { target -> matchesComposite(color, target, background) }

    private fun matchesComposite(color: Int, target: Int, background: Int): Boolean {
        for (step in MIN_ALPHA_STEP..MAX_ALPHA_STEP) {
            val alpha = step / 20.0
            val expectedRed = blend(channel(target, 16), channel(background, 16), alpha)
            val expectedGreen = blend(channel(target, 8), channel(background, 8), alpha)
            val expectedBlue = blend(channel(target, 0), channel(background, 0), alpha)
            if (
                abs(channel(color, 16) - expectedRed) <= COLOR_TOLERANCE &&
                abs(channel(color, 8) - expectedGreen) <= COLOR_TOLERANCE &&
                abs(channel(color, 0) - expectedBlue) <= COLOR_TOLERANCE
            ) return true
        }
        return false
    }

    private fun cornerMedian(pixels: IntArray, width: Int, height: Int): Int {
        val span = maxOf(1, minOf(width, height) / 12)
        val samples = ArrayList<Int>(span * span * 4)
        for (y in 0 until span) for (x in 0 until span) {
            samples += pixels[y * width + x]
            samples += pixels[y * width + (width - 1 - x)]
            samples += pixels[(height - 1 - y) * width + x]
            samples += pixels[(height - 1 - y) * width + (width - 1 - x)]
        }
        fun median(shift: Int): Int = samples.map { channel(it, shift) }.sorted()[samples.size / 2]
        return (0xff shl 24) or (median(16) shl 16) or (median(8) shl 8) or median(0)
    }

    private fun sector(nx: Double, ny: Double): Int {
        val angle = (atan2(ny, nx) + 2.0 * PI) % (2.0 * PI)
        return (angle / (2.0 * PI) * SECTOR_COUNT).toInt().coerceIn(0, SECTOR_COUNT - 1)
    }

    private fun channel(color: Int, shift: Int): Int = color ushr shift and 0xff
    private fun blend(foreground: Int, background: Int, alpha: Double): Int =
        (foreground * alpha + background * (1.0 - alpha)).roundToInt()
    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator

    private val GOLD_COLORS = intArrayOf(0xffd6a85f.toInt(), 0xffe2b86f.toInt())
    private val DARK_COLORS = intArrayOf(0xff17120d.toInt(), 0xff3b291c.toInt())
    private val PARCHMENT_COLORS = intArrayOf(0xfff2d6a2.toInt(), 0xfff7e7c6.toInt())
    private const val SECTOR_COUNT = 8
    private const val MIN_SECTORS = 7
    private const val MIN_ALPHA_STEP = 11
    private const val MAX_ALPHA_STEP = 20
    private const val COLOR_TOLERANCE = 20
    private const val OUTER_RING_MIN = 0.86
    private const val OUTER_RING_MAX = 1.05
    private const val DARK_BAND_MIN = 0.58
    private const val DARK_BAND_MAX = 0.88
    private const val CENTER_MAX = 0.52
    private const val MIN_OUTER_GOLD = 0.025
    private const val MAX_OUTER_GOLD = 0.45
    private const val MIN_DARK_BAND = 0.42
    private const val MIN_CENTER_PARCHMENT = 0.18
    private const val MIN_CENTER_DARK = 0.06
}
