package com.echolink.mobile

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout

/**
 * Lets an upward swipe that begins anywhere in the review enlarge the panel.
 * Child controls keep normal touch behavior when the panel is already expanded.
 */
class ExpandableReviewPanel(context: Context) : LinearLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawY = 0f
    private var startingHeightPx = 0
    private var collapsedHeightPx = 0
    private var maximumHeightPx = 0
    private var dragging = false
    private var onResize: ((Int) -> Unit)? = null

    fun configureExpansion(
        collapsedHeightPx: Int,
        maximumHeightPx: Int,
        onResize: (Int) -> Unit,
    ) {
        this.collapsedHeightPx = collapsedHeightPx
        this.maximumHeightPx = maximumHeightPx
        this.onResize = onResize
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawY = event.rawY
                startingHeightPx = height.takeIf { it > 0 } ?: collapsedHeightPx
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val upwardDrag = (downRawY - event.rawY).toInt()
                if (ReviewPanelExpansionPolicy.shouldStartExpansion(
                        upwardDragPx = upwardDrag,
                        currentHeightPx = startingHeightPx,
                        maximumHeightPx = maximumHeightPx,
                        touchSlopPx = touchSlop,
                    )
                ) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                onResize?.invoke(
                    ReviewPanelExpansionPolicy.resizedHeight(
                        startingHeightPx = startingHeightPx,
                        upwardDragPx = (downRawY - event.rawY).toInt(),
                        collapsedHeightPx = collapsedHeightPx,
                        maximumHeightPx = maximumHeightPx,
                    ),
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
