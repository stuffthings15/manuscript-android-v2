package com.echolink.mobile

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The paragraph that stopped the second corpus being measurable at all.
 *
 * Essay 01 paragraph 2 of 20_error_riddled_essays_500_percent_more_errors, and
 * the answer the on-device model actually returned for it, captured from the
 * Samsung S20 on 2026-08-15. Every difference between the two is a repair the
 * writer wants: beekeepling to beekeeping, a duplicated "discussion" removed,
 * ihnvolved to involved, "too judge" to "to judge", "rather then" to "rather
 * than", and cear to clear.
 *
 * On the phone it was refused as CONTENT_WORD_CHANGED and the paragraph fell to
 * the sentence-salvage path, which is one model call per sentence. Five of the
 * eighteen paragraphs in this essay were refused the same way.
 *
 * This test PASSES, and that is the point of it. It proves the refusal is not
 * in the raw pair: given exactly what the writer typed and exactly what the
 * model answered, the validator is content. So the hard failure comes from the
 * form the pipeline actually validates, which is the protected text with the
 * writer's entities replaced by placeholders, and not from these two strings.
 *
 * It is therefore evidence, not a reproduction. Reproducing the refusal needs
 * the protected original logged beside the rejected candidate; today only the
 * candidate is logged, which is why this could be narrowed only this far.
 */
class LongParagraphRepairAcceptanceTest {
    @Test
    fun theModelsRealAnswerToARealCorpusParagraphIsAccepted() {
        val validation = AiSafetyValidator.validate(SOURCE, CANDIDATE)

        assertFalse(
            "hard failures: " + validation.hardFailures,
            SafetyIssue.CONTENT_WORD_CHANGED in validation.hardFailures,
        )
    }

    private companion object {
        val SOURCE = """The subject of urban beekeepling is often described with a simple promise or a simple warning, but either version leaves out the conditions that determine what actually happens. Managed hives can support environmental education, local food systems, and public interest in pollinators, but they must be operated carefully so honeybees do not crowd out native species or create avoidable neighborhood conflicts.. A serious discussion	discussion must examine the people ihnvolved, the physical or institutional systems that support the work, the evidences used too judge results, and the unintended consequences that can appear after early excitement fades. This essay considers those connected questions rather then treating urban beekeeping as an isolated trend.    It also argues that responsible practice requires cear goals, continuing evaluation, and a willingness to change direction when evidence contradicts expectations."""

        val CANDIDATE = """The subject of urban beekeeping is often described with a simple promise or a simple warning, but either version leaves out the conditions that determine what actually happens. Managed hives can support environmental education, local food systems, and public interest in pollinators, but they must be operated carefully so honeybees do not crowd out native species or create avoidable neighborhood conflicts. A serious discussion must examine the people involved, the physical or institutional systems that support the work, the evidences used to judge results, and the unintended consequences that can appear after early excitement fades. This essay considers those connected questions rather than treating urban beekeeping as an isolated trend. It also argues that responsible practice requires clear goals, continuing evaluation, and a willingness to change direction when evidence contradicts expectations."""
    }
}
