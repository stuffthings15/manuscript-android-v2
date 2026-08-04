package com.echolink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderedBrandContractTest {
    @Test
    fun acceptedFrameSurvivesEverySupportedMask() {
        val pixels = framedArtwork(64, 64)
        RenderedBrandContract.Mask.entries.forEach { mask ->
            assertTrue(mask.name, RenderedBrandContract.evaluate(pixels, 64, 64, mask).passes)
        }
    }

    @Test
    fun shiftedEnlargedRecoloredAndReplacedArtworkCannotPass() {
        val accepted = framedArtwork(64, 64)
        val shifted = RenderedBrandContract.shift(accepted, 64, 64, 10, 0)
        val enlarged = RenderedBrandContract.scaleNearest(accepted, 64, 64, 1.55f)
        val recolored = accepted.map {
            if (it == ManuscriptPalette.BORDER) 0xff2299ff.toInt() else it
        }.toIntArray()
        val replaced = IntArray(64 * 64) { ManuscriptPalette.BACKGROUND }

        listOf(shifted, enlarged, recolored, replaced).forEach { mutation ->
            assertFalse(
                RenderedBrandContract.evaluate(
                    mutation,
                    64,
                    64,
                    RenderedBrandContract.Mask.SAMSUNG,
                ).passes,
            )
        }
    }

    private fun framedArtwork(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val distance = kotlin.math.sqrt(
            (x - (width - 1) / 2.0) * (x - (width - 1) / 2.0) +
                (y - (height - 1) / 2.0) * (y - (height - 1) / 2.0),
        )
        when {
            distance in 14.0..17.0 -> ManuscriptPalette.BORDER
            distance <= 19.0 -> ManuscriptPalette.BACKGROUND
            else -> 0
        }
    }
}
