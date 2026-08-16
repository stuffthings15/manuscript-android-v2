package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceSpeechBoundarySourceTest {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val mainRoot = File(androidRoot, "app/src/main")

    @Test
    fun dictationUsesOnlyTheStrictOnDeviceRecognizerAndNeverUploadsVoice() {
        // Boundary updated 2026-08-01 at Curtis's direction: Manuscript now
        // owns in-app dictation, but ONLY through Android's strict on-device
        // recognizer. Audio never reaches a cloud path.
        val manifest = File(mainRoot, "AndroidManifest.xml").readText()
        val source = allKotlinSource()
        val speechSource = javaSource("OnDeviceSpeechSession.kt")

        assertTrue(manifest.contains("android.permission.RECORD_AUDIO"))
        assertTrue(manifest.contains("android.hardware.microphone"))
        assertTrue(manifest.contains("android:required=\"false\""))
        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("android.speech.RecognitionService"))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(speechSource.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"))
        assertTrue(speechSource.contains("SpeechRecognizer.createOnDeviceSpeechRecognizer"))
        assertFalse(
            Regex("SpeechRecognizer\\s*\\.\\s*createSpeechRecognizer\\s*\\(")
                .containsMatchIn(source),
        )
        assertFalse(source.contains("setRequestProperty(\"prompt\""))
    }

    @Test
    fun processTextRemainsTheNarrowSelectedTextFallback() {
        val manifest = File(mainRoot, "AndroidManifest.xml").readText()
        val source = allKotlinSource()

        assertTrue(manifest.contains("android.intent.action.PROCESS_TEXT"))
        assertTrue(manifest.contains("android:mimeType=\"text/plain\""))
        assertTrue(source.contains("Intent.ACTION_PROCESS_TEXT"))
        assertTrue(source.contains("Intent.EXTRA_PROCESS_TEXT"))
        assertTrue(source.contains("Intent.EXTRA_PROCESS_TEXT_READONLY"))
        assertTrue(source.contains("OnDeviceGrammarEngine.get"))
        assertFalse(source.contains("LocalWritingAssistant.suggest"))
        assertTrue(
            source.contains("setResult(Activity.RESULT_OK") ||
                source.contains("setResult(RESULT_OK"),
        )
        assertTrue(source.contains("finish()"))
    }

    @Test
    fun systemWideHelperUsesOptInAccessibilityWithoutReplacingGboardOrUsingClipboard() {
        val manifest = File(mainRoot, "AndroidManifest.xml").readText()
        val source = allKotlinSource()

        assertFalse(manifest.contains("android.permission.BIND_INPUT_METHOD"))
        assertTrue(manifest.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        // SYSTEM_ALERT_WINDOW and TYPE_APPLICATION_OVERLAY are now present, for
        // the GUI build manual's draggable floating assistant. That is the one
        // boundary this test used to guard which has been deliberately
        // reversed; the rest of it still stands, and matters more.
        //
        // Manuscript still does not replace the keyboard, and still never
        // touches the clipboard. The across-app correction panel is still an
        // accessibility overlay, so the overlay permission stays optional.
        assertFalse(source.contains("InputMethodService"))
        assertTrue(source.contains("AccessibilityService"))
        assertTrue(source.contains("TYPE_ACCESSIBILITY_OVERLAY"))
        assertFalse(source.contains("ClipboardManager"))
        assertFalse(source.contains("ClipData"))
    }

    @Test
    fun mainEditorUsesSystemKeyboardAndLocalWritingOnly() {
        val source = javaSource("MainActivity.kt")

        assertTrue(source.contains("InputType.TYPE_CLASS_TEXT"))
        assertTrue(source.contains("WritingReviewSession.create"))
        assertTrue(source.contains("session.replaceCurrent"))
        assertFalse(source.contains("http://"))
        assertFalse(source.contains("https://"))
    }

    private fun javaSource(name: String): String = File(
        mainRoot,
        "java/com/echolink/mobile/$name",
    ).readText()

    private fun allKotlinSource(): String = File(mainRoot, "java")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .joinToString("\n") { it.readText() }
}
