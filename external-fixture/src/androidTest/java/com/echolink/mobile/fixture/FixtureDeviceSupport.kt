package com.echolink.mobile.fixture

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
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
        assertTrue("Exact Manuscript V2 component is not enabled.", secureSetting("enabled_accessibility_services").split(':').contains(SERVICE_COMPONENT))
        assertTrue("Global accessibility is off.", secureSetting("accessibility_enabled") == "1")
        val dump = shell("dumpsys accessibility")
        val bound = dump.section("Bound services", "Crashed services")
        val crashed = dump.section("Crashed services", "Enabled services")
        assertTrue("Manuscript service is not bound.\n$dump", bound.contains(SERVICE_CLASS))
        assertFalse("Manuscript service crashed.\n$dump", crashed.contains(SERVICE_CLASS))
    }

    protected fun assertInstalledCorrectionModel() {
        val path = shell("run-as $PRODUCT_PACKAGE ls no_backup/qwen3_models")
        assertTrue("Installed local model path is required for the product contract run.", path.contains(".gguf"))
    }

    protected fun withFixture(mode: String, block: () -> Unit) {
        fixtureContext.startActivity(Intent(fixtureContext, S23ExternalEditorFixtureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(S23ExternalEditorFixtureActivity.EXTRA_MODE, mode)
        })
        try {
            assertTrue("Fixture application window did not become active.", waitUntil { activePackage() == fixturePackage })
            block()
        } finally {
            if (activePackage() == fixturePackage && findDescription(S23ExternalEditorFixtureActivity.FINISH_DESCRIPTION) != null) tapDescription(S23ExternalEditorFixtureActivity.FINISH_DESCRIPTION)
        }
    }

    protected fun tapDescription(description: String) {
        val node = requireNotNull(findDescription(description)) { "Missing node: $description" }
        val bounds = Rect().also(node::getBoundsInScreen)
        node.recycle()
        assertFalse("Empty bounds for $description", bounds.isEmpty)
        val started = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(started, started, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(started, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue(automation.injectInputEvent(down, true)); assertTrue(automation.injectInputEvent(up, true)) } finally { down.recycle(); up.recycle() }
    }

    protected fun findDescription(description: String): AccessibilityNodeInfo? = findNode { it.contentDescription?.toString() == description }
    protected fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        automation.windows.forEach { window ->
            val root = window.root ?: return@forEach
            try { findNode(root, predicate)?.let { return it } } finally { root.recycle() }
        }
        return null
    }
    private fun findNode(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { findNode(child, predicate)?.let { return it } } finally { child.recycle() }
        }
        return null
    }

    protected fun descriptionVisible(description: String, expected: Boolean, timeoutMs: Long = 5_000L): Boolean {
        waitUntil(timeoutMs) { (findDescription(description) != null) == expected }
        return findDescription(description).let { node -> node?.recycle(); node != null }
    }
    protected fun descriptionNeverVisible(description: String, durationMs: Long = 1_000L) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        do { assertFalse("Unexpected surface: $description", findDescription(description) != null); SystemClock.sleep(50L) } while (SystemClock.elapsedRealtime() < deadline)
    }
    protected fun activePackage(): String = automation.rootInActiveWindow?.let { root -> try { root.packageName?.toString().orEmpty() } finally { root.recycle() } }.orEmpty()
    protected fun fixtureApplicationRoot(): AccessibilityNodeInfo? = automation.windows.firstNotNullOfOrNull { window ->
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@firstNotNullOfOrNull null
        val root = window.root ?: return@firstNotNullOfOrNull null
        if (root.packageName?.toString() == fixturePackage) root else { root.recycle(); null }
    }
    protected fun productStructuralLocator(root: AccessibilityNodeInfo, focused: AccessibilityNodeInfo): String? {
        val product = fixtureContext.createPackageContext(PRODUCT_PACKAGE, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val locatorClass = product.classLoader.loadClass("com.echolink.mobile.AccessibilityNodeStructuralLocator")
        val locatorObject = locatorClass.getField("INSTANCE").get(null)
        val locator = locatorClass.getMethod("from", AccessibilityNodeInfo::class.java, AccessibilityNodeInfo::class.java).invoke(locatorObject, root, focused) ?: return null
        return locator.javaClass.getMethod("encoded").invoke(locator) as String
    }
    protected fun secureSetting(key: String): String = shell("settings get secure $key")
    protected fun shell(command: String): String = automation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() } }
    protected fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do { if (condition()) return true; SystemClock.sleep(50L) } while (SystemClock.elapsedRealtime() < deadline)
        return condition()
    }
    private fun String.section(start: String, end: String): String = substringAfter(start, "").substringBefore(end, "")
    companion object {
        const val PRODUCT_PACKAGE = "com.remote.Manuscript.v2.debug"
        const val FIXTURE_PACKAGE = "com.remote.Manuscript.v2.fixture"
        const val SERVICE_CLASS = "com.echolink.mobile.ManuscriptAccessibilityService"
        const val SERVICE_COMPONENT = "$PRODUCT_PACKAGE/$SERVICE_CLASS"
    }
}
