package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManuscriptProductSourceTest {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val mainRoot = File(androidRoot, "app/src/main")
    private val sourceRoot = File(mainRoot, "java/com/echolink/mobile")

    @Test
    fun visibleBrandIsManuscriptWithTheApprovedSlogan() {
        val manifest = File(mainRoot, "AndroidManifest.xml").readText()
        val strings = File(mainRoot, "res/values/strings.xml").readText()
        val activity = source("MainActivity.kt")

        assertTrue(strings.contains("<string name=\"app_name\">Manuscript Android Version 2</string>"))
        assertTrue(
            strings.contains(
                "<string name=\"app_slogan\">Flawless writing powered locally</string>",
            ),
        )
        assertTrue(manifest.contains("android:label=\"@string/launcher_name\""))
        assertTrue(activity.contains("R.string.app_slogan"))
        assertFalse(Regex(">[^<]*Echo-Link[^<]*<").containsMatchIn(strings))
    }

    @Test
    fun brandUsesTheAncientManuscriptPaletteAndScrollMark() {
        val styles = File(mainRoot, "res/values/styles.xml").readText()
        val icon = File(mainRoot, "res/drawable/ic_manuscript.xml").readText()

        for (color in listOf("#17120D", "#E7B85C")) assertTrue(styles.contains(color))
        for (color in listOf("#17120D", "#D6A85F", "#F2D6A2", "#3B291C")) {
            assertTrue("Missing brand color: $color", icon.contains(color))
        }
        assertTrue(icon.contains("M314,194H710"))
        assertTrue(icon.contains("M590,710L770,453"))
    }

    @Test
    fun compactCardShowsOnlyTheSuggestionAndProgress() {
        val ids = File(mainRoot, "res/values/ids.xml").readText()
        val activity = source("MainActivity.kt")

        for (id in listOf(
            "writing_suggestion_card",
            "writing_counter",
            "writing_suggestion",
            "writing_tone",
            "writing_replace_action",
            "writing_dismiss_action",
        )) {
            assertTrue("Missing card ID: $id", ids.contains("name=\"$id\""))
        }
        assertFalse(ids.contains("name=\"writing_original\""))
        assertTrue(activity.contains("block.index"))
        assertTrue(activity.contains("block.total"))
        assertTrue(activity.contains("block.replacement"))
        assertFalse(activity.contains("reviewOriginal"))
        assertTrue(activity.contains("WritingAssistUiPolicy.shouldShowCard"))
        assertTrue(activity.contains("LinearLayout.LayoutParams.WRAP_CONTENT"))
    }

    @Test
    fun replaceAndDismissAdvanceWhileTheSelectedTonePersists() {
        val activity = source("MainActivity.kt")

        assertTrue(activity.contains("WritingReviewSession.createParagraphs"))
        assertTrue(activity.contains("session.replaceCurrent(editor.text.toString(), selectedTone)"))
        assertTrue(activity.contains("session.dismissCurrent()"))
        assertTrue(activity.contains("session.current(selectedTone)"))
        assertTrue(activity.contains("renderCurrentBlock()"))
        assertTrue(activity.contains("grammarEngine.polishDocument("))
        assertTrue(activity.contains("onParagraphCompleted"))
    }

    @Test
    fun processTextRouteOffersTheSameLocalCorrectionAndToneChoices() {
        val processSource = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .firstOrNull { it.contains("Intent.ACTION_PROCESS_TEXT") }
            .orEmpty()

        assertTrue(processSource.isNotBlank())
        assertTrue(processSource.contains("Intent.EXTRA_PROCESS_TEXT"))
        assertTrue(processSource.contains("WritingTone.entries"))
        assertTrue(processSource.contains("OnDeviceGrammarEngine.get(this)"))
        assertTrue(processSource.contains("grammarEngine.polish"))
        assertTrue(processSource.contains("modelReplacement ?: return"))
        assertTrue(processSource.contains("R.string.writing_replace_action"))
        assertTrue(processSource.contains("R.string.writing_dismiss_action"))
    }

    private fun source(name: String): String = File(sourceRoot, name).readText()
}
