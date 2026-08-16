package com.echolink.mobile

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The reusable pieces the GUI build manual's phase 1 asks for, built in the
 * idiom this codebase already uses: functions that return configured views.
 *
 * There is no res/layout directory here and this does not add one. What it adds
 * is a single definition of each shape, because the same card was being
 * hand-assembled in several places and the rounded-background helper alone had
 * four identical copies.
 *
 * Sizes come from [ManuscriptMetrics] and colours from [ManuscriptPalette].
 * Nothing in this file invents a number or a colour.
 */
object ManuscriptComponents {

    /**
     * The one rounded background.
     *
     * Was defined identically in MainActivity, ManuscriptAccessibilityService,
     * OfficialCorrectionHeader and ProcessTextActivity.
     */
    fun rounded(
        context: Context,
        fill: Int,
        radiusDp: Int,
        stroke: Int,
        strokeWidthDp: Int = 1,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = context.manuscriptDp(radiusDp).toFloat()
        setStroke(context.manuscriptDp(strokeWidthDp), stroke)
    }

    /** A surface that holds related content. 16 dp radius, 12 dp padding. */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(
            context,
            ManuscriptPalette.SURFACE,
            ManuscriptMetrics.RADIUS_CARD_DP,
            ManuscriptPalette.BORDER,
        )
        val padding = context.manuscriptDp(ManuscriptMetrics.SPACE_CARD_DP)
        setPadding(padding, padding, padding, padding)
    }

    /**
     * A section title. Marked as an accessibility heading so a screen reader
     * user can jump between sections instead of reading everything.
     */
    fun sectionHeader(context: Context, text: CharSequence): TextView =
        TextView(context).apply {
            this.text = text
            textSize = ManuscriptMetrics.TEXT_SECTION_TITLE_SP
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ManuscriptPalette.TEXT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        }

    /**
     * A tappable navigation or settings row: title, optional supporting line.
     *
     * The whole row is the target and it is at least 48 dp tall, rather than
     * the label alone being tappable.
     */
    fun settingsRow(
        context: Context,
        title: CharSequence,
        subtitle: CharSequence? = null,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        minimumHeight = context.manuscriptDp(ManuscriptMetrics.TOUCH_TARGET_DP)
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(
            context,
            ManuscriptPalette.SURFACE_RAISED,
            ManuscriptMetrics.RADIUS_ROW_DP,
            ManuscriptPalette.BORDER,
        )
        val horizontal = context.manuscriptDp(ManuscriptMetrics.SPACE_CARD_DP)
        val vertical = context.manuscriptDp(ManuscriptMetrics.SPACE_RELATED_DP)
        setPadding(horizontal, vertical, horizontal, vertical)
        addView(
            TextView(context).apply {
                text = title
                textSize = ManuscriptMetrics.TEXT_BODY_SP
                setTextColor(ManuscriptPalette.TEXT)
            },
        )
        if (subtitle != null) {
            addView(
                TextView(context).apply {
                    text = subtitle
                    textSize = ManuscriptMetrics.TEXT_SUPPORT_SP
                    setTextColor(ManuscriptPalette.MUTED_TEXT)
                },
            )
        }
        contentDescription = if (subtitle == null) title else "$title. $subtitle"
        setOnClickListener { onClick() }
    }

    /**
     * A prompt chip. Selection is carried by fill, border AND the selected
     * state a screen reader reads, never by colour alone.
     */
    fun chip(
        context: Context,
        label: CharSequence,
        selected: Boolean,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        textSize = ManuscriptMetrics.TEXT_SUPPORT_SP
        isAllCaps = false
        isClickable = enabled
        isFocusable = enabled
        isSelected = selected
        this.isEnabled = enabled
        minimumHeight = context.manuscriptDp(ManuscriptMetrics.TOUCH_TARGET_DP)
        gravity = Gravity.CENTER
        val horizontal = context.manuscriptDp(ManuscriptMetrics.SPACE_CARD_DP)
        val vertical = context.manuscriptDp(ManuscriptMetrics.SPACE_RELATED_DP)
        setPadding(horizontal, vertical, horizontal, vertical)
        setTextColor(
            when {
                !enabled -> ManuscriptPalette.DISABLED
                selected -> ManuscriptPalette.BACKGROUND
                else -> ManuscriptPalette.TEXT
            },
        )
        background = rounded(
            context,
            if (selected) ManuscriptPalette.ACCENT else ManuscriptPalette.SURFACE_RAISED,
            ManuscriptMetrics.RADIUS_CHIP_DP,
            if (enabled) ManuscriptPalette.BORDER else ManuscriptPalette.DISABLED,
        )
        contentDescription = label
        setOnClickListener { if (enabled) onClick() }
    }

    /**
     * A label that reports rather than invites, such as the category on a
     * correction card.
     *
     * Chip-shaped but deliberately not a [chip]: it is not clickable, not
     * focusable and carries no touch target, because a control the writer can
     * press and that does nothing is worse than no control. Quieter than the
     * accent so it cannot be mistaken for one.
     */
    fun tag(context: Context, label: CharSequence): TextView =
        TextView(context).apply {
            text = label
            textSize = ManuscriptMetrics.TEXT_METADATA_SP
            isAllCaps = false
            setTextColor(ManuscriptPalette.INFORMATION)
            val horizontal = context.manuscriptDp(ManuscriptMetrics.SPACE_RELATED_DP)
            val vertical = context.manuscriptDp(ManuscriptMetrics.SPACE_MICRO_DP)
            setPadding(horizontal, vertical, horizontal, vertical)
            background = rounded(
                context,
                ManuscriptPalette.SURFACE_RAISED,
                ManuscriptMetrics.RADIUS_CHIP_DP,
                ManuscriptPalette.INFORMATION,
            )
            contentDescription = label
        }

    /**
     * The Assistant Status pill: a colour, and always the state's name beside
     * it. See [AssistantStatus].
     */
    fun statusPill(context: Context, status: AssistantStatus): TextView =
        TextView(context).apply {
            val label = context.getString(status.labelRes)
            text = label
            textSize = ManuscriptMetrics.TEXT_SUPPORT_SP
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ManuscriptPalette.BACKGROUND)
            val horizontal = context.manuscriptDp(ManuscriptMetrics.SPACE_CARD_DP)
            val vertical = context.manuscriptDp(ManuscriptMetrics.SPACE_MICRO_DP)
            setPadding(horizontal, vertical, horizontal, vertical)
            background = rounded(
                context,
                status.color,
                ManuscriptMetrics.RADIUS_CHIP_DP,
                status.color,
            )
            contentDescription = context.getString(status.detailRes)
        }

    fun primaryButton(context: Context, label: CharSequence, onClick: () -> Unit): Button =
        button(context, label, ManuscriptPalette.ACCENT_DARK, ManuscriptPalette.TEXT, onClick)

    fun secondaryButton(context: Context, label: CharSequence, onClick: () -> Unit): Button =
        button(context, label, ManuscriptPalette.SURFACE_RAISED, ManuscriptPalette.TEXT, onClick)

    private fun button(
        context: Context,
        label: CharSequence,
        fill: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        contentDescription = label
        isAllCaps = false
        textSize = ManuscriptMetrics.TEXT_BODY_SP
        minHeight = context.manuscriptDp(ManuscriptMetrics.TOUCH_TARGET_DP)
        minimumHeight = context.manuscriptDp(ManuscriptMetrics.TOUCH_TARGET_DP)
        minWidth = 0
        minimumWidth = 0
        val horizontal = context.manuscriptDp(ManuscriptMetrics.SPACE_CARD_DP)
        setPadding(horizontal, 0, horizontal, 0)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textColor)
        background = rounded(
            context,
            fill,
            ManuscriptMetrics.RADIUS_ROW_DP,
            ManuscriptPalette.BORDER,
        )
        setOnClickListener { onClick() }
    }

    /**
     * An empty or error state: a line explaining it, and an action when there
     * is one. The manual requires both rather than a blank area.
     */
    fun message(
        context: Context,
        text: CharSequence,
        actionLabel: CharSequence? = null,
        onAction: (() -> Unit)? = null,
    ): LinearLayout = card(context).apply {
        addView(
            TextView(context).apply {
                this.text = text
                textSize = ManuscriptMetrics.TEXT_BODY_SP
                setTextColor(ManuscriptPalette.MUTED_TEXT)
            },
        )
        if (actionLabel != null && onAction != null) {
            addView(
                secondaryButton(context, actionLabel) { onAction() },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = context.manuscriptDp(ManuscriptMetrics.SPACE_RELATED_DP)
                },
            )
        }
    }

    /** Vertical gap between sections, as a view so it works inside any column. */
    fun sectionGap(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.manuscriptDp(ManuscriptMetrics.SPACE_SECTION_DP),
        )
    }
}
