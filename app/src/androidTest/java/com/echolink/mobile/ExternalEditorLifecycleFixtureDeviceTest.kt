package com.echolink.mobile

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalEditorLifecycleFixtureDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
    ).apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }
    private val targetContext = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName
    private val triggerDescription = targetContext.getString(R.string.external_assistant_open)
    private val panelCloseDescription = targetContext.getString(R.string.close_action)

    @Test
    fun everyFixtureModeObservesActualManuscriptTriggerAndPanelBehavior() {
        requireEnabledManuscriptService()

        withFixture(S23ExternalEditorFixtureActivity.MODE_EMPTY) {
            assertDescriptionNeverAppears(triggerDescription)
        }
        withFixture(S23ExternalEditorFixtureActivity.MODE_PASSWORD) {
            assertDescriptionNeverAppears(triggerDescription)
        }
        withFixture(S23ExternalEditorFixtureActivity.MODE_NORMAL) {
            assertTrue(waitForDescription(triggerDescription, expected = true))
        }
        withFixture(S23ExternalEditorFixtureActivity.MODE_MULTILINE) {
            assertTrue(waitForDescription(triggerDescription, expected = true))
            tapDescription(triggerDescription)
            assertTrue(waitForDescription(panelCloseDescription, expected = true))
            tapDescription(panelCloseDescription)
            assertTrue(waitForDescription(triggerDescription, expected = true))
        }
    }

    @Test
    fun detachedAndTargetLossModesRemoveTheActualManuscriptSurface() {
        requireEnabledManuscriptService()

        listOf(
            S23ExternalEditorFixtureActivity.MODE_DETACHED,
            S23ExternalEditorFixtureActivity.MODE_TARGET_LOSS,
        ).forEach { mode ->
            withFixture(mode) {
                assertTrue(waitForDescription(triggerDescription, expected = true))
                tapDescription(triggerDescription)
                assertTrue(waitForDescription(panelCloseDescription, expected = true))
                tapDescription(S23ExternalEditorFixtureActivity.lifecycleActionDescription(mode))
                assertDescriptionNeverAppears(panelCloseDescription, observationMs = 1_000L)
                assertDescriptionNeverAppears(triggerDescription, observationMs = 1_000L)
            }
        }
    }

    @Test
    fun triggerVisibleSettingsSwitchMeetsLifecycleTimings() {
        assertSettingsSwitch(panelOpen = false)
    }

    @Test
    fun panelOpenSettingsSwitchMeetsLifecycleTimings() {
        assertSettingsSwitch(panelOpen = true)
    }

    @Test
    fun duplicateViewIdRowsHaveDistinctStableStructuralLocators() {
        withFixture(S23ExternalEditorFixtureActivity.MODE_STRUCTURAL_FIELDS) {
            val firstDescription = S23ExternalEditorFixtureActivity.STRUCTURAL_FIELD_ONE_DESCRIPTION
            val secondDescription = S23ExternalEditorFixtureActivity.STRUCTURAL_FIELD_TWO_DESCRIPTION

            tapDescription(firstDescription)
            val first = waitForFocusedFieldProof(firstDescription)
            tapDescription(secondDescription)
            val second = waitForFocusedFieldProof(secondDescription)
            tapDescription(firstDescription)
            val firstAgain = waitForFocusedFieldProof(firstDescription)

            assertEquals(first.viewId, second.viewId)
            assertTrue(first.viewId.isNotBlank())
            assertNotEquals(first.locator, second.locator)
            assertEquals(first, firstAgain)
        }
    }

    private fun waitForFocusedFieldProof(expectedDescription: String): FocusedFieldProof {
        var proof: FocusedFieldProof? = null
        assertTrue(
            waitUntil {
                proof = focusedFieldProof(expectedDescription)
                proof != null
            },
        )
        return requireNotNull(proof)
    }

    private fun focusedFieldProof(expectedDescription: String): FocusedFieldProof? {
        val applicationRoot = uiAutomation.rootInActiveWindow ?: return null
        val focusedNode = applicationRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (
            focusedNode == null ||
            !focusedNode.isFocused ||
            !focusedNode.isEditable ||
            focusedNode.contentDescription?.toString() != expectedDescription ||
            focusedNode.packageName?.toString() != applicationRoot.packageName?.toString()
        ) {
            focusedNode?.recycle()
            applicationRoot.recycle()
            return null
        }
        return try {
            val viewId = focusedNode.viewIdResourceName?.takeIf(String::isNotBlank) ?: return null
            val locator = AccessibilityNodeStructuralLocator.from(applicationRoot, focusedNode)
                ?: return null
            FocusedFieldProof(viewId, locator)
        } finally {
            focusedNode.recycle()
            applicationRoot.recycle()
        }
    }

    private fun assertSettingsSwitch(panelOpen: Boolean) {
        requireEnabledManuscriptService()
        withFixture(S23ExternalEditorFixtureActivity.MODE_PACKAGE_SWITCH) {
            assertTrue(waitForDescription(triggerDescription, expected = true))
            if (panelOpen) {
                tapDescription(triggerDescription)
                assertTrue(waitForDescription(panelCloseDescription, expected = true))
                assertEquals(0, countDescription(triggerDescription))
            }
            val disappearStartedAt = SystemClock.elapsedRealtime()
            val disappearDeadline = disappearStartedAt + SURFACE_DISAPPEAR_MS

            tapDescription(
                S23ExternalEditorFixtureActivity.lifecycleActionDescription(
                    S23ExternalEditorFixtureActivity.MODE_PACKAGE_SWITCH,
                ),
            )

            assertTrue(waitUntilDeadline(disappearDeadline) { activePackage() == SETTINGS_PACKAGE })
            assertEquals(SETTINGS_PACKAGE, activePackage())
            assertTrue(
                waitUntilDeadline(disappearDeadline) {
                    countDescription(triggerDescription) == 0 &&
                        countDescription(panelCloseDescription) == 0
                },
            )
            assertTrue(SystemClock.elapsedRealtime() - disappearStartedAt <= SURFACE_DISAPPEAR_MS)
            assertDescriptionCountStable(triggerDescription, expectedCount = 0)
            assertDescriptionCountStable(panelCloseDescription, expectedCount = 0)

            val returnStartedAt = SystemClock.elapsedRealtime()
            val returnDeadline = returnStartedAt + TRIGGER_RETURN_MS
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertTrue(waitUntilDeadline(returnDeadline) { activePackage() == fixturePackage })
            assertTrue(
                waitUntilDeadline(returnDeadline) {
                    countDescription(triggerDescription) == 1
                },
            )
            assertTrue(SystemClock.elapsedRealtime() - returnStartedAt <= TRIGGER_RETURN_MS)
            assertEquals(0, countDescription(panelCloseDescription))
            assertDescriptionCountStable(triggerDescription, expectedCount = 1)
        }
    }

    private fun requireEnabledManuscriptService() {
        val expectedService =
            "${targetContext.packageName}/com.echolink.mobile.ManuscriptAccessibilityService"
        val enabled = secureSetting("enabled_accessibility_services")
            .split(':')
            .contains(expectedService)
        if (enabled) return
        val receipt = File(targetContext.filesDir, "test-evidence").apply { mkdirs() }
            .resolve("review-surface-service-prerequisite.txt")
            .apply {
                writeText(
                    "status=SKIPPED\n" +
                        "reason=Manuscript accessibility service is not enabled\n" +
                        "required=$expectedService\n",
                )
            }
        assumeTrue("Explicit prerequisite receipt: ${receipt.absolutePath}", false)
    }

    private fun withFixture(mode: String, block: () -> Unit) {
        instrumentation.context.startActivity(
            Intent(instrumentation.context, S23ExternalEditorFixtureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(S23ExternalEditorFixtureActivity.EXTRA_MODE, mode)
            },
        )
        try {
            assertTrue(
                "External fixture package did not become active.",
                waitUntil { activePackage() == fixturePackage },
            )
            block()
        } finally {
            shell("am force-stop $fixturePackage")
        }
    }

    private fun tapDescription(description: String) {
        val node = findDescription(description)
            ?: error("Manuscript surface not found: $description")
        val bounds = Rect().also(node::getBoundsInScreen)
        node.recycle()
        tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun tap(x: Float, y: Float) {
        val downAt = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val up = MotionEvent.obtain(
            downAt,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            x,
            y,
            0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            assertTrue(uiAutomation.injectInputEvent(down, true))
            assertTrue(uiAutomation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun waitForDescription(
        description: String,
        expected: Boolean,
        timeoutMs: Long = 5_000L,
    ): Boolean {
        waitUntil(timeoutMs) { hasDescription(description) == expected }
        return findDescription(description).let { node ->
            node?.recycle()
            node != null
        }
    }

    private fun hasDescription(description: String): Boolean =
        findDescription(description).let { node ->
            node?.recycle()
            node != null
        }

    private fun assertDescriptionNeverAppears(
        description: String,
        observationMs: Long = 5_000L,
    ) {
        assertDescriptionCountStable(description, expectedCount = 0, observationMs = observationMs)
    }

    private fun assertDescriptionCountStable(
        description: String,
        expectedCount: Int,
        observationMs: Long = 1_000L,
    ) {
        val deadline = SystemClock.elapsedRealtime() + observationMs
        do {
            assertEquals(expectedCount, countDescription(description))
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        assertEquals(expectedCount, countDescription(description))
    }

    private fun countDescription(description: String): Int =
        uiAutomation.windows.sumOf { window ->
            val root = window.root ?: return@sumOf 0
            try {
                countDescription(root, description)
            } finally {
                root.recycle()
            }
        }.takeIf { it > 0 } ?: uiAutomation.rootInActiveWindow?.let { root ->
            try {
                countDescription(root, description)
            } finally {
                root.recycle()
            }
        } ?: 0

    private fun countDescription(node: AccessibilityNodeInfo, description: String): Int {
        var count = if (node.contentDescription?.toString() == description) 1 else 0
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                count += countDescription(child, description)
            } finally {
                child.recycle()
            }
        }
        return count
    }

    private fun findDescription(description: String): AccessibilityNodeInfo? {
        uiAutomation.windows.forEach { window ->
            val root = window.root ?: return@forEach
            try { findDescription(root, description)?.let { return it } }
            finally { root.recycle() }
        }
        val root = uiAutomation.rootInActiveWindow ?: return null
        return try {
            findDescription(root, description)
        } finally {
            root.recycle()
        }
    }

    private fun findDescription(
        node: AccessibilityNodeInfo,
        description: String,
    ): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString() == description) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                findDescription(child, description)?.let { return it }
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun activePackage(): String {
        val root = uiAutomation.rootInActiveWindow ?: return ""
        return try {
            root.packageName?.toString().orEmpty()
        } finally {
            recycleDeviceNode(root)
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleDeviceNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }

    private fun secureSetting(key: String): String = uiAutomation
        .executeShellCommand("settings get secure $key")
        .use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor)
                .bufferedReader()
                .use { it.readText().trim() }
        }

    private fun shell(command: String): String = uiAutomation
        .executeShellCommand(command)
        .use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor)
                .bufferedReader()
                .use { it.readText().trim() }
        }

    private fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        return waitUntilDeadline(SystemClock.elapsedRealtime() + timeoutMs, condition)
    }

    private fun waitUntilDeadline(deadline: Long, condition: () -> Boolean): Boolean {
        do {
            if (condition()) return true
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return condition()
    }

    companion object {
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val SURFACE_DISAPPEAR_MS = 300L
        private const val TRIGGER_RETURN_MS = 500L
    }

    private data class FocusedFieldProof(
        val viewId: String,
        val locator: FieldStructuralLocator,
    )
}
