package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The design tokens, pinned to the GUI build manual and to the artwork.
 *
 * Two different jobs here. The metric values are pinned because a screen that
 * drifts a few density pixels from the rest is the thing tokens exist to stop.
 * The palette's original thirteen constants are pinned because they are copied
 * from the canonical Manuscript artwork, and changing one is a brand change
 * rather than a code change.
 */
class ManuscriptDesignTokensTest {
    @Test
    fun spacingRadiiAndTypeMatchTheManual() {
        assertEquals(4, ManuscriptMetrics.SPACE_MICRO_DP)
        assertEquals(8, ManuscriptMetrics.SPACE_RELATED_DP)
        assertEquals(12, ManuscriptMetrics.SPACE_CARD_DP)
        assertEquals(16, ManuscriptMetrics.SPACE_SCREEN_DP)
        assertEquals(24, ManuscriptMetrics.SPACE_SECTION_DP)
        assertEquals(32, ManuscriptMetrics.SPACE_MAJOR_DP)

        assertEquals(8, ManuscriptMetrics.RADIUS_CHIP_DP)
        assertEquals(12, ManuscriptMetrics.RADIUS_ROW_DP)
        assertEquals(16, ManuscriptMetrics.RADIUS_CARD_DP)
        assertEquals(24, ManuscriptMetrics.RADIUS_PANEL_DP)

        assertEquals(28f, ManuscriptMetrics.TEXT_DISPLAY_SP, 0f)
        assertEquals(24f, ManuscriptMetrics.TEXT_PAGE_TITLE_SP, 0f)
        assertEquals(20f, ManuscriptMetrics.TEXT_SECTION_TITLE_SP, 0f)
        assertEquals(16f, ManuscriptMetrics.TEXT_BODY_SP, 0f)
        assertEquals(14f, ManuscriptMetrics.TEXT_SUPPORT_SP, 0f)
        assertEquals(12f, ManuscriptMetrics.TEXT_METADATA_SP, 0f)
        assertEquals(18f, ManuscriptMetrics.TEXT_CORRECTION_SP, 0f)

        // Manual section 15. A collapsed card outside this band is either too
        // small to read or big enough to bury the host text.
        assertEquals(56, ManuscriptMetrics.CARD_MIN_HEIGHT_DP)
        assertEquals(88, ManuscriptMetrics.CARD_MAX_COLLAPSED_HEIGHT_DP)
    }

    @Test
    fun theTouchTargetHasOneOwner() {
        assertEquals(48, ManuscriptMetrics.TOUCH_TARGET_DP)
        // Everything else reads the general rule rather than restating it. Two
        // copies of a number are two numbers waiting to disagree, and this one
        // had four copies before the tokens landed.
        assertEquals(
            ManuscriptMetrics.TOUCH_TARGET_DP,
            ManuscriptTriggerPresentation.TOUCH_TARGET_DP,
        )
        val sources = File(
            System.getProperty("echoLinkAndroidDir") ?: ".",
            "app/src/main/java/com/echolink/mobile",
        )
        val literalCopies = sources.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".kt") && it.name != "ManuscriptMetrics.kt" }
            .filter { it.readText().contains("MIN_TOUCH_TARGET_DP = 48") }
            .map { it.name }
        assertEquals(
            "the 48 dp target belongs to ManuscriptMetrics: $literalCopies",
            emptyList<String>(),
            literalCopies,
        )
    }

    @Test
    fun theArtworkPaletteIsUnchanged() {
        assertEquals(0xff17120d.toInt(), ManuscriptPalette.BACKGROUND)
        assertEquals(0xff211a12.toInt(), ManuscriptPalette.SURFACE)
        assertEquals(0xff2e2418.toInt(), ManuscriptPalette.SURFACE_RAISED)
        assertEquals(0xff43321c.toInt(), ManuscriptPalette.SURFACE_FOCUSED)
        assertEquals(0xffd6a85f.toInt(), ManuscriptPalette.BORDER)
        assertEquals(0xffe2b86f.toInt(), ManuscriptPalette.ACCENT)
        assertEquals(0xff8b5b2b.toInt(), ManuscriptPalette.ACCENT_DARK)
        assertEquals(0xfff7e7c6.toInt(), ManuscriptPalette.TEXT)
        assertEquals(0xfff2d6a2.toInt(), ManuscriptPalette.MUTED_TEXT)
        assertEquals(0xffff8378.toInt(), ManuscriptPalette.ERROR)
        assertEquals(0xffff978b.toInt(), ManuscriptPalette.REMOVED)
        assertEquals(0xff0d1c16.toInt(), ManuscriptPalette.INSERTED)
        assertEquals(0xff77e2ac.toInt(), ManuscriptPalette.INSERTED_BACKGROUND)
    }

    @Test
    fun theAddedSemanticTokensAreOpaqueExceptTheScrim() {
        // Success reuses the inserted-text green rather than inventing a second
        // green, so a correction that landed and text that was inserted read as
        // the same idea.
        assertEquals(ManuscriptPalette.INSERTED_BACKGROUND, ManuscriptPalette.SUCCESS)

        listOf(
            ManuscriptPalette.WARNING,
            ManuscriptPalette.SUCCESS,
            ManuscriptPalette.INFORMATION,
            ManuscriptPalette.DISABLED,
        ).forEach { color ->
            assertEquals(0xff, (color ushr 24) and 0xff)
        }
        // The scrim is the one that must not be opaque; it dims what is behind.
        assertTrue((ManuscriptPalette.SCRIM ushr 24) and 0xff < 0xff)
    }

    @Test
    fun densityConversionHasOneDefinition() {
        val sources = File(System.getProperty("echoLinkAndroidDir") ?: ".", "app/src/main/java/com/echolink/mobile")
        val inlineCopies = sources.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".kt") && it.name != "ManuscriptMetrics.kt" }
            .filter { it.readText().contains("displayMetrics.density") }
            .map { it.name }

        assertEquals(
            "density conversion belongs to ManuscriptMetrics: $inlineCopies",
            emptyList<String>(),
            inlineCopies,
        )
    }
}
