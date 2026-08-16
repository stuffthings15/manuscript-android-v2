package com.echolink.mobile

import android.content.Context

/**
 * Spacing, radii, type and touch targets from the Android Writing Assistant GUI
 * build manual, sections 4 and 15.
 *
 * These are named rather than typed at each call site so a screen cannot drift
 * from the rest by a few density pixels, and so a reviewer can see which rule a
 * number came from. [ManuscriptPalette] does the same job for colour.
 *
 * This object also owns the density conversion. Before it there were three
 * copies of the same one-line function, in [MainActivity],
 * [ManuscriptAccessibilityService] and [ManuscriptTriggerViewFactory], which is
 * how two of them end up disagreeing later.
 */
object ManuscriptMetrics {
    /** 4 dp, between parts of one control. */
    const val SPACE_MICRO_DP = 4

    /** 8 dp, between related elements. */
    const val SPACE_RELATED_DP = 8

    /** 12 dp, padding inside a compact card. */
    const val SPACE_CARD_DP = 12

    /** 16 dp, padding at the edge of a screen. */
    const val SPACE_SCREEN_DP = 16

    /** 24 dp, between sections. */
    const val SPACE_SECTION_DP = 24

    /** 32 dp, between major blocks. */
    const val SPACE_MAJOR_DP = 32

    /**
     * 48 dp, the smallest a primary interactive target may be.
     *
     * The manual states this for every primary control, and the accessibility
     * section repeats it. [ManuscriptTriggerPresentation] reads this rather
     * than carrying its own copy.
     */
    const val TOUCH_TARGET_DP = 48

    const val RADIUS_CHIP_DP = 8
    const val RADIUS_ROW_DP = 12
    const val RADIUS_CARD_DP = 16
    const val RADIUS_PANEL_DP = 24

    /** 28 sp. The manual gives 28 to 32; the smaller end suits long titles. */
    const val TEXT_DISPLAY_SP = 28f
    const val TEXT_PAGE_TITLE_SP = 24f
    const val TEXT_SECTION_TITLE_SP = 20f
    const val TEXT_BODY_SP = 16f
    const val TEXT_SUPPORT_SP = 14f
    const val TEXT_METADATA_SP = 12f

    /**
     * 18 sp for the writer's own words inside a correction.
     *
     * Deliberately larger than body text: this is the text being judged, and
     * the reader has to be able to see the difference between two spellings.
     */
    const val TEXT_CORRECTION_SP = 18f

    /** A collapsed compact suggestion card, manual section 15. */
    const val CARD_MIN_HEIGHT_DP = 56
    const val CARD_MAX_COLLAPSED_HEIGHT_DP = 88

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

/** The one density conversion. See [ManuscriptMetrics]. */
fun Context.manuscriptDp(value: Int): Int = ManuscriptMetrics.dp(this, value)
