package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Assistant Status card's model, from section 6 of the GUI build manual,
 * and the rule from section 3 that colour is never the only signal.
 */
class AssistantStatusTest {
    @Test
    fun theManualsFiveStatesAllExist() {
        assertEquals(
            listOf("ACTIVE", "PAUSED", "PERMISSION_MISSING", "SERVICE_ERROR", "EDITOR_ONLY"),
            AssistantStatus.entries.map { it.name },
        )
    }

    @Test
    fun everyStateCarriesALabelSoColourIsNeverTheOnlySignal() {
        AssistantStatus.entries.forEach { status ->
            assertTrue(status.name, status.labelRes != 0)
            assertTrue(status.name, status.detailRes != 0)
        }
    }

    @Test
    fun everyStateTheUserCanActOnNamesItsAction() {
        // The manual requires a permission failure to link to its repair. A
        // state with nothing to do is the only one allowed a null action.
        assertNull(AssistantStatus.ACTIVE.repairActionRes)
        listOf(
            AssistantStatus.PAUSED,
            AssistantStatus.PERMISSION_MISSING,
            AssistantStatus.SERVICE_ERROR,
            AssistantStatus.EDITOR_ONLY,
        ).forEach { status ->
            assertNotNull(status.name, status.repairActionRes)
        }
    }

    @Test
    fun onlyRealFaultsAskForAttention() {
        assertTrue(AssistantStatus.PERMISSION_MISSING.needsAttention)
        assertTrue(AssistantStatus.SERVICE_ERROR.needsAttention)
        // Paused is the user's own choice and editor-only is a supported way to
        // run the product, so neither is dressed up as a fault.
        assertFalse(AssistantStatus.PAUSED.needsAttention)
        assertFalse(AssistantStatus.EDITOR_ONLY.needsAttention)
        assertFalse(AssistantStatus.ACTIVE.needsAttention)
    }

    @Test
    fun statesAreDistinguishableByColourAsWellAsLabel() {
        val colors = AssistantStatus.entries.map { it.color }
        assertEquals(colors.size, colors.distinct().size)
    }

    @Test
    fun editorOnlyIsNotReportedAsAPermissionFailure() {
        // A user who never turned across-apps on is running the supported
        // editor-only product. Calling that a permission failure would put a
        // repair prompt in front of someone with nothing broken.
        assertEquals(
            AssistantStatus.EDITOR_ONLY,
            AssistantStatus.forState(acrossAppsEnabled = false, acrossAppsRequested = false),
        )
        assertEquals(
            AssistantStatus.PERMISSION_MISSING,
            AssistantStatus.forState(acrossAppsEnabled = false, acrossAppsRequested = true),
        )
        assertEquals(
            AssistantStatus.ACTIVE,
            AssistantStatus.forState(acrossAppsEnabled = true, acrossAppsRequested = true),
        )
        // Enabled without a recorded request is still active: the permission is
        // what matters, and the flag only disambiguates the off case.
        assertEquals(
            AssistantStatus.ACTIVE,
            AssistantStatus.forState(acrossAppsEnabled = true, acrossAppsRequested = false),
        )
    }

    @Test
    fun theRoundedBackgroundHasOneOwner() {
        val sources = File(
            System.getProperty("echoLinkAndroidDir") ?: ".",
            "app/src/main/java/com/echolink/mobile",
        )
        val copies = sources.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".kt") && it.name != "ManuscriptComponents.kt" }
            .filter { it.readText().contains("shape = GradientDrawable.RECTANGLE") }
            .map { it.name }

        // ManuscriptTriggerViewFactory keeps its own: the trigger is a circle
        // with its own frame and border from ManuscriptTriggerPresentation, not
        // a card. Everything else reads the shared one.
        assertEquals(
            "rounded backgrounds belong to ManuscriptComponents: $copies",
            listOf("ManuscriptTriggerViewFactory.kt"),
            copies,
        )
    }
}
