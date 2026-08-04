package com.echolink.mobile

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsIconParityTest {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val mainRoot = File(androidRoot, "app/src/main")
    private val canonicalBrandRoot = File(androidRoot, "brand/canonical")

    @Test
    fun generatedIconsAreBoundToTheCanonicalWindowsArtwork() {
        val icon = source("res/drawable/ic_manuscript.xml")
        val assistIcon = source("res/drawable/ic_manuscript_assist.xml")
        val foreground = source("res/drawable/ic_manuscript_launcher_foreground.xml")
        val launcher = source("res/mipmap-anydpi-v26/ic_launcher.xml")
        val roundLauncher = source("res/mipmap-anydpi-v26/ic_launcher_round.xml")
        val generator = File(androidRoot, "tools/generate_android_brand_assets.py")

        assertTrue(icon.contains(CANONICAL_OUTER_PATH))
        assertTrue(icon.contains(CANONICAL_BORDER_PATH))
        assertTrue(icon.contains("android:strokeColor=\"#D6A85F\""))
        assertTrue(icon.contains("android:strokeWidth=\"58\""))
        assertTrue(assistIcon.contains("android:viewportWidth=\"1024\""))
        assertTrue(assistIcon.contains(CANONICAL_OUTER_PATH))
        assertTrue(assistIcon.contains("M590,710L770,453"))
        assertTrue(generator.isFile)
        // 0.49 keeps the complete gold frame inside the guaranteed 66dp
        // launcher safe circle on every OEM mask; the near-black adaptive
        // background fills the remainder.
        assertTrue(
            foreground.contains(
                "android:scaleX=\"0.49\"",
            ),
        )
        assertTrue(foreground.contains("android:scaleY=\"0.49\""))
        assertTrue(foreground.contains("android:translateX=\"261.12\""))
        assertTrue(foreground.contains("android:translateY=\"261.12\""))
        for (adaptiveIcon in listOf(launcher, roundLauncher)) {
            assertTrue(
                adaptiveIcon.contains(
                    "<foreground android:drawable=\"@drawable/ic_manuscript_launcher_foreground\" />",
                ),
            )
            assertFalse(adaptiveIcon.contains("<monochrome"))
        }
        assertEquals(CANONICAL_SVG_SHA256, sha256(File(canonicalBrandRoot, "manuscript.svg")))
        assertEquals(CANONICAL_PNG_SHA256, sha256(File(canonicalBrandRoot, "manuscript.png")))
    }

    @Test
    fun generatedVectorsUseOnlyTheLockedPaletteWithoutTinting() {
        val vectors = listOf(
            source("res/drawable/ic_manuscript.xml"),
            source("res/drawable/ic_manuscript_assist.xml"),
            source("res/drawable/ic_manuscript_launcher_foreground.xml"),
        )
        val colorPattern = Pattern.compile("#[0-9A-Fa-f]{6}")

        vectors.forEach { vector ->
            val matcher = colorPattern.matcher(vector)
            val colors = buildSet {
                while (matcher.find()) add(matcher.group().uppercase(Locale.ROOT))
            }
            assertTrue("Unexpected icon colors: $colors", LOCKED_PALETTE.containsAll(colors))
            assertFalse(vector.contains("android:tint="))
            assertFalse(vector.contains("?attr/"))
        }
    }

    private fun source(relativePath: String): String =
        File(mainRoot, relativePath).readText()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { byte ->
            String.format(Locale.ROOT, "%02X", byte.toInt() and 0xff)
        }
    }

    private companion object {
        const val CANONICAL_SVG_SHA256 =
            "C85E54DD5051D33D488DEDE79A1F556235E08211E6C8F576219DD89F6C0FE84E"
        const val CANONICAL_PNG_SHA256 =
            "7D4E71968686DF861B0BD8D1AC3D1D38AC7CB17BC048D1BE6F7DB1F42C9ED9E7"
        val LOCKED_PALETTE = setOf(
            "#17120D",
            "#D6A85F",
            "#F2D6A2",
            "#F7E7C6",
            "#E2B86F",
            "#8B5B2B",
            "#3B291C",
        )
        const val CANONICAL_OUTER_PATH =
            "M220,0H804A220,220 0,0 1,1024,220V804A220,220 0,0 1,804,1024" +
                "H220A220,220 0,0 1,0,804V220A220,220 0,0 1,220,0Z"
        const val CANONICAL_BORDER_PATH =
            "M242,54H782A188,188 0,0 1,970,242V782A188,188 0,0 1,782,970" +
                "H242A188,188 0,0 1,54,782V242A188,188 0,0 1,242,54Z"
    }
}
