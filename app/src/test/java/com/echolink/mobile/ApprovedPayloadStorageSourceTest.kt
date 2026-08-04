package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedPayloadStorageSourceTest {
    private val sourceRoot = File(
        checkNotNull(System.getProperty("echoLinkAndroidDir")),
        "app/src/main/java/com/echolink/mobile",
    )

    @Test
    fun productionKeyIsNonExportableAndroidKeystoreAesGcm() {
        val keySource = source("AndroidKeystoreKeyProvider.kt")
        val cryptoSource = source("ApprovedPayloadCrypto.kt")

        assertTrue(keySource.contains("AndroidKeyStore"))
        assertTrue(keySource.contains("KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT"))
        assertTrue(keySource.contains("KeyProperties.BLOCK_MODE_GCM"))
        assertTrue(keySource.contains("KeyProperties.ENCRYPTION_PADDING_NONE"))
        assertTrue(keySource.contains("setRandomizedEncryptionRequired(true)"))
        assertTrue(cryptoSource.contains("AES/GCM/NoPadding"))
        assertTrue(cryptoSource.contains("cipher.init(Cipher.ENCRYPT_MODE, keyProvider())"))
        assertTrue(cryptoSource.contains("updateAAD"))
    }

    @Test
    fun preferencesContainOnlyAnEncryptedEnvelopeAndLegacyPlaintextIsPurged() {
        val storeSource = source("ApprovedTextStore.kt")

        assertTrue(storeSource.contains("echo_link_approved_payload_encrypted_v1"))
        assertTrue(storeSource.contains("CIPHERTEXT_KEY"))
        assertTrue(storeSource.contains("IV_KEY"))
        assertTrue(storeSource.contains("purgeLegacyPlaintext"))
        assertTrue(storeSource.contains("echo_link_approved_text_v1"))
        assertFalse(storeSource.contains("putString(APPROVED_TEXT_KEY, approved)"))
    }

    @Test
    fun processTextNeverPersistsOrCopiesTheSelectedText() {
        val processSource = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .firstOrNull { it.contains("Intent.ACTION_PROCESS_TEXT") }
            .orEmpty()

        assertTrue(processSource.isNotBlank())
        assertTrue(processSource.contains("Intent.EXTRA_PROCESS_TEXT"))
        assertTrue(processSource.contains("setResult("))
        assertFalse(processSource.contains("ApprovedTextStore"))
        assertFalse(processSource.contains("store.approve"))
        assertFalse(processSource.contains("ClipboardManager"))
        assertFalse(processSource.contains("ClipData"))
    }

    @Test
    fun processTextRejectsUnboundedInputAndProtectsTheReviewWindow() {
        val processSource = source("ProcessTextActivity.kt")

        assertTrue(processSource.contains("ExternalWritingPolicy.materializeReviewText"))
        assertFalse(
            processSource.contains(
                "getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()",
            ),
        )
        assertTrue(processSource.contains("WindowManager.LayoutParams.FLAG_SECURE"))
    }

    @Test
    fun activityDoesNotSaveReviewedPlaintextAsViewState() {
        val activitySource = source("MainActivity.kt")

        assertTrue(activitySource.contains("isSaveEnabled = false"))
        assertTrue(activitySource.contains("ApplicationInfo.FLAG_DEBUGGABLE == 0"))
        assertTrue(activitySource.contains("WindowManager.LayoutParams.FLAG_SECURE"))
    }

    private fun source(name: String): String = File(sourceRoot, name).readText()
}
