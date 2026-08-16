package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewedTextShareSourceTest {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val mainRoot = File(androidRoot, "app/src/main")
    private val activity = File(
        mainRoot,
        "java/com/echolink/mobile/MainActivity.kt",
    ).readText()

    @Test
    fun shareIsAnExplicitAccessibleChooserAction() {
        val strings = File(mainRoot, "res/values/strings.xml").readText()

        assertTrue(strings.contains("<string name=\"share_document_action\">Share</string>"))
        assertTrue(activity.contains("secondaryButton(R.string.share_document_action) { shareDocument() }"))
        assertTrue(activity.contains("ReviewedTextSharePolicy.accept(editor.text)"))
        assertTrue(activity.contains("Intent(Intent.ACTION_SEND)"))
        assertTrue(activity.contains("type = \"text/plain\""))
        assertTrue(activity.contains("putExtra(Intent.EXTRA_TEXT, reviewed)"))
        assertTrue(activity.contains("Intent.createChooser("))
        assertTrue(activity.contains("startActivity(Intent.createChooser("))
        assertTrue(activity.contains("secondaryButton("))
        // The share button still has to meet the 48 dp accessible target. That
        // number now has one owner, so this checks the wiring rather than a
        // literal; ManuscriptDesignTokensTest pins the value itself at 48.
        assertTrue(activity.contains("MIN_TOUCH_TARGET_DP = ManuscriptMetrics.TOUCH_TARGET_DP"))
    }

    @Test
    fun sharingAddsNoClipboardNetworkOrApprovalShortcut() {
        val manifest = File(mainRoot, "AndroidManifest.xml").readText()
        val shareMethod = activity.substringAfter("private fun shareDocument()")
            .substringBefore("private fun configureEdgeToEdge")

        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertFalse(shareMethod.contains("HttpURLConnection"))
        assertFalse(activity.contains("ClipboardManager"))
        assertFalse(activity.contains("ClipData"))
        assertFalse(shareMethod.contains("store.approve"))
        assertFalse(shareMethod.contains("performClick"))
    }
}
