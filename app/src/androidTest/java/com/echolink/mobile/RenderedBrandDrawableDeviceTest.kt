package com.echolink.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenderedBrandDrawableDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    @Test
    fun actualHeaderDrawableMatchesCanonicalSourceWithinNormalizedThreshold() {
        val canonicalBytes = instrumentation.context.assets.open("manuscript.png").use { it.readBytes() }
        assertEquals(CANONICAL_PNG_SHA256, sha256(canonicalBytes))
        val canonical = BitmapFactory.decodeByteArray(canonicalBytes, 0, canonicalBytes.size)
        val rendered = render(R.drawable.ic_manuscript_assist, 512)
        val normalizedCanonical = Bitmap.createScaledBitmap(canonical, 512, 512, true)

        assertTrue(
            "normalized pixel difference exceeded $NORMALIZED_DIFFERENCE_THRESHOLD",
            normalizedPixelDifference(rendered, normalizedCanonical) <= NORMALIZED_DIFFERENCE_THRESHOLD,
        )
    }

    @Test
    fun actualAdaptiveForegroundKeepsTheGoldFrameInsideEveryMask() {
        // This verifies a generated render only. It deliberately neither writes nor
        // approves an S23 golden; human device approval remains a later gate.
        val rendered = render(R.drawable.ic_manuscript_launcher_foreground, 512)
        val pixels = IntArray(512 * 512)
        rendered.getPixels(pixels, 0, 512, 0, 0, 512, 512)

        RenderedBrandContract.Mask.entries.forEach { mask ->
            val result = RenderedBrandContract.evaluate(pixels, 512, 512, mask)
            assertTrue("$mask: ${result.reasons}", result.passes)
        }
    }

    @Test
    fun renderedContractRejectsFourRealDrawableMutations() {
        val rendered = render(R.drawable.ic_manuscript_launcher_foreground, 512)
        val original = IntArray(512 * 512)
        rendered.getPixels(original, 0, 512, 0, 0, 512, 512)
        val recolored = original.map {
            if (RenderedBrandContract.isGold(it)) 0xff2299ff.toInt() else it
        }.toIntArray()
        val replaced = IntArray(original.size) { ManuscriptPalette.BACKGROUND }
        val mutations = listOf(
            RenderedBrandContract.shift(original, 512, 512, 96, 0),
            RenderedBrandContract.scaleNearest(original, 512, 512, 1.75f),
            recolored,
            replaced,
        )
        mutations.forEach { mutation ->
            assertTrue(
                RenderedBrandContract.Mask.entries.none {
                    RenderedBrandContract.evaluate(mutation, 512, 512, it).passes
                },
            )
        }
    }

    private fun render(drawableId: Int, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val drawable = requireNotNull(ContextCompat.getDrawable(target, drawableId)).mutate()
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun normalizedPixelDifference(left: Bitmap, right: Bitmap): Double {
        var channelDifference = 0L
        for (y in 0 until left.height) {
            for (x in 0 until left.width) {
                val a = left.getPixel(x, y)
                val b = right.getPixel(x, y)
                channelDifference += kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b))
                channelDifference += kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b))
                channelDifference += kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
                channelDifference += kotlin.math.abs(android.graphics.Color.alpha(a) - android.graphics.Color.alpha(b))
            }
        }
        return channelDifference.toDouble() / (left.width * left.height * 4L * 255L)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xff) }

    private companion object {
        const val CANONICAL_PNG_SHA256 =
            "7D4E71968686DF861B0BD8D1AC3D1D38AC7CB17BC048D1BE6F7DB1F42C9ED9E7"
        // Android vector anti-aliasing and PNG resampling differ slightly.
        const val NORMALIZED_DIFFERENCE_THRESHOLD = 0.08
    }
}
