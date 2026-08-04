package com.echolink.mobile

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            assertFalse(waitForDescription(triggerDescription, expected = false))
        }
        withFixture(S23ExternalEditorFixtureActivity.MODE_PASSWORD) {
            assertFalse(waitForDescription(triggerDescription, expected = false))
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
                tap(activity.window.decorView.findViewWithTag(
                    S23ExternalEditorFixtureActivity.LIFECYCLE_ACTION_TAG,
                ))
                assertFalse(waitForDescription(triggerDescription, expected = false))
                assertFalse(waitForDescription(panelCloseDescription, expected = false))
            }
        }
    }

    @Test
    fun packageSwitchUsesSettingsAndFixtureStopsThenResumesWithOneTrigger() {
        requireEnabledManuscriptService()
        withFixture(S23ExternalEditorFixtureActivity.MODE_PACKAGE_SWITCH) { activity ->
            assertTrue(waitForDescription(triggerDescription, expected = true))
            val resumesBeforeSwitch = activity.resumeCount

            tap(activity.window.decorView.findViewWithTag(
                S23ExternalEditorFixtureActivity.LIFECYCLE_ACTION_TAG,
            ))

            assertTrue(waitUntil { activePackage() == SETTINGS_PACKAGE })
            assertEquals(SETTINGS_PACKAGE, activePackage())
            assertTrue(waitUntil { activity.stopCount >= 1 })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertTrue(waitUntil { activity.resumeCount > resumesBeforeSwitch })
            assertTrue(waitForDescription(triggerDescription, expected = true))
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

    private fun activePackage(): String = instrumentation.uiAutomation.rootInActiveWindow
        ?.packageName?.toString().orEmpty()

    private fun secureSetting(key: String): String = instrumentation.uiAutomation
        .executeShellCommand("settings get secure $key")
        .use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor)
                .bufferedReader()
                .use { it.readText().trim() }
        }

    private fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (condition()) return true
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return condition()
    }

    companion object {
        private const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
