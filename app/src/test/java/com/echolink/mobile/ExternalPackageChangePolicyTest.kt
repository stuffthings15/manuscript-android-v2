package com.echolink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPackageChangePolicyTest {
    @Test
    fun verifiedSettingsWindowWinsOverStaleRetainedRoot() {
        assertTrue(
            ExternalPackageChangePolicy.confirmed(
                retainedPackage = "fixture.editor",
                verifiedApplicationWindowPackage = "com.android.settings",
            ),
        )
    }

    @Test
    fun verifiedSamePackageWindowKeepsCurrentSurface() {
        assertFalse(
            ExternalPackageChangePolicy.confirmed(
                retainedPackage = "fixture.editor",
                verifiedApplicationWindowPackage = "fixture.editor",
            ),
        )
    }

    @Test
    fun unverifiedImeOrSelfOverlayCannotConfirmPackageChange() {
        assertFalse(
            ExternalPackageChangePolicy.confirmed(
                retainedPackage = "fixture.editor",
                verifiedApplicationWindowPackage = null,
            ),
        )
        assertFalse(
            ExternalPackageChangePolicy.confirmed(
                retainedPackage = "",
                verifiedApplicationWindowPackage = "com.android.settings",
            ),
        )
    }

    @Test
    fun exactDifferentApplicationWindowStateCanHideEarlyWithoutAuthorizingAdoption() {
        assertTrue(
            ExternalPackageChangePolicy.confirmedEarlyApplicationWindowStateChange(
                retainedPackage = "fixture.editor",
                eventPackage = "com.android.settings",
                eventWindowId = 41,
                matchingApplicationWindowCount = 1,
                isWindowStateChanged = true,
            ),
        )
        assertFalse(
            ExternalPackageChangePolicy.confirmedEarlyApplicationWindowStateChange(
                retainedPackage = "fixture.editor",
                eventPackage = "ime.or.overlay",
                eventWindowId = 42,
                matchingApplicationWindowCount = 0,
                isWindowStateChanged = true,
            ),
        )
    }

    @Test
    fun samePackageMalformedAndDuplicateWindowSignalsCannotHideEarly() {
        val invalidCases = listOf(
            arrayOf("fixture.editor", "fixture.editor", 41, 1, true),
            arrayOf("fixture.editor", "other.app", -1, 1, true),
            arrayOf("fixture.editor", "other.app", 41, 2, true),
            arrayOf("fixture.editor", "other.app", 41, 1, false),
            arrayOf("", "other.app", 41, 1, true),
        )

        invalidCases.forEach { values ->
            assertFalse(
                ExternalPackageChangePolicy.confirmedEarlyApplicationWindowStateChange(
                    retainedPackage = values[0] as String,
                    eventPackage = values[1] as String,
                    eventWindowId = values[2] as Int,
                    matchingApplicationWindowCount = values[3] as Int,
                    isWindowStateChanged = values[4] as Boolean,
                ),
            )
        }
    }

    @Test
    fun provisionalExactCrossPackageLifecycleSignalCanHideEarly() {
        assertTrue(
            ExternalPackageChangePolicy.provisionalTransitionHint(
                retainedPackage = "fixture.editor",
                eventPackage = "com.android.settings",
                eventWindowId = 42,
                isApplicationLifecycleEvent = true,
                isInputMethodWindow = false,
                matchingApplicationWindowCount = 1,
            ),
        )
        listOf(
            arrayOf("fixture.editor", "fixture.editor", 42, true, false, 1),
            arrayOf("", "com.android.settings", 42, true, false, 1),
            arrayOf("fixture.editor", "", 42, true, false, 1),
            arrayOf("fixture.editor", "com.android.settings", -1, true, false, 1),
            arrayOf("fixture.editor", "com.android.settings", 42, false, false, 1),
            arrayOf("fixture.editor", "keyboard.ime", 42, true, true, 0),
            arrayOf("fixture.editor", "system.ui", 42, true, false, 0),
            arrayOf("fixture.editor", "other.app", 42, true, false, 2),
        ).forEach { values ->
            assertFalse(
                ExternalPackageChangePolicy.provisionalTransitionHint(
                    retainedPackage = values[0] as String,
                    eventPackage = values[1] as String,
                    eventWindowId = values[2] as Int,
                    isApplicationLifecycleEvent = values[3] as Boolean,
                    isInputMethodWindow = values[4] as Boolean,
                    matchingApplicationWindowCount = values[5] as Int,
                ),
            )
        }
    }

}
