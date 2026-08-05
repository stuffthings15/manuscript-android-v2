package com.echolink.mobile

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelfTesterSafetyTest {
    private val sourceRoot = File(
        checkNotNull(System.getProperty("echoLinkAndroidDir")),
        "app/src/main/java/com/echolink/mobile",
    )

    @Test
    fun publicSelfTestApisRequireAnExplicitStopSignal() {
        val gemmaApi:
            (Context, InstalledAiModel, BackendKind, () -> Boolean) -> Boolean =
            GemmaModelSelfTester::verify
        val qwenApi: (Context, File, () -> Boolean) -> Boolean =
            Qwen3ModelSelfTester::verify

        assertNotNull(gemmaApi)
        assertNotNull(qwenApi)
    }

    @Test
    fun bothSelfTestersCheckStopBeforeLoadGenerationAndMandatoryCleanup() {
        verifiers().forEach { verifier ->
            val events = mutableListOf<String>()
            val operations = RecordingSelfTestOperations(events = events)

            assertTrue(
                verifier.name,
                verifier.verify(
                    {
                        events += "stop"
                        false
                    },
                    operations,
                ),
            )
            assertEquals(
                verifier.name,
                listOf("stop", "load", "stop", "generate", "stop", "cleanup"),
                events,
            )
        }
    }

    @Test
    fun stopBeforeEachBoundaryInterruptsWithoutSkippingRequiredCleanup() {
        verifiers().forEach { verifier ->
            listOf(
                StopCase(
                    stopAtCheck = 1,
                    expectedEvents = listOf("stop-1"),
                ),
                StopCase(
                    stopAtCheck = 2,
                    expectedEvents =
                        listOf("stop-1", "load", "stop-2", "stop-3", "cleanup"),
                ),
                StopCase(
                    stopAtCheck = 3,
                    expectedEvents = listOf(
                        "stop-1",
                        "load",
                        "stop-2",
                        "generate",
                        "stop-3",
                        "cleanup",
                    ),
                ),
            ).forEach { case ->
                val events = mutableListOf<String>()
                var checks = 0
                val operations = RecordingSelfTestOperations(events = events)

                val failure = captureFailure {
                    verifier.verify(
                        {
                            checks += 1
                            events += "stop-$checks"
                            checks >= case.stopAtCheck
                        },
                        operations,
                    )
                }

                assertTrue(
                    "${verifier.name} stop check ${case.stopAtCheck}: $failure",
                    failure is InterruptedIOException,
                )
                assertEquals(
                    "${verifier.name} stop check ${case.stopAtCheck}",
                    case.expectedEvents,
                    events,
                )
            }
        }
    }

    @Test
    fun cancellationAndInterruptionFromEveryBoundaryAreRethrownUnchanged() {
        verifiers().forEach { verifier ->
            Boundary.entries.forEach { boundary ->
                listOf<Throwable>(
                    CancellationException("private cancellation detail"),
                    InterruptedIOException("private interruption detail"),
                ).forEach { expected ->
                    val operations = RecordingSelfTestOperations(
                        events = mutableListOf(),
                        failures = mapOf(boundary to expected),
                    )

                    val actual = captureFailure {
                        verifier.verify(
                            { false },
                            operations,
                        )
                    }

                    assertSame("${verifier.name} $boundary", expected, actual)
                    assertTrue(
                        "${verifier.name} $boundary must clean up after a load attempt",
                        operations.cleanupAttempted,
                    )
                }
            }
        }
    }

    @Test
    fun finalStopCancellationOutranksAnEarlierOrdinaryFailure() {
        verifiers().forEach { verifier ->
            listOf(Boundary.LOAD to 2, Boundary.GENERATE to 3).forEach { (boundary, stopCheck) ->
                listOf<Throwable>(
                    CancellationException("stop cancelled"),
                    InterruptedIOException("stop interrupted"),
                ).forEach { stopFailure ->
                    val ordinary = IOException("ordinary self-test failure")
                    val operations = RecordingSelfTestOperations(
                        events = mutableListOf(),
                        failures = mapOf(boundary to ordinary),
                    )
                    var checks = 0

                    val actual = captureFailure {
                        verifier.verify(
                            {
                                checks += 1
                                if (checks == stopCheck) throw stopFailure
                                false
                            },
                            operations,
                        )
                    }

                    assertSame("${verifier.name} $boundary", stopFailure, actual)
                    assertTrue(stopFailure.suppressed.contains(ordinary))
                    assertTrue(operations.cleanupAttempted)
                }
            }
        }
    }

    @Test
    fun cleanupCancellationOutranksAnEarlierOrdinaryFailure() {
        verifiers().forEach { verifier ->
            listOf(Boundary.LOAD, Boundary.GENERATE).forEach { boundary ->
                listOf<Throwable>(
                    CancellationException("cleanup cancelled"),
                    InterruptedIOException("cleanup interrupted"),
                ).forEach { cleanupFailure ->
                    val ordinary = IOException("ordinary self-test failure")
                    val operations = RecordingSelfTestOperations(
                        events = mutableListOf(),
                        failures = mapOf(
                            boundary to ordinary,
                            Boundary.CLEANUP to cleanupFailure,
                        ),
                    )

                    val actual = captureFailure {
                        verifier.verify({ false }, operations)
                    }

                    assertSame("${verifier.name} $boundary", cleanupFailure, actual)
                    assertTrue(cleanupFailure.suppressed.contains(ordinary))
                }
            }
        }
    }

    @Test
    fun primaryCancellationStaysPrimaryWhenCleanupAlsoFails() {
        verifiers().forEach { verifier ->
            val cancellation = CancellationException("generation cancelled")
            val cleanupFailure = IOException("ordinary cleanup failure")
            val operations = RecordingSelfTestOperations(
                events = mutableListOf(),
                failures = mapOf(
                    Boundary.GENERATE to cancellation,
                    Boundary.CLEANUP to cleanupFailure,
                ),
            )

            val actual = captureFailure {
                verifier.verify({ false }, operations)
            }

            assertSame(verifier.name, cancellation, actual)
            assertTrue(cancellation.suppressed.contains(cleanupFailure))
        }
    }

    @Test
    fun selfTesterSourcesContainNoRawThrowableMessageOrAndroidLogOutput() {
        listOf(
            "GemmaModelSelfTester.kt",
            "Qwen3ModelSelfTester.kt",
        ).forEach { fileName ->
            val source = File(sourceRoot, fileName).readText()

            assertFalse(fileName, source.contains("android.util.Log"))
            assertFalse(fileName, Regex("""\bLog\.""").containsMatchIn(source))
            assertFalse(
                fileName,
                Regex("""\b[A-Za-z_][A-Za-z0-9_]*\.message\b""")
                    .containsMatchIn(source),
            )
        }
    }

    private fun verifiers(): List<NamedVerifier> = listOf(
        NamedVerifier("Gemma") { stopped, operations ->
            GemmaModelSelfTester.verify(stopped, operations)
        },
        NamedVerifier("Qwen") { stopped, operations ->
            Qwen3ModelSelfTester.verify(stopped, operations)
        },
    )

    private fun captureFailure(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }

    private data class NamedVerifier(
        val name: String,
        val verify: (
            stopped: () -> Boolean,
            operations: ModelSelfTestOperations,
        ) -> Boolean,
    )

    private data class StopCase(
        val stopAtCheck: Int,
        val expectedEvents: List<String>,
    )

    private enum class Boundary {
        LOAD,
        GENERATE,
        CLEANUP,
    }

    private class RecordingSelfTestOperations(
        private val events: MutableList<String>,
        private val failures: Map<Boundary, Throwable> = emptyMap(),
    ) : ModelSelfTestOperations {
        var cleanupAttempted = false
            private set

        override fun load() {
            events += "load"
            failAt(Boundary.LOAD)
        }

        override fun generate(): Boolean {
            events += "generate"
            failAt(Boundary.GENERATE)
            return true
        }

        override fun cleanup() {
            cleanupAttempted = true
            events += "cleanup"
            failAt(Boundary.CLEANUP)
        }

        private fun failAt(boundary: Boundary) {
            failures[boundary]?.let { throw it }
        }
    }
}
