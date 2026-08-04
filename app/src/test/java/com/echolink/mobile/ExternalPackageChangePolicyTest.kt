package com.echolink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPackageChangePolicyTest {
    @Test
    fun verifiedApplicationWindowChangeIsImmediateEvenWhenFocusIsMissing() {
        assertTrue(
            ExternalPackageChangePolicy.confirmed(
                retainedPackage = "fixture.editor",
                activeWindowPackage = "com.android.settings",
                eventPackage = "com.android.settings",
                verifiedApplicationWindowTransition = true,
            ),
        )
    }

    @Test
    fun confirmedWindowTransitionUsesEventPackageWhenActiveRootIsMissing() {
        assertTrue(
            ExternalPackageChangePolicy.confirmed(
                retainedPackage = "fixture.editor",
                activeWindowPackage = "",
                eventPackage = "com.android.settings",
                verifiedApplicationWindowTransition = true,
            ),
        )
    }

    @Test
    fun keyboardEventsDoNotPretendTheApplicationChanged() {
        assertFalse(
            ExternalPackageChangePolicy.confirmed(
                retainedPackage = "fixture.editor",
                activeWindowPackage = "fixture.editor",
                eventPackage = "keyboard.package",
                verifiedApplicationWindowTransition = false,
            ),
        )
    }

    @Test
    fun unverifiedActiveWindowDifferenceCannotClearForImeOrOverlayNoise() {
        assertFalse(
            ExternalPackageChangePolicy.confirmed(
                retainedPackage = "fixture.editor",
                activeWindowPackage = "keyboard.package",
                eventPackage = "keyboard.package",
                verifiedApplicationWindowTransition = false,
            ),
        )
    }
}
