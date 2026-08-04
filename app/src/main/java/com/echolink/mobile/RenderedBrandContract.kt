package com.echolink.mobile

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pixel-level launcher contract shared by host and real-drawable tests. */
object RenderedBrandContract {
    enum class Mask { CIRCLE, ROUNDED_SQUARE, SQUIRCLE, SAMSUNG }

    data class Result(val passes: Boolean, val reasons: List<String>)

    fun evaluate(pixels: IntArray, width: Int, height: Int, mask: Mask): Result {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val reasons = mutableListOf<String>()
        val gold = mutableListOf<Pair<Int, Int>>()
        val dark = mutableListOf<Pair<Int, Int>>()
        val artwork = mutableListOf<Pair<Int, Int>>()
        val centerX = (width - 1) / 2.0
        val centerY = (height - 1) / 2.0

        pixels.forEachIndexed { index, color ->
            if (alpha(color) < OPAQUE_ALPHA) return@forEachIndexed
            val x = index % width
            val y = index / width
            artwork += x to y
            if (isGold(color)) gold += x to y
            if (isDarkFrame(color)) dark += x to y
        }
        if (gold.isEmpty()) reasons += "gold border missing"

        if (gold.isNotEmpty()) {
            val minX = gold.minOf { it.first }
            val maxX = gold.maxOf { it.first }
            val minY = gold.minOf { it.second }
            val maxY = gold.maxOf { it.second }
            val middleBandX = width * 0.20..width * 0.80
            val middleBandY = height * 0.20..height * 0.80
            val hasTop = gold.any { it.second <= minY + 2 && it.first.toDouble() in middleBandX }
            val hasBottom = gold.any { it.second >= maxY - 2 && it.first.toDouble() in middleBandX }
            val hasLeft = gold.any { it.first <= minX + 2 && it.second.toDouble() in middleBandY }
            val hasRight = gold.any { it.first >= maxX - 2 && it.second.toDouble() in middleBandY }
            if (!(hasTop && hasBottom && hasLeft && hasRight)) reasons += "gold border missing on a side"
            val darkTop = dark.any { it.second < minY && it.first.toDouble() in middleBandX }
            val darkBottom = dark.any { it.second > maxY && it.first.toDouble() in middleBandX }
            val darkLeft = dark.any { it.first < minX && it.second.toDouble() in middleBandY }
            val darkRight = dark.any { it.first > maxX && it.second.toDouble() in middleBandY }
            if (!(darkTop && darkBottom && darkLeft && darkRight)) {
                reasons += "dark frame missing outside gold on a side"
            }
            if (gold.any { (x, y) -> !insideMask(x, y, width, height, mask) || touchesMaskBoundary(x, y, width, height, mask) }) {
                reasons += "gold touches crop boundary"
            }
        }

        val safeRadius = minOf(width, height) * SAFE_CIRCLE_RADIUS_RATIO
        if (artwork.any { (x, y) ->
                sqrt((x - centerX).pow(2) + (y - centerY).pow(2)) > safeRadius
            }
        ) reasons += "artwork exceeds adaptive safe circle"

        return Result(reasons.isEmpty(), reasons)
    }

    fun isGold(color: Int): Boolean = colorDistance(color, ManuscriptPalette.BORDER) <= COLOR_TOLERANCE ||
        colorDistance(color, ManuscriptPalette.ACCENT) <= COLOR_TOLERANCE

    fun shift(
        pixels: IntArray,
        width: Int,
        height: Int,
        deltaX: Int,
        deltaY: Int,
    ): IntArray = IntArray(pixels.size).also { result ->
        for (y in 0 until height) for (x in 0 until width) {
            val sourceX = x - deltaX
            val sourceY = y - deltaY
            if (sourceX in 0 until width && sourceY in 0 until height) {
                result[y * width + x] = pixels[sourceY * width + sourceX]
            }
        }
    }

    fun scaleNearest(pixels: IntArray, width: Int, height: Int, scale: Float): IntArray {
        require(scale > 0f)
        val centerX = (width - 1) / 2f
        val centerY = (height - 1) / 2f
        return IntArray(pixels.size) { index ->
            val x = index % width
            val y = index / width
            val sourceX = ((x - centerX) / scale + centerX).roundToInt()
            val sourceY = ((y - centerY) / scale + centerY).roundToInt()
            if (sourceX in 0 until width && sourceY in 0 until height) {
                pixels[sourceY * width + sourceX]
            } else 0
        }
    }

    private fun touchesMaskBoundary(x: Int, y: Int, width: Int, height: Int, mask: Mask): Boolean =
        NEIGHBORS.any { (dx, dy) -> !insideMask(x + dx, y + dy, width, height, mask) }

    private fun insideMask(x: Int, y: Int, width: Int, height: Int, mask: Mask): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        val nx = abs((2.0 * x / (width - 1)) - 1.0)
        val ny = abs((2.0 * y / (height - 1)) - 1.0)
        return when (mask) {
            Mask.CIRCLE -> nx * nx + ny * ny <= 1.0
            Mask.ROUNDED_SQUARE -> roundedSquareContains(nx, ny, 0.28)
            Mask.SQUIRCLE -> nx.pow(4) + ny.pow(4) <= 1.0
            Mask.SAMSUNG -> nx.pow(5) + ny.pow(5) <= 0.93.pow(5)
        }
    }

    private fun roundedSquareContains(nx: Double, ny: Double, radius: Double): Boolean {
        val straight = 1.0 - radius
        if (nx <= straight || ny <= straight) return nx <= 1.0 && ny <= 1.0
        val dx = nx - straight
        val dy = ny - straight
        return dx * dx + dy * dy <= radius * radius
    }

    private fun alpha(color: Int): Int = color ushr 24 and 0xff
    private fun isDarkFrame(color: Int): Boolean =
        colorDistance(color, ManuscriptPalette.BACKGROUND) <= COLOR_TOLERANCE

    private fun colorDistance(left: Int, right: Int): Int = maxOf(
        abs((left ushr 16 and 0xff) - (right ushr 16 and 0xff)),
        abs((left ushr 8 and 0xff) - (right ushr 8 and 0xff)),
        abs((left and 0xff) - (right and 0xff)),
    )

    private val NEIGHBORS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
    private const val OPAQUE_ALPHA = 224
    private const val COLOR_TOLERANCE = 30
    private const val SAFE_CIRCLE_RADIUS_RATIO = 0.315
}
