package com.echolink.mobile

object ManuscriptTriggerPresentation {
    /** The general rule, not a second opinion. See [ManuscriptMetrics]. */
    const val TOUCH_TARGET_DP = ManuscriptMetrics.TOUCH_TARGET_DP
    const val VISUAL_SIZE_DP = 32
    const val PADDING_DP = (TOUCH_TARGET_DP - VISUAL_SIZE_DP) / 2

    const val PULSE_ALPHA_FROM = 1f
    const val PULSE_ALPHA_TO = 0.62f
    const val PULSE_SCALE_FROM = 1f
    const val PULSE_SCALE_TO = 1.08f
    const val PULSE_HALF_CYCLE_MS = 700L

    const val FRAME_COLOR = 0xFF17120D.toInt()
    const val BORDER_COLOR = 0xFFD6A85F.toInt()
}
