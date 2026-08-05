package com.echolink.mobile.fixture

import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalEditorLifecycleFixtureDeviceTest : FixtureDeviceSupport() {
    private val trigger get() = manuscriptString("external_assistant_open")
    private val close get() = manuscriptString("close_action")

    @After fun manuscriptServiceRemainsBound() = requireHealthyManuscriptService()

    @Test fun everyFixtureModeObservesActualManuscriptTriggerAndPanelBehavior() {
        requireHealthyManuscriptService()
        withFixture(S23ExternalEditorFixtureActivity.MODE_EMPTY) { descriptionNeverVisible(trigger) }
        withFixture(S23ExternalEditorFixtureActivity.MODE_PASSWORD) { descriptionNeverVisible(trigger) }
        withFixture(S23ExternalEditorFixtureActivity.MODE_NORMAL) { assertTrue(descriptionVisible(trigger, true)) }
        withFixture(S23ExternalEditorFixtureActivity.MODE_MULTILINE) {
            assertTrue(descriptionVisible(trigger, true))
            tapDescription(trigger)
            assertTrue(descriptionVisible(close, true))
            tapDescription(close)
            assertTrue(descriptionVisible(trigger, true))
        }
    }

    @Test fun detachedAndTargetLossModesRemoveTheActualManuscriptSurface() {
        requireHealthyManuscriptService()
        listOf(S23ExternalEditorFixtureActivity.MODE_DETACHED, S23ExternalEditorFixtureActivity.MODE_TARGET_LOSS).forEach { mode ->
            withFixture(mode) {
                assertTrue(descriptionVisible(trigger, true))
                tapDescription(trigger)
                assertTrue(descriptionVisible(close, true))
                tapDescription(S23ExternalEditorFixtureActivity.actionDescription(mode))
                descriptionNeverVisible(close)
                descriptionNeverVisible(trigger)
            }
        }
    }

    @Test fun triggerVisibleSettingsSwitchMeetsLifecycleTimings() = assertSettingsSwitch(false)
    @Test fun panelOpenSettingsSwitchMeetsLifecycleTimings() = assertSettingsSwitch(true)

    @Test fun duplicateViewIdRowsHaveDistinctStableStructuralLocators() {
        requireHealthyManuscriptService()
        withFixture(S23ExternalEditorFixtureActivity.MODE_STRUCTURAL_FIELDS) {
            tapDescription(S23ExternalEditorFixtureActivity.STRUCTURAL_FIELD_ONE_DESCRIPTION)
            val first = focusedFieldProof(S23ExternalEditorFixtureActivity.STRUCTURAL_FIELD_ONE_DESCRIPTION)
            tapDescription(S23ExternalEditorFixtureActivity.STRUCTURAL_FIELD_TWO_DESCRIPTION)
            val second = focusedFieldProof(S23ExternalEditorFixtureActivity.STRUCTURAL_FIELD_TWO_DESCRIPTION)
            tapDescription(S23ExternalEditorFixtureActivity.STRUCTURAL_FIELD_ONE_DESCRIPTION)
            val firstAgain = focusedFieldProof(S23ExternalEditorFixtureActivity.STRUCTURAL_FIELD_ONE_DESCRIPTION)
            assertEquals(first.viewId, second.viewId)
            assertTrue(first.viewId.isNotBlank())
            assertNotEquals(first.locator, second.locator)
            assertEquals(first, firstAgain)
        }
    }

    private fun assertSettingsSwitch(panelOpen: Boolean) {
        requireHealthyManuscriptService()
        withFixture(S23ExternalEditorFixtureActivity.MODE_PACKAGE_SWITCH) {
            assertTrue(descriptionVisible(trigger, true))
            if (panelOpen) { tapDescription(trigger); assertTrue(descriptionVisible(close, true)) }
            val departedAt = SystemClock.elapsedRealtime()
            tapDescription(S23ExternalEditorFixtureActivity.actionDescription(S23ExternalEditorFixtureActivity.MODE_PACKAGE_SWITCH))
            assertTrue(waitUntil(300L) { activePackage() == "com.android.settings" })
            assertTrue(waitUntil(300L) { !descriptionVisible(trigger, false, 0L) && !descriptionVisible(close, false, 0L) })
            assertTrue(SystemClock.elapsedRealtime() - departedAt <= 300L)
            val returnedAt = SystemClock.elapsedRealtime()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertTrue(waitUntil(500L) { activePackage() == fixturePackage })
            assertTrue(descriptionVisible(trigger, true, 500L))
            assertTrue(SystemClock.elapsedRealtime() - returnedAt <= 500L)
            descriptionNeverVisible(close, 250L)
        }
    }

    private fun focusedFieldProof(description: String): FocusedFieldProof {
        var proof: FocusedFieldProof? = null
        assertTrue(waitUntil { proof = applicationFocusedFieldProof(description); proof != null })
        return requireNotNull(proof)
    }

    private fun applicationFocusedFieldProof(description: String): FocusedFieldProof? {
        val root = fixtureApplicationRoot() ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null || focused.contentDescription?.toString() != description || !focused.isFocused || !focused.isEditable) {
            focused?.recycle(); root.recycle(); return null
        }
        return try {
            val viewId = focused.viewIdResourceName?.takeIf(String::isNotBlank) ?: return null
            val locator = productStructuralLocator(root, focused) ?: return null
            FocusedFieldProof(viewId, locator)
        } finally {
            focused.recycle(); root.recycle()
        }
    }

    private data class FocusedFieldProof(val viewId: String, val locator: String)
}
