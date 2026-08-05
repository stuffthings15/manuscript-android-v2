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
        withFixture(S23ExternalEditorFixtureActivity.MODE_EMPTY) { productSurfaceNeverVisible(trigger) }
        withFixture(S23ExternalEditorFixtureActivity.MODE_PASSWORD) { productSurfaceNeverVisible(trigger) }
        withFixture(S23ExternalEditorFixtureActivity.MODE_NORMAL) { assertTrue(productSurfaceMeetsExpectation(trigger, true)) }
        withFixture(S23ExternalEditorFixtureActivity.MODE_MULTILINE) {
            assertTrue(productSurfaceMeetsExpectation(trigger, true))
            val triggerRegion = expectedRenderedTriggerRegion(
                S23ExternalEditorFixtureActivity.editorDescription(S23ExternalEditorFixtureActivity.MODE_MULTILINE),
            )
            tapProductSurface(trigger)
            assertTrue(productSurfaceMeetsExpectation(close, true))
            val closeRegion = productSurfaceBounds(close)
            var closeLookupStartedAt = 0L
            val (closeInjectedAt, rendered) = recordRenderedTriggerReturn(
                name = "close",
                triggerRegion = triggerRegion,
                transitionRegion = closeRegion,
            ) {
                closeLookupStartedAt = SystemClock.elapsedRealtime()
                tapProductSurface(close)
            }
            val lookupMs = closeInjectedAt - closeLookupStartedAt
            val exactIdentity = productSurfaceMeetsExpectation(trigger, true)
            assertTrue(
                "Recorded V2 trigger missed 500ms after visible panel disappearance; lookup=${lookupMs}ms " +
                    "transitionAt=${rendered.transitionAtMs}ms triggerAt=${rendered.triggerAtMs}ms " +
                    "triggerDelay=${rendered.triggerDelayMs}ms encodedFrames=${rendered.encodedFrameCount}",
                rendered.matched,
            )
            assertTrue("Rendered trigger lacked eventual exact V2 overlay/contentDescription identity.", exactIdentity)
            println(
                "FIXTURE_CLOSE_TIMING lookupMs=$lookupMs transitionAtMs=${rendered.transitionAtMs} " +
                    "triggerAtMs=${rendered.triggerAtMs} triggerDelayMs=${rendered.triggerDelayMs} " +
                    "encodedFrames=${rendered.encodedFrameCount} exactIdentity=$exactIdentity",
            )
        }
    }

    @Test fun detachedAndTargetLossModesRemoveTheActualManuscriptSurface() {
        requireHealthyManuscriptService()
        listOf(S23ExternalEditorFixtureActivity.MODE_DETACHED, S23ExternalEditorFixtureActivity.MODE_TARGET_LOSS).forEach { mode ->
            withFixture(mode) {
                assertTrue(productSurfaceMeetsExpectation(trigger, true))
                tapProductSurface(trigger)
                assertTrue(productSurfaceMeetsExpectation(close, true))
                tapFixtureDescription(S23ExternalEditorFixtureActivity.actionDescription(mode))
                productSurfaceNeverVisible(close)
                productSurfaceNeverVisible(trigger)
            }
        }
    }

    @Test fun triggerVisibleSettingsSwitchMeetsLifecycleTimings() = assertSettingsSwitch(false)
    @Test fun panelOpenSettingsSwitchMeetsLifecycleTimings() = assertSettingsSwitch(true)

    @Test fun duplicateViewIdRowsHaveDistinctStableStructuralLocators() {
        requireHealthyManuscriptService()
        withFixture(S23ExternalEditorFixtureActivity.MODE_STRUCTURAL_FIELDS) {
            tapFixtureDescription(S23ExternalEditorFixtureActivity.STRUCTURAL_FIELD_ONE_DESCRIPTION)
            val first = focusedFieldProof(S23ExternalEditorFixtureActivity.STRUCTURAL_FIELD_ONE_DESCRIPTION)
            tapFixtureDescription(
                S23ExternalEditorFixtureActivity.actionDescription(
                    S23ExternalEditorFixtureActivity.MODE_STRUCTURAL_FIELDS,
                ),
            )
            val second = focusedFieldProof(S23ExternalEditorFixtureActivity.STRUCTURAL_FIELD_TWO_DESCRIPTION)
            tapFixtureDescription(S23ExternalEditorFixtureActivity.STRUCTURAL_FIELD_ONE_DESCRIPTION)
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
            assertTrue(productSurfaceMeetsExpectation(trigger, true))
            val triggerRegion = expectedRenderedTriggerRegion(
                S23ExternalEditorFixtureActivity.editorDescription(S23ExternalEditorFixtureActivity.MODE_PACKAGE_SWITCH),
            )
            val fixtureTransitionRegion = fixtureBounds(
                S23ExternalEditorFixtureActivity.editorDescription(S23ExternalEditorFixtureActivity.MODE_PACKAGE_SWITCH),
            )
            if (panelOpen) { tapProductSurface(trigger); assertTrue(productSurfaceMeetsExpectation(close, true)) }
            val settingsLaunchStartedAt = SystemClock.elapsedRealtime()
            tapFixtureDescription(S23ExternalEditorFixtureActivity.actionDescription(S23ExternalEditorFixtureActivity.MODE_PACKAGE_SWITCH))
            val settingsActivated = waitUntil { applicationWindowPresent("com.android.settings") }
            val settingsActivatedAt = SystemClock.elapsedRealtime()
            val settingsActivationMs = settingsActivatedAt - settingsLaunchStartedAt
            assertTrue("Settings did not become active; elapsed=${settingsActivationMs}ms", settingsActivated)
            val disappearDeadline = settingsActivatedAt + 300L
            val departureTrace = FixtureSettingsDepartureDiagnostics(
                settingsActivatedAtMs = settingsActivatedAt,
                deadlineMs = disappearDeadline,
                output = ::println,
            )
            departureTrace.pollStarted(SystemClock.elapsedRealtime())
            var surfacePoll = 0
            val surfaceRemoved = waitUntilDeadline(disappearDeadline) {
                val pollStartedAt = SystemClock.elapsedRealtime()
                val absent = !hasV2OverlaySurface()
                val pollCompletedAt = SystemClock.elapsedRealtime()
                departureTrace.queryCompleted(
                    index = surfacePoll++,
                    startedAtMs = pollStartedAt,
                    endedAtMs = pollCompletedAt,
                    absent = absent,
                )
                absent
            }
            val conditionReturnedAt = SystemClock.elapsedRealtime()
            departureTrace.conditionReturned(conditionReturnedAt, surfaceRemoved)
            val disappearedMs = conditionReturnedAt - settingsActivatedAt
            departureTrace.assertionStarted(SystemClock.elapsedRealtime())
            try {
                assertTrue("V2 surface removal missed the total 300ms gate; elapsed=${disappearedMs}ms", surfaceRemoved)
                assertTrue(disappearedMs <= 300L)
            } finally {
                departureTrace.assertionCompleted(SystemClock.elapsedRealtime())
                departureTrace.flush()
            }
            val (navigationStartedAt, rendered) = recordRenderedTriggerReturn(
                name = "settings-${if (panelOpen) "panel" else "trigger"}",
                triggerRegion = triggerRegion,
                transitionRegion = fixtureTransitionRegion,
            ) {
                val startedAt = SystemClock.elapsedRealtime()
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                startedAt
            }
            val fixtureReturned = waitUntil { applicationWindowPresent(fixturePackage) }
            val fixtureReturnedAt = SystemClock.elapsedRealtime()
            val navigationMs = fixtureReturnedAt - navigationStartedAt
            assertTrue("Fixture did not return after Back; elapsed=${navigationMs}ms", fixtureReturned)
            val exactIdentity = productSurfaceMeetsExpectation(trigger, true)
            assertTrue(
                "Recorded V2 trigger missed 500ms after visible fixture return; " +
                    "transitionAt=${rendered.transitionAtMs}ms triggerAt=${rendered.triggerAtMs}ms " +
                    "triggerDelay=${rendered.triggerDelayMs}ms encodedFrames=${rendered.encodedFrameCount}",
                rendered.matched,
            )
            assertTrue("Rendered trigger lacked eventual exact V2 overlay/contentDescription identity.", exactIdentity)
            println(
                "FIXTURE_SETTINGS_TIMING panelOpen=$panelOpen " +
                    "settingsActivationMs=$settingsActivationMs disappearedMs=$disappearedMs " +
                    "navigationMs=$navigationMs transitionAtMs=${rendered.transitionAtMs} " +
                    "triggerAtMs=${rendered.triggerAtMs} triggerDelayMs=${rendered.triggerDelayMs} " +
                    "encodedFrames=${rendered.encodedFrameCount} exactIdentity=$exactIdentity",
            )
            productSurfaceNeverVisible(close, 250L)
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
