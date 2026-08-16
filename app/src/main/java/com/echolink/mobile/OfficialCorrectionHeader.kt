package com.echolink.mobile

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** One official header for every correction surface. */
class OfficialCorrectionHeader(
    context: Context,
    title: CharSequence,
    onClose: (() -> Unit)?,
    onSettings: (() -> Unit)?,
    closeDescription: CharSequence = context.getString(R.string.close_action),
    settingsDescription: CharSequence = context.getString(R.string.open_manuscript_settings),
) : LinearLayout(context) {
    val counterView: TextView

    init {
        id = R.id.official_correction_header
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(ManuscriptPalette.SURFACE, 12f, ManuscriptPalette.BORDER)
        setPadding(dp(6), dp(4), dp(4), dp(4))

        addView(ImageView(context).apply {
            id = R.id.official_correction_header_icon
            setImageResource(R.drawable.ic_manuscript_assist)
            contentDescription = null
        }, LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })

        addView(TextView(context).apply {
            id = R.id.official_correction_header_title
            text = title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ManuscriptPalette.TEXT)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                isAccessibilityHeading = true
            }
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        counterView = TextView(context).apply {
            id = R.id.writing_counter
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ManuscriptPalette.ACCENT)
            visibility = View.GONE
        }
        addView(counterView, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(4) })

        addView(actionButton(
            id = R.id.official_correction_header_settings,
            glyph = SETTINGS_GLYPH,
            description = settingsDescription,
            action = onSettings,
        ), LayoutParams(dp(MIN_TOUCH_TARGET_DP), dp(MIN_TOUCH_TARGET_DP)))

        addView(actionButton(
            id = R.id.writing_close_action,
            glyph = CLOSE_GLYPH,
            description = closeDescription,
            action = onClose,
            invokeOnPrimaryDown = true,
        ), LayoutParams(dp(MIN_TOUCH_TARGET_DP), dp(MIN_TOUCH_TARGET_DP)))
    }

    fun showCounter(show: Boolean = true) {
        counterView.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun actionButton(
        id: Int,
        glyph: String,
        description: CharSequence,
        action: (() -> Unit)?,
        invokeOnPrimaryDown: Boolean = false,
    ): Button = Button(context).apply {
        this.id = id
        text = glyph
        contentDescription = description
        isAllCaps = false
        textSize = if (glyph == CLOSE_GLYPH) 24f else 18f
        minWidth = dp(MIN_TOUCH_TARGET_DP)
        minimumWidth = dp(MIN_TOUCH_TARGET_DP)
        minHeight = dp(MIN_TOUCH_TARGET_DP)
        minimumHeight = dp(MIN_TOUCH_TARGET_DP)
        setPadding(0, 0, 0, 0)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ManuscriptPalette.TEXT)
        background = rounded(
            ManuscriptPalette.SURFACE_RAISED,
            10f,
            ManuscriptPalette.ACCENT,
        )
        visibility = if (action == null) View.GONE else View.VISIBLE
        isEnabled = action != null
        val pressGate = action?.let(::PrimaryPressActionGate)
        setOnClickListener { pressGate?.onClick() }
        if (invokeOnPrimaryDown && pressGate != null) {
            var touchSequence = false
            setOnTouchListener { button, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val inside = event.x >= 0f && event.x < button.width &&
                            event.y >= 0f && event.y < button.height
                        Log.i(
                            "ManuscriptReview",
                            "event=close-touch-down;elapsed_ms=${SystemClock.elapsedRealtime()}",
                        )
                        touchSequence = pressGate.onPrimaryDown(button.isEnabled, inside)
                        if (touchSequence) button.post(pressGate::endTouchGesture)
                        touchSequence
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                    -> touchSequence.also {
                        touchSequence = false
                        pressGate.endTouchGesture()
                    }
                    MotionEvent.ACTION_MOVE -> touchSequence
                    else -> false
                }
            }
        }
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int): GradientDrawable =
        ManuscriptComponents.rounded(context, fill, radiusDp.toInt(), stroke)

    private fun dp(value: Int): Int = context.manuscriptDp(value)

    private companion object {
        const val MIN_TOUCH_TARGET_DP = ManuscriptMetrics.TOUCH_TARGET_DP
        const val CLOSE_GLYPH = "\u00d7"
        const val SETTINGS_GLYPH = "\u2699"
    }
}

internal class PrimaryPressActionGate(private val action: () -> Unit) {
    private var touchActionInvoked = false

    fun onPrimaryDown(enabled: Boolean, inside: Boolean): Boolean {
        if (!enabled || !inside || touchActionInvoked) return false
        touchActionInvoked = true
        action()
        return true
    }

    fun onClick() {
        if (!touchActionInvoked) action()
    }

    fun endTouchGesture() {
        touchActionInvoked = false
    }
}
