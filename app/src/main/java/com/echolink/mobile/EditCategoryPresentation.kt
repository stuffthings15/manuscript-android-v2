package com.echolink.mobile

/**
 * The word shown on a correction card for each [EditCategory].
 *
 * The GUI build manual's compact card carries a count, a category, the
 * replacement and a dismiss control. The categories already existed and were
 * classified on every edit, but they were never shown: the card said what to
 * change without ever saying what kind of mistake it was.
 *
 * The names here are the writer's, not the parser's. WORD_FORM, CONTEXTUAL_WORD
 * and REORDERING describe how the diff was classified; "Word form",
 * "Word choice" and "Word order" describe what the reader is looking at.
 * INSERTION and DELETION are named from the writer's side too, as a missing or
 * extra word, because "Insertion" reads as an instruction rather than a
 * finding.
 */
object EditCategoryPresentation {
    fun labelRes(category: EditCategory): Int = when (category) {
        EditCategory.CAPITALIZATION -> R.string.edit_category_capitalization
        EditCategory.PUNCTUATION -> R.string.edit_category_punctuation
        EditCategory.SPELLING -> R.string.edit_category_spelling
        EditCategory.WORD_FORM -> R.string.edit_category_word_form
        EditCategory.AGREEMENT -> R.string.edit_category_agreement
        EditCategory.PRONOUN -> R.string.edit_category_pronoun
        EditCategory.CONTEXTUAL_WORD -> R.string.edit_category_contextual_word
        EditCategory.INSERTION -> R.string.edit_category_insertion
        EditCategory.DELETION -> R.string.edit_category_deletion
        EditCategory.REORDERING -> R.string.edit_category_reordering
        EditCategory.STYLE -> R.string.edit_category_style
    }
}
