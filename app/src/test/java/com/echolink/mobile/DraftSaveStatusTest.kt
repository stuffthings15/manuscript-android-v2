package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The draft editor's autosave states, which the GUI build manual asks to be
 * surfaced rather than left silent.
 */
class DraftSaveStatusTest {
    @Test
    fun aRefusedWriteIsReportedAsAFailure() {
        // The store returns false both when keystore encryption fails and when
        // the preferences write is refused. Either way the writing is only in
        // the editor, which is the thing the writer has to be told.
        assertEquals(
            DraftSaveStatus.FAILED,
            DraftSaveStatus.afterWrite(textIsEmpty = false, saved = false),
        )
    }

    @Test
    fun anEmptyEditorIsIdleRatherThanSaved() {
        // Clearing a draft succeeds, but reporting "Saved" for an empty editor
        // claims something was written that was not.
        assertEquals(
            DraftSaveStatus.IDLE,
            DraftSaveStatus.afterWrite(textIsEmpty = true, saved = true),
        )
        assertEquals(
            DraftSaveStatus.SAVED,
            DraftSaveStatus.afterWrite(textIsEmpty = false, saved = true),
        )
    }

    @Test
    fun anEmptyEditorThatCouldNotBeClearedStillReportsTheFailure() {
        // A clear that fails leaves an old draft on disk, so silence here would
        // hide the previous writing still being stored.
        assertEquals(
            DraftSaveStatus.FAILED,
            DraftSaveStatus.afterWrite(textIsEmpty = true, saved = false),
        )
    }

    @Test
    fun onlyTheFailureAsksForAttention() {
        assertTrue(DraftSaveStatus.FAILED.needsAttention)
        assertFalse(DraftSaveStatus.SAVED.needsAttention)
        assertFalse(DraftSaveStatus.SAVING.needsAttention)
        assertFalse(DraftSaveStatus.IDLE.needsAttention)
    }

    @Test
    fun everyStateWithSomethingToSayCarriesAWordAsWellAsAColour() {
        // Colour is never the only signal. IDLE is the one state allowed no
        // label, because an editor nobody has typed in has nothing to report.
        assertNull(DraftSaveStatus.IDLE.labelRes)
        listOf(
            DraftSaveStatus.SAVING,
            DraftSaveStatus.SAVED,
            DraftSaveStatus.FAILED,
        ).forEach { status ->
            assertNotNull(status.name, status.labelRes)
        }
    }

    @Test
    fun theFailureDoesNotShareAColourWithSuccess() {
        assertTrue(DraftSaveStatus.FAILED.color != DraftSaveStatus.SAVED.color)
    }

    @Test
    fun savingIsReportedBeforeTheDebouncedWriteAndAfterIt() {
        // The status is worth nothing if the screen only updates on success.
        // scheduleDraftPersistence must show SAVING, and persistDraftNow must
        // render whatever afterWrite returns.
        val source = File(
            System.getProperty("echoLinkAndroidDir") ?: ".",
            "app/src/main/java/com/echolink/mobile/MainActivity.kt",
        ).readText()
        assertTrue(
            "scheduleDraftPersistence must report SAVING",
            source.contains("renderDraftStatus(DraftSaveStatus.SAVING)"),
        )
        assertTrue(
            "persistDraftNow must report the store's own result",
            source.contains("DraftSaveStatus.afterWrite("),
        )
    }
}
