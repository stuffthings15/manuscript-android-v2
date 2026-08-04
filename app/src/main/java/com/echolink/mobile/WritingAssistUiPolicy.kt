package com.echolink.mobile

/** Pure policy shared by the Manuscript editor UI and its unit tests. */
object WritingAssistUiPolicy {
    const val ICON_VISUAL_SIZE_DP = 32
    const val ICON_TOUCH_TARGET_DP = 48
    // Paragraph-sized context gives Qwen enough meaning while keeping each card compact.
    // WritingReviewSession never lets a block cross a paragraph boundary.
    const val SENTENCES_PER_SUGGESTION = 3

    fun shouldShowIcon(editorHasFocus: Boolean, text: CharSequence): Boolean =
        editorHasFocus && text.isNotBlank()

    fun shouldShowCard(
        editorHasFocus: Boolean,
        iconWasClicked: Boolean,
        hasSuggestion: Boolean,
    ): Boolean = editorHasFocus && iconWasClicked && hasSuggestion
}
