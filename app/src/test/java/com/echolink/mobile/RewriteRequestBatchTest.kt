package com.echolink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteRequestBatchTest {
    @Test
    fun startingSeveralCorrectionsDoesNotCancelEarlierCorrections() {
        val batch = RewriteRequestBatch()
        val first = RecordingRequest()
        val second = RecordingRequest()
        val third = RecordingRequest()
        val fourth = RecordingRequest()

        assertTrue(batch.begin("1"))
        batch.attach("1", first)
        assertTrue(batch.begin("2"))
        batch.attach("2", second)
        assertTrue(batch.begin("3"))
        batch.attach("3", third)
        assertTrue(batch.begin("4"))
        batch.attach("4", fourth)

        assertFalse(first.cancelled)
        assertFalse(second.cancelled)
        assertFalse(third.cancelled)
        assertFalse(fourth.cancelled)
        assertFalse(batch.begin("2"))
    }

    @Test
    fun toneChangeCancelsEveryOutstandingCorrectionButNotCompletedWork() {
        val batch = RewriteRequestBatch()
        val completed = RecordingRequest()
        val pending = RecordingRequest()

        assertTrue(batch.begin("completed"))
        batch.attach("completed", completed)
        batch.complete("completed")
        assertTrue(batch.begin("pending"))
        batch.attach("pending", pending)

        batch.cancelAll()

        assertFalse(completed.cancelled)
        assertTrue(pending.cancelled)
    }

    private class RecordingRequest : LocalPolishRequest {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }
}
