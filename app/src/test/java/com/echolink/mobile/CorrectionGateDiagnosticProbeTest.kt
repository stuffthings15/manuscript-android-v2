package com.echolink.mobile

import java.io.File
import org.junit.Test

/**
 * Diagnostic probe. Measures how often the acceptance gates reject a correct
 * human proofread of error-heavy text, and whether they still reject real
 * meaning changes. Writes a report to build/correction-gate-probe.txt.
 */
class CorrectionGateDiagnosticProbeTest {

    private data class Pair(val label: String, val bad: String, val good: String)

    private val corrections = listOf(
        Pair(
            "contraction-cant",
            "i cant beleive the report was submited late agian.",
            "I can't believe the report was submitted late again.",
        ),
        Pair(
            "contraction-dont",
            "we dont no why the server keeps crashin every night.",
            "We don't know why the server keeps crashing every night.",
        ),
        Pair(
            "contraction-youre",
            "youre going to need too check youre email before the meeting.",
            "You're going to need to check your email before the meeting.",
        ),
        Pair(
            "agreement-simple",
            "The team are ready and the reports is finished.",
            "The team is ready and the reports are finished.",
        ),
        Pair(
            "double-negative",
            "He dont judge nobody who arrives late to the session.",
            "He doesn't judge anyone who arrives late to the session.",
        ),
        Pair(
            "nobody-spelling",
            "no body told me the deadline had moved to friday.",
            "Nobody told me the deadline had moved to Friday.",
        ),
        Pair(
            "punctuation-comma",
            "After the review was done we sent the file to marketing and they approved it",
            "After the review was done, we sent the file to marketing, and they approved it.",
        ),
        Pair(
            "runon-split",
            "the meeting ran long we missed the bus it was raining hard.",
            "The meeting ran long. We missed the bus. It was raining hard.",
        ),
        Pair(
            "capitalization",
            "on monday i met sarah in san diego to discuss the budget.",
            "On Monday I met Sarah in San Diego to discuss the budget.",
        ),
        Pair(
            "spelling-heavy",
            "Ther are alot of recieve errors in this documnet, definately fix them.",
            "There are a lot of receive errors in this document; definitely fix them.",
        ),
        Pair(
            "number-style",
            "there is 3 errors left in the draft.",
            "There are three errors left in the draft.",
        ),
        Pair(
            "must-of",
            "You must of forgot to save the file before you closed it.",
            "You must have forgotten to save the file before you closed it.",
        ),
        Pair(
            "its-vs-its",
            "The company lost it's way, and its clear the staff was unhappy.",
            "The company lost its way, and it's clear the staff were unhappy.",
        ),
        Pair(
            "vocabulary",
            "The results was very good and the meeting went real good too.",
            "The results were excellent, and the meeting went well too.",
        ),
        Pair(
            "structure-clarity",
            "Being that the budget is tight, we should of cut the travel costs first.",
            "Because the budget is tight, we should have cut the travel costs first.",
        ),
        Pair(
            "tense",
            "Yesterday she go to the office and finish the audit by herself.",
            "Yesterday she went to the office and finished the audit by herself.",
        ),
        Pair(
            "modal-fix",
            "The vendor cant deliver on time, so we cant sign the contract today.",
            "The vendor can't deliver on time, so we can't sign the contract today.",
        ),
        Pair(
            "apostrophe-plural",
            "All the manager's agreed the report's format was'nt clear.",
            "All the managers agreed the report's format wasn't clear.",
        ),
        Pair(
            "spacing",
            "Please review the draft .Then send it back ,thanks !",
            "Please review the draft. Then send it back, thanks!",
        ),
        Pair(
            "two-paragraph",
            "we finished the audit last week. the findings was minor.\n" +
                "next quater we will repeat the review with a bigger team.",
            "We finished the audit last week. The findings were minor.\n" +
                "Next quarter we will repeat the review with a bigger team.",
        ),
        Pair(
            "place-name",
            "we flew to bora bora and then to walla walla for the confrence.",
            "We flew to Bora Bora and then to Walla Walla for the conference.",
        ),
        Pair(
            "needless-modal",
            "You can be able to submit the form after you can finish the review.",
            "You can submit the form after you finish the review.",
        ),
        Pair(
            "wordy",
            "Due to the fact that the deadline was moved, we was forced to work late.",
            "Because the deadline was moved, we were forced to work late.",
        ),
        Pair(
            "question-mark",
            "did you send the invoice yet. i cant find it in the folder",
            "Did you send the invoice yet? I can't find it in the folder.",
        ),
    )

