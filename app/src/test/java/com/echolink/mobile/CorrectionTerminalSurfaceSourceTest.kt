package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionTerminalSurfaceSourceTest {
    private val sourceRoot = File(
        checkNotNull(System.getProperty("echoLinkAndroidDir")),
        "app/src/main/java/com/echolink/mobile",
    )

    @Test
    fun everyCorrectionSurfaceRendersTheSharedTerminalResult() {
        listOf(
            "MainActivity.kt",
            "ManuscriptAccessibilityService.kt",
            "ProcessTextActivity.kt",
        ).forEach { file ->
            val source = File(sourceRoot, file).readText()
            assertTrue(
                "$file must use the shared terminal result",
                source.contains("CorrectionTerminalResult"),
            )
            assertTrue(
                "$file must use the shared terminal engine entry point",
                source.contains("polishDocumentTerminal(") ||
                    source.contains("polishTerminal("),
            )
        }
    }
}
