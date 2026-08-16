package com.echolink.mobile

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The manual's expanded review: a sheet with a drag handle, a navigator, the
 * writer's sentence stacked above the suggested one, Apply and Dismiss, and
 * the secondary actions.
 *
 * It lives in its own file for two reasons. MainActivity is already about
 * 2,950 lines, and ManuscriptProductSourceTest scans that file as text to
 * enforce that the COMPACT card shows only the suggestion: it asserts
 * MainActivity.kt never mentions reviewOriginal, and that no id is named
 * writing_original. That rule is deliberate and it agrees with the manual,
 * where Original and Suggested belong to the expanded review and not to the
 * card. So the ids here are expanded_review_*, which keeps both the letter and
 * the point of it.
 *
 * This builds views and reports taps. It owns no review state: that stays with
 * ReviewSurfaceController and the session, so there is still one answer to
 * what is under review.
 */
class ExpandedReviewSheet(
    context: Context,
    private val onApply: () -> Unit,
    private val onDismiss: () -> Unit,
    private val onUndo: () -> Unit,
    private val onExplain: () -> Unit,
    private val onCollapse: () -> Unit,
) : LinearLayout(context) {

    private val navigator: TextView
    private val categoryTag: TextView
    private val originalText: TextView
    private val suggestedText: TextView
    private val explanation: TextView
    private val undoAction: TextView
    private val applyAction: TextView

    init {
        id = R.id.expanded_review_sheet
        orientation = VERTICAL
        background = ManuscriptComponents.rounded(
            context,
            ManuscriptPalette.SURFACE,
            ManuscriptMetrics.RADIUS_PANEL_DP,
            ManuscriptPalette.BORDER,
        )
        val padding = context.manuscriptDp(ManuscriptMetrics.SPACE_SCREEN_DP)
        setPadding(padding, context.manuscriptDp(ManuscriptMetrics.SPACE_RELATED_DP), padding, padding)

        // The drag handle. It is also a real control: a handle that can only be
        // dragged is unusable by anyone who cannot drag, so tapping it collapses
        // the sheet and it carries a name a screen reader can read.
        //
        // The bar the eye sees is 4 dp. The thing a finger hits is the full
        // 48 dp row around it, because the manual's touch target rule is about
        // what can be pressed, not what can be seen.
        addView(
            LinearLayout(context).apply {
                id = R.id.expanded_review_handle
                orientation = VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = context.manuscriptDp(ManuscriptMetrics.TOUCH_TARGET_DP)
                contentDescription = context.getString(R.string.expanded_review_collapse)
                isClickable = true
                isFocusable = true
                setOnClickListener { onCollapse() }
                addView(
                    View(context).apply {
                        background = ManuscriptComponents.rounded(
                            context,
                            ManuscriptPalette.MUTED_TEXT,
                            ManuscriptMetrics.RADIUS_CHIP_DP,
                            ManuscriptPalette.MUTED_TEXT,
                        )
                    },
                    LayoutParams(
                        context.manuscriptDp(HANDLE_WIDTH_DP),
                        context.manuscriptDp(HANDLE_HEIGHT_DP),
                    ),
                )
            },
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.manuscriptDp(ManuscriptMetrics.TOUCH_TARGET_DP),
            ).apply {
                bottomMargin = context.manuscriptDp(ManuscriptMetrics.SPACE_RELATED_DP)
            },
        )

        // Navigator: where the reader is, and what kind of mistake this is.
        val navigatorRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        navigator = TextView(context).apply {
            id = R.id.expanded_review_navigator
            textSize = ManuscriptMetrics.TEXT_SECTION_TITLE_SP
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ManuscriptPalette.TEXT)
        }
        navigatorRow.addView(
            navigator,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        categoryTag = ManuscriptComponents.tag(context, "").apply {
            id = R.id.expanded_review_category
            visibility = View.GONE
        }
        navigatorRow.addView(categoryTag)
        addView(navigatorRow, matchWidth(context, ManuscriptMetrics.SPACE_CARD_DP))

        addView(caption(context, R.string.original_label), matchWidth(context, 0))
        originalText = body(context, R.id.expanded_review_original).apply {
            setTextColor(ManuscriptPalette.MUTED_TEXT)
        }
        addView(originalText, matchWidth(context, ManuscriptMetrics.SPACE_CARD_DP))

        addView(caption(context, R.string.suggestion_label), matchWidth(context, 0))
        suggestedText = body(context, R.id.expanded_review_suggested)
        addView(suggestedText, matchWidth(context, ManuscriptMetrics.SPACE_RELATED_DP))

        explanation = TextView(context).apply {
            id = R.id.expanded_review_explanation
            textSize = ManuscriptMetrics.TEXT_SUPPORT_SP
            setTextColor(ManuscriptPalette.INFORMATION)
            visibility = View.GONE
        }
        addView(explanation, matchWidth(context, ManuscriptMetrics.SPACE_CARD_DP))

        val primaryRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        applyAction = ManuscriptComponents.primaryButton(
            context = context,
            label = context.getString(R.string.expanded_review_apply),
            onClick = onApply,
        ).apply { id = R.id.expanded_review_apply }
        primaryRow.addView(applyAction, weighted(context, endMargin = ManuscriptMetrics.SPACE_MICRO_DP))
        primaryRow.addView(
            ManuscriptComponents.secondaryButton(
                context = context,
                label = context.getString(R.string.writing_dismiss_action),
                onClick = onDismiss,
            ).apply { id = R.id.expanded_review_dismiss },
            weighted(context, startMargin = ManuscriptMetrics.SPACE_MICRO_DP),
        )
        addView(primaryRow, matchWidth(context, ManuscriptMetrics.SPACE_RELATED_DP))

        // Secondary actions. The manual names four. Two of them, Add to
        // Dictionary and Report, are not drawn here because neither exists:
        // ignoring a word needs a store and a change to the correction
        // pipeline, and reporting needs somewhere to send a report, which this
        // product does not have and its privacy policy rules out. A button
        // that looks live and does nothing is worse than an absent one.
        val secondaryRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        undoAction = ManuscriptComponents.secondaryButton(
            context = context,
            label = context.getString(R.string.writing_undo_action),
            onClick = onUndo,
        ).apply { id = R.id.expanded_review_undo }
        secondaryRow.addView(undoAction, weighted(context, endMargin = ManuscriptMetrics.SPACE_MICRO_DP))
        secondaryRow.addView(
            ManuscriptComponents.secondaryButton(
                context = context,
                label = context.getString(R.string.expanded_review_explain),
                onClick = onExplain,
            ).apply { id = R.id.expanded_review_explain },
            weighted(context, startMargin = ManuscriptMetrics.SPACE_MICRO_DP),
        )
        addView(secondaryRow, matchWidth(context, 0))
    }

    /**
     * Fills the sheet in.
     *
     * [category] and [explanation] are nullable because a block can be under
     * review with no single focused change, and naming a category that was
     * never established would put a confident word on a finding that does not
     * exist.
     */
    fun render(
        index: Int,
        total: Int,
        original: String,
        suggested: CharSequence,
        category: EditCategory?,
        explanationText: String?,
        canUndo: Boolean,
        canApply: Boolean,
    ) {
        navigator.text = context.getString(R.string.review_counter, index, total)
        if (category == null) {
            categoryTag.visibility = View.GONE
            categoryTag.text = ""
        } else {
            val label = context.getString(EditCategoryPresentation.labelRes(category))
            categoryTag.visibility = View.VISIBLE
            categoryTag.text = label
            categoryTag.contentDescription = label
        }
        originalText.text = original
        originalText.contentDescription =
            context.getString(R.string.original_label) + ". " + original
        suggestedText.text = suggested
        this.explanation.text = explanationText.orEmpty()
        this.explanation.visibility =
            if (explanationText.isNullOrBlank()) View.GONE else View.VISIBLE
        undoAction.isEnabled = canUndo
        applyAction.isEnabled = canApply
    }

    /** Shows the explanation the Explain action asked for. */
    fun showExplanation(text: String?) {
        explanation.text = text.orEmpty()
        explanation.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun caption(context: Context, textRes: Int): TextView = TextView(context).apply {
        text = context.getString(textRes)
        textSize = ManuscriptMetrics.TEXT_METADATA_SP
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ManuscriptPalette.MUTED_TEXT)
    }

    private fun body(context: Context, viewId: Int): TextView = TextView(context).apply {
        id = viewId
        textSize = ManuscriptMetrics.TEXT_CORRECTION_SP
        setTextColor(ManuscriptPalette.TEXT)
        setTextIsSelectable(true)
    }

    private fun matchWidth(context: Context, bottomMarginDp: Int): LayoutParams =
        LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = context.manuscriptDp(bottomMarginDp) }

    private fun weighted(
        context: Context,
        startMargin: Int = 0,
        endMargin: Int = 0,
    ): LayoutParams = LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
    ).apply {
        marginStart = context.manuscriptDp(startMargin)
        marginEnd = context.manuscriptDp(endMargin)
    }

    private companion object {
        const val HANDLE_WIDTH_DP = 40
        const val HANDLE_HEIGHT_DP = 4
    }
}
