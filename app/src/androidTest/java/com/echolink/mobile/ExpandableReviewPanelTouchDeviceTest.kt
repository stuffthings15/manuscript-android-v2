package com.echolink.mobile

import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
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
        val child = TrackingScrollView()
        val panel = configuredPanel(200, 600, heights, child)
        measureAndLayout(panel, 300, 200)

        assertTrue(panel.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 180f)))
        val claimed = panel.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 20f))

        assertTrue(claimed)
        assertEquals(1, heights.size)
        assertTrue(heights.single() > 200)
        assertEquals(0, child.moveCount)
        assertEquals(1, child.cancelCount)
    }

    @Test
    fun maximumHeightLeavesNormalListScrollingUnintercepted() = onMain {
        val heights = mutableListOf<Int>()
        val child = TrackingScrollView()
        val panel = configuredPanel(200, 600, heights, child)
        measureAndLayout(panel, 300, 600)

        assertTrue(panel.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 500f)))
        val remainedWithChild = panel.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 300f))

        assertTrue(remainedWithChild)
        assertTrue(heights.isEmpty())
        assertEquals(1, child.moveCount)
        assertEquals(0, child.cancelCount)
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
        child: View? = null,
    ): ExpandableReviewPanel = ExpandableReviewPanel(
        InstrumentationRegistry.getInstrumentation().targetContext,
    ).apply {
        child?.let {
            addView(
                it,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        configureExpansion(collapsedHeight, maximumHeight) { heights += it }
    }

    private fun measureAndLayout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
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

    private inner class TrackingScrollView : ScrollView(
        InstrumentationRegistry.getInstrumentation().targetContext,
    ) {
        var moveCount = 0
        var cancelCount = 0

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> moveCount += 1
                MotionEvent.ACTION_CANCEL -> cancelCount += 1
            }
            return true
        }
    }
}
