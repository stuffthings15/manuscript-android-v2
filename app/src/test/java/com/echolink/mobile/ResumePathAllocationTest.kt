package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resume path must not allocate drawables.
 *
 * This exists because of a captured ANR, not a theory. renderAssistantStatus
 * built a new GradientDrawable on every call and is called from onResume. A
 * GradientDrawable builds a Paint, Paint registers a native allocation, and
 * that registration asks the runtime whether native pressure warrants a
 * collection. With llama.cpp holding 1.3 to 2.5 GB the answer is yes, so the
 * allocation blocked the main thread and input dispatch timed out:
 *
 *   Subject: Input dispatching timed out
 *   VMRuntime.notifyNativeAllocation
 *   Paint.<init> -> GradientDrawable.<init>
 *   ManuscriptComponents.rounded (ManuscriptComponents.kt:40)
 *   MainActivity.renderAssistantStatus (MainActivity.kt:1643)
 *   MainActivity.onResume (MainActivity.kt:430)
 *
 * To the writer that is a frozen screen where no button works, which is
 * exactly how it was reported.
 */
class ResumePathAllocationTest {
    private val activity = File(
        System.getProperty("echoLinkAndroidDir") ?: ".",
        "app/src/main/java/com/echolink/mobile/MainActivity.kt",
    ).readText()

    private fun bodyOf(functionName: String): String =
        activity.substringAfter("private fun $functionName(")
            .substringBefore("\n    private fun ")

    @Test
    fun renderingTheStatusDoesNotBuildADrawable() {
        val body = bodyOf("renderAssistantStatus")
        assertFalse(
            "renderAssistantStatus runs on resume and must not allocate a drawable: $body",
            body.contains("ManuscriptComponents.rounded"),
        )
        assertFalse(body.contains("GradientDrawable("))
    }

    @Test
    fun theStatusPillBackgroundIsCreatedOnceAndRecoloured() {
        assertTrue(activity.contains("private val assistantStatusPillBackground"))
        val body = bodyOf("renderAssistantStatus")
        assertTrue(body.contains("assistantStatusPillBackground.setColor("))
        assertTrue(body.contains("assistantStatusPillBackground.setStroke("))
    }

    @Test
    fun theDrawableIsAttachedOnlyWhenItIsNotAlreadyTheBackground() {
        // Assigning the same drawable again each render would call
        // setBackground, which invalidates and re-lays-out for no reason on a
        // path that runs every time the screen comes back.
        val body = bodyOf("renderAssistantStatus")
        assertTrue(
            body.contains("!== assistantStatusPillBackground"),
        )
    }
}
