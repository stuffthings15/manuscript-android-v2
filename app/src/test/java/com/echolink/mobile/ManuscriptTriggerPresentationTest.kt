package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManuscriptTriggerPresentationTest {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val sourceRoot = File(androidRoot, "app/src/main/java/com/echolink/mobile")

    @Test
    fun bothWritingSurfacesUseOneFactoryAndOneStateController() {
        val activity = File(sourceRoot, "MainActivity.kt").readText()
        val service = File(sourceRoot, "ManuscriptAccessibilityService.kt").readText()

        listOf(activity, service).forEach { source ->
            assertTrue(source.contains("ManuscriptTriggerViewFactory.create("))
            assertTrue(source.contains("TriggerPulseController.applyState("))
        }
        assertFalse(activity.contains("setImageResource(R.drawable.ic_manuscript_assist)"))
        assertFalse(service.contains("setImageResource(R.drawable.ic_manuscript_assist)"))
    }

    @Test
    fun sharedPresentationMatchesTheLockedContract() {
        assertEquals(48, ManuscriptTriggerPresentation.TOUCH_TARGET_DP)
        assertEquals(32, ManuscriptTriggerPresentation.VISUAL_SIZE_DP)
        assertEquals(8, ManuscriptTriggerPresentation.PADDING_DP)
        assertEquals(1f, ManuscriptTriggerPresentation.PULSE_ALPHA_FROM)
        assertEquals(0.62f, ManuscriptTriggerPresentation.PULSE_ALPHA_TO)
        assertEquals(1f, ManuscriptTriggerPresentation.PULSE_SCALE_FROM)
        assertEquals(1.08f, ManuscriptTriggerPresentation.PULSE_SCALE_TO)
        assertEquals(700L, ManuscriptTriggerPresentation.PULSE_HALF_CYCLE_MS)
    }
}
