package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalFieldIdentityTest {
    @Test
    fun recreatedNodeKeepsIdentityWithoutTextOrObjectHash() {
        val first = ExternalFieldIdentity.from(
            packageName = "example.editor",
            windowId = 7,
            viewId = "example.editor:id/body",
            className = "android.widget.EditText",
            drawingOrder = 3,
        )
        val recreated = ExternalFieldIdentity.from(
            packageName = "example.editor",
            windowId = 7,
            viewId = "example.editor:id/body",
            className = "android.widget.EditText",
            drawingOrder = 9,
        )

        assertEquals(first, recreated)
        assertTrue(first.value.contains("example.editor:id/body"))
        assertTrue(!first.value.contains("generated words"))
    }

    @Test
    fun fieldsWithoutViewIdsKeepIdentityAcrossBoundsAndNodeRecreation() {
        val first = ExternalFieldIdentity.from(
            packageName = "example.editor",
            windowId = 7,
            viewId = null,
            className = "android.widget.EditText",
            drawingOrder = 3,
        )
        val moved = ExternalFieldIdentity.from(
            packageName = "example.editor",
            windowId = 7,
            viewId = null,
            className = "android.widget.EditText",
            drawingOrder = 3,
        )

        assertEquals(first, moved)
        assertTrue(first.value.contains("order:android.widget.EditText:3"))
    }

    @Test
    fun fieldsWithoutViewIdsUseStableDrawingOrderToRemainDistinct() {
        val first = ExternalFieldIdentity.from(
            packageName = "example.editor",
            windowId = 7,
            viewId = null,
            className = "android.widget.EditText",
            drawingOrder = 3,
        )
        val other = ExternalFieldIdentity.from(
            packageName = "example.editor",
            windowId = 7,
            viewId = null,
            className = "android.widget.EditText",
            drawingOrder = 4,
        )

        assertNotEquals(first, other)
    }

    @Test
    fun recreatedWindowAfterRotationKeepsSemanticFieldIdentity() {
        val beforeRotation = ExternalFieldIdentity.from(
            packageName = "example.editor",
            windowId = 7,
            viewId = null,
            className = "android.widget.EditText",
            drawingOrder = 3,
        )
        val afterRotation = ExternalFieldIdentity.from(
            packageName = "example.editor",
            windowId = 12,
            viewId = null,
            className = "android.widget.EditText",
            drawingOrder = 3,
        )

        assertEquals(beforeRotation, afterRotation)
    }
}
