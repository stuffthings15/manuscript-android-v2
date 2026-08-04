package com.echolink.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceTestEvidenceWriterTest {
    @Test
    fun privateEvidenceIsReadableAndRejectsDuplicateAndUnsafeNames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileName = "round-proofread-${System.nanoTime()}.json"
        val location = DeviceTestEvidenceWriter.writePrivate(
            context,
            fileName,
            """{"success":true}""",
        )

        assertEquals("""{"success":true}""", File(location).readText())
        assertTrue(location.replace('\\', '/').contains("/files/test-evidence/"))
        assertThrows(IllegalArgumentException::class.java) {
            DeviceTestEvidenceWriter.writePrivate(context, fileName, """{"success":false}""")
        }
        assertEquals("""{"success":true}""", File(location).readText())
        assertThrows(IllegalArgumentException::class.java) {
            DeviceTestEvidenceWriter.writePrivate(context, "../escape.json", "{}")
        }
    }

    @Test
    fun sealedCorpusDocumentIdIsPinned() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val corpusText = context.assets.open(
            "device_tests/gpt56-five-page-all-tones-v2.json",
        ).bufferedReader().use { it.readText() }

        AllToneFullPageDeviceTest.requireExpectedDocumentId(JSONObject(corpusText))
        assertThrows(IllegalArgumentException::class.java) {
            AllToneFullPageDeviceTest.requireExpectedDocumentId(
                JSONObject().put("document_id", "wrong-document-id"),
            )
        }
    }
}