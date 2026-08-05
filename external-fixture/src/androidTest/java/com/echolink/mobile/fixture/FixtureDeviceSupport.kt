package com.echolink.mobile.fixture

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

open class FixtureDeviceSupport {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val fixtureContext = instrumentation.targetContext
    protected val fixturePackage = fixtureContext.packageName
    protected val automation = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
    ).apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    protected fun manuscriptString(name: String): String {
        val product = fixtureContext.createPackageContext(PRODUCT_PACKAGE, 0)
        val id = product.resources.getIdentifier(name, "string", PRODUCT_PACKAGE)
        check(id != 0) { "Installed Manuscript resource missing: $name" }
        return product.getString(id)
    }

    protected fun requireHealthyManuscriptService() {
        assertTrue("Instrumentation must target the dedicated fixture.", fixturePackage == FIXTURE_PACKAGE)
        assertFalse("Fixture instrumentation must never target Manuscript.", fixturePackage == PRODUCT_PACKAGE)
        assertTrue(
            "Only the exact Manuscript V2 component may be enabled.",
            secureSetting("enabled_accessibility_services").split(':').filter(String::isNotBlank) == listOf(SERVICE_COMPONENT),
        )
        assertTrue("Global accessibility is off.", secureSetting("accessibility_enabled") == "1")
        val accessibility = shell("dumpsys accessibility")
        val bound = accessibility.section("Bound services", "Crashed services")
        val crashed = accessibility.section("Crashed services", "Client list info")
        assertTrue("Expected exactly one bound accessibility service.\n$accessibility", bound.countOccurrences("Service[") == 1)
        assertTrue("A Manuscript service is not bound.\n$accessibility", bound.contains(SERVICE_CLASS))
        assertTrue("Any crashed service invalidates V2 proof.\n$accessibility", crashed.trim().removePrefix(":") == "{}")
        val service = shell("dumpsys activity services $SERVICE_COMPONENT")
        assertTrue("Exact V2 service record is absent.\n$service", service.contains("ServiceRecord"))
        assertTrue("Exact V2 service is not bound.\n$service", service.contains("hasBound=true"))
        assertTrue("Exact V2 component mismatch.\n$service", service.contains(SERVICE_COMPONENT))
    }

    protected fun assertInstalledCorrectionModel() {
        val path = shell("run-as $PRODUCT_PACKAGE ls no_backup/qwen3_models")
        assertTrue("Installed local model path is required for the product contract run.", path.contains(".gguf"))
    }

    protected fun withFixture(mode: String, block: () -> Unit) {
        clearFixtureAndV2Surfaces()
        fixtureContext.startActivity(Intent(fixtureContext, S23ExternalEditorFixtureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(S23ExternalEditorFixtureActivity.EXTRA_MODE, mode)
        })
        try {
            assertTrue("Fixture application window did not become active.", waitUntil { activePackage() == fixturePackage })
            val editorDescription = S23ExternalEditorFixtureActivity.editorDescription(mode)
            assertTrue(
                "Fixture root/editor was not ready: $editorDescription",
                waitUntil { findFixtureDescription(editorDescription).let { node -> node?.recycle(); node != null } },
            )
            block()
        } finally {
            if (activePackage() != fixturePackage) {
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                waitUntil { activePackage() == fixturePackage }
            }
            findProductSurface(manuscriptString("close_action"))?.let { node ->
                node.recycle()
                tapProductSurface(manuscriptString("close_action"))
            }
            if (activePackage() == fixturePackage && findFixtureDescription(S23ExternalEditorFixtureActivity.FINISH_DESCRIPTION) != null) {
                tapFixtureDescription(S23ExternalEditorFixtureActivity.FINISH_DESCRIPTION)
                waitUntil { activePackage() != fixturePackage }
            }
            var clean = v2OverlayClearsWithinGateAndStaysAbsent()
            if (!clean) {
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                clean = v2OverlayClearsWithinGateAndStaysAbsent()
            }
            assertTrue("Fixture cleanup left a V2 overlay after the 300ms removal gate.", clean)
        }
    }

    private fun clearFixtureAndV2Surfaces() {
        if (activePackage() == fixturePackage && findFixtureDescription(S23ExternalEditorFixtureActivity.FINISH_DESCRIPTION) != null) {
            tapFixtureDescription(S23ExternalEditorFixtureActivity.FINISH_DESCRIPTION)
        }
        if (hasV2OverlaySurface()) {
            findProductSurface(manuscriptString("close_action"))?.let { node ->
                node.recycle()
                tapProductSurface(manuscriptString("close_action"))
            } ?: instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        }
        assumeTrue(
            "STALE_V2_OVERLAY: V2 overlay missed the 300ms removal gate or reappeared during the 750ms stable interval.",
            v2OverlayClearsWithinGateAndStaysAbsent(),
        )
    }

    protected fun tapFixtureDescription(description: String) = tapNode(
        requireNotNull(findFixtureDescription(description)) { "Missing fixture node: $description" },
    )

    protected fun tapProductSurface(description: String) = tapNode(
        requireNotNull(findProductSurface(description)) { "Missing V2 overlay node: $description" },
    )

    private fun tapNode(node: AccessibilityNodeInfo): Long {
        val bounds = Rect().also(node::getBoundsInScreen)
        node.recycle()
        assertFalse("Empty bounds", bounds.isEmpty)
        val started = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(started, started, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(started, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val injectedAt = SystemClock.elapsedRealtime()
        try { assertTrue(automation.injectInputEvent(down, true)); assertTrue(automation.injectInputEvent(up, true)) } finally { down.recycle(); up.recycle() }
        return injectedAt
    }

    protected fun swipeUpOnProductSurface(description: String) {
        val node = requireNotNull(findProductSurface(description)) { "Missing V2 overlay node: $description" }
        val bounds = Rect().also(node::getBoundsInScreen)
        node.recycle()
        assertFalse("Empty V2 overlay bounds", bounds.isEmpty)
        val x = bounds.exactCenterX()
        val startY = bounds.exactCenterY()
        val upwardDistance = minOf(300f, (startY - 1f).coerceAtLeast(1f))
        val endY = startY - upwardDistance
        val started = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(started, started, MotionEvent.ACTION_DOWN, x, startY, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val move = MotionEvent.obtain(started, started + 120L, MotionEvent.ACTION_MOVE, x, endY, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(started, started + 220L, MotionEvent.ACTION_UP, x, endY, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            assertTrue(automation.injectInputEvent(down, true))
            assertTrue(automation.injectInputEvent(move, true))
            assertTrue(automation.injectInputEvent(up, true))
        } finally { down.recycle(); move.recycle(); up.recycle() }
    }

    protected fun findFixtureDescription(description: String): AccessibilityNodeInfo? = findInFixtureWindow {
        it.contentDescription?.toString() == description
    }

    protected fun findProductSurface(description: String): AccessibilityNodeInfo? {
        automation.windows.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@forEach
            val root = window.root ?: return@forEach
            try {
                if (root.packageName?.toString() != PRODUCT_PACKAGE) return@forEach
                findNode(root) { node ->
                    node.packageName?.toString() == PRODUCT_PACKAGE &&
                        node.contentDescription?.toString() == description
                }?.let { return it }
            } finally { root.recycle() }
        }
        return null
    }

    protected fun hasV2OverlaySurface(): Boolean = automation.windows.any { window ->
        if (window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@any false
        val root = window.root ?: return@any false
        try { root.packageName?.toString() == PRODUCT_PACKAGE } finally { root.recycle() }
    }

    private fun v2OverlayClearsWithinGateAndStaysAbsent(): Boolean =
        FixtureCleanupTiming.awaitRemovalThenStableAbsence(
            isOverlayPresent = ::hasV2OverlaySurface,
            nowMs = SystemClock::elapsedRealtime,
            pauseMs = SystemClock::sleep,
        )

    protected fun v2OverlayHeightContaining(description: String): Int? = automation.windows.firstNotNullOfOrNull { window ->
        if (window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@firstNotNullOfOrNull null
        val root = window.root ?: return@firstNotNullOfOrNull null
        try {
            if (root.packageName?.toString() != PRODUCT_PACKAGE) return@firstNotNullOfOrNull null
            val containsDescription = findNode(root) { node ->
                node.packageName?.toString() == PRODUCT_PACKAGE &&
                    node.contentDescription?.toString() == description
            }
            containsDescription?.recycle()
            if (containsDescription == null) null else Rect().also(window::getBoundsInScreen).height()
        } finally { root.recycle() }
    }

    private fun findInFixtureWindow(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = fixtureApplicationRoot() ?: return null
        return try { findNode(root, predicate) } finally { root.recycle() }
    }

    private fun findNode(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { findNode(child, predicate)?.let { return it } } finally { child.recycle() }
        }
        return null
    }

    protected fun productSurfaceMeetsExpectation(description: String, expected: Boolean, timeoutMs: Long = 5_000L): Boolean {
        val sample = {
            findProductSurface(description).let { node ->
                node?.recycle()
                (node != null) == expected
            }
        }
        return if (timeoutMs <= 0L) sample() else waitUntil(timeoutMs, sample)
    }

    protected fun productSurfaceNeverVisible(description: String, durationMs: Long = 1_000L) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        do {
            assertFalse("Unexpected V2 surface: $description", findProductSurface(description) != null)
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
    }

    protected fun activePackage(): String = automation.rootInActiveWindow?.let { root ->
        try { root.packageName?.toString().orEmpty() } finally { root.recycle() }
    }.orEmpty()

    protected fun applicationWindowPresent(packageName: String): Boolean = automation.windows.any { window ->
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@any false
        val root = window.root ?: return@any false
        try { root.packageName?.toString() == packageName } finally { root.recycle() }
    }

    protected fun fixtureApplicationRoot(): AccessibilityNodeInfo? = automation.windows.firstNotNullOfOrNull { window ->
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@firstNotNullOfOrNull null
        val root = window.root ?: return@firstNotNullOfOrNull null
        if (root.packageName?.toString() == fixturePackage) root else { root.recycle(); null }
    }

    protected fun fixtureImeAndFocusedFieldPresent(): Boolean {
        val imeVisible = automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val root = fixtureApplicationRoot() ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return try {
            imeVisible && focused != null && focused.isEditable && focused.isFocused &&
                focused.packageName?.toString() == fixturePackage
        } finally { focused?.recycle(); root.recycle() }
    }

    protected fun tapFixtureEditorForIme(mode: String) {
        tapFixtureDescription(S23ExternalEditorFixtureActivity.editorDescription(mode))
        assertTrue(
            "IME and focused fixture editor were not ready after a physical editor tap.",
            waitUntil { fixtureImeAndFocusedFieldPresent() },
        )
    }

    protected fun productStructuralLocator(root: AccessibilityNodeInfo, focused: AccessibilityNodeInfo): String? {
        val product = fixtureContext.createPackageContext(PRODUCT_PACKAGE, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val locatorClass = product.classLoader.loadClass("com.echolink.mobile.AccessibilityNodeStructuralLocator")
        val locatorObject = locatorClass.getField("INSTANCE").get(null)
        val locator = locatorClass.getMethod("from", AccessibilityNodeInfo::class.java, AccessibilityNodeInfo::class.java).invoke(locatorObject, root, focused) ?: return null
        return locator.javaClass.getMethod("encoded").invoke(locator) as String
    }

    protected fun secureSetting(key: String): String = shell("settings get secure $key")
    protected fun shell(command: String): String = automation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { it.readText().trim() } }
    protected fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        return waitUntilDeadline(SystemClock.elapsedRealtime() + timeoutMs, condition)
    }

    protected fun waitUntilDeadline(deadline: Long, condition: () -> Boolean): Boolean {
        while (true) {
            val conditionMet = condition()
            val observedAt = SystemClock.elapsedRealtime()
            if (conditionMet) return observedAt <= deadline
            if (observedAt >= deadline) return false
            SystemClock.sleep(minOf(50L, deadline - observedAt))
        }
    }

    private fun String.section(start: String, end: String): String = substringAfter(start, "").substringBefore(end, "")
    private fun String.countOccurrences(value: String): Int = split(value).size - 1

    companion object {
        const val PRODUCT_PACKAGE = "com.remote.Manuscript.v2.debug"
        const val FIXTURE_PACKAGE = "com.remote.Manuscript.v2.fixture"
        const val SERVICE_CLASS = "com.echolink.mobile.ManuscriptAccessibilityService"
        const val SERVICE_COMPONENT = "$PRODUCT_PACKAGE/$SERVICE_CLASS"
    }
}