    /** Candidates that genuinely change meaning. The gate SHOULD reject these. */
    private val meaningChanges = listOf(
        Pair(
            "flip-negation",
            "The vendor did not approve the budget on Monday.",
            "The vendor approved the budget on Monday.",
        ),
        Pair(
            "change-number",
            "We shipped 42 units to the warehouse last night.",
            "We shipped 24 units to the warehouse last night.",
        ),
        Pair(
            "change-time",
            "The meeting starts at 3pm in the main hall.",
            "The meeting starts at 5pm in the main hall.",
        ),
        Pair(
            "drop-obligation",
            "Every technician must sign the safety sheet before entry.",
            "Every technician signs the safety sheet before entry.",
        ),
        Pair(
            "add-fact",
            "The audit covered the finance department.",
            "The audit covered the finance department and the legal department.",
        ),
        Pair(
            "swap-actor",
            "Sarah approved the release after the review.",
            "Mark approved the release after the review.",
        ),
        Pair(
            "drop-clause",
            "The report is complete, but the appendix is still missing pages.",
            "The report is complete.",
        ),
        Pair(
            "flip-permission",
            "Contractors may not enter the server room without an escort.",
            "Contractors may enter the server room without an escort.",
        ),
    )

    @Test
    fun measureAcceptanceGates() {
        val report = StringBuilder()
        report.appendLine("MANUSCRIPT ANDROID CORRECTION GATE PROBE")
        report.appendLine("=".repeat(78))
        report.appendLine()
        report.appendLine("A. SAFETY VALIDATOR vs CORRECT HUMAN PROOFREADS")
        report.appendLine("   (hard failures here = correct AI output thrown away)")
        report.appendLine()

        var falseRejects = 0
        corrections.forEach { pair ->
            val validation = AiSafetyValidator.validate(
                original = pair.bad,
                candidate = pair.good,
                toneTransformation = false,
                requiresVisibleChange = false,
            )
            val verdict = if (validation.safe) "ACCEPT" else "REJECT"
            if (!validation.safe) falseRejects++
            report.appendLine(
                "  [$verdict] ${pair.label}  hard=${validation.hardFailures} " +
                    "high=${validation.highRisks}",
            )
        }
        report.appendLine()
        report.appendLine(
            "  FALSE REJECTIONS: $falseRejects of ${corrections.size} " +
                "correct proofreads blocked",
        )
        report.appendLine()

        report.appendLine("B. RESIDUAL GRAMMAR VETO vs THE SAME CORRECT TEXT")
        report.appendLine("   (errorScore != 0 or safetyNet rewrite = RESIDUAL_GRAMMAR_RISK)")
        report.appendLine()
        var falseVetoes = 0
        corrections.forEach { pair ->
            val score = GrammarQualityGate.errorScore(pair.good)
            val repaired = GrammarSafetyNet.repair(pair.good)
            val clean = score == 0 && repaired == pair.good
            if (!clean) falseVetoes++
            val verdict = if (clean) "PASS  " else "VETO  "
            report.appendLine("  [$verdict] ${pair.label}  errorScore=$score")
            if (repaired != pair.good) {
                report.appendLine("            safetyNet rewrote to: $repaired")
            }
        }
        report.appendLine()
        report.appendLine(
            "  FALSE VETOES: $falseVetoes of ${corrections.size} correct texts " +
                "flagged as still broken",
        )
        report.appendLine()

        report.appendLine("C. CONTROL: REAL MEANING CHANGES (gate SHOULD reject)")
        report.appendLine()
        var leaks = 0
        meaningChanges.forEach { pair ->
            val validation = AiSafetyValidator.validate(
                original = pair.bad,
                candidate = pair.good,
                toneTransformation = false,
                requiresVisibleChange = false,
            )
            if (validation.safe) leaks++
            val verdict = if (validation.safe) "LEAK  " else "BLOCK "
            report.appendLine(
                "  [$verdict] ${pair.label}  hard=${validation.hardFailures} " +
                    "high=${validation.highRisks}",
            )
        }
        report.appendLine()
        report.appendLine(
            "  LEAKS: $leaks of ${meaningChanges.size} meaning changes accepted",
        )
        report.appendLine()

        report.appendLine("D. ERROR-DETECTION EVIDENCE ON THE BROKEN SOURCE")
        report.appendLine("   (CorrectionTerminalResolver uses this to demand a retry)")
        report.appendLine()
        var undetected = 0
        corrections.forEach { pair ->
            val detected = CorrectionErrorEvidence.hasErrors(pair.bad)
            if (!detected) undetected++
            report.appendLine("  [${if (detected) "SEES " else "BLIND"}] ${pair.label}")
        }
        report.appendLine()
        report.appendLine(
            "  UNDETECTED BROKEN SOURCES: $undetected of ${corrections.size}",
        )

        val out = File("build/correction-gate-probe.txt")
        out.parentFile?.mkdirs()
        out.writeText(report.toString())
        println(report.toString())
    }
}
