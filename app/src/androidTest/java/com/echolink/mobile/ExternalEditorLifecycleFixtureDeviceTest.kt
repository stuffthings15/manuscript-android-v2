package com.echolink.mobile

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
    private val targetContext = instrumentation.targetContext
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
            withFixture(mode) { activity ->
                assertTrue(waitForDescription(triggerDescription, expected = true))
                tapDescription(triggerDescription)
                assertTrue(waitForDescription(panelCloseDescription, expected = true))
                tap(activity.window.decorView.findViewWithTag(
                    S23ExternalEditorFixtureActivity.LIFECYCLE_ACTION_TAG,
                ))
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
        val candidates = instrumentation.uiAutomation.windows.mapNotNull { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@mapNotNull null
            val root = window.root ?: return@mapNotNull null
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (
                focused == null ||
                !focused.isFocused ||
                !focused.isEditable ||
                focused.contentDescription?.toString() != expectedDescription ||
                focused.packageName?.toString() != root.packageName?.toString()
            ) {
                focused?.recycle()
                root.recycle()
                return@mapNotNull null
            }
            root to focused
        }
        if (candidates.size != 1) {
            candidates.forEach { (root, focused) ->
                focused.recycle()
                root.recycle()
            }
            return null
        }
        val (applicationRoot, focusedNode) = candidates.single()
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
        withFixture(S23ExternalEditorFixtureActivity.MODE_PACKAGE_SWITCH) { activity ->
            assertTrue(waitForDescription(triggerDescription, expected = true))
            if (panelOpen) {
                tapDescription(triggerDescription)
                assertTrue(waitForDescription(panelCloseDescription, expected = true))
                assertEquals(0, countDescription(triggerDescription))
            }
            val resumesBeforeSwitch = activity.resumeCount
            val disappearStartedAt = SystemClock.elapsedRealtime()
            val disappearDeadline = disappearStartedAt + SURFACE_DISAPPEAR_MS

            tap(activity.window.decorView.findViewWithTag(
                S23ExternalEditorFixtureActivity.LIFECYCLE_ACTION_TAG,
            ))

            assertTrue(waitUntilDeadline(disappearDeadline) { activePackage() == SETTINGS_PACKAGE })
            assertEquals(SETTINGS_PACKAGE, activePackage())
            assertTrue(
                waitUntilDeadline(disappearDeadline) {
                    countDescription(triggerDescription) == 0 &&
                        countDescription(panelCloseDescription) == 0
                },
            )
            assertTrue(SystemClock.elapsedRealtime() - disappearStartedAt <= SURFACE_DISAPPEAR_MS)
            assertTrue(waitUntil { activity.stopCount >= 1 })
            assertDescriptionCountStable(triggerDescription, expectedCount = 0)
            assertDescriptionCountStable(panelCloseDescription, expectedCount = 0)

            val returnStartedAt = SystemClock.elapsedRealtime()
            val returnDeadline = returnStartedAt + TRIGGER_RETURN_MS
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertTrue(waitUntilDeadline(returnDeadline) { activity.resumeCount > resumesBeforeSwitch })
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

    private fun withFixture(mode: String, block: (S23ExternalEditorFixtureActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.context, S23ExternalEditorFixtureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(S23ExternalEditorFixtureActivity.EXTRA_MODE, mode)
            },
        ) as S23ExternalEditorFixtureActivity
        try {
            instrumentation.waitForIdleSync()
            block(activity)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun tapDescription(description: String) {
        val node = findDescription(description)
            ?: error("Manuscript surface not found: $description")
        val bounds = Rect().also(node::getBoundsInScreen)
        node.recycle()
        tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun tap(view: View) {
        val location = IntArray(2).also(view::getLocationOnScreen)
        tap(location[0] + view.width / 2f, location[1] + view.height / 2f)
    }

    private fun tap(x: Float, y: Float) {
        val downAt = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0),
        )
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0),
        )
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
        instrumentation.uiAutomation.windows.sumOf { window ->
            val root = window.root ?: return@sumOf 0
            try {
                countDescription(root, description)
            } finally {
                root.recycle()
            }
        }

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
        instrumentation.uiAutomation.windows.forEach { window ->
            val root = window.root ?: return@forEach
            try {
                findDescription(root, description)?.let { return it }
            } finally {
                root.recycle()
            }
        }
        return null
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
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return ""
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

    private fun secureSetting(key: String): String = instrumentation.uiAutomation
        .executeShellCommand("settings get secure $key")
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
