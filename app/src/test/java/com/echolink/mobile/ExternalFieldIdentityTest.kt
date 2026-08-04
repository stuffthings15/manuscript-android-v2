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
            bounds = FieldBounds(10, 20, 500, 220),
        )
        val recreated = ExternalFieldIdentity.from(
            packageName = "example.editor",
            windowId = 7,
            viewId = "example.editor:id/body",
            className = "android.widget.EditText",
            bounds = FieldBounds(99, 99, 999, 999),
        )

        assertEquals(first, recreated)
        assertTrue(first.value.contains("example.editor:id/body"))
        assertTrue(!first.value.contains("generated words"))
    }

    @Test
    fun fieldsWithoutViewIdsUseContentFreeGeometryFallback() {
        val first = ExternalFieldIdentity.from(
            packageName = "example.editor",
            windowId = 7,
            viewId = null,
            className = "android.widget.EditText",
            bounds = FieldBounds(10, 20, 500, 220),
        )
        val other = ExternalFieldIdentity.from(
            packageName = "example.editor",
            windowId = 7,
            viewId = null,
            className = "android.widget.EditText",
            bounds = FieldBounds(10, 300, 500, 500),
        )

        assertNotEquals(first, other)
    }
}
