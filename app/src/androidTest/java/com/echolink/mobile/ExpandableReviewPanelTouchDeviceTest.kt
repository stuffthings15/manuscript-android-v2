package com.echolink.mobile

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpandableReviewPanelTouchDeviceTest {
    @Test
    fun firstThresholdMoveImmediatelyResizesAndClaimsCollapsedPanel() = onMain {
        val heights = mutableListOf<Int>()
        val panel = configuredPanel(200, 600, heights)
        panel.layout(0, 0, 300, 200)

        assertFalse(panel.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 500f)))
        val intercepted = panel.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, 400f))

        assertTrue(intercepted)
        assertEquals(1, heights.size)
        assertTrue(heights.single() > 200)
    }

    @Test
    fun maximumHeightLeavesNormalListScrollingUnintercepted() = onMain {
        val heights = mutableListOf<Int>()
        val panel = configuredPanel(200, 600, heights)
        panel.layout(0, 0, 300, 600)

        panel.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 500f))
        val intercepted = panel.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, 300f))

        assertFalse(intercepted)
        assertTrue(heights.isEmpty())
    }

    @Test
    fun downwardMovementClampsAndCancellationReleasesDragOwnership() = onMain {
        val heights = mutableListOf<Int>()
        val panel = configuredPanel(200, 600, heights)
        panel.layout(0, 0, 300, 300)

        panel.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 500f))
        assertTrue(panel.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, 400f)))
        panel.onTouchEvent(event(MotionEvent.ACTION_MOVE, 650f))
        assertEquals(200, heights.last())

        panel.onTouchEvent(event(MotionEvent.ACTION_CANCEL, 650f))
        val countAfterCancel = heights.size
        panel.onTouchEvent(event(MotionEvent.ACTION_MOVE, 350f))
        assertEquals(countAfterCancel, heights.size)
    }

    private fun configuredPanel(
        collapsedHeight: Int,
        maximumHeight: Int,
        heights: MutableList<Int>,
    ): ExpandableReviewPanel = ExpandableReviewPanel(
        InstrumentationRegistry.getInstrumentation().targetContext,
    ).apply {
        configureExpansion(collapsedHeight, maximumHeight) { heights += it }
    }

    private fun event(action: Int, y: Float): MotionEvent = MotionEvent.obtain(
        0L,
        0L,
        action,
        10f,
        y,
        0,
    )

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}
