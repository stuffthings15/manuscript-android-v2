package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The across-app helper runs inside every app the user types in, and the
 * system builds its accessibility trees on the shared system_server thread.
 * A too-greedy configuration therefore lags the whole phone, not just
 * Manuscript. These limits are a product requirement, not a style choice.
 */
class AccessibilityServiceCostTest {
    private val config = File(
        checkNotNull(System.getProperty("echoLinkAndroidDir")),
        "app/src/main/res/xml/accessibility_service_config.xml",
    ).readText()

    @Test
    fun serviceNeverRequestsEveryViewInEveryApp() {
        // flagIncludeNotImportantViews forces the platform to build a node for
        // every view in every foreground app. Manuscript only needs editable
        // fields, which are always important views.
        assertFalse(config.contains("flagIncludeNotImportantViews"))
    }

    @Test
    fun serviceDoesNotSubscribeToWindowChurn() {
        // typeWindowsChanged fires on animations, toasts, and keyboard
        // transitions. Focus, text, and window-state events already cover
        // every case Manuscript reacts to.
        assertFalse(config.contains("typeWindowsChanged"))
        assertTrue(config.contains("typeViewFocused"))
        assertTrue(config.contains("typeViewTextChanged"))
        assertTrue(config.contains("typeWindowStateChanged"))
    }

    @Test
    fun eventDeliveryIsThrottled() {
        val timeout = Regex("android:notificationTimeout=\"(\\d+)\"")
            .find(config)
            ?.groupValues
            ?.get(1)
            ?.toInt()
        assertTrue("notificationTimeout must be declared", timeout != null)
        assertTrue(
            "notificationTimeout $timeout ms is too aggressive for a global service",
            (timeout ?: 0) >= 200,
        )
    }
}
