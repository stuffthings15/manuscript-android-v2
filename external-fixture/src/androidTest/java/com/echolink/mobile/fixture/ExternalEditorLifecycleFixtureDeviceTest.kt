package com.echolink.mobile.fixture

import android.os.Build
import android.os.SystemClock
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
            tapProductSurface(trigger)
            assertTrue(productSurfaceMeetsExpectation(close, true))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val editorDescription = S23ExternalEditorFixtureActivity.editorDescription(S23ExternalEditorFixtureActivity.MODE_MULTILINE)
                val triggerRegion = expectedRenderedTriggerRegion(editorDescription)
                val fixtureReferenceRegion = fixtureReturnReferenceRegion(editorDescription)
                val fixtureReference = captureFixtureReturnReference(fixtureReferenceRegion)
                var closeLookupStartedAt = 0L
                val closeResult = try {
                    recordRenderedTriggerReturn(
                        name = "close",
                        triggerRegion = triggerRegion,
                        fixtureReference = fixtureReference,
                        fixtureReferenceRegion = fixtureReferenceRegion,
                    ) {
                        closeLookupStartedAt = SystemClock.elapsedRealtime()
                        tapProductSurface(close)
                    }
                } finally {
                    fixtureReference.recycle()
                }
                val (closeInjectedAt, rendered) = closeResult
                val lookupMs = closeInjectedAt - closeLookupStartedAt
                val exactIdentity = productSurfaceMeetsExpectation(trigger, true)
                assertTrue(
                    "Recorded V2 trigger failed the ${rendered.contractProfile} rendered-return contract " +
                        "ceiling=${rendered.ceilingMs}ms after visible panel disappearance; lookup=${lookupMs}ms " +
                        "transitionAt=${rendered.transitionAtMs}ms triggerAt=${rendered.triggerAtMs}ms " +
                        "triggerDelay=${rendered.triggerDelayMs}ms encodedFrames=${rendered.encodedFrameCount}",
                    rendered.matched,
                )
                assertTrue("Rendered trigger lacked eventual exact V2 overlay/contentDescription identity.", exactIdentity)
                println(
                    "FIXTURE_CLOSE_TIMING lookupMs=$lookupMs transitionAtMs=${rendered.transitionAtMs} " +
                        "triggerAtMs=${rendered.triggerAtMs} triggerDelayMs=${rendered.triggerDelayMs} " +
                        "encodedFrames=${rendered.encodedFrameCount} exactIdentity=$exactIdentity " +
                        "contract=${rendered.contractProfile} ceilingMs=${rendered.ceilingMs}",
                )
            } else {
                val observation = recordAccessibilityTriggerReturn(
                    transitionCondition = { findProductSurface(close)?.let { it.recycle(); false } ?: true },
                    triggerDescription = trigger,
                ) {
                    val startedAt = SystemClock.elapsedRealtime()
                    tapProductSurface(close)
                    startedAt
                }
                assertTrue(
                    "Pre-P exact accessibility trigger missed the ${observation.ceilingMs}ms functional ceiling " +
                        "after panel disappearance; transitionAt=${observation.transitionAtMs} " +
                        "triggerAt=${observation.triggerAtMs} triggerDelay=${observation.triggerDelayMs}",
                    observation.matched,
                )
                println(
                    "FIXTURE_CLOSE_ACCESSIBILITY_TIMING transitionAtMs=${observation.transitionAtMs} " +
                        "triggerAtMs=${observation.triggerAtMs} triggerDelayMs=${observation.triggerDelayMs} " +
                        "contract=pre-p-accessibility-functional ceilingMs=${observation.ceilingMs}",
                )
            }
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
                assertTrue(
                    "$mode surface missed the ${renderedReturnContract.profile.reportName} " +
                        "${renderedReturnContract.removalCeilingMs}ms removal gate or reappeared.",
                    v2OverlayClearsWithinGateAndStaysAbsent(),
                )
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
            val editorDescription = S23ExternalEditorFixtureActivity.editorDescription(S23ExternalEditorFixtureActivity.MODE_PACKAGE_SWITCH)
            val triggerRegion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                expectedRenderedTriggerRegion(editorDescription)
            } else null
            val fixtureReferenceRegion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                fixtureReturnReferenceRegion(editorDescription)
            } else null
            val fixtureReference = fixtureReferenceRegion?.let(::captureFixtureReturnReference)
            if (panelOpen) { tapProductSurface(trigger); assertTrue(productSurfaceMeetsExpectation(close, true)) }
            val settingsLaunchStartedAt = SystemClock.elapsedRealtime()
            tapFixtureDescription(S23ExternalEditorFixtureActivity.actionDescription(S23ExternalEditorFixtureActivity.MODE_PACKAGE_SWITCH))
            val settingsActivated = waitUntil { applicationWindowPresent("com.android.settings") }
            val settingsActivatedAt = SystemClock.elapsedRealtime()
            val settingsActivationMs = settingsActivatedAt - settingsLaunchStartedAt
            assertTrue("Settings did not become active; elapsed=${settingsActivationMs}ms", settingsActivated)
            val disappearDeadline = settingsActivatedAt + renderedReturnContract.removalCeilingMs
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
                assertTrue(
                    "V2 surface removal missed the ${renderedReturnContract.profile.reportName} " +
                        "${renderedReturnContract.removalCeilingMs}ms gate; elapsed=${disappearedMs}ms",
                    surfaceRemoved,
                )
                assertTrue(disappearedMs <= renderedReturnContract.removalCeilingMs)
            } finally {
                departureTrace.assertionCompleted(SystemClock.elapsedRealtime())
                departureTrace.flush()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val settingsResult = try {
                    recordRenderedTriggerReturn(
                        name = "settings-${if (panelOpen) "panel" else "trigger"}",
                        triggerRegion = requireNotNull(triggerRegion),
                        fixtureReference = requireNotNull(fixtureReference),
                        fixtureReferenceRegion = requireNotNull(fixtureReferenceRegion),
                    ) {
                        val startedAt = SystemClock.elapsedRealtime()
                        pressBack()
                        startedAt
                    }
                } finally {
                    fixtureReference?.recycle()
                }
                val (navigationStartedAt, rendered) = settingsResult
                val fixtureReturned = waitUntil { applicationWindowPresent(fixturePackage) }
                val fixtureReturnedAt = SystemClock.elapsedRealtime()
                val navigationMs = fixtureReturnedAt - navigationStartedAt
                assertTrue("Fixture did not return after Back; elapsed=${navigationMs}ms", fixtureReturned)
                val exactIdentity = productSurfaceMeetsExpectation(trigger, true)
                assertTrue(
                    "Recorded V2 trigger failed the ${rendered.contractProfile} rendered-return contract " +
                        "ceiling=${rendered.ceilingMs}ms after visible fixture return; " +
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
                        "encodedFrames=${rendered.encodedFrameCount} exactIdentity=$exactIdentity " +
                        "contract=${rendered.contractProfile} ceilingMs=${rendered.ceilingMs}",
                )
            } else {
                val observation = recordAccessibilityTriggerReturn(
                    transitionCondition = { applicationWindowPresent(fixturePackage) },
                    triggerDescription = trigger,
                ) {
                    val startedAt = SystemClock.elapsedRealtime()
                    pressBack()
                    startedAt
                }
                assertTrue("Fixture did not return after Back.", observation.transitionAtMs != null)
                assertTrue(
                    "Pre-P exact accessibility trigger missed the ${observation.ceilingMs}ms functional ceiling " +
                        "after fixture return; transitionAt=${observation.transitionAtMs} " +
                        "triggerAt=${observation.triggerAtMs} triggerDelay=${observation.triggerDelayMs}",
                    observation.matched,
                )
                println(
                    "FIXTURE_SETTINGS_ACCESSIBILITY_TIMING panelOpen=$panelOpen " +
                        "settingsActivationMs=$settingsActivationMs disappearedMs=$disappearedMs " +
                        "transitionAtMs=${observation.transitionAtMs} triggerAtMs=${observation.triggerAtMs} " +
                        "triggerDelayMs=${observation.triggerDelayMs} " +
                        "contract=pre-p-accessibility-functional ceilingMs=${observation.ceilingMs}",
                )
            }
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
