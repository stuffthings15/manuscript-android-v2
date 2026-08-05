package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManagerSelfTestCallSiteSafetySourceTest {
    private val sourceRoot = File(
        checkNotNull(System.getProperty("echoLinkAndroidDir")),
        "app/src/main/java/com/echolink/mobile",
    )

    @Test
    fun readinessThreadsMapEveryExceptionToTheFixedFailedState() {
        managerContracts().forEach { contract ->
            val method = functionBody(contract.source, "fun verifyInstalledModelAsync(")
            val thread = threadBody(method, contract.threadName)
            val guarded = blockAfter(thread, Regex("""\btry\s*\{"""))
            val caught = blockAfter(thread, EXCEPTION_CATCH)

            contract.selfTesterCalls.forEach { call ->
                assertTrue("${contract.label}: $call must be guarded", guarded.contains(call))
            }
            assertEquals(
                "${contract.label}: every Exception must use one common failure path",
                1,
                CATCH_HEADER.findAll(thread).count(),
            )
            assertTrue(
                "${contract.label}: self-test exceptions need a fixed failure state",
                fixedFailurePattern(contract.stateType, contract.selectionName)
                    .containsMatchIn(caught),
            )
            assertFalse(
                "${contract.label}: do not expose exception text",
                caught.contains(".message"),
            )
            assertFalse(
                "${contract.label}: ordinary cancellation must not escape",
                caught.contains("throw "),
            )
            assertFalse(
                "${contract.label}: Error must retain normal JVM rethrow semantics",
                THROWABLE_CATCH.containsMatchIn(thread),
            )
        }
    }

    @Test
    fun readinessThreadsAlwaysReleaseTheirGuardAndPostOneCallbackFromFinally() {
        managerContracts().forEach { contract ->
            val method = functionBody(contract.source, "fun verifyInstalledModelAsync(")
            val thread = threadBody(method, contract.threadName)
            val finallyBlock = blockAfter(thread, Regex("""\bfinally\s*\{"""))
            val release = "${contract.runningFlag}.set(false)"
            val callback = ".post(onFinished)"

            assertEquals(contract.label, 1, occurrences(thread, release))
            assertEquals(contract.label, 1, occurrences(thread, callback))
            assertTrue(
                "${contract.label}: running flag must clear in finally",
                finallyBlock.contains(release),
            )
            assertTrue(
                "${contract.label}: callback must post in finally",
                finallyBlock.contains(callback),
            )
        }
    }

    @Test
    fun gpuQualificationIsBestEffortOnlyAfterDurableActivation() {
        val source = source("AiModelManager.kt")
        val activation = functionBody(source, "internal fun qualifyAndActivate(")
        val gpuQualification = functionBody(source, "private fun qualifyGpuIfSupported(")
        val guarded = blockAfter(gpuQualification, Regex("""\btry\s*\{"""))
        val caught = blockAfter(gpuQualification, EXCEPTION_CATCH)
        val activationIndex = activation.indexOf(
            "activateQualifiedCandidate(selection, qualification)",
        )
        val gpuIndex = activation.indexOf("qualifyGpuIfSupported(")

        assertTrue(
            "activation must complete before optional GPU qualification",
            activationIndex >= 0,
        )
        assertTrue("GPU qualification must remain post-activation", gpuIndex > activationIndex)
        assertTrue(
            "the optional GPU self-test must be inside its exception boundary",
            guarded.contains("GemmaModelSelfTester.verify("),
        )
        assertEquals(
            "GPU exceptions must use one best-effort boundary",
            1,
            CATCH_HEADER.findAll(gpuQualification).count(),
        )
        assertFalse("GPU self-test cancellation must not escape", caught.contains("throw "))
        assertFalse("GPU failure must not replace Ready", gpuQualification.contains("state ="))
        assertFalse(
            "GPU failure must not clear the durable self-test",
            gpuQualification.contains("clearSelfTested("),
        )
        assertFalse(
            "GPU failure must not enter the download failure path",
            gpuQualification.contains("onDownloadFailed("),
        )
        assertFalse(
            "GPU failure must not create a Failed state",
            gpuQualification.contains("AiModelState.Failed"),
        )
    }

    private fun managerContracts(): List<ManagerContract> = listOf(
        ManagerContract(
            label = "AiModelManager",
            source = source("AiModelManager.kt"),
            threadName = "Manuscript-AI-readiness",
            runningFlag = "readinessRunning",
            stateType = "AiModelState",
            selectionName = "selection",
            selfTesterCalls = listOf(
                "GemmaModelSelfTester.verify(",
                "Qwen3ModelSelfTester.verify(",
            ),
        ),
        ManagerContract(
            label = "Qwen3ModelManager",
            source = source("Qwen3ModelManager.kt"),
            threadName = "Manuscript-Qwen3-readiness",
            runningFlag = "startupReadinessRunning",
            stateType = "Qwen3ModelState",
            selectionName = "tier",
            selfTesterCalls = listOf("Qwen3ModelSelfTester.verify("),
        ),
    )

    private fun source(fileName: String): String = File(sourceRoot, fileName).readText()

    private fun threadBody(method: String, threadName: String): String {
        val prefix = "Thread({"
        val suffix = "}, \"$threadName\").start()"
        val start = method.indexOf(prefix)
        val end = method.indexOf(suffix, start + prefix.length)
        if (start < 0 || end < 0) {
            throw AssertionError("Missing bounded $threadName thread body")
        }
        return method.substring(start + prefix.length, end)
    }

    private fun functionBody(source: String, signature: String): String {
        val signatureIndex = source.indexOf(signature)
        if (signatureIndex < 0) throw AssertionError("Missing function: $signature")
        val header = FUNCTION_BODY_HEADER.find(source, signatureIndex)
        val openingBrace = header?.let { source.indexOf('{', it.range.first) } ?: -1
        if (openingBrace < 0) throw AssertionError("Missing function body: $signature")
        return braceBody(source, openingBrace)
    }

    private fun blockAfter(source: String, header: Regex): String {
        val match = header.find(source)
            ?: throw AssertionError("Missing guarded block: ${header.pattern}")
        val openingBrace = source.indexOf('{', match.range.first)
        return braceBody(source, openingBrace)
    }

    private fun braceBody(source: String, openingBrace: Int): String {
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(openingBrace + 1, index)
                }
            }
        }
        throw AssertionError("Unclosed source block")
    }

    private fun fixedFailurePattern(stateType: String, selectionName: String): Regex = Regex(
        """state\s*=\s*${Regex.escape(stateType)}\.Failed\s*\(\s*""" +
            """${Regex.escape(selectionName)}\s*,\s*"AI readiness check failed\."\s*,?\s*\)""",
    )

    private fun occurrences(source: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(source).count()

    private data class ManagerContract(
        val label: String,
        val source: String,
        val threadName: String,
        val runningFlag: String,
        val stateType: String,
        val selectionName: String,
        val selfTesterCalls: List<String>,
    )

    private companion object {
        val CATCH_HEADER = Regex("""\bcatch\s*\(""")
        val EXCEPTION_CATCH = Regex(
            """\bcatch\s*\(\s*(?:_|[A-Za-z_][A-Za-z0-9_]*)\s*:\s*Exception\s*\)\s*\{""",
        )
        val THROWABLE_CATCH = Regex(
            """\bcatch\s*\(\s*(?:_|[A-Za-z_][A-Za-z0-9_]*)\s*:\s*Throwable\s*\)""",
        )
        val FUNCTION_BODY_HEADER = Regex(
            """\)\s*(?::\s*[A-Za-z0-9_?.<>]+)?\s*\{""",
        )
    }
}
