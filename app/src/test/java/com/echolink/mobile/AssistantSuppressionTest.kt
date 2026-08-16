package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 16 of the manual: the four ways to send the assistant away differ in
 * how long they last and how far they reach, and confusing two of them means
 * doing something the writer did not ask for.
 */
class AssistantSuppressionTest {
    private val mail = "com.example.mail"
    private val notes = "com.example.notes"
    private val fresh = AssistantSuppressionState()

    @Test
    fun nothingIsHiddenToStartWith() {
        assertTrue(fresh.mayShow(mail, now = 0L))
        assertNull(fresh.suppressionFor(mail, now = 0L))
    }

    @Test
    fun hidingForThisVisitAffectsOnlyThatApplication() {
        val state = fresh.after(AssistantHideChoice.HIDE_UNTIL_APP_CLOSES, mail, now = 0L)
        assertFalse(state.mayShow(mail, now = 0L))
        assertTrue("Another application was not what the writer hid", state.mayShow(notes, now = 0L))
    }

    @Test
    fun leavingTheApplicationEndsAHideScopedToTheVisit() {
        val hidden = fresh.after(AssistantHideChoice.HIDE_UNTIL_APP_CLOSES, mail, now = 0L)
        val moved = hidden.onForegroundApplicationChanged(notes)
        // Coming back to mail must show it again. Left uncleared this would
        // behave like a block, which is a different choice.
        assertTrue(moved.mayShow(mail, now = 0L))
    }

    @Test
    fun stayingInTheSameApplicationKeepsTheHide() {
        val hidden = fresh.after(AssistantHideChoice.HIDE_UNTIL_APP_CLOSES, mail, now = 0L)
        assertFalse(hidden.onForegroundApplicationChanged(mail).mayShow(mail, now = 0L))
    }

    @Test
    fun anHourIsEverywhereAndEndsByItself() {
        val state = fresh.after(AssistantHideChoice.HIDE_FOR_ONE_HOUR, mail, now = 1_000L)
        assertFalse(state.mayShow(mail, now = 1_000L))
        assertFalse("An hour is not scoped to one app", state.mayShow(notes, now = 1_000L))
        assertFalse(state.mayShow(mail, now = 1_000L + AssistantSuppressionState.ONE_HOUR_MS - 1))
        assertTrue(
            "The hour has to end without the writer doing anything",
            state.mayShow(mail, now = 1_000L + AssistantSuppressionState.ONE_HOUR_MS),
        )
    }

    @Test
    fun blockingAnApplicationOutlastsTheVisitAndTheHour() {
        val state = fresh.after(AssistantHideChoice.BLOCK_THIS_APP, mail, now = 0L)
        assertFalse(state.mayShow(mail, now = 0L))
        assertFalse(
            state.onForegroundApplicationChanged(notes).mayShow(mail, now = 0L),
        )
        assertFalse(state.mayShow(mail, now = 10L * AssistantSuppressionState.ONE_HOUR_MS))
        assertTrue(state.mayShow(notes, now = 0L))
    }

    @Test
    fun aBlockCanBeUndone() {
        val blocked = fresh.after(AssistantHideChoice.BLOCK_THIS_APP, mail, now = 0L)
        assertTrue(blocked.unblock(mail).mayShow(mail, now = 0L))
    }

    @Test
    fun turningItOffEverywhereBeatsEverythingAndDoesNotExpire() {
        val state = fresh.after(AssistantHideChoice.TURN_OFF_EVERYWHERE, mail, now = 0L)
        assertFalse(state.mayShow(mail, now = 0L))
        assertFalse(state.mayShow(notes, now = 0L))
        assertFalse(
            "Off means off until the writer turns it back on",
            state.mayShow(notes, now = 1_000L * AssistantSuppressionState.ONE_HOUR_MS),
        )
    }

    @Test
    fun theReasonIsNamedSoAScreenCanSayWhichOneApplies() {
        assertEquals(
            AssistantSuppressionReason.BLOCKED_APP,
            fresh.after(AssistantHideChoice.BLOCK_THIS_APP, mail, 0L).suppressionFor(mail, 0L),
        )
        assertEquals(
            AssistantSuppressionReason.HIDDEN_FOR_NOW,
            fresh.after(AssistantHideChoice.HIDE_FOR_ONE_HOUR, mail, 0L).suppressionFor(mail, 0L),
        )
        assertEquals(
            AssistantSuppressionReason.HIDDEN_THIS_VISIT,
            fresh.after(AssistantHideChoice.HIDE_UNTIL_APP_CLOSES, mail, 0L)
                .suppressionFor(mail, 0L),
        )
        assertEquals(
            AssistantSuppressionReason.TURNED_OFF,
            fresh.after(AssistantHideChoice.TURN_OFF_EVERYWHERE, mail, 0L)
                .suppressionFor(mail, 0L),
        )
    }

    @Test
    fun theWidestReasonIsReportedWhenSeveralApply() {
        // Turned off everywhere is the one the writer must be told about,
        // because it is the only one that will not end on its own.
        val state = fresh
            .after(AssistantHideChoice.BLOCK_THIS_APP, mail, 0L)
            .after(AssistantHideChoice.TURN_OFF_EVERYWHERE, mail, 0L)
        assertEquals(
            AssistantSuppressionReason.TURNED_OFF,
            state.suppressionFor(mail, 0L),
        )
    }
}
