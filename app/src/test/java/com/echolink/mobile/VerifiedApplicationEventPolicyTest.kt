package com.echolink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedApplicationEventPolicyTest {
    private val eventA = VerifiedApplicationEventMetadata(
        eventPackage = "fixture.a",
        eventWindowId = 41,
        rootPackage = "fixture.a",
        rootWindowId = 41,
        applicationWindow = true,
    )
    private val focusA = VerifiedApplicationFocusMetadata(
        packageName = "fixture.a",
        windowId = 41,
        focused = true,
        editable = true,
        password = false,
    )

    @Test
    fun staleActiveRootCannotOverrideVerifiedEventRoot() {
        assertTrue(VerifiedApplicationEventPolicy.acceptsEvent(eventA))
        assertTrue(VerifiedApplicationEventPolicy.acceptsFocus(eventA, focusA))
        assertTrue(
            ExternalPackageChangePolicy.confirmed(
                retainedPackage = "fixture.b",
                verifiedApplicationWindowPackage = eventA.eventPackage,
            ),
        )
    }

    @Test
    fun absentOrMatchingRetainedTargetCanAdoptOnlyVerifiedRootFocus() {
        assertFalse(
            ExternalPackageChangePolicy.confirmed(
                retainedPackage = "",
                verifiedApplicationWindowPackage = eventA.eventPackage,
            ),
        )
        assertFalse(
            ExternalPackageChangePolicy.confirmed(
                retainedPackage = "fixture.a",
                verifiedApplicationWindowPackage = eventA.eventPackage,
            ),
        )
        assertTrue(VerifiedApplicationEventPolicy.acceptsFocus(eventA, focusA))
        assertFalse(
            VerifiedApplicationEventPolicy.acceptsFocus(
                eventA,
                focusA.copy(packageName = "fixture.b"),
            ),
        )
        assertFalse(
            VerifiedApplicationEventPolicy.acceptsFocus(
                eventA,
                focusA.copy(windowId = 99),
            ),
        )
    }

    @Test
    fun missingVerifiedFocusCannotAdoptStaleActiveRoot() {
        assertFalse(
            VerifiedApplicationEventPolicy.acceptsFocus(
                eventA,
                focusA.copy(focused = false),
            ),
        )
        assertFalse(
            VerifiedApplicationEventPolicy.acceptsFocus(
                eventA,
                focusA.copy(editable = false),
            ),
        )
    }

    @Test
    fun imeOverlayAndInvalidWindowEventsAreUnverified() {
        assertFalse(VerifiedApplicationEventPolicy.acceptsEvent(eventA.copy(applicationWindow = false)))
        assertFalse(VerifiedApplicationEventPolicy.acceptsEvent(eventA.copy(eventWindowId = -1)))
        assertFalse(VerifiedApplicationEventPolicy.acceptsEvent(eventA.copy(rootWindowId = -1)))
        assertFalse(VerifiedApplicationEventPolicy.acceptsEvent(eventA.copy(rootPackage = "fixture.b")))
        assertFalse(
            VerifiedApplicationEventPolicy.acceptsEvent(
                eventA.copy(matchingApplicationWindowCount = 2),
            ),
        )
    }
}
