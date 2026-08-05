package com.echolink.mobile.fixture

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
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
    protected val renderedReturnContract by lazy {
        FixtureRenderedReturnContract.select(
            hardware = Build.HARDWARE,
            requestedProfile = InstrumentationRegistry.getArguments().getString(
                FixtureRenderedReturnContract.PROFILE_ARGUMENT,
            ),
        )
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val accessibility = shell("dumpsys accessibility")
            assertFalse(
                "The platform could not report accessibility health.\n$accessibility",
                accessibility.contains("Exception occurred while dumping"),
            )
            val bound = accessibility.section("Bound services", "Crashed services")
            val crashed = accessibility.section("Crashed services", "Client list info")
            assertTrue("Expected exactly one bound accessibility service.\n$accessibility", bound.countOccurrences("Service[") == 1)
            assertTrue("A Manuscript service is not bound.\n$accessibility", bound.contains(SERVICE_CLASS))
            assertTrue("Any crashed service invalidates V2 proof.\n$accessibility", crashed.trim().removePrefix(":") == "{}")
        }
        val service = shell("dumpsys activity services $SERVICE_COMPONENT")
        assertTrue("Exact V2 service record is absent.\n$service", service.contains("ServiceRecord"))
        assertTrue("Exact V2 service is not bound.\n$service", service.contains("hasBound=true"))
        assertTrue("Exact V2 component mismatch.\n$service", service.contains(SERVICE_COMPONENT))
        assertFalse("A dead V2 binding invalidates proof.\n$service", service.contains(" DEAD "))
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
        FixtureFinallyCleanup.run(body = {
            assertTrue("Fixture application window did not become active.", waitUntil { activePackage() == fixturePackage })
            val editorDescription = S23ExternalEditorFixtureActivity.editorDescription(mode)
            assertTrue(
                "Fixture root/editor was not ready: $editorDescription",
                waitUntil { findFixtureDescription(editorDescription).let { node -> node?.recycle(); node != null } },
            )
            block()
        }, cleanup = {
            if (activePackage() != fixturePackage) {
                pressBack()
                waitUntil { activePackage() == fixturePackage }
            }
            findProductSurface(manuscriptString("close_action"))?.let { node ->
                node.recycle()
                tapProductSurface(manuscriptString("close_action"))
            }
            finishFixtureWithUserInput()
            var clean = v2OverlayClearsWithinGateAndStaysAbsent()
            if (!clean) {
                pressBack()
                clean = v2OverlayClearsWithinGateAndStaysAbsent()
            }
            assertTrue(
                "Fixture cleanup left a V2 overlay after the ${renderedReturnContract.profile.reportName} " +
                    "${renderedReturnContract.removalCeilingMs}ms removal gate.",
                clean,
            )
        })
    }

    private fun clearFixtureAndV2Surfaces() {
        finishFixtureWithUserInput()
        if (hasV2OverlaySurface()) {
            findProductSurface(manuscriptString("close_action"))?.let { node ->
                node.recycle()
                tapProductSurface(manuscriptString("close_action"))
            } ?: pressBack()
        }
        assumeTrue(
            "STALE_V2_OVERLAY: V2 overlay missed the 300ms removal gate or reappeared during the 750ms stable interval.",
            v2OverlayClearsWithinGateAndStaysAbsent(),
        )
    }

    private fun finishFixtureWithUserInput() {
        if (activePackage() != fixturePackage) return
        pressBack()
        if (waitUntil(500L) { activePackage() != fixturePackage }) return
        if (findFixtureDescription(S23ExternalEditorFixtureActivity.FINISH_DESCRIPTION) == null) return
        tapFixtureDescription(S23ExternalEditorFixtureActivity.FINISH_DESCRIPTION)
        assertTrue(
            "Fixture did not leave after Back and a real Finish tap.",
            waitUntil { activePackage() != fixturePackage },
        )
    }

    protected fun pressBack() {
        val started = SystemClock.uptimeMillis()
        val down = KeyEvent(
            started,
            started,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_BACK,
            0,
            0,
            KeyEvent.KEYCODE_UNKNOWN,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD,
        )
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        assertTrue(automation.injectInputEvent(down, true))
        assertTrue(automation.injectInputEvent(up, true))
    }

    protected fun tapFixtureDescription(description: String): Long {
        var node: AccessibilityNodeInfo? = null
        assertTrue(
            "Missing fixture node after bounded acquisition: $description",
            waitUntil { node = findFixtureDescription(description); node != null },
        )
        return tapNode(requireNotNull(node))
    }

    protected fun tapProductSurface(description: String) = tapNode(
        requireNotNull(findProductSurface(description)) { "Missing V2 overlay node: $description" },
    )

    private fun tapNode(node: AccessibilityNodeInfo): Long {
        val bounds = Rect().also(node::getBoundsInScreen)
        node.recycle()
        assertFalse("Empty bounds", bounds.isEmpty)
        val started = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(started, started, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(started, started + 50L, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val injectedAt = SystemClock.elapsedRealtime()
        try {
            assertTrue(automation.injectInputEvent(down, true))
            SystemClock.sleep(50L)
            assertTrue(automation.injectInputEvent(up, true))
        } finally { down.recycle(); up.recycle() }
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

    protected fun expectedRenderedTriggerRegion(editorDescription: String): Rect {
        val editorBounds = fixtureBounds(editorDescription)
        val metrics = fixtureContext.resources.displayMetrics
        val size = fixtureDp(48)
        val x = (editorBounds.right - size).coerceIn(0, maxOf(0, metrics.widthPixels - size))
        val preferredY = if (editorBounds.top >= size + fixtureDp(8)) {
            editorBounds.top - size - fixtureDp(4)
        } else {
            editorBounds.bottom + fixtureDp(4)
        }
        val y = preferredY.coerceIn(0, maxOf(0, metrics.heightPixels - size))
        return Rect(x, y, x + size, y + size)
    }

    protected fun fixtureReturnReferenceRegion(editorDescription: String): Rect {
        val editorBounds = fixtureBounds(editorDescription)
        val triggerBounds = expectedRenderedTriggerRegion(editorDescription)
        return Rect(
            editorBounds.left,
            triggerBounds.top,
            triggerBounds.left - fixtureDp(8),
            triggerBounds.bottom,
        ).also { check(!it.isEmpty) { "Fixture reference region is empty: $it" } }
    }

    protected fun captureFixtureReturnReference(region: Rect): Bitmap {
        val screenshot = requireNotNull(automation.takeScreenshot()) { "Fixture reference screenshot is unavailable." }
        return try {
            check(region.left >= 0 && region.top >= 0 && region.right <= screenshot.width && region.bottom <= screenshot.height) {
                "Fixture reference region $region exceeds ${screenshot.width}x${screenshot.height}."
            }
            Bitmap.createBitmap(screenshot, region.left, region.top, region.width(), region.height())
        } finally {
            screenshot.recycle()
        }
    }

    protected fun fixtureBounds(description: String): Rect {
        val node = requireNotNull(findFixtureDescription(description)) { "Missing fixture bounds: $description" }
        return Rect().also(node::getBoundsInScreen).also { node.recycle() }
    }

    protected fun productSurfaceBounds(description: String): Rect {
        val node = requireNotNull(findProductSurface(description)) { "Missing V2 bounds: $description" }
        return Rect().also(node::getBoundsInScreen).also { node.recycle() }
    }

    protected fun recordRenderedTriggerReturn(
        name: String,
        triggerRegion: Rect,
        fixtureReference: Bitmap,
        fixtureReferenceRegion: Rect,
        action: () -> Long,
    ): Pair<Long, FixtureRenderedVideoProbe.Observation> {
        val directory = requireNotNull(fixtureContext.getExternalFilesDir(null)) {
            "Fixture external-files directory is unavailable."
        }
        val video = File(directory, "round5-$name.mp4")
        if (video.exists()) check(video.delete()) { "Could not replace generated fixture recording: ${video.absolutePath}" }
        val recordingRequestedAt = SystemClock.elapsedRealtime()
        val descriptor = automation.executeShellCommand(
            "screenrecord --time-limit ${renderedReturnContract.recordingDurationSeconds} " +
                "--bit-rate 4000000 ${video.absolutePath}",
        )
        assertTrue("screenrecord did not start for $name", waitUntil(1_000L) { shell("pidof screenrecord").isNotBlank() })
        SystemClock.sleep(250L)
        val injectedAt = action()
        FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        descriptor.close()
        assertTrue("screenrecord produced no fixture video for $name", video.isFile && video.length() > 0L)
        shell("chmod 0644 ${video.absolutePath}")
        val ignoreBeforeMs = (injectedAt - recordingRequestedAt - 100L).coerceAtLeast(100L)
        return injectedAt to FixtureRenderedVideoProbe.analyze(
            path = video.absolutePath,
            triggerRegion = triggerRegion,
            fixtureReference = fixtureReference,
            fixtureReferenceRegion = fixtureReferenceRegion,
            ignoreBeforeMs = ignoreBeforeMs,
            contract = renderedReturnContract,
        )
    }

    protected fun recordAccessibilityTriggerReturn(
        transitionCondition: () -> Boolean,
        triggerDescription: String,
        action: () -> Long,
    ): AccessibilityTriggerReturnObservation {
        check(Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            "Accessibility-window timing is reserved for pre-P devices."
        }
        val actionStartedAt = action()
        var transitionAt: Long? = null
        val transitionObserved = waitUntil {
            if (!transitionCondition()) return@waitUntil false
            transitionAt = SystemClock.elapsedRealtime()
            true
        }
        if (!transitionObserved) {
            return AccessibilityTriggerReturnObservation(
                actionStartedAtMs = actionStartedAt,
                transitionAtMs = null,
                triggerAtMs = null,
                ceilingMs = renderedReturnContract.ceilingMs,
            )
        }
        var triggerAt: Long? = null
        waitUntilDeadline(requireNotNull(transitionAt) + renderedReturnContract.ceilingMs) {
            val node = findProductSurface(triggerDescription) ?: return@waitUntilDeadline false
            node.recycle()
            triggerAt = SystemClock.elapsedRealtime()
            true
        }
        return AccessibilityTriggerReturnObservation(
            actionStartedAtMs = actionStartedAt,
            transitionAtMs = transitionAt,
            triggerAtMs = triggerAt,
            ceilingMs = renderedReturnContract.ceilingMs,
        )
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

    protected fun v2OverlayClearsWithinGateAndStaysAbsent(): Boolean =
        FixtureCleanupTiming.awaitRemovalThenStableAbsence(
            isOverlayPresent = ::hasV2OverlaySurface,
            nowMs = SystemClock::elapsedRealtime,
            pauseMs = SystemClock::sleep,
            removalGateMs = renderedReturnContract.removalCeilingMs,
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
        return FixtureDeadlineTiming.awaitCondition(
            deadlineMs = deadline,
            condition = condition,
            nowMs = SystemClock::elapsedRealtime,
            pauseMs = SystemClock::sleep,
        )
    }

    private fun fixtureDp(value: Int): Int =
        (value * fixtureContext.resources.displayMetrics.density).toInt()

    private fun String.section(start: String, end: String): String = substringAfter(start, "").substringBefore(end, "")
    private fun String.countOccurrences(value: String): Int = split(value).size - 1

    companion object {
        const val PRODUCT_PACKAGE = "com.remote.Manuscript.v2.debug"
        const val FIXTURE_PACKAGE = "com.remote.Manuscript.v2.fixture"
        const val SERVICE_CLASS = "com.echolink.mobile.ManuscriptAccessibilityService"
        const val SERVICE_COMPONENT = "$PRODUCT_PACKAGE/$SERVICE_CLASS"
    }

    protected data class AccessibilityTriggerReturnObservation(
        val actionStartedAtMs: Long,
        val transitionAtMs: Long?,
        val triggerAtMs: Long?,
        val ceilingMs: Long,
    ) {
        val triggerDelayMs: Long?
            get() = if (transitionAtMs == null || triggerAtMs == null) null else triggerAtMs - transitionAtMs

        val matched: Boolean
            get() = triggerDelayMs?.let { it in 0L..ceilingMs } == true
    }
}
