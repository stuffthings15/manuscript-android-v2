package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedContractV1Test {
    @Test
    fun parsesAuthoritativeSharedManifestAndDeclaresAndroid() {
        val root = File(requireNotNull(System.getProperty("echoLinkContractDir")))
        val manifest = SharedContractV1.parseManifest(File(root, "manifest.json").readText())

        assertEquals("1.0.0", manifest.version)
        assertTrue("android" in manifest.clientTargets)
        assertEquals(5, manifest.operations.size)
    }

    @Test
    fun capabilityRequestRoundTripsWithStrictPrivacy() {
        val raw = SharedContractV1.capabilityRequest("android-test-1", "0.1.0")
        val request = SharedContractV1.parseRequest(raw)

        assertEquals("android-test-1", request.requestId)
        assertEquals("capabilities", request.operation)
        assertFalse(request.privacy.loopbackModelAllowed)
        assertEquals("none", request.privacy.textRetention)
        assertEquals("android", request.input["client_platform"]?.toString()?.trim('"'))
    }

    @Test
    fun rejectsPrivacyDowngradeAndUnknownFields() {
        val valid = SharedContractV1.capabilityRequest("android-test-2", "0.1.0")

        val remote = valid.replace("\"external_network_allowed\":false", "\"external_network_allowed\":true")
        assertFails { SharedContractV1.parseRequest(remote) }

        val unknown = valid.replaceFirst("{", "{\"unexpected\":true,")
        assertFails { SharedContractV1.parseRequest(unknown) }
    }

    @Test
    fun rejectsOperationSpecificShapeMismatch() {
        val raw = """
            {
              "contract_version":"1.0.0",
              "request_id":"bad-shape",
              "operation":"dictation_cleanup",
              "privacy":{
                "local_only":true,
                "external_network_allowed":false,
                "loopback_model_allowed":false,
                "retain_audio":false,
                "text_retention":"none",
                "telemetry_allowed":false
              },
              "input":{"text":"hello"}
            }
        """.trimIndent()

        assertFails { SharedContractV1.parseRequest(raw) }
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Expected strict contract rejection.", failed)
    }
}
