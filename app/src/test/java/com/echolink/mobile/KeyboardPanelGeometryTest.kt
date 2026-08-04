package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardPanelGeometryTest {
    @Test
    fun currentKeyboardFrameWinsOverTheRememberedFrame() {
        val current = KeyboardPanelBounds(1080, 700, 0, 80)
        assertEquals(
            current,
            KeyboardPanelMemoryPolicy.resolve(
                current = current,
                lastVisible = KeyboardPanelSnapshot(KeyboardPanelBounds(1080, 820, 0, 80), 1080, 2400, 80),
                currentScreenWidthPx = 1080,
                currentScreenHeightPx = 2400,
                currentProtectedBottomPx = 80,
            ),
        )
    }

    @Test
    fun rememberedFrameCoversTransientMissingAndroidFrame() {
        assertEquals(
            KeyboardPanelBounds(1080, 673, 0, 84),
            KeyboardPanelMemoryPolicy.resolve(
                current = null,
                lastVisible = KeyboardPanelSnapshot(KeyboardPanelBounds(1080, 673, 0, 84), 1080, 2400, 84),
                currentScreenWidthPx = 1080,
                currentScreenHeightPx = 2400,
                currentProtectedBottomPx = 84,
            ),
        )
    }

    @Test
    fun rememberedFrameIsRejectedAfterScreenGeometryChanges() {
        assertEquals(
            null,
            KeyboardPanelMemoryPolicy.resolve(
                current = null,
                lastVisible = KeyboardPanelSnapshot(KeyboardPanelBounds(1080, 673, 0, 84), 1080, 2400, 84),
                currentScreenWidthPx = 2400,
                currentScreenHeightPx = 1080,
                currentProtectedBottomPx = 84,
            ),
        )
    }

    @Test
    fun anchoredKeyboardWindowMatchesItsUsableFrame() {
        assertEquals(
            KeyboardPanelBounds(1080, 673, 0, 84),
            KeyboardWindowGeometryPolicy.fromWindow(
                screenWidthPx = 1080,
                screenHeightPx = 2400,
                windowLeftPx = 0,
                windowTopPx = 1643,
                windowRightPx = 1080,
                windowBottomPx = 2400,
                protectedBottomPx = 84,
                minimumKeyboardHeightPx = 200,
            ),
        )
    }

    @Test
    fun floatingKeyboardUsesItsExactFrame() {
        assertEquals(
            KeyboardPanelBounds(600, 500, 300, 900),
            KeyboardWindowGeometryPolicy.fromWindow(
                screenWidthPx = 1080,
                screenHeightPx = 2400,
                windowLeftPx = 300,
                windowTopPx = 1000,
                windowRightPx = 900,
                windowBottomPx = 1500,
                protectedBottomPx = 84,
                minimumKeyboardHeightPx = 200,
            ),
        )
    }

    @Test
    fun nearFullScreenImeContainerIsRejected() {
        assertEquals(
            null,
            KeyboardWindowGeometryPolicy.fromWindow(
                screenWidthPx = 1080,
                screenHeightPx = 2400,
                windowLeftPx = 0,
                windowTopPx = 63,
                windowRightPx = 1080,
                windowBottomPx = 2400,
                protectedBottomPx = 84,
                minimumKeyboardHeightPx = 200,
            ),
        )
    }

    @Test
    fun panelMatchesKeyboardAndStopsAboveNavigationArea() {
        val bounds = KeyboardPanelGeometry.calculate(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            imeBottomInsetPx = 820,
            navigationBottomInsetPx = 63,
            gestureBottomInsetPx = 40,
            fallbackHeightPx = 840,
        )

        assertEquals(757, bounds.heightPx)
        assertEquals(1080, bounds.widthPx)
        assertEquals(0, bounds.leftOffsetPx)
        assertEquals(63, bounds.bottomOffsetPx)
    }

    @Test
    fun largerGestureAreaRemainsOutsideTheOverlay() {
        val bounds = KeyboardPanelGeometry.calculate(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            imeBottomInsetPx = 820,
            navigationBottomInsetPx = 40,
            gestureBottomInsetPx = 96,
            fallbackHeightPx = 840,
        )

        assertEquals(724, bounds.heightPx)
        assertEquals(96, bounds.bottomOffsetPx)
    }

    @Test
    fun fallbackNeverCoversTheProtectedBottomArea() {
        val bounds = KeyboardPanelGeometry.calculate(
            screenWidthPx = 1080,
            screenHeightPx = 600,
            imeBottomInsetPx = 0,
            navigationBottomInsetPx = 80,
            gestureBottomInsetPx = 60,
            fallbackHeightPx = 700,
        )

        assertEquals(520, bounds.heightPx)
        assertEquals(80, bounds.bottomOffsetPx)
    }
}
