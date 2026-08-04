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
}
