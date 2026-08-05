package com.echolink.mobile

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
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
    private val uiAutomation = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
    ).apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }
    private val targetContext = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName
    private val triggerDescription =
        targetContext.getString(R.string.external_assistant_open)
    private val lastPhysicalTapBounds = Rect()

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
        val modelReady = AiModelManager.get(targetContext).installedFile() != null

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
            if (modelReady) {
                assertTrue(
                    waitForDescription(
                        targetContext.getString(R.string.close_action),
                        expected = true,
                    ),
                )
                assertTrue(clickDescription(targetContext.getString(R.string.close_action)))
            } else {
                assertTrue(
                    "External trigger touch did not open model setup. " +
                        "bounds=$lastPhysicalTapBounds active=${activePackage()}",
                    waitUntil { activePackage() == targetContext.packageName },
                )
                assertTrue(
                    waitForLabel(
                        targetContext.getString(R.string.qwen_model_install_action),
                    ),
                )
            }
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

    private fun secureSetting(key: String): String =
        uiAutomation
            .executeShellCommand("settings get secure $key")
            .use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor)
                    .bufferedReader()
                    .use { it.readText().trim() }
            }

    private fun shell(command: String): String =
        uiAutomation
            .executeShellCommand(command)
            .use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor)
                    .bufferedReader()
                    .use { it.readText().trim() }
            }

    private fun activePackage(): String {
        val root = uiAutomation.rootInActiveWindow ?: return ""
        return try {
            root.packageName?.toString().orEmpty()
        } finally {
            root.recycle()
        }
    }

    private fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (condition()) return true
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return condition()
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

    private fun waitForLabel(label: String, timeoutMs: Long = 5_000L): Boolean =
        waitUntil(timeoutMs) {
            findNode { node ->
                node.text?.toString()?.equals(label, ignoreCase = true) == true ||
                    node.contentDescription?.toString()?.equals(label, ignoreCase = true) == true
            }.let { node ->
                node?.recycle()
                node != null
            }
        }

    private fun clickDescription(description: String): Boolean {
        val node = findDescription(description) ?: return false
        val bounds = Rect().also(node::getBoundsInScreen)
        lastPhysicalTapBounds.set(bounds)
        node.recycle()
        if (bounds.isEmpty) return false
        shell("input tap ${bounds.exactCenterX()} ${bounds.exactCenterY()}")
        return true
    }

    private fun findDescription(description: String): AccessibilityNodeInfo? {
        return findNode { node -> node.contentDescription?.toString() == description }
    }

    private fun findNode(
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        uiAutomation.windows.forEach { window ->
            val root = window.root ?: return@forEach
            try { findNode(root, predicate)?.let { return it } }
            finally { root.recycle() }
        }
        val root = uiAutomation.rootInActiveWindow ?: return null
        return try {
            findNode(root, predicate)
        } finally {
            root.recycle()
        }
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(node)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                findNode(child, predicate)?.let { return it }
            } finally {
                child.recycle()
            }
        }
        return null
    }
}
