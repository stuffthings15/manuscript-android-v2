package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalFieldIdentityTest {
    @Test
    fun api33UniqueIdSurvivesHierarchyChanges() {
        val first = identity(uniqueId = "platform-field-41", windowId = 7, path = listOf(0, 2))
        val recreated = identity(uniqueId = "platform-field-41", windowId = 12, path = listOf(4, 1))

        assertEquals(first, recreated)
    }

    @Test
    fun api33DifferentUniqueIdsRemainDistinct() {
        val first = identity(uniqueId = "platform-field-41", windowId = 7, path = listOf(0, 2))
        val second = identity(uniqueId = "platform-field-42", windowId = 7, path = listOf(0, 2))

        assertNotEquals(first, second)
    }

    @Test
    fun api26FreshNodeAtSameWindowPathKeepsIdentityThroughKeyboardLayoutChanges() {
        val beforeKeyboard = identity(windowId = 7, path = listOf(0, 2, 1))
        val afterKeyboard = identity(windowId = 7, path = listOf(0, 2, 1))

        assertEquals(beforeKeyboard, afterKeyboard)
    }

    @Test
    fun api26WindowRecreationStrictlyInvalidatesFallbackIdentity() {
        val beforeRecreation = identity(windowId = 7, path = listOf(0, 2, 1))
        val afterRecreation = identity(windowId = 12, path = listOf(0, 2, 1))

        assertNotEquals(beforeRecreation, afterRecreation)
    }

    @Test
    fun nullViewIdFieldsUnderDifferentParentsRemainDistinct() {
        val leftParent = identity(windowId = 7, path = listOf(0, 1, 0))
        val rightParent = identity(windowId = 7, path = listOf(0, 2, 0))

        assertNotEquals(leftParent, rightParent)
    }

    @Test
    fun duplicateViewIdsInDifferentRowsDoNotCollapse() {
        val firstRow = identity(
            windowId = 7,
            path = listOf(0, 1, 0),
            viewId = "example.editor:id/repeated_body",
        )
        val secondRow = identity(
            windowId = 7,
            path = listOf(0, 2, 0),
            viewId = "example.editor:id/repeated_body",
        )

        assertNotEquals(firstRow, secondRow)
        assertTrue(firstRow.value.contains("example.editor:id/repeated_body"))
    }

    @Test
    fun viewIdAndStructuralPathTogetherRemainStableForFreshNode() {
        val first = identity(
            windowId = 7,
            path = listOf(0, 2, 0),
            viewId = "example.editor:id/body",
        )
        val recreated = identity(
            windowId = 7,
            path = listOf(0, 2, 0),
            viewId = "example.editor:id/body",
        )

        assertEquals(first, recreated)
    }

    private fun identity(
        uniqueId: String? = null,
        windowId: Int,
        path: List<Int>,
        viewId: String? = null,
    ): ExternalFieldIdentity = ExternalFieldIdentity.from(
        packageName = "example.editor",
        platformUniqueId = uniqueId,
        viewId = viewId,
        structuralLocator = FieldStructuralLocator(
            windowId = windowId,
            ancestry = path.mapIndexed { depth, childIndex ->
                StructuralNodeStep(
                    parentClassName = "fixture.Parent$depth",
                    childIndex = childIndex,
                    childDrawingOrder = depth + 1,
                )
            },
            leafClassName = "android.widget.EditText",
        ),
    )
}
