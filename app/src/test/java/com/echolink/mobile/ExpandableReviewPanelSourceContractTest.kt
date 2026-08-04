package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandableReviewPanelSourceContractTest {
    @Test
    fun thresholdCrossingMoveResizesBeforeInterceptionReturns() {
        val source = File(
            requireNotNull(System.getProperty("echoLinkAndroidDir")),
            "app/src/main/java/com/echolink/mobile/ExpandableReviewPanel.kt",
        ).readText()
        val interceptBody = source.substringAfter("override fun onInterceptTouchEvent")
            .substringBefore("override fun onTouchEvent")
        val resize = interceptBody.indexOf("emitResize(event)")
        val interceptReturn = interceptBody.indexOf("return true")

        assertTrue("ACTION_MOVE must emit resize inside onInterceptTouchEvent", resize >= 0)
        assertTrue("Resize must occur before interception returns", resize < interceptReturn)
    }
}
