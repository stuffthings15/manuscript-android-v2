package com.echolink.mobile

/**
 * Where the floating assistant sits, and where it lands when it is let go.
 *
 * Sections 12 to 14 of the GUI build manual: the trigger can be dragged, it
 * snaps to the nearer edge, and its side and height survive a rotation, a
 * split-screen change and a foldable being opened.
 *
 * Pure arithmetic on purpose. Screen geometry is the part of a floating window
 * that is hardest to test on a device and easiest to get wrong in a way nobody
 * notices until the phone is turned sideways, so none of it needs a device.
 */
object FloatingAssistantPlacement {

    enum class Side { LEFT, RIGHT }

    /**
     * Which edge the trigger belongs to once the finger lifts.
     *
     * Decided by the trigger's own centre against the middle of the screen, so
     * a drag that crosses the halfway point changes sides and one that does
     * not comes back where it started.
     */
    fun snapSide(triggerLeftPx: Int, triggerSizePx: Int, screenWidthPx: Int): Side {
        val centre = triggerLeftPx + triggerSizePx / 2
        return if (centre * 2 <= screenWidthPx) Side.LEFT else Side.RIGHT
    }

    /** The x the trigger rests at, inset from the edge by [marginPx]. */
    fun restingX(
        side: Side,
        triggerSizePx: Int,
        screenWidthPx: Int,
        marginPx: Int,
    ): Int {
        val maximum = (screenWidthPx - triggerSizePx).coerceAtLeast(0)
        return when (side) {
            Side.LEFT -> marginPx.coerceAtMost(maximum)
            Side.RIGHT -> (screenWidthPx - triggerSizePx - marginPx).coerceIn(0, maximum)
        }
    }

    /**
     * Keeps the trigger inside the part of the screen the writer can reach.
     *
     * [protectedTopPx] and [protectedBottomPx] are the status bar and the
     * navigation or gesture area. A trigger under either is visible and
     * untappable, which is worse than one that has moved.
     */
    fun clampY(
        yPx: Int,
        triggerSizePx: Int,
        screenHeightPx: Int,
        protectedTopPx: Int,
        protectedBottomPx: Int,
    ): Int {
        val lowest = (screenHeightPx - protectedBottomPx - triggerSizePx)
        // A screen too short for the trigger between its protected areas has
        // no correct answer. Prefer the top, which is never under a gesture
        // bar, over an arithmetic result below zero.
        if (lowest <= protectedTopPx) return protectedTopPx.coerceAtLeast(0)
        return yPx.coerceIn(protectedTopPx, lowest)
    }

    /**
     * The stored vertical position, as a fraction of the usable height.
     *
     * Pixels do not survive a rotation: 1,600 px down a portrait screen is off
     * the bottom of the same phone in landscape. A fraction does, which is what
     * the manual means by recalculating on posture change rather than
     * forgetting where the writer put it.
     */
    fun verticalFraction(
        yPx: Int,
        triggerSizePx: Int,
        screenHeightPx: Int,
        protectedTopPx: Int,
        protectedBottomPx: Int,
    ): Float {
        val usable = (screenHeightPx - protectedTopPx - protectedBottomPx - triggerSizePx)
        if (usable <= 0) return 0f
        return ((yPx - protectedTopPx).toFloat() / usable).coerceIn(0f, 1f)
    }

    /** The inverse of [verticalFraction], for the screen as it is now. */
    fun yForFraction(
        fraction: Float,
        triggerSizePx: Int,
        screenHeightPx: Int,
        protectedTopPx: Int,
        protectedBottomPx: Int,
    ): Int {
        val usable = (screenHeightPx - protectedTopPx - protectedBottomPx - triggerSizePx)
        if (usable <= 0) return protectedTopPx.coerceAtLeast(0)
        val y = protectedTopPx + (fraction.coerceIn(0f, 1f) * usable).toInt()
        return clampY(y, triggerSizePx, screenHeightPx, protectedTopPx, protectedBottomPx)
    }

    /**
     * Whether a touch that has moved this far is a drag rather than a tap.
     *
     * Below the system's own slop it stays a tap, so the trigger still opens
     * the review for anyone whose finger is not perfectly still.
     */
    fun isDrag(totalMovementPx: Int, touchSlopPx: Int): Boolean =
        totalMovementPx > touchSlopPx
}
