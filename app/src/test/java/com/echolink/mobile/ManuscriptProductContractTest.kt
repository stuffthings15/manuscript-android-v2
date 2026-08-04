package com.echolink.mobile

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManuscriptProductContractTest {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val contractFile = File(
        androidRoot,
        "docs/product-contract/manuscript-android-v2.json",
    )

    @Test
    fun contractLocksBrandPackagesTriggersAccessibilityAndTerminalResults() {
        assertTrue(
            "Missing locked product contract: ${contractFile.absolutePath}",
            contractFile.isFile,
        )
        val contract = Json.parseToJsonElement(contractFile.readText()).jsonObject

        assertEquals(
            "com.remote.Manuscript.v2",
            contract.getValue("canonicalReleasePackage").jsonPrimitive.content,
        )
        assertEquals(
            "com.remote.Manuscript.v2.debug",
            contract.getValue("canonicalDebugPackage").jsonPrimitive.content,
        )
        assertEquals(
            listOf("com.echolink.manuscript"),
            contract.getValue("legacyPackagesForbidden").jsonArray.map {
                it.jsonPrimitive.content
            },
        )

        val brand = contract.getValue("brand").jsonObject
        assertEquals(
            listOf(
                "#17120D",
                "#D6A85F",
                "#F2D6A2",
                "#F7E7C6",
                "#E2B86F",
                "#8B5B2B",
                "#3B291C",
            ),
            brand.getValue("palette").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(brand.getValue("launcherMustPreserveOuterGoldFrame").jsonPrimitive.boolean)
        assertFalse(brand.getValue("themedMonochromeIconAllowed").jsonPrimitive.boolean)

        val surfaces = contract.getValue("surfaces").jsonArray.map {
            it.jsonPrimitive.content
        }
        assertEquals(
            listOf(
                "android_launcher",
                "in_app_writing_trigger",
                "across_app_overlay_trigger",
                "accessibility_settings_guidance",
            ),
            surfaces,
        )

        val trigger = contract.getValue("trigger").jsonObject
        assertEquals(48, trigger.getValue("touchTargetDp").jsonPrimitive.int)
        assertEquals(32, trigger.getValue("visualSizeDp").jsonPrimitive.int)
        assertTrue(trigger.getValue("requiresFocusedEditableText").jsonPrimitive.boolean)
        assertFalse(trigger.getValue("passwordFieldsAllowed").jsonPrimitive.boolean)
        assertFalse(trigger.getValue("emptyFieldsAllowed").jsonPrimitive.boolean)

        val accessibility = contract.getValue("accessibilitySettings").jsonObject
        assertEquals(
            "accessibility_installed_services",
            accessibility.getValue("samsungHomeRowKey").jsonPrimitive.content,
        )
        assertEquals(
            "user_installed_services_category",
            accessibility.getValue("aospHomeRowKey").jsonPrimitive.content,
        )

        val correction = contract.getValue("correction").jsonObject
        assertFalse(correction.getValue("blankSuggestionAllowed").jsonPrimitive.boolean)
        assertFalse(correction.getValue("infiniteLoadingAllowed").jsonPrimitive.boolean)
        assertFalse(correction.getValue("originalTextChangesOnFailure").jsonPrimitive.boolean)
        assertEquals(
            setOf("success", "retryable_failure", "model_required"),
            correction.getValue("terminalStates").jsonArray
                .map { it.jsonPrimitive.content }
                .toSet(),
        )
    }

    @Test
    fun runtimeAndReleaseFilesUseOnlyTheCanonicalPackage() {
        val build = File(androidRoot, "app/build.gradle").readText()
        val manifest = File(androidRoot, "app/src/main/AndroidManifest.xml").readText()
        val releaseScripts = listOf(
            File(androidRoot, "tools/build_play_bundle.ps1"),
            File(androidRoot, "tools/security_release_gate.ps1"),
        ).filter(File::isFile)

        assertTrue(build.contains("applicationId \"com.remote.Manuscript.v2\""))
        assertFalse(build.contains("com.echolink.manuscript"))
        assertFalse(manifest.contains("com.echolink.manuscript"))
        releaseScripts.forEach { script ->
            assertFalse(
                "Legacy package in ${script.name}",
                script.readText().contains("com.echolink.manuscript"),
            )
        }
    }

    @Test
    fun contractUsesPlainAsciiPunctuation() {
        assertTrue(contractFile.isFile)
        assertFalse(contractFile.readText().contains('\u2014'))
    }
}
