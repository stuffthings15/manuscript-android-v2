package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialCorrectionHeaderSourceTest {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val sourceRoot = File(androidRoot, "app/src/main/java/com/echolink/mobile")

    @Test
    fun allThreeCorrectionSurfacesUseTheOneOfficialHeader() {
        val header = source("OfficialCorrectionHeader.kt")
        val palette = source("ManuscriptPalette.kt")

        assertTrue(header.contains("id = R.id.official_correction_header"))
        assertTrue(header.contains("R.drawable.ic_manuscript_assist"))
        assertTrue(header.contains("ManuscriptPalette."))
        assertTrue(header.contains("contentDescription = null"))
        assertTrue(header.contains("minimumWidth = dp(MIN_TOUCH_TARGET_DP)"))
        assertTrue(header.contains("minimumHeight = dp(MIN_TOUCH_TARGET_DP)"))
        assertTrue(header.contains("onClose: (() -> Unit)?"))
        assertTrue(header.contains("onSettings: (() -> Unit)?"))
        assertTrue(header.contains("const val CLOSE_GLYPH = \"\\u00d7\""))
        assertTrue(palette.contains("object ManuscriptPalette"))

        val consumers = listOf(
            source("MainActivity.kt"),
            source("ProcessTextActivity.kt"),
            source("ManuscriptAccessibilityService.kt"),
        )
        consumers.forEach { consumer ->
            assertEquals(1, Regex("OfficialCorrectionHeader\\(").findAll(consumer).count())
            assertFalse(consumer.contains("text = \"Ã—\""))
        }
        assertTrue(consumers.last().contains("text = \"\\u2501\\u2501\\u2501\""))
    }

    @Test
    fun processTextCannotKeepItsFormerBlueGreenPalette() {
        val processText = source("ProcessTextActivity.kt")
        listOf(
            "Color.rgb(10, 13, 18)",
            "Color.rgb(26, 33, 43)",
            "Color.rgb(32, 227, 138)",
            "Color.rgb(14, 94, 62)",
        ).forEach { forbidden -> assertFalse(forbidden, processText.contains(forbidden)) }
        assertTrue(processText.contains("ManuscriptPalette.BACKGROUND"))
        assertTrue(processText.contains("ManuscriptPalette.ACCENT"))
    }

    private fun source(name: String): String = File(sourceRoot, name).readText()
}
