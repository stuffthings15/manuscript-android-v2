package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceGrammarEngineTest {
    @Test
    fun safetyNetRepairsPeoplesFailureCapturedFromSamsungDevice() {
        assertEquals(
            "Dogs are good pets because they are nice, and they do lots of stuff for people.",
            GrammarSafetyNet.repair(
                "Dogs are good pets because they are nice and, they do lots of stuff for peoples.",
            ),
        )
        assertEquals(
            "The museum works with Indigenous peoples.",
            GrammarSafetyNet.repair("The museum works with Indigenous peoples."),
        )
    }

    @Test
    fun everyVisibleToneHasADistinctCompletePrompt() {
        val specs = TonePromptLibrary.allSpecs()
        assertEquals(23, specs.size)
        assertEquals(WritingTone.entries.toSet(), specs.map { it.tone }.toSet())
        assertEquals(23, specs.map { it.instruction }.toSet().size)

        specs.forEach { spec ->
            val prompt = TonePromptLibrary.build(spec.tone, "Keep Project Atlas due on July 14.")
            assertTrue(prompt.contains(spec.instruction))
            assertTrue(prompt.contains("Correct every grammar, spelling, punctuation"))
            assertTrue(prompt.contains("Preserve the exact meaning, facts, names, numbers"))
            assertTrue(prompt.contains("Do not invent, remove, or contradict information."))
            assertTrue(prompt.endsWith("Keep Project Atlas due on July 14."))
        }
    }

    @Test
    fun compactPromptStillRequiresCompleteObjectiveCorrectionAndFactPreservation() {
        val prompt = TonePromptLibrary.buildCompact(
            WritingTone.PROOFREAD,
            "project northstar were not ready.",
        )

        assertTrue(prompt.contains("Correct every grammar, spelling, punctuation"))
        assertTrue(prompt.contains("Preserve the exact meaning, facts, names, numbers"))
        assertTrue(prompt.endsWith("project northstar were not ready."))
    }

    @Test
    fun promptsAndValidationForbidEmDashes() {
        val source = "Project Atlas is ready. Morgan approved it."
        val output = "Project Atlas is ready \u2014 Morgan approved it."

        WritingTone.entries.forEach { tone ->
            assertTrue(TonePromptLibrary.build(tone, source).contains("Never use em dashes."))
            assertTrue(TonePromptLibrary.buildCompact(tone, source).contains("Never use em dashes."))
            assertFalse(RewriteValidator.isValid(source, source, output, tone))
        }
    }

    @Test
    fun everyPromptRequiresSentenceLevelGrammarAuditAndExactMarkup() {
        WritingTone.entries.forEach { tone ->
            listOf(
                TonePromptLibrary.build(tone, "A list:\n- item one."),
                TonePromptLibrary.buildCompact(tone, "A list:\n- item one."),
            ).forEach { prompt ->
                assertTrue(prompt.contains("subject-verb agreement"))
                assertTrue(prompt.contains("fragments, run-ons, and comma splices"))
                assertTrue(prompt.contains("dangling modifiers and faulty parallelism"))
                assertTrue(prompt.contains("current roles in present tense"))
                assertTrue(prompt.contains("scheduled actions in future tense"))
                assertTrue(prompt.contains("Keep every Markdown heading prefix and list marker exactly"))
                assertTrue(prompt.contains("Preserve every permission, prohibition, obligation"))
                assertTrue(prompt.contains("Do not strengthen, weaken, add, or remove"))
                assertTrue(
                    prompt.contains(
                        "Repair every dangling introductory modifier by naming an actor already present",
                    ),
                )
                assertTrue(prompt.contains("Do not convert a current job role into past tense"))
            }
        }
    }

    @Test
    fun qwenReceivesOriginalTextWhenRuleLayerHasUnverifiedWordChanges() {
        assertEquals(
            "The three loaner tablets use iOS. This fallback is required.",
            RewriteInputPolicy.select(
                source = "The three loaner tablets use iOS. This fallback is required.",
                prepared = "The three loader tablets use its. This callback is required.",
            ),
        )
    }

    @Test
    fun balancedQwenKeepsItsQualityContextWhenBatterySaverIsOn() {
        val profile = Qwen3RuntimeProfilePolicy.create(
            tier = Qwen3Tier.BALANCED,
            constrained = true,
            availableProcessors = 8,
        )

        assertEquals(1_024, profile.contextSize)
        assertTrue(profile.maximumSourceCharacters <= 600)
        assertEquals(2, profile.threadCount)
        assertTrue(profile.conserveResources)
    }

    @Test
    fun toneOutputPolicyRejectsPromptEchoAndLostMeaning() {
        val source = "Send Project Atlas to Morgan tomorrow because the report has errors."
        assertFalse(
            ToneOutputPolicy.isSafe(
                source,
                "Requirements: Rewrite the text and return only the answer.",
                WritingTone.PROFESSIONAL,
            ),
        )
        assertFalse(
            ToneOutputPolicy.isSafe(
                source,
                "Morgan will get a new job tomorrow.",
                WritingTone.FRIENDLY,
            ),
        )
        assertTrue(
            ToneOutputPolicy.isSafe(
                source,
                "Please send Project Atlas to Morgan tomorrow because the report contains errors.",
                WritingTone.PROFESSIONAL,
            ),
        )
        assertFalse(
            ToneOutputPolicy.isSafe(
                source,
                source,
                WritingTone.FRIENDLY,
            ),
        )
        assertTrue(ToneOutputPolicy.isSafe(source, source, WritingTone.PROOFREAD))
    }

    @Test
    fun professionalPolicyAcceptsTheSuppliedGoldStandardDogRewrite() {
        val source = """
            I think i should own a dog becaus dogs are real nice and they make you feel better even if your day was bad. A dog is like your best freind almost all the time and they dont really care if your rich or poor or ugly or anything they just likes you. Thats one of the biggest reason why i think everybody should atleast have one dog.

            Dogs also helps you not be lonely because there always around wanting to play or just sit there. If i was by myself all day the dog would keep me company and it would make things alot less boring. Sometimes people gets sad and a dog can cheer them up by doing funny stuff or wagging there tail.

            Another thing is dogs can protect your house maybe bark at strangers and make people think twice before coming in. Even a little dog barks really loud sometimes. Thats good because it can warn you if somethings going on outside at night or when nobodys paying attention.

            Dogs also gets you outside more. You gotta walk them feed them and take care of them every day. That means i would probably get more exercise instead of just sitting around doing nothing all day long watching tv or my phone all the time. Thats probly healthier.

            They can also teach responsiblity even if its alot of work. Feeding them giving water cleaning messes taking them to the vet all of that is stuff you got to remember. It might make me more better at taking care of things later on in life.

            Also dogs are fun. You can throw a ball play fetch go to parks or just run around. Some dogs even know tricks and its funny watching them do stuff. There always happy to see you when you come home which makes you feel good inside.

            So thats why i think i should own a dog because they help people they make life happier they protect you sometimes and there just really good animals. It might cost money and take time but i think its worth it anyway cause dogs is awesome and everybody should atleast think about getting one.
        """.trimIndent()
        val goldStandard = """
            I believe I should own a dog because dogs are loyal companions that provide comfort, friendship, and unconditional love. They do not judge people based on appearance or wealth, and they are always happy to spend time with their owners. Having a dog would make each day more enjoyable and provide a dependable source of companionship.

            Dogs can also help reduce loneliness by always being there to play, relax, or simply keep you company. Their cheerful personalities and playful behavior can improve your mood and make difficult days feel a little easier.

            Another important benefit is that dogs can provide an added sense of security. Many dogs will bark when someone approaches the house, alerting their owners to unusual activity. Even small dogs can serve as effective early warning systems.

            Owning a dog also encourages a healthier lifestyle. Daily walks, playtime, and regular exercise help both the dog and the owner stay active. Instead of spending all day indoors, I would have a reason to get outside and be more physically active.

            Caring for a dog also teaches responsibility. Feeding, providing fresh water, grooming, cleaning up after the dog, and taking it to veterinary appointments all require commitment and consistency. These responsibilities help develop good habits and time management skills.

            Finally, dogs are simply fun to have around. Playing fetch, visiting parks, teaching tricks, and spending quality time together create lasting memories. Dogs greet their owners with excitement every day, making home feel more welcoming.

            Overall, I believe owning a dog would improve my life by providing companionship, encouraging a healthier lifestyle, teaching responsibility, offering additional security, and bringing happiness into my daily routine.
        """.trimIndent()

        assertTrue(ToneOutputPolicy.isSafe(source, goldStandard, WritingTone.PROFESSIONAL))
        assertTrue(
            RewriteValidator.isValid(
                source,
                source,
                goldStandard,
                WritingTone.PROFESSIONAL,
            ),
        )
    }

    @Test
    fun toneOutputPolicyAllowsSafeTerseCavemanRewrite() {
        val source = "You failed to send the files. Fix this immediately."

        assertTrue(
            ToneOutputPolicy.isSafe(
                source,
                "Send files now.",
                WritingTone.CAVEMAN,
            ),
        )
    }

    @Test
    fun safetyNetRepairsReportedAgreementFailures() {
        assertEquals(
            "Are we going to the store tomorrow?",
            GrammarSafetyNet.repair("Is we going to the store tomorrow?"),
        )
        assertEquals(
            "Those included are wrong.",
            GrammarSafetyNet.repair("Those included is wrong."),
        )
        assertEquals(
            "As a result, the owner may exercise more than before.",
            GrammarSafetyNet.repair(
                "As a result, the owner may exercise more than previously accustomed.",
            ),
        )
        assertEquals(
            "That is why I believe owning a dog is worthwhile.",
            GrammarSafetyNet.repair("It is why I believe owning a dog is worthwhile."),
        )
        assertEquals(
            "These are tasks that need to be remembered.",
            GrammarSafetyNet.repair("These are tasks that needs to be remembered."),
        )
        assertEquals(
            "These experiences may help those involved become more capable.",
            GrammarSafetyNet.repair(
                "These experiences may help them become more capable.",
            ),
        )
        assertEquals(
            0,
            GrammarQualityGate.errorScore(
                "The positive impact can improve a person's well-being.",
            ),
        )
        assertEquals(
            "He and I were going because we needed help.",
            GrammarSafetyNet.repair("Him and I were going because we needed help."),
        )
        assertEquals(
            "He and I were going because we need help.",
            LocalWritingAssistant.suggest(
                "Me and him was going because we needs help.",
                WritingTone.PROOFREAD,
            ),
        )
        assertEquals(
            "Between you and me, the analysis is better.",
            GrammarSafetyNet.repair("Between you and I, the analysis is more better."),
        )
        assertEquals(
            "Neither the managers nor the director is aware.",
            GrammarSafetyNet.repair("Neither the managers nor the director are aware."),
        )
        assertEquals(
            "The recommendations were based on evidence.",
            GrammarSafetyNet.repair("The recommendations were, based on evidence."),
        )
        assertEquals(
            "The company may have avoided the loss.",
            GrammarSafetyNet.repair("The company may avoided the loss."),
        )
        assertEquals(
            "Neither the evidence nor the testimony supports the claim.",
            GrammarSafetyNet.repair(
                "Neither the evidence nor the testimony support the claim.",
            ),
        )
        assertEquals(
            "Had the team known about the dependency, they would have changed the schedule.",
            GrammarSafetyNet.repair(
                "Had the team known about the dependency. They would change the schedule.",
            ),
        )
        assertEquals(
            "They would have changed the schedule.",
            GrammarSafetyNet.repair("They would of changed the schedule."),
        )
        assertEquals(
            "The proposal recommends that every department submit its findings.",
            GrammarSafetyNet.repair(
                "The proposal recommends that every department submits its findings.",
            ),
        )
        assertEquals(
            "The software not only failed, but it also deleted the backup.",
            GrammarSafetyNet.repair(
                "The software not only failed, but also it also deleted the backup.",
            ),
        )
        assertEquals(
            "The audit ended; however, the reviewers disagreed.",
            GrammarSafetyNet.repair(
                "The audit ended, however, the reviewers disagreed.",
            ),
        )
        assertEquals(
            "I couldn't care less, regardless of the delay.",
            GrammarSafetyNet.repair(
                "I could care less, irregardless of the delay.",
            ),
        )
        assertEquals(
            "The number of errors was high, the number of columns was too large, and less time was available.",
            GrammarSafetyNet.repair(
                "The amount of errors was high, the number of columns was too many, and fewer time was available.",
            ),
        )
        assertEquals(
            "The steering committee wants a weekly report, but it has not agreed on the format.",
            GrammarSafetyNet.repair(
                "The steering committee wants a weekly report, but they have not agreed on the format.",
            ),
        )
        assertEquals(
            "A series of outages has caused delays.",
            GrammarSafetyNet.repair("A series of outages have caused delays."),
        )
        assertEquals(
            "It is essential that the administrator verify every account; otherwise, access may fail.",
            GrammarSafetyNet.repair(
                "It is essential that the administrator verifies every account, otherwise access may fail.",
            ),
        )
        assertEquals(
            "If I were responsible, I would apply the rules consistently.",
            GrammarSafetyNet.repair(
                "If I was responsible, I would apply the rules consistently.",
            ),
        )
        assertEquals(
            "The manager, along with two analysts, was asked to respond.",
            GrammarSafetyNet.repair(
                "The manager, along with two analysts, were asked to respond.",
            ),
        )
        assertEquals(
            "Access is granted only after the administrator verifies the account.",
            GrammarSafetyNet.repair(
                "Access are granted only after the administrator verifies the account.",
            ),
        )
        assertEquals(
            "Unauthorized users may enter the system.",
            GrammarSafetyNet.repair("Unauthorized users may enters the system."),
        )
    }

    @Test
    fun grammarGateDoesNotMistakeSingularWordsEndingInSForPlurals() {
        assertEquals(
            0,
            GrammarQualityGate.errorScore(
                "A status summary describes a process, a series, and a glass panel.",
            ),
        )
        assertEquals(
            0,
            GrammarQualityGate.errorScore("The team has three immediate tasks:"),
        )
        assertEquals(
            "The team has three immediate tasks:",
            GrammarSafetyNet.repair("The team has three immediate tasks:"),
        )
        assertTrue(GrammarQualityGate.errorScore("The team assigned a tasks.") > 0)
    }

    @Test
    fun safetyNetProposesRepairsForPhysicalFivePageMisses() {
        assertEquals(
            "# Project Northstar: Five-Page Field Readiness Brief",
            GrammarSafetyNet.repair(
                "# project Northstar: Five Page Field Readiness Brief",
            ),
        )
        assertEquals(
            "Although the steering committee approved the budget, it has not approved overtime.",
            GrammarSafetyNet.repair(
                "Although the steering committee approved the budget, they have not approved overtime.",
            ),
        )
        assertEquals(
            "Northstar is a six-week digital-access pilot.",
            GrammarSafetyNet.repair("Northstar is a six week digital access pilot."),
        )
        assertEquals(
            "Mira said, \"Consent cannot be skipped.\"",
            GrammarSafetyNet.repair("Mira said \"Consent cannot be skipped.\""),
        )
        assertEquals(
            "After the draft instructions were read, the color contrast looked inadequate.",
            GrammarSafetyNet.repair(
                "After reading the draft instructions, the color contrast looked inadequate.",
            ),
        )
        assertEquals(
            "Dr. Bell should also check that every diagram has alt text, " +
                "every video includes captions, and should also test the QR codes.",
            GrammarSafetyNet.repair(
                "Dr. Bell should also check that every diagram has alt text, " +
                    "every video includes captions, and test the QR codes.",
            ),
        )
        assertEquals(
            "No student records may leave the center, even if a parent requests them.",
            GrammarSafetyNet.repair(
                "No student records may leave the center, even if a parent requests it.",
            ),
        )
        assertEquals(
            "The tablets must use encryption and lock automatically.",
            GrammarSafetyNet.repair(
                "The tablets must use encryption and automatic lock.",
            ),
        )
        assertEquals(
            "The second link requires a six-digit access code.",
            GrammarSafetyNet.repair(
                "The second link requires a six digit access code.",
            ),
        )
        assertEquals(
            "- Mira: Send the script, train volunteers, and confirm the room.",
            GrammarSafetyNet.repair(
                "- Mira: Send the script, trains volunteers, and confirm the room.",
            ),
        )
        assertEquals(
            "Before release, Jonah will configure them, and Elena will verify the settings.",
            GrammarSafetyNet.repair(
                "Before release, Jonah will configure them and Elena will verify the settings.",
            ),
        )
        assertEquals(
            "The team should be careful and transparent and should document decisions.",
            GrammarSafetyNet.repair(
                "The team should be careful, transparent, and document decisions.",
            ),
        )
        assertEquals(
            "Only Mira may access the dashboard; no volunteer may publish screenshots. " +
                "This restriction follows the privacy plan and needs to be clarified.",
            GrammarSafetyNet.repair(
                "Only Mira may access the dashboard; however, volunteers may publish screenshots " +
                    "when they think it is helpful. This permission is incorrect according to the " +
                    "privacy plan and needs clarification.",
            ),
        )
    }

    @Test
    fun proofreadSkipsTheToneModelAndUsesOneNeuralPass() {
        assertFalse(OnDeviceInferencePlan.usesToneModel(WritingTone.PROOFREAD))
        WritingTone.entries.filterNot { it == WritingTone.PROOFREAD }.forEach {
            assertTrue(OnDeviceInferencePlan.usesToneModel(it))
        }
    }

    @Test
    fun resultSelectorNeverReplacesRejectedAiWithRules() {
        val source = "Are we going to the store tomorrow? Those included are wrong."
        val prepared = "Are we going to the store tomorrow? Those included are wrong."

        assertThrows(LocalRewriteRejectedException::class.java) {
            GrammarResultSelector.select(
                source,
                prepared,
                "Are we going to the store the tomorrow? Those included is wrong.",
            )
        }
    }

    @Test
    fun acceptedAiWordingIsReturnedExactlyWithoutRuleRewriting() {
        val aiOutput = "If I was responsible, I would apply the rules consistently."

        assertEquals(
            aiOutput,
            GrammarResultSelector.select(aiOutput, aiOutput, aiOutput),
        )
    }

    @Test
    fun resultSelectorRejectsBrokenCounterfactualTense() {
        val source =
            "If the policy had been implemented earlier, the company may have avoided the losses."

        assertThrows(LocalRewriteRejectedException::class.java) {
            GrammarResultSelector.select(
                source,
                source,
                "If the policy had been implemented earlier, the company may avoid the losses.",
            )
        }
    }

    @Test
    fun proofreadPreservesAdultVocabulary() {
        val prepared =
            "It is essential that the administrator verify every account before access is granted."
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                prepared,
                "It is essential that the administrator check every account before access is granted.",
            ),
        )
        assertTrue(
            ProofreadPreservationPolicy.preservesWording(
                "The results differ from the report.",
                "The result differs from the report.",
            ),
        )
        assertFalse(
            ProofreadPreservationPolicy.preservesWording(
                "One reason the project failed is that requirements were unclear.",
                "One reason the project failed. Is that requirements were unclear.",
            ),
        )
        assertThrows(LocalRewriteRejectedException::class.java) {
            GrammarResultSelector.select(
                prepared,
                prepared,
                "It is essential that the administrator check every account before access is granted.",
            )
        }
        assertEquals(
            "They represent the pattern, and it poses a risk; nor were the reviewers informed.",
            GrammarSafetyNet.repair(
                "They represents the pattern, and it pose a risk; nor was the reviewers informed.",
            ),
        )
        assertEquals(
            "One of the reasons the project has failed is that requirements were unclear.",
            GrammarSafetyNet.repair(
                "One of the reasons the project has failed. Is that requirements were unclear.",
            ),
        )
    }

    @Test
    fun proofreadPreservationAllowsRequiredSpellingPronounAndFragmentRepairs() {
        val prepared =
            "Project Northstar has a Readyness brief. Mira spoke to Jonah Patel and myself. " +
                "They reviewed the plan. Because trust matters."
        val corrected =
            "Project Northstar has a Readiness brief. Mira spoke to Jonah Patel and me. " +
                "They reviewed the plan because trust matters."

        assertTrue(ProofreadPreservationPolicy.preservesWording(prepared, corrected))
    }

    @Test
    fun activeDocumentPipelineUsesRulesOnlyAsAnAiReviewedRetryProposal() {
        val source =
            "Dogs wag their tails. The amount of columns was high. We had fewer information. " +
                "They should makes the report clear and could skipped nothing."
        val candidate = source

        assertEquals(
            "Dogs wag their tails. The number of columns was high. We had less information. " +
                "They should make the report clear and could have skipped nothing.",
            AiGrammarPostEditor.edit(source, candidate, WritingTone.PROOFREAD),
        )
        assertEquals(
            "The museum works with Indigenous peoples.",
            AiGrammarPostEditor.edit(
                "The museum works with Indigenous peoples.",
                "The museum works with Indigenous peoples.",
                WritingTone.PROOFREAD,
            ),
        )
        assertEquals(
            "# Tasks\n\n- Jonah: Deliver the kits.\n- Elena: Verify the seals.\n",
            AiGrammarPostEditor.edit(
                "# Tasks\n\n- Jonah: deliver the kits.\n- Elena: verify the seals.\n",
                "# Tasks\n\nJonah: Deliver the kits.\nElena: Verify the seals.",
                WritingTone.PROOFREAD,
            ),
        )

        val engine = File(
            requireNotNull(System.getProperty("echoLinkAndroidDir")),
            "app/src/main/java/com/echolink/mobile/OnDeviceGrammarEngine.kt",
        ).readText()
        val livePipeline = engine
            .substringAfter("fun polish(")
            .substringBefore("fun trimMemory(")
        assertTrue(livePipeline.contains("PersistentDocumentCorrectionRunner"))
        assertTrue(livePipeline.contains("runner.run("))
        assertFalse(livePipeline.contains("AiGrammarPostEditor.edit"))
        assertTrue(
            livePipeline.contains(
                "prepareGrammarRetryCandidate = GrammarSafetyNet::repair",
            ),
        )
        assertTrue(
            livePipeline.contains(
                "acceptsGeneratedGrammar = correctionRouter::acceptsGeneratedGrammar",
            ),
        )
        assertTrue(
            livePipeline.contains(
                "correctionRouter::passesGeneratedGrammarChecks",
            ),
        )
        val router = File(
            requireNotNull(System.getProperty("echoLinkAndroidDir")),
            "app/src/main/java/com/echolink/mobile/LocalCorrectionPipeline.kt",
        ).readText()
        val generatedGrammarGate = router
            .substringAfter("fun acceptsGeneratedGrammar")
            .substringBefore("private fun detectStructuralIssues")
        assertTrue(
            generatedGrammarGate.contains(
                "GrammarSafetyNet.repair(text) != text",
            ),
        )
        assertTrue(
            router
                .substringAfter("fun passesGeneratedGrammarChecks")
                .substringBefore("private fun detectStructuralIssues")
                .contains("GrammarQualityGate.errorScore(text) != 0"),
        )
    }

    @Test
    fun grammarSafetyNetDoesNotSplitEmbeddedHowClause() {
        val correct = "Dogs wag their tails because it helps people understand how they feel."

        assertEquals(correct, GrammarSafetyNet.repair(correct))
    }
    @Test
    fun residualDeviceGrammarFamiliesAreRepairedAfterAi() {
        val source = """
            The Readyness review was aimed at Jordan Lee and myself.
            Neither the analysts nor the site manager are allowed to publish.
            The checklist are mandatory, it cannot be skipped.
            Morgan asked whether the shipment was insured?
            The carrier confirms coverage last Tuesday.
            Ava favors one tablet, Ben prefers paper.
            A tablet creates a queue, paper creates a privacy risk.
            Jordan will configure the tablets and Ava had verified the settings.
            Mark incomplete items red, however nobody knows who approves them.
            Morgan will run a rehearsal, record defects, then assigns owners.
            The volunteers does not approve exceptions.
            This policy is different than the prior policy.
            They need to explain deletion. Because trust matters.
            The plan includes a guide and a help desk, neither of which are ready.
            Staff should explain the rule, record the request, and they must not share it.
            If a coordinator is unsure. They should email the manager.
            The first link is public, the second link requires a code.
            If a volunteer asks for, the password, staff should explain the rule.
            The group needs a format that is readable, complete, and does not expose records.

            - Jordan: Deliver the kits, labeling every carton, and he must update the count.
            - Ava: Verify seals; photographs damage; and submit the log.
            - Morgan: Sends the script, train volunteers, confirm the room.
            - Module 3: teaches escalation and incident reporting.
        """.trimIndent()

        val repaired = AiGrammarPostEditor.edit(source, source, WritingTone.PROOFREAD)

        assertTrue(repaired, repaired.contains("Readiness review"))
        assertTrue(repaired.contains("Jordan Lee and me"))
        assertTrue(repaired.contains("site manager is allowed"))
        assertTrue(repaired.contains("checklist is mandatory; it cannot"))
        assertTrue(repaired.contains("shipment was insured."))
        assertTrue(repaired.contains("carrier confirmed coverage last Tuesday"))
        assertTrue(repaired.contains("Ava favors one tablet; Ben prefers paper"))
        assertTrue(repaired.contains("A tablet creates a queue; paper creates a privacy risk"))
        assertTrue(repaired.contains("Jordan will configure the tablets, and Ava will verify"))
        assertTrue(repaired.contains("red; however, nobody"))
        assertTrue(repaired.contains("record defects, then assign owners"))
        assertTrue(repaired.contains("volunteers do not"))
        assertTrue(repaired.contains("different from the prior policy"))
        assertTrue(repaired.contains("explain deletion because trust matters."))
        assertTrue(repaired.contains("neither of which is ready"))
        assertTrue(repaired.contains("record the request, and not share it"))
        assertTrue(repaired, repaired.contains("If a coordinator is unsure, they should email the manager."))
        assertTrue(repaired.contains("The first link is public; the second link requires a code."))
        assertTrue(repaired.contains("asks for the password"))
        assertTrue(repaired.contains("a readable, complete format that does not expose records"))
        assertTrue(repaired.contains("- Jordan: Deliver the kits, label every carton, and update the count."))
        assertTrue(repaired.contains("- Ava: Verify seals; photograph damage; and submit the log."))
        assertTrue(repaired.contains("- Morgan: Send the script, train volunteers, confirm the room."))
        assertTrue(repaired.contains("- Module 3 teaches escalation and incident reporting."))
    }

    @Test
    fun processTextSuppressesDuplicateActiveCorrectionRequests() {
        val root = File(requireNotNull(System.getProperty("echoLinkAndroidDir")))
        val source = File(
            root,
            "app/src/main/java/com/echolink/mobile/ProcessTextActivity.kt",
        ).readText()

        assertTrue(source.contains("activeModelRequestKey == requestKey"))
        assertTrue(source.contains("activeModelRequestKey = requestKey"))
    }

    @Test
    fun everyCorrectionEntryPointChecksForInstalledAi() {
        val root = File(requireNotNull(System.getProperty("echoLinkAndroidDir")))
        listOf(
            "MainActivity.kt",
            "ProcessTextActivity.kt",
            "ManuscriptAccessibilityService.kt",
        ).forEach { fileName ->
            val source = File(
                root,
                "app/src/main/java/com/echolink/mobile/$fileName",
            ).readText()
            assertTrue(
                "$fileName did not route missing AI to installation.",
                source.contains("LocalAiCorrectionGate.isReady("),
            )
        }
    }

    @Test
    fun correctionGateRejectsMissingAiAndAllowsInstalledAi() {
        assertFalse(LocalAiCorrectionGate.isReady(null))
        assertThrows(LocalModelRequiredException::class.java) {
            LocalAiCorrectionGate.requireInstalled(null)
        }

        val installedAi = File("verified-local-ai")
        assertTrue(LocalAiCorrectionGate.isReady(installedAi))
        LocalAiCorrectionGate.requireInstalled(installedAi)
    }

    @Test
    fun outputPolicyRejectsLostProtectedContent() {
        val source = "Email test@example.com before 4:30 at https://example.com."
        assertTrue(
            GrammarOutputPolicy.isSafe(
                source,
                "Please email test@example.com before 4:30 at https://example.com.",
            ),
        )
        assertFalse(GrammarOutputPolicy.isSafe(source, "Please email someone tomorrow."))
    }

    @Test
    fun appUsesAiFirstAndNeverRuleRewritesAiOutput() {
        val root = File(requireNotNull(System.getProperty("echoLinkAndroidDir")))
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val build = File(root, "app/build.gradle").readText()
        val engine = File(
            root,
            "app/src/main/java/com/echolink/mobile/OnDeviceGrammarEngine.kt",
        ).readText()
        val runtime = File(
            root,
            "app/src/main/java/com/echolink/mobile/LiteRtGemmaBackend.kt",
        ).readText()
        val manager = File(
            root,
            "app/src/main/java/com/echolink/mobile/AiModelManager.kt",
        ).readText()

        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(build.contains("onnxruntime-android:1.27.0"))
        assertFalse(build.contains("onnxruntime-extensions-android"))
        assertTrue(build.contains("project(\":llama-runtime\")"))
        assertTrue(build.contains("litertlm-android:0.14.0"))
        assertTrue(engine.contains("No prompt, source text, or generated text is sent"))
        assertTrue(engine.contains("PersistentDocumentCorrectionRunner"))
        assertTrue(engine.contains("DocumentJobRequest"))
        val livePipeline = engine
            .substringAfter("fun polish(")
            .substringBefore("fun trimMemory(")
        assertTrue(
            livePipeline.contains(
                "prepareGrammarRetryCandidate = GrammarSafetyNet::repair",
            ),
        )
        assertFalse(livePipeline.contains("AiGrammarPostEditor"))
        assertFalse(livePipeline.contains("correctionRouter.analyze"))
        val installGate = engine.indexOf("LocalAiCorrectionGate.requireInstalled")
        val aiRewrite = engine.indexOf("runner.run(")
        assertTrue(installGate >= 0)
        assertTrue(installGate < aiRewrite)
        assertTrue(runtime.contains("SamplerConfig"))
        assertTrue(runtime.contains("seed = samplingPolicy.seed"))
        assertTrue(manager.contains("OneTimeWorkRequestBuilder<AiModelDownloadWorker>"))
        assertTrue(manager.contains("qualifyAndActivate"))
    }

    @Test
    fun qwen3TierSelectionScalesWithPhoneCapability() {
        val gib = 1_073_741_824L
        assertEquals(
            Qwen3Tier.BASIC,
            Qwen3CapabilitySelector.select(
                Qwen3DeviceCapability(4 * gib, 4, 2 * gib, true),
            ),
        )
        assertEquals(
            Qwen3Tier.BALANCED,
            Qwen3CapabilitySelector.select(
                Qwen3DeviceCapability(8 * gib, 8, 5 * gib, true),
            ),
        )
        assertEquals(
            Qwen3Tier.ADVANCED,
            Qwen3CapabilitySelector.select(
                Qwen3DeviceCapability(
                    totalRamBytes = 12 * gib,
                    availableProcessors = 8,
                    usableStorageBytes = 8 * gib,
                    is64Bit = true,
                    availableRamBytes = 4 * gib,
                    androidApi = 35,
                    performanceClass = 35,
                    hasVulkanHardware = true,
                ),
                Qwen3InferenceBenchmark(Qwen3Tier.BALANCED, 2_500L, true),
            ),
        )
        assertEquals(
            Qwen3Tier.BASIC,
            Qwen3CapabilitySelector.select(
                Qwen3DeviceCapability(12 * gib, 8, 8 * gib, true),
                mode = Qwen3UserMode.FASTER,
            ),
        )
        assertEquals(3, Qwen3Tier.entries.size)
        assertTrue(Qwen3Tier.entries.all { it.repository.contains("Qwen3") })
        assertTrue(Qwen3Tier.BALANCED.fileName.endsWith("Q4_K_M.gguf"))
    }

    @Test
    fun s23PlusKeepsBalancedModelWhenTransientFreeMemoryIsLow() {
        val gib = 1_073_741_824L
        assertEquals(
            Qwen3Tier.BALANCED,
            Qwen3CapabilitySelector.select(
                Qwen3DeviceCapability(
                    totalRamBytes = 7_242_412L * 1_024L,
                    availableProcessors = 8,
                    usableStorageBytes = 179L * gib,
                    is64Bit = true,
                    availableRamBytes = 1_586_832L * 1_024L,
                    androidApi = 36,
                    performanceClass = 0,
                    hasVulkanHardware = true,
                ),
            ),
        )
    }

    @Test
    fun s20UltraBestQualityUsesAdvancedModelForMarketed12GbRam() {
        val gib = 1_073_741_824L
        assertEquals(
            Qwen3Tier.ADVANCED,
            Qwen3CapabilitySelector.select(
                Qwen3DeviceCapability(
                    totalRamBytes = 10_857_128L * 1_024L,
                    availableProcessors = 8,
                    usableStorageBytes = 40L * gib,
                    is64Bit = true,
                    availableRamBytes = 5L * gib,
                    androidApi = 33,
                    performanceClass = 0,
                    hasVulkanHardware = true,
                ),
                mode = Qwen3UserMode.BEST_QUALITY,
            ),
        )
    }

    @Test
    fun s23PlusDowngradesToCompactModelAfterSlowBalancedBenchmark() {
        val gib = 1_073_741_824L
        assertEquals(
            Qwen3Tier.BASIC,
            Qwen3CapabilitySelector.select(
                Qwen3DeviceCapability(
                    totalRamBytes = 7_242_412L * 1_024L,
                    availableProcessors = 8,
                    usableStorageBytes = 179L * gib,
                    is64Bit = true,
                    availableRamBytes = 1_586_832L * 1_024L,
                    androidApi = 36,
                    performanceClass = 0,
                    hasVulkanHardware = true,
                ),
                Qwen3InferenceBenchmark(Qwen3Tier.BALANCED, 16_000L, true),
            ),
        )
    }

    @Test
    fun startupReadinessGuidesEachMissingRequirementInOrder() {
        val selection = AiRuntimeSelection(
            GemmaModelCatalog.fast,
            BackendKind.LITERT_CPU,
        )
        assertEquals(
            ManuscriptStartupAction.ENABLE_ACROSS_APPS,
            ManuscriptStartupReadiness.nextAction(
                acrossAppsEnabled = false,
                modelState = AiModelState.ConsentRequired(selection),
            ),
        )
        assertEquals(
            ManuscriptStartupAction.INSTALL_MODEL,
            ManuscriptStartupReadiness.nextAction(
                acrossAppsEnabled = true,
                modelState = AiModelState.ConsentRequired(selection),
            ),
        )
        assertEquals(
            ManuscriptStartupAction.WAIT_FOR_MODEL,
            ManuscriptStartupReadiness.nextAction(
                acrossAppsEnabled = true,
                modelState = AiModelState.Downloading(
                    selection,
                    downloaded = 500L,
                    total = 1_000L,
                ),
            ),
        )
        assertEquals(
            ManuscriptStartupAction.READY,
            ManuscriptStartupReadiness.nextAction(
                acrossAppsEnabled = true,
                modelState = AiModelState.Ready(
                    selection,
                    InstalledAiModel(
                        GemmaModelCatalog.fast,
                        File("gemma-4-E2B-it.litertlm"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun startupReadinessNeverCallsAnUnverifiedModelReady() {
        val selection = AiRuntimeSelection(
            GemmaModelCatalog.fast,
            BackendKind.LITERT_CPU,
        )
        assertFalse(
            ManuscriptStartupReadiness.isModelReady(
                AiModelState.Checking(selection),
            ),
        )
        assertFalse(
            ManuscriptStartupReadiness.isModelReady(
                AiModelState.Failed(selection, "Self-test failed"),
            ),
        )
        assertTrue(
            ManuscriptStartupReadiness.isModelReady(
                AiModelState.Ready(
                    selection,
                    InstalledAiModel(
                        GemmaModelCatalog.fast,
                        File("gemma-4-E2B-it.litertlm"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun latestRewriteRequestCancelsStaleWork() {
        val first = RecordingRewriteRequest()
        val second = RecordingRewriteRequest()
        val slot = LatestRewriteRequestSlot()

        slot.replace(first)
        slot.replace(second)

        assertTrue(first.cancelled)
        assertFalse(second.cancelled)

        slot.cancel()

        assertTrue(second.cancelled)
    }

    @Test
    fun slowDeviceDoesNotRunOldBalancedModelWhileCompactModelDownloads() {
        assertFalse(
            Qwen3RuntimeTierPolicy.canRun(
                selected = Qwen3Tier.BASIC,
                installed = Qwen3Tier.BALANCED,
            ),
        )
        assertTrue(
            Qwen3RuntimeTierPolicy.canRun(
                selected = Qwen3Tier.BASIC,
                installed = Qwen3Tier.BASIC,
            ),
        )
        assertTrue(
            Qwen3RuntimeTierPolicy.canRun(
                selected = Qwen3Tier.ADVANCED,
                installed = Qwen3Tier.BALANCED,
            ),
        )
    }

    private class RecordingRewriteRequest : LocalPolishRequest {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }

    @Test
    fun failedAiNeverReturnsRuleOnlyCorrection() {
        assertThrows(LocalRewriteRejectedException::class.java) {
            RewriteFallbackPolicy.select(
                prepared = "dogs are awesome",
                tone = WritingTone.EMPATHETIC,
                modelCandidateAvailable = false,
            )
        }
        assertThrows(LocalRewriteRejectedException::class.java) {
            RewriteFallbackPolicy.select(
                prepared = "dogs are awesome",
                tone = WritingTone.EMPATHETIC,
                modelCandidateAvailable = true,
            )
        }
        assertThrows(LocalRewriteRejectedException::class.java) {
            RewriteFallbackPolicy.select(
                prepared = "dogs are awesome",
                tone = WritingTone.PROOFREAD,
                modelCandidateAvailable = false,
            )
        }
        assertThrows(LocalRewriteRejectedException::class.java) {
            RewriteFallbackPolicy.select(
                prepared = "dogs are awesome",
                tone = WritingTone.PROOFREAD,
                modelCandidateAvailable = true,
            )
        }
    }

    @Test
    fun qualityGateRejectsTheBadRealQwenGrammarSeenOnTheEmulator() {
        val source = "i has three error it dont make sense and punctuation is bad"
        val deterministic = LocalWritingAssistant.suggest(source, WritingTone.PROOFREAD)
        val badModel = "i have three error it done make sense and punctuation is bad"

        assertTrue(GrammarQualityGate.errorScore(badModel) > 0)
        assertThrows(LocalRewriteRejectedException::class.java) {
            GrammarResultSelector.select(source, deterministic, badModel)
        }
    }

    @Test
    fun longRewriteChunksReassembleWithoutChangingFormatting() {
        val source = buildString {
            repeat(14) { index ->
                append("Paragraph $index has enough words to exercise safe sentence splitting.")
                if (index != 13) append(if (index % 2 == 0) "\r\n\r\n" else "\n")
            }
        }
        val chunks = LocalRewriteChunker.split(source, 220)
        assertTrue(chunks.size > 1)
        assertEquals(source, chunks.joinToString("") { it.text + it.separatorAfter })
    }

    @Test
    fun eachParagraphIsItsOwnModelRequestAndFormattingStillRoundTrips() {
        val source = "First paragraph.\r\n\r\nSecond paragraph.\n\nThird paragraph."
        val chunks = LocalRewriteChunker.split(source, 1_024)

        assertEquals(3, chunks.size)
        assertTrue(chunks.all { !Regex("\\n\\s*\\n").containsMatchIn(it.text) })
        assertEquals(source, chunks.joinToString("") { it.text + it.separatorAfter })
    }

    @Test
    fun rewriteValidatorRejectsChangedFactsNamesAndFormatting() {
        val source = "Send Project Atlas to Morgan on July 14.\n\n- Keep ticket 417."
        assertTrue(
            RewriteValidator.isValid(
                source,
                source,
                "Please send Project Atlas to Morgan on July 14.\n\n- Keep ticket 417.",
                WritingTone.FRIENDLY,
            ),
        )
        assertFalse(
            RewriteValidator.isValid(
                source,
                source,
                "Please send Project Atlas to Jordan on July 15.\n- Keep ticket 418.",
                WritingTone.FRIENDLY,
            ),
        )
    }

    @Test
    fun rewriteValidatorAllowsRequiredProperNameCapitalization() {
        val source = "# project northstar: field brief\n\nThe project starts April 14, 2027."
        val corrected = "# Project Northstar: Field Brief\n\nThe project starts April 14, 2027."

        assertTrue(
            RewriteValidator.isValid(
                source = source,
                prepared = corrected,
                output = corrected,
                tone = WritingTone.PROOFREAD,
            ),
        )
    }
}
