package com.echolink.mobile

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class S23ProductContractDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val triggerDescription =
        targetContext.getString(R.string.external_assistant_open)

    @Test
    fun generatedFieldsFollowTheLockedTriggerAndKeyboardContract() {
        val expectedService =
            "${targetContext.packageName}/com.echolink.mobile.ManuscriptAccessibilityService"
        assertTrue(
            "Enable Manuscript in Installed apps before running S23 acceptance.",
            secureSetting("enabled_accessibility_services")
                .split(':')
                .contains(expectedService),
        )
        val keyboardBefore = secureSetting("default_input_method")

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
            assertTrue(clickDescription(triggerDescription))
            assertTrue(
                waitForDescription(
                    targetContext.getString(R.string.close_action),
                    expected = true,
                ),
            )
            assertTrue(clickDescription(targetContext.getString(R.string.close_action)))
        }
        withFixture(S23ExternalEditorFixtureActivity.MODE_TRANSIENT) {
            assertTrue(waitForDescription(triggerDescription, expected = true))
            SystemClock.sleep(
                S23ExternalEditorFixtureActivity.TRANSIENT_CLEAR_DELAY_MS + 500L,
            )
            assertFalse(waitForDescription(triggerDescription, expected = false))
        }

        assertEquals(keyboardBefore, secureSetting("default_input_method"))
    }

    private fun withFixture(mode: String, block: (Activity) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(
                instrumentation.context,
                S23ExternalEditorFixtureActivity::class.java,
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(S23ExternalEditorFixtureActivity.EXTRA_MODE, mode)
            },
        )
        try {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500L)
            block(activity)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun secureSetting(key: String): String =
        instrumentation.uiAutomation
            .executeShellCommand("settings get secure $key")
            .use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor)
                    .bufferedReader()
                    .use { it.readText().trim() }
            }

    private fun waitForDescription(
        description: String,
        expected: Boolean,
        timeoutMs: Long = 5_000L,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            val found = findDescription(description) != null
            if (found == expected) return found
            SystemClock.sleep(100L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return findDescription(description) != null
    }

    private fun clickDescription(description: String): Boolean {
        val node = findDescription(description) ?: return false
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            node.recycle()
        }
    }

    private fun findDescription(description: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.windows.forEach { window ->
            val root = window.root ?: return@forEach
            findDescription(root, description)?.let { return it }
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
}
