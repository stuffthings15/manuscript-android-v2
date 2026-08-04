package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPersonalToolsUiSourceTest {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val mainRoot = File(androidRoot, "app/src/main")
    private val activity = File(
        mainRoot,
        "java/com/echolink/mobile/MainActivity.kt",
    ).readText()

    @Test
    fun activityExposesAccessibleLocalVocabularyAndSnippetControls() {
        val ids = File(mainRoot, "res/values/ids.xml").readText()
        val strings = File(mainRoot, "res/values/strings.xml").readText()

        for (id in listOf(
            "local_tools_status",
            "vocabulary_spoken",
            "vocabulary_written",
            "vocabulary_category",
            "vocabulary_summary",
            "snippet_trigger",
            "snippet_content",
            "snippet_summary",
        )) {
            assertTrue("Missing stable ID: $id", ids.contains("name=\"$id\""))
        }
        assertTrue(activity.contains("private fun formField"))
        assertTrue(activity.contains("hint = getString(hintRes)"))
        assertTrue(activity.contains("minimumHeight = dp(MIN_TOUCH_TARGET_DP)"))
        assertTrue(activity.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(strings.contains("encrypted on this device"))
        assertTrue(strings.contains("Nothing is uploaded."))
    }

    @Test
    fun personalizePageUsesTheEncryptedVocabularyAndSnippetStore() {
        assertTrue(activity.contains("localTools = AndroidLocalPersonalToolsStore(this)"))
        assertTrue(activity.contains("localTools.upsertVocabulary"))
        assertTrue(activity.contains("localTools.deleteVocabulary"))
        assertTrue(activity.contains("localTools.upsertSnippet"))
        assertTrue(activity.contains("localTools.deleteSnippet"))
        assertTrue(activity.contains("localTools.listVocabulary"))
        assertTrue(activity.contains("localTools.listSnippets"))
        assertFalse(activity.contains("android.permission.INTERNET"))
    }

    @Test
    fun summariesStayBoundedAndDoNotDisplaySnippetContents() {
        val summaryMethod = activity.substringAfter("private fun refreshLocalToolsSummary()")
            .substringBefore("private fun shareDocument")

        assertTrue(activity.contains("SUMMARY_ENTRY_LIMIT = 8"))
        assertTrue(summaryMethod.contains("entries.take(SUMMARY_ENTRY_LIMIT)"))
        assertTrue(summaryMethod.contains("joinToString(\"\\n\") { it.trigger }"))
        assertFalse(summaryMethod.contains("it.content"))
    }
}
