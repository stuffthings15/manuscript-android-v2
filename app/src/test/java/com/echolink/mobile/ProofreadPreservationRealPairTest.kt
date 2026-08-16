package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The pair the phone actually judged, captured from the Samsung S20 on
 * 2026-08-15: the deterministically prepared target for essay 01 paragraph 2 of
 * the 500-percent corpus, and the answer the on-device model returned.
 *
 * A proofread candidate is judged against the prepared target, not against what
 * the writer typed, which is why this refusal cannot be reproduced from the
 * writer's text alone.
 *
 * Six differences, every one a repair. Bisected one at a time against the
 * shipped 82,834-word dictionary, exactly one is refused: "rather then" to
 * "rather than". Both spellings are real words and neither is a function word,
 * so it reads as a content-word swap rather than a correction, and that single
 * word dropped the paragraph to the sentence-salvage path.
 *
 * What this test does NOT do is prove the fix. Simulating the fixed build by
 * running GrammarSafetyNet.repair over this prepared text does not work: the
 * repair is not idempotent on already-prepared text, and re-running it here
 * turns "support" into "supports", a mismatch the real build would never
 * produce. More basically, changing the prepared text changes what the model is
 * asked and therefore what it answers, so no stored candidate can stand in for
 * the new one. That verification belongs on the phone.
 */
class ProofreadPreservationRealPairTest {
    private fun shippedDictionary(): (String) -> Boolean {
        val root = System.getProperty("echoLinkAndroidDir") ?: "."
        val file = File(root, "app/src/main/assets/symspell/frequency_dictionary_en.txt")
        check(file.isFile) { "no dictionary at " + file.absolutePath }
        val words = HashSet<String>()
        file.forEachLine { line ->
            val head = line.trim().substringBefore(' ')
            if (head.isNotEmpty()) words.add(head.lowercase())
        }
        return { word -> word.lowercase() in words }
    }

    @Test
    fun theRealPairIsRefusedAndTheRefusalIsTheThenThanSwap() {
        val known = shippedDictionary()

        assertFalse(
            "the captured refusal no longer reproduces, so this test measures nothing",
            ProofreadPreservationPolicy.preservesWording(PREPARED, CANDIDATE, known),
        )
        // Isolated, this is the one repair of the six the policy refuses.
        assertFalse(
            "then to than is no longer refused on its own",
            ProofreadPreservationPolicy.preservesWording(
                PREPARED,
                PREPARED.replace("rather then", "rather than"),
                known,
            ),
        )
    }

    private companion object {
        val PREPARED = """The subject of urban beekeepling is often described with a simple promise or a simple warning, but either version leaves out the conditions that determine what actually happens. Managed hives can support environmental education, local food systems, and public interest in pollinators, but they must be operated carefully so honeybees do not crowd out native species or create avoidable neighborhood conflicts.. A serious discussion	discussion must examine the people ihnvolved, the physical or institutional systems that support the work, the evidences used too judge results, and the unintended consequences that can appear after early excitement fades. This essay considers those connected questions rather then treating urban beekeeping as an isolated trend.    It also argues that responsible practice requires cear goals, continuing evaluation, and a willingness to change direction when evidence contradicts expectations."""

        val CANDIDATE = """The subject of urban beekeeping is often described with a simple promise or a simple warning, but either version leaves out the conditions that determine what actually happens. Managed hives can support environmental education, local food systems, and public interest in pollinators, but they must be operated carefully so honeybees do not crowd out native species or create avoidable neighborhood conflicts. A serious discussion must examine the people involved, the physical or institutional systems that support the work, the evidences used to judge results, and the unintended consequences that can appear after early excitement fades. This essay considers those connected questions rather than treating urban beekeeping as an isolated trend. It also argues that responsible practice requires clear goals, continuing evaluation, and a willingness to change direction when evidence contradicts expectations."""
    }
}
