package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The manual's compact card must name the kind of mistake, not just the fix. */
class EditCategoryPresentationTest {
    @Test
    fun everyCategoryHasAWord() {
        // A when with no else: a new EditCategory will not compile until it is
        // given a label, which is the point of writing it that way.
        EditCategory.entries.forEach { category ->
            assertTrue(category.name, EditCategoryPresentation.labelRes(category) != 0)
        }
    }

    @Test
    fun noTwoCategoriesShareAWord() {
        val labels = EditCategory.entries.map(EditCategoryPresentation::labelRes)
        assertEquals(
            "each category needs its own word or the card cannot tell them apart",
            labels.size,
            labels.distinct().size,
        )
    }
}
