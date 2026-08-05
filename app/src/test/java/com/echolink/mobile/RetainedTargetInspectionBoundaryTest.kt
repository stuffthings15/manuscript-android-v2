package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RetainedTargetInspectionBoundaryTest {
    @Test
    fun anyThrowWhileInspectingAStalePlatformNodeFailsClosedAsDetached() {
        val evidence = RetainedTargetInspectionBoundary.inspect {
            throw IllegalStateException("stale AccessibilityNodeInfo")
        }

        assertEquals(RetainedTargetEvidence.VERIFIED_EXACT_TARGET_DETACHED, evidence)
    }

    @Test
    fun successfulInspectionPreservesItsExactEvidence() {
        val evidence = RetainedTargetInspectionBoundary.inspect {
            RetainedTargetEvidence.VERIFIED_EXACT_TARGET_ELIGIBLE
        }

        assertEquals(RetainedTargetEvidence.VERIFIED_EXACT_TARGET_ELIGIBLE, evidence)
    }

    @Test
    fun fatalVmErrorsAreNotSwallowedAsOrdinaryStaleNodes() {
        val fatal = OutOfMemoryError("fatal")

        try {
            RetainedTargetInspectionBoundary.inspect { throw fatal }
            fail("Expected the fatal VM error to propagate")
        } catch (actual: OutOfMemoryError) {
            assertEquals(fatal, actual)
        }
    }

    @Test
    fun exactNodeRefreshFailureOrThrowOnWindowsChangedFailsClosed() {
        assertEquals(
            RetainedTargetEvidence.VERIFIED_EXACT_TARGET_DETACHED,
            RetainedTargetWindowChangeInspection.inspect(
                isWindowsChanged = true,
                exactNodeStillAttached = { false },
            ),
        )
        assertEquals(
            RetainedTargetEvidence.VERIFIED_EXACT_TARGET_DETACHED,
            RetainedTargetWindowChangeInspection.inspect(
                isWindowsChanged = true,
                exactNodeStillAttached = { throw IllegalStateException("stale node") },
            ),
        )
    }

    @Test
    fun oneHundredOverlayOrImeWindowEventsCannotInvalidateAnAttachedExactNode() {
        repeat(100) {
            assertEquals(
                RetainedTargetEvidence.VERIFIED_EXACT_TARGET_ELIGIBLE,
                RetainedTargetWindowChangeInspection.inspect(
                    isWindowsChanged = true,
                    exactNodeStillAttached = { true },
                ),
            )
        }
        assertEquals(
            RetainedTargetEvidence.UNVERIFIED_EVENT,
            RetainedTargetWindowChangeInspection.inspect(
                isWindowsChanged = false,
                exactNodeStillAttached = { false },
            ),
        )
    }
}
