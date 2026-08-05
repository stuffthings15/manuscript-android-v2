package com.echolink.mobile.fixture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureActionContractTest {
    @Test fun transientModeRequiresExplicitClearAction() {
        assertTrue(FixtureActionContract.isAvailable("transient"))
        assertTrue(FixtureActionContract.clearsEditor("transient"))
        assertEquals("Clear generated transient field", FixtureActionContract.description("transient"))
    }

    @Test fun normalEditorHasNoClearAction() {
        assertFalse(FixtureActionContract.isAvailable("normal"))
        assertFalse(FixtureActionContract.clearsEditor("normal"))
    }

    @Test fun lifecycleActionsDoNotClearTheEditor() {
        listOf("detached", "target-loss", "package-switch").forEach { mode ->
            assertTrue(FixtureActionContract.isAvailable(mode))
            assertFalse(FixtureActionContract.clearsEditor(mode))
        }
    }
}
