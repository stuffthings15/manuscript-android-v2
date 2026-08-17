package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards Curtis's standing instructions against being quietly reverted.
 *
 * This exists because of a three week loop that cost real time. Curtis set a
 * standing workflow on 2026-07-22: every Manuscript Android update is tested,
 * signed, and published to the Google Play Internal testing track. He then had
 * to reauthorise it in session after session, because each new session read a
 * temporary "local and USB-only, do not publish" rule left over from an August
 * reliability loop and treated it as current.
 *
 * The rule was not merely written down. It was ENFORCED: a unit test asserted
 * that play/README.md still said "USB-only" and "Do not". So a session that
 * tried to follow the standing instruction broke the build, concluded it had
 * misunderstood, and reverted. The decision was being overturned automatically.
 *
 * The lesson, which matters more than this file: a temporary rule encoded in a
 * test outlives the task it was written for, and then defends itself. If a
 * rule here ever contradicts a standing instruction in AI Brain, the standing
 * instruction wins and the test is the thing that is wrong.
 */
class StandingInstructionsTest {
    private val root = File(System.getProperty("echoLinkAndroidDir") ?: ".")

    private fun text(relativePath: String): String = File(root, relativePath).readText()

    /** Files a session actually reads before deciding how to work. */
    private val guidance = listOf("CLAUDE.md", "README.md", "play/README.md")

    @Test
    fun noGuidanceFileForbidsPublishingToPlay() {
        val forbidding = Regex(
            "(?i)(do not|don't|never|must not)[^.\\n]{0,80}(publish|play upload|upload to play)",
        )
        guidance.forEach { path ->
            val body = text(path)
            // Struck-through history is allowed to quote the old rule; a line
            // that still ASSERTS it is not.
            val live = body.lines().filterNot { it.trimStart().startsWith("- ~~") || it.contains("~~") }
            val offenders = live.filter { forbidding.containsMatchIn(it) }
            assertTrue(
                "$path reasserts a rule Curtis overturned. Publishing to Play " +
                    "Internal testing is his standing workflow, set 2026-07-22: $offenders",
                offenders.isEmpty(),
            )
        }
    }

    @Test
    fun noGuidanceFileCallsDistributionUsbOnly() {
        guidance.forEach { path ->
            val live = text(path).lines()
                .filterNot { it.contains("~~") || it.contains("used to say") || it.contains("used to") }
            val offenders = live.filter { it.contains("USB-only", ignoreCase = true) && !it.contains("not the destination") }
            assertTrue(
                "$path still calls Version 2 distribution USB-only. That was a " +
                    "temporary rule from an August reliability loop and it " +
                    "outlived its task: $offenders",
                offenders.isEmpty(),
            )
        }
    }

    @Test
    fun theStandingWorkflowIsStatedWhereASessionWillSeeIt() {
        // CLAUDE.md is read by every session working in this tree, which is
        // exactly why the stale rule did so much damage sitting there.
        val claude = text("CLAUDE.md")
        assertTrue(
            "CLAUDE.md must state the Play Internal testing workflow",
            claude.contains("Internal testing", ignoreCase = true),
        )
        assertTrue(
            "CLAUDE.md must name Version 2's own package",
            claude.contains("com.remote.Manuscript.v2"),
        )
    }

    @Test
    fun guidanceDefersToTheStandingRecord() {
        // The precedence rule is the actual fix. Without it, the next stale
        // line in a repo file beats a standing instruction all over again.
        val claude = text("CLAUDE.md")
        assertTrue(
            "CLAUDE.md must say that a standing instruction in AI Brain wins " +
                "over a contradicting rule in this repository",
            claude.contains("standing instruction wins"),
        )
    }
}
