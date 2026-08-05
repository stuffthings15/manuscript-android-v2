package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSurfaceLifecycleHarnessSourceTest {
    @Test
    fun hostTouchReadyWaitsForMatchingBoundsAcrossSeparateDrawFrames() {
        val source = File(
            System.getProperty("user.dir"),
            "src/androidTest/java/com/echolink/mobile/ReviewSurfaceLifecycleDeviceTest.kt",
        ).readText()

        assertTrue(source.contains("awaitStableVisibleBoundsAcrossFrames(close)"))
        assertTrue(source.contains("ViewTreeObserver.OnPreDrawListener"))
        assertTrue(source.contains("current == previous"))
        assertTrue(source.contains("view.postInvalidateOnAnimation()"))
        assertFalse(source.contains("fullyVisibleBounds(close)?.also"))
    }
}
