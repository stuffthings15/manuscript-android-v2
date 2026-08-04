package com.echolink.mobile

data class KeyboardPanelBounds(
    val widthPx: Int,
    val heightPx: Int,
    val leftOffsetPx: Int,
    val bottomOffsetPx: Int,
)

data class KeyboardPanelSnapshot(
    val panelBounds: KeyboardPanelBounds,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val protectedBottomPx: Int,
)

object KeyboardPanelMemoryPolicy {
    fun resolve(
        current: KeyboardPanelBounds?,
        lastVisible: KeyboardPanelSnapshot?,
        currentScreenWidthPx: Int,
        currentScreenHeightPx: Int,
        currentProtectedBottomPx: Int,
    ): KeyboardPanelBounds? {
        if (current != null) return current
        return lastVisible
            ?.takeIf {
                it.screenWidthPx == currentScreenWidthPx &&
                    it.screenHeightPx == currentScreenHeightPx &&
                    it.protectedBottomPx == currentProtectedBottomPx
            }
            ?.panelBounds
    }
}

object KeyboardWindowGeometryPolicy {
    fun fromWindow(
        screenWidthPx: Int,
        screenHeightPx: Int,
        windowLeftPx: Int,
        windowTopPx: Int,
        windowRightPx: Int,
        windowBottomPx: Int,
        protectedBottomPx: Int,
        minimumKeyboardHeightPx: Int,
    ): KeyboardPanelBounds? {
        require(screenWidthPx > 0)
        require(screenHeightPx > 0)
        require(minimumKeyboardHeightPx >= 0)
        val left = windowLeftPx.coerceIn(0, screenWidthPx)
        val right = windowRightPx.coerceIn(0, screenWidthPx)
        val width = right - left
        if (width <= 0) return null
        val top = windowTopPx.coerceIn(0, screenHeightPx)
        val bottom = windowBottomPx.coerceIn(0, screenHeightPx)
        val rawHeight = bottom - top
        if (rawHeight < minimumKeyboardHeightPx) return null
        if (rawHeight > screenHeightPx * 3 / 4) return null

        val protectedBottom = protectedBottomPx.coerceIn(0, screenHeightPx)
        val usableBottom = minOf(bottom, screenHeightPx - protectedBottom)
        val height = usableBottom - top
        if (height <= 0) return null
        return KeyboardPanelBounds(
            widthPx = width,
            heightPx = height,
            leftOffsetPx = left,
            bottomOffsetPx = maxOf(protectedBottom, screenHeightPx - bottom),
        )
    }
}

object KeyboardPanelGeometry {
    fun calculate(
        screenWidthPx: Int,
        screenHeightPx: Int,
        imeBottomInsetPx: Int,
        navigationBottomInsetPx: Int,
        gestureBottomInsetPx: Int,
        fallbackHeightPx: Int,
    ): KeyboardPanelBounds {
        val protectedBottom = maxOf(navigationBottomInsetPx, gestureBottomInsetPx)
            .coerceIn(0, screenHeightPx)
        val keyboardHeight = (imeBottomInsetPx - protectedBottom).coerceAtLeast(0)
        val availableHeight = (screenHeightPx - protectedBottom).coerceAtLeast(1)
        val height = if (keyboardHeight > 0) {
            keyboardHeight.coerceAtMost(availableHeight)
        } else {
            fallbackHeightPx.coerceIn(1, availableHeight)
        }
        return KeyboardPanelBounds(
            widthPx = screenWidthPx.coerceAtLeast(1),
            heightPx = height,
            leftOffsetPx = 0,
            bottomOffsetPx = protectedBottom,
        )
    }
}

object ReviewPanelExpansionPolicy {
    fun maximumHeight(
        screenHeightPx: Int,
        protectedBottomPx: Int,
        collapsedHeightPx: Int,
    ): Int {
        val usableHeight = (screenHeightPx - protectedBottomPx).coerceAtLeast(1)
        return maxOf(collapsedHeightPx, usableHeight * 2 / 3).coerceAtMost(usableHeight)
    }

    fun resizedHeight(
        startingHeightPx: Int,
        upwardDragPx: Int,
        collapsedHeightPx: Int,
        maximumHeightPx: Int,
    ): Int = (startingHeightPx + upwardDragPx).coerceIn(
        collapsedHeightPx.coerceAtMost(maximumHeightPx),
        maximumHeightPx,
    )

    fun shouldStartExpansion(
        upwardDragPx: Int,
        currentHeightPx: Int,
        maximumHeightPx: Int,
        touchSlopPx: Int,
    ): Boolean = upwardDragPx > touchSlopPx && currentHeightPx < maximumHeightPx
}
