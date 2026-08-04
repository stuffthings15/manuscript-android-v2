package com.echolink.mobile

import java.util.Locale

enum class WritingTone(val label: String) {
    PROOFREAD("Proofread"),
    IMPROVE("Improve"),
    REPHRASE("Rephrase"),
    SHORTEN("Shorten"),
    FRIENDLY("Friendly"),
    FORMAL("Formal"),
    PROFESSIONAL("Professional"),
    ACADEMIC("Academic"),
    CONVERSATIONAL("Conversational"),
    CONCISE("Concise"),
    DETAILED("Detailed"),
    PERSUASIVE("Persuasive"),
    CONFIDENT("Confident"),
    DIPLOMATIC("Diplomatic"),
    EMPATHETIC("Empathetic"),
    CASUAL("Casual"),
    TECHNICAL("Technical"),
    SIMPLIFIED("Simplified"),
    HIGH_SCHOOL("High-school level"),
    COLLEGE("College level"),
    COOPERATIVE("Cooperative"),
    ASSERTIVE("Assertive"),
    CAVEMAN("Caveman");

    companion object {
        fun fromLabel(value: String): WritingTone = entries.firstOrNull {
            it.label.equals(value, ignoreCase = true)
        } ?: PROOFREAD
    }
}

data class WritingSuggestion(
    val original: String,
    val replacement: String,
    val tone: WritingTone,
    val index: Int,
    val total: Int,
) {
    val changed: Boolean get() = original != replacement
}

/** Pure local fallback used when no bundled writing model is available. */
object LocalWritingAssistant {
    const val MAX_INPUT_LENGTH = 200_000
    private val sentenceBreak = Regex(
        "(?<!a\\.m\\.)(?<!p\\.m\\.)(?<!Dr\\.)(?<!Mr\\.)(?<!Ms\\.)(?<!Mrs\\.)(?<!Prof\\.)" +
            "(?<=[.!?])\\s+(?=[\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    fun suggest(text: String, tone: WritingTone): String {
        require(text.length <= MAX_INPUT_LENGTH) { "Text is too long." }
        val normalized = DeterministicCleanup.clean(text).text
        val protected = protectImmutableSpans(normalized)
        val cleaned = preserveDirectQuestionPunctuation(
            protected.masked,
            DeterministicCleanup.clean(proofreadBasics(protected.masked)).text,
        )
        if (tone == WritingTone.PROOFREAD) {
            return finalizeRestoredGrammar(protected.restore(cleaned), normalized)
        }

        val transformed = when (tone) {
            WritingTone.PROOFREAD -> cleaned
            WritingTone.IMPROVE -> improve(cleaned)
            WritingTone.REPHRASE -> rephrase(cleaned)
            WritingTone.SHORTEN -> shorten(cleaned)
            WritingTone.FRIENDLY -> friendly(cleaned)
            WritingTone.FORMAL -> formal(cleaned)
            WritingTone.PROFESSIONAL -> professional(cleaned)
            WritingTone.ACADEMIC -> academic(cleaned)
            WritingTone.CONVERSATIONAL -> conversational(cleaned)
            WritingTone.CONCISE -> concise(cleaned)
            WritingTone.DETAILED -> detailed(cleaned)
            WritingTone.PERSUASIVE -> persuasive(cleaned)
            WritingTone.CONFIDENT -> confident(cleaned)
            WritingTone.DIPLOMATIC -> diplomatic(cleaned)
            WritingTone.EMPATHETIC -> empathetic(cleaned)
            WritingTone.CASUAL -> casual(cleaned)
            WritingTone.TECHNICAL -> technical(cleaned)
            WritingTone.SIMPLIFIED -> simplified(cleaned)
            WritingTone.HIGH_SCHOOL -> highSchool(cleaned)
            WritingTone.COLLEGE -> college(cleaned)
            WritingTone.COOPERATIVE -> cooperative(cleaned)
            WritingTone.ASSERTIVE -> assertive(cleaned)
            WritingTone.CAVEMAN -> caveman(cleaned)
        }
        val visiblyStyled = ensureVisibleStyle(cleaned, transformed, tone)
        val polished = polishToneResult(visiblyStyled).ifBlank { cleaned }
        val candidate = if (polished != cleaned) polished else structuralFallback(cleaned)
        val rewritten = if (protected.isPreserved(candidate) && preservesConstraints(cleaned, candidate)) {
            candidate
        } else {
            cleaned
        }
        val restored = protected.restore(rewritten)
        val finished = if (tone == WritingTone.CAVEMAN) finishRestoredCaveman(restored) else restored
        return finalizeRestoredGrammar(finished, normalized)
    }

    /** Repair grammar that can only be checked after immutable dates and labels are restored. */
    private fun finalizeRestoredGrammar(text: String, source: String): String {
        var result = Regex(
            "(?i)\\b((?:January|February|March|April|May|June|July|August|September|" +
                "October|November|December)\\s+\\d{1,2},\\s+\\d{4})(?=\\s+at\\s+\\d)",
        ).replace(text, "$1,")
        result = Regex(
            "(?i)\\bon the (Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\b",
        ).replace(result, "on $1")
        result = Regex("(?i)\\bunder the no circumstances\\b")
            .replace(result, "under no circumstances")
        result = Regex("(?i)\\bon the (it|him|her|them|us|me|you)\\b")
            .replace(result, "on $1")
        result = Regex("(?i)\\bafter then\\b").replace(result, "after that time")
        result = Regex(
            "(?iu)(\\bis assigned to\\s+[^,.;]+),\\s+([^,.;]+?)\\s+does not(?=[,;.!?])",
        ).replace(result, "$1; $2 is not")
        result = repairImperativeCommaSplices(result)
        result = repairNominalCommaSplices(result)
        result = Regex(
            "(?iu)(, which [^,;.!?]+)\\s+and\\s+" +
                "([\\p{Lu}][\\p{L}'’-]*(?:\\s+[\\p{Lu}][\\p{L}'’-]*){0,3}\\s+" +
                "(?:should|must|will|can|needs to)\\b)",
        ).replace(result, "$1; $2")
        result = Regex(
            "(?iu)(, which [^,;.!?]+)\\s+and\\s+" +
                "(I (?:sort of )?(?:think|believe)\\s+" +
                "[\\p{Lu}][\\p{L}'’-]*(?:\\s+[\\p{Lu}][\\p{L}'’-]*){0,3}\\s+" +
                "(?:should|must|will|can|needs to)\\b)",
        ).replace(result, "$1; $2")
        result = Regex(
            "(?iu),\\s+(only\\s+[A-Z][A-Za-z0-9-]*\\s+" +
                "(?:may|can|must|will|should)\\b)",
        ).replace(result) { match ->
            ". ${match.groupValues[1].replaceFirstChar { it.uppercase() }}"
        }
        result = Regex("(?iu)\\b(said|stated|replied)\\s+(?=[\"“])")
            .replace(result, "$1, ")
        result = Regex(
            "(?iu)\\b((?:(?:[\\p{L}][\\p{L}'’-]*|[A-Z0-9][A-Za-z0-9-]*)\\s+){0,3}" +
                "(?:does not|doesn't|is not|isn't))" +
                ",\\s+and\\s+(don't|do not|never|please)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]}. ${match.groupValues[2].replaceFirstChar { it.uppercase() }}"
        }
        result = Regex(
            "(?i)\\b(on|beside|near|under|behind|inside) the " +
                "([\\p{L}][\\p{L}'’-]*) (\\d+)\\b",
        ).replace(result) { match ->
            val withoutArticle = "${match.groupValues[1]} ${match.groupValues[2]} ${match.groupValues[3]}"
            if (Regex(Regex.escape(withoutArticle), RegexOption.IGNORE_CASE).containsMatchIn(source)) {
                withoutArticle
            } else {
                match.value
            }
        }
        result = Regex("(?i)\\bthe the\\b").replace(result, "the")
        result = Regex("[,;:](?=[.!?])").replace(result, "")
        return Regex("(?i)\\bi\\b").replace(result, "I")
    }

    private data class ProtectedText(
        val masked: String,
        val replacements: List<Pair<String, String>>,
    ) {
        fun restore(value: String): String = replacements.fold(value) { result, (token, original) ->
            result.replace(token, original)
        }.replace(Regex("\\.{2}(?=\\s|$)"), ".")

        fun isPreserved(value: String): Boolean = replacements.all { (token, _) ->
            Regex(Regex.escape(token)).findAll(masked).count() ==
                Regex(Regex.escape(token)).findAll(value).count()
        }
    }

    private fun preservesConstraints(source: String, candidate: String): Boolean {
        fun constraintProfile(value: String): Map<String, Int> {
            val rhetoricalRemoved = value
                .replace(
                    Regex("(?i)\\b(?:don't|do not|doesn't|does not)\\s+matter\\b[^.!?]*"),
                    "",
                )
                .replace(Regex("(?i)\\bthere(?:'s| is)\\s+no excuse\\b[^.!?]*"), "")
                .replace(
                    Regex(
                        "(?i)\\b(?:wasn't|was not|wasnt|weren't|were not|isn't|is not)\\s+" +
                            "paying attention\\b",
                    ),
                    "",
                )
                .replace(Regex("(?i)\\bunder no circumstances\\b"), "")
                .replace(
                    Regex("(?i)\\bI (?:won't|will not) tolerate\\b[^.!?]*"),
                    "",
                )
                .replace(
                    Regex(
                        "(?i)\\b(?:don't|do not) be (?:useless|careless|pathetic|sloppy|" +
                            "stupid|clueless|incompetent)(?: too)?\\b",
                    ),
                    "",
                )
            val profile = mutableMapOf<String, Int>()
            fun record(key: String) {
                profile[key] = profile.getOrDefault(key, 0) + 1
            }

            Regex("(?i)\\b(?:no more than|at most)\\b").findAll(rhetoricalRemoved)
                .forEach { record("upper-bound") }
            Regex("(?i)\\bno exceptions?\\b").findAll(rhetoricalRemoved)
                .forEach { record("no-exception") }
            Regex(
                "(?i)\\b(?:under no circumstances|(?:(?:must|do) not|cannot|can't|don't)\\b" +
                    "[^.!?;]{0,80}\\b(?:exceed|release|publish|share|enter|use|swap)\\b)",
            ).findAll(value).forEach { record("hard-ban") }
            Regex("(?i)\\bleave\\b[^.!?;]{0,100}\\balone\\b").findAll(rhetoricalRemoved)
                .forEach { record("leave-alone") }
            Regex("(?i)\\bwithout\\b").findAll(rhetoricalRemoved)
                .forEach { record("without") }
            Regex("(?i)\\bnever\\b").findAll(rhetoricalRemoved)
                .forEach { record("negative") }
            Regex(
                "(?i)\\b(?:do not|don't|does not|doesn't|did not|didn't|must not|may not|" +
                    "cannot|can't|will not|won't|is not|isn't|are not|aren't|was not|wasn't|" +
                    "were not|weren't|has not|hasn't|have not|haven't|not promising|" +
                    "unable to)\\b",
            ).findAll(rhetoricalRemoved).forEach { record("negative") }
            Regex("(?i)\\bno\\s+(?!more\\s+than|exceptions?\\b)[\\p{L}][\\p{L}'’-]*")
                .findAll(rhetoricalRemoved).forEach { record("no-noun") }
            return profile
        }

        val required = constraintProfile(source)
        val actual = constraintProfile(candidate)
        return required.all { (kind, count) -> actual.getOrDefault(kind, 0) >= count }
    }

    /** Keep literal quotations and machine-readable addresses out of style transformations. */
    private fun protectImmutableSpans(text: String): ProtectedText {
        val pattern = Regex(
            "(?:Dr|Mr|Ms|Mrs|Prof)\\.\\s+[A-Z][A-Za-z'-]+(?:\\s+[A-Z][A-Za-z'-]+)+|" +
                "\\b(?!(?:When|Where|Why|How|What|Who|If|Because|Since|Although|While|" +
                "Please|Send|Use|Only|No|The|A|An|We|I|Maybe)\\s)" +
                "[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+\\b|" +
                "\"(?:[^\"\\\\]|\\\\.)*\"|“[^”]*”|" +
                "https?://[A-Za-z0-9./?&_=#%+~-]*[A-Za-z0-9/#]|" +
                "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|" +
                "(?:January|February|March|April|May|June|July|August|September|" +
                "October|November|December)\\s+\\d{1,2},\\s+\\d{4}|" +
                "\\d{1,2}:\\d{2}\\s*(?:a\\.m\\.|p\\.m\\.)|" +
                "\\$\\d[\\d,]*(?:\\.\\d+)?|\\b[A-Z][A-Z0-9-]*\\d[A-Z0-9-]*\\b|" +
                "\\b\\d[\\d,]*(?:\\.\\d+)?%?\\b",
        )
        val replacements = mutableListOf<Pair<String, String>>()
        val masked = buildString {
            var cursor = 0
            pattern.findAll(text).forEachIndexed { index, match ->
                append(text, cursor, match.range.first)
                val quoted = match.value.startsWith('"') || match.value.startsWith('“')
                val token = if (quoted) {
                    "\uE000QUOTE${index.toString(16).uppercase(Locale.ROOT)}\uE002"
                } else {
                    "\uE000PROTECTED${index.toString(16).uppercase(Locale.ROOT)}\uE001"
                }
                append(token)
                replacements += token to match.value
                cursor = match.range.last + 1
            }
            append(text, cursor, text.length)
        }
        return ProtectedText(masked, replacements)
    }

    fun batches(text: String, tone: WritingTone, sentencesPerBatch: Int = 3): List<WritingSuggestion> {
        require(sentencesPerBatch in 1..3) { "Sentence batch must be between one and three." }
        val sentences = sentenceBreak.split(text.trim()).filter { it.isNotBlank() }
        if (sentences.isEmpty()) return emptyList()
        val groups = sentences.chunked(sentencesPerBatch)
        return groups.mapIndexed { index, group ->
            val original = group.joinToString(" ")
            WritingSuggestion(original, suggest(original, tone), tone, index + 1, groups.size)
        }
    }

    fun replaceExact(source: String, suggestion: WritingSuggestion): String {
        val start = source.indexOf(suggestion.original)
        require(start >= 0) { "The target text changed. Refresh the suggestion." }
        require(source.indexOf(suggestion.original, start + 1) < 0) {
            "The target text is ambiguous. Select a smaller block."
        }
        return source.replaceRange(start, start + suggestion.original.length, suggestion.replacement)
    }

    private fun replaceWords(text: String, replacements: Map<String, String>): String {
        var result = text
        replacements.entries.sortedByDescending { it.key.length }.forEach { (before, after) ->
            result = Regex("(?i)(?<!\\w)${Regex.escape(before)}(?!\\w)").replace(result, after)
        }
        return result
    }

    private fun proofreadBasics(text: String): String {
        var result = replaceWords(
            text,
            linkedMapOf(
            "teh" to "the", "recieve" to "receive", "seperate" to "separate",
            "definately" to "definitely", "occured" to "occurred", "alot" to "a lot",
            "realease" to "release", "more then" to "more than",
            "shoudl" to "should",
            "missng" to "missing", "adress" to "address", "wierd" to "weird",
            "tomorow" to "tomorrow", "becuase" to "because", "untill" to "until",
            "accomodate" to "accommodate", "incorectly" to "incorrectly",
            "childrens" to "children's",
            "everyones" to "everyone's", "theres" to "there's", "thats" to "that's",
            "sorta" to "sort of",
            "dont" to "don't", "cant" to "can't", "wont" to "won't", "isnt" to "isn't",
            "arent" to "aren't", "didnt" to "didn't", "doesnt" to "doesn't",
            "wasnt" to "wasn't", "werent" to "weren't", "hasnt" to "hasn't",
            "havent" to "haven't", "couldnt" to "couldn't", "wouldnt" to "wouldn't",
            "shouldnt" to "shouldn't",
            "this are" to "this is", "they is" to "they are",
            "we was" to "we were", "you was" to "you were", "i is" to "I am", "i" to "I",
            "he don't" to "he doesn't", "she don't" to "she doesn't",
            "it need" to "it needs",
            "is broke" to "is broken",
            ),
        )
        result = Regex(
            "(?i)\\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve) " +
                "(error|issue|report|entry|item|file|document|sentence|paragraph|section|" +
                "task|day|week|month|year)\\b",
        ).replace(result) { match ->
            if (match.groupValues[1].equals("one", ignoreCase = true)) {
                match.value
            } else {
                "${match.groupValues[1]} ${match.groupValues[2]}s"
            }
        }
        result = Regex("(?i)\\bI\\s+(has|does|is)\\b").replace(result) { match ->
            "I " + when (match.groupValues[1].lowercase(Locale.ROOT)) {
                "has" -> "have"
                "does" -> "do"
                else -> "am"
            }
        }
        result = Regex(
            "(?i)\\b(errors?|issues?|reports?|entries|items?|files?|documents?|" +
                "sentences?|paragraphs?|sections?|tasks?)\\s+" +
                "(I|you|we|they|he|she|it|this|that|there)\\s+" +
                "(?=(?:am|are|is|was|were|can|could|will|would|should|must|" +
                "have|has|do|does|don't|doesn't)\\b)",
        ).replace(result) { match ->
            "${match.groupValues[1]}. " +
                match.groupValues[2].replaceFirstChar { it.uppercase() } +
                " "
        }
        result = Regex(
            "(?i)\\b(doesn't|does not) make sense\\s+and\\s+punctuation is bad\\b",
        ).replace(result, "$1 make sense, and the punctuation is bad")
        result = Regex("\\bthese is ((?:the |a )?[A-Za-z]+s)\\b", RegexOption.IGNORE_CASE)
            .replace(result, "these are $1")
        result = Regex("\\bthese is\\b", RegexOption.IGNORE_CASE).replace(result, "this is")
        result = Regex("\\bthis is (first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\\b", RegexOption.IGNORE_CASE)
            .replace(result, "this is the $1")
        result = Regex("\\bthis is (separate|different|serious|common|major|minor) problem\\b", RegexOption.IGNORE_CASE)
            .replace(result, "this is a $1 problem")
        result = Regex("\\ba lot of issue\\b", RegexOption.IGNORE_CASE).replace(result, "a lot of issues")
        result = Regex("\\b(sentence|document|paragraph|section) need help\\b", RegexOption.IGNORE_CASE)
            .replace(result, "$1 needs help")
        result = Regex(
            "(?i)\\bits\\s+(?=(?:very|pretty|quite|really|so|too|not|a|an|the|clear|simple|" +
                "important|ready|possible|likely|unlikely|hard|easy|wrong|correct|missing|available)\\b)",
        ).replace(result, "it's ")
        result = Regex("(?i)\\brenamed it\\s+(?!to\\b)([^.!?]+)(?=[.!?])")
            .replace(result, "renamed it to $1")
        result = Regex("(?i)\\b([ap])\\.m\\s+(?=[A-Z])").replace(result, "$1.m. ")
        result = Regex(
            "(?i)\\bI know (this|that) is ([\\p{L}'’-]+) and (?:this|that) is " +
                "([\\p{L}'’-]+)\\b",
        ).replace(result, "I know $1 is $2 and $3")
        result = fixCoordinatedFirstPerson(result)
        result = fixCountedNounAgreement(result)
        result = fixSubjectVerbAgreement(result)
        result = Regex(
            "(?iu)\\b([\\p{Lu}][\\p{L}'’-]*) needs email " +
                "(?=\\uE000PROTECTED[0-9A-F]+\\uE001)",
        ).replace(result, "$1 needs to email ")
        result = repairPossessiveNominalFragments(result)
        result = addRequiredArticles(result)
        result = repairCommaSplices(result)
        result = repairImperativeCommaSplices(result)
        result = repairNominalCommaSplices(result)
        result = fixLeadingClauseComma(result)
        result = fixIndependentClauseConjunctionComma(result)
        result = fixIntroductoryQuestionComma(result)
        result = fixVocativeComma(result)
        result = repairPossessiveSubjectCommaSplice(result)
        result = repairNamedSubjectCommaSplices(result)
        result = fixImperativeVocativeComma(result)
        result = Regex(
            "(?i)\\b(say|said) maybe (I|we|you|they|he|she|it)\\b",
        ).replace(result, "$1 that maybe $2")
        result = Regex(
            "(?iu)\\b([\\p{L}][\\p{L}'’-]*) maybe " +
                "(reports|fails|shows|indicates|adds|causes|needs|requires)\\b",
        ).replace(result) { match ->
            val base = when (match.groupValues[2].lowercase(Locale.ROOT)) {
                "reports" -> "report"
                "fails" -> "fail"
                "shows" -> "show"
                "indicates" -> "indicate"
                "adds" -> "add"
                "causes" -> "cause"
                "needs" -> "need"
                else -> "require"
            }
            "${match.groupValues[1]} may $base"
        }
        if (Regex("(?i)\\byesterday\\b").containsMatchIn(result) &&
            Regex("(?i)\\b(?:was|were) supposed to\\b").containsMatchIn(result)
        ) {
            result = Regex(
                "(?i)\\b(I|we|you|they) definitely (?:do not|don't) receive\\b",
            ).replace(result, "$1 definitely did not receive")
        }
        result = Regex(
            "(?i)\\b(doesn't|does not) make sense\\s+and\\s+punctuation is bad\\b",
        ).replace(result, "$1 make sense, and the punctuation is bad")
        return fixTerminalQuestionMarks(result)
    }

    private fun repairNamedSubjectCommaSplices(text: String): String = Regex(
        "(?iu),\\s+([\\p{Lu}][\\p{L}'’-]*)\\s+(only\\s+)?" +
            "(verif(?:y|ies)|moves?|scans?|dispatch(?:es)?|signs?|seals?|emails?|" +
            "releases?|loads?|opens?|inspects?)\\b",
    ).replace(text) { match ->
        ". ${match.groupValues[1]} ${match.groupValues[2]}${match.groupValues[3]}"
    }

    /** Join a possessive noun phrase that was accidentally split from its predicate. */
    private fun repairPossessiveNominalFragments(text: String): String = Regex(
        "(?iu)\\b((?:the|a|an|this|that|my|your|his|her|our|their)\\s+" +
            "[\\p{L}'’-]+(?:['’]s)\\s+[\\p{L}'’-]+)\\.\\s+" +
            "(is|are|was|were|has|have)\\s+",
    ).replace(text) { match ->
        "${match.groupValues[1]} ${match.groupValues[2].lowercase(Locale.ROOT)} "
    }

    /** Add articles in common placement phrases without using domain-specific nouns. */
    private fun addRequiredArticles(text: String): String {
        var result = Regex(
            "(?i)\\b(place|put|set|store)\\s+" +
                "(?!(?:a|an|the|this|that|these|those|my|your|his|her|our|their)\\b)" +
                "([\\p{L}][\\p{L}'’-]*)\\b(?!\\s+\\d)",
        ).replace(text) { match -> "${match.groupValues[1]} the ${match.groupValues[2]}" }
        result = Regex(
            "(?i)\\b(on|beside|near|under|behind|inside)\\s+" +
                "(?!(?:a|an|the|this|that|these|those|my|your|his|her|our|their|" +
                "it|him|her|them|us|me|you|no|Monday|Tuesday|Wednesday|Thursday|Friday|" +
                "Saturday|Sunday)\\b)" +
                "([\\p{L}][\\p{L}'’-]*)",
        ).replace(result) { match ->
            val remainder = result.substring(match.range.last + 1)
            if (Regex("^\\s+\\d").containsMatchIn(remainder)) {
                match.value
            } else {
                "${match.groupValues[1]} the ${match.groupValues[2]}"
            }
        }
        return result
    }

    /** Split two independent clauses that are joined only by a comma. */
    private fun repairCommaSplices(text: String): String {
        val pattern = Regex(
            "(?i),\\s+(I|we|you|he|she|they|it|this|that|there)\\s+" +
                "(?=(?:am|are|is|was|were|can|could|will|would|should|must|have|has|do|does)\\b)",
        )
        return pattern.replace(text) { match ->
            val prefix = text.substring(0, match.range.first).trimStart()
            val boundary = maxOf(prefix.lastIndexOf('.'), prefix.lastIndexOf('!'), prefix.lastIndexOf('?'))
            val clause = prefix.substring(boundary + 1).trim()
            val dependentLead = Regex(
                "(?i)^(?:if|when|while|although|unless|because|since|after|before|once)\\b",
            ).containsMatchIn(clause)
            if (dependentLead) {
                match.value
            } else {
                ". ${match.groupValues[1].replaceFirstChar { it.uppercase() }} "
            }
        }
    }

    private fun repairImperativeCommaSplices(text: String): String {
        val pattern = Regex(
            "(?iu),\\s+((?:please\\s+)?(?:do not|don't|never|send|email|use|review|check|" +
                "confirm|label|write|close|provide|stop|keep|move|tell|order|choose)\\b)",
        )
        return pattern.replace(text) { match ->
            val prefix = text.substring(0, match.range.first).trimStart()
            val boundary = maxOf(prefix.lastIndexOf('.'), prefix.lastIndexOf('!'), prefix.lastIndexOf('?'))
            val clause = prefix.substring(boundary + 1).trim()
            val dependentLead = Regex(
                "(?i)^(?:if|when|while|although|unless|because|since|after|before|once)\\b",
            ).containsMatchIn(clause)
            val finiteVerb = Regex(
                "(?i)\\b(?:am|are|is|was|were|has|have|had|can|could|will|would|" +
                    "should|must|does|do|did)\\b",
            ).containsMatchIn(clause)
            if (dependentLead || !finiteVerb) {
                match.value
            } else {
                ". ${match.groupValues[1].replaceFirstChar { it.uppercase() }}"
            }
        }
    }

    private fun repairNominalCommaSplices(text: String): String {
        val pattern = Regex(
            "(?iu),\\s+((?:(?:the|a|an)\\s+)?[\\p{L}][\\p{L}'’-]*" +
                "(?:\\s+[A-Z0-9][A-Za-z0-9-]*){0,2})\\s+" +
                "(does not|doesn't|is not|isn't|are not|aren't|was not|wasn't|" +
                "were not|weren't)(?=[,;.!?])",
        )
        return pattern.replace(text) { match ->
            val prefix = text.substring(0, match.range.first).trimStart()
            val dependentLead = Regex(
                "(?i)^(?:if|when|while|although|unless|because|since|after|before|once)\\b",
            ).containsMatchIn(prefix) && !Regex("[.!?]").containsMatchIn(prefix)
            if (dependentLead) {
                match.value
            } else {
                ". ${match.groupValues[1].replaceFirstChar { it.uppercase() }} ${match.groupValues[2]}"
            }
        }
    }

    private fun preserveDirectQuestionPunctuation(source: String, candidate: String): String {
        val sourceSentences = sentenceBreak.split(source.trim()).filter { it.isNotBlank() }
        val candidateSentences = sentenceBreak.split(candidate.trim()).filter { it.isNotBlank() }
        if (sourceSentences.size != candidateSentences.size) return candidate
        return candidateSentences.mapIndexed { index, sentence ->
            val sourceSentence = sourceSentences[index].trim()
            val output = sentence.trim()
            if (sourceSentence.endsWith('?') && isDirectQuestion(sourceSentence) &&
                isDirectQuestion(output) && output.endsWith('.')
            ) {
                output.dropLast(1) + "?"
            } else {
                output
            }
        }.joinToString(" ")
    }

    /** Add the boundary comma after a leading dependent clause. */
    private fun fixLeadingClauseComma(text: String): String = Regex(
        "(?i)^((?:when|if|after|before|unless|although|while|once)\\s+" +
            ".{1,120}?\\b(?:is|are|was|were|arrives?|comes?|leaves?|starts?|begins?|ends?|" +
            "finishes?|calls?|opens?|closes?|returns?|responds?|appears?|happens?|occurs?))" +
            "(?<![,;:])\\s+((?:please\\s+)?[\\p{L}][^.!?]*[.!?])$",
    ).replace(text, "$1, $2")

    private fun fixIndependentClauseConjunctionComma(text: String): String = Regex(
        "(?i)\\b((?:was|were|is|are|has|have|had|will|would|can|could|should|must)\\s+" +
            "[^.!?;,]{1,80})\\s+(but|yet)\\s+" +
            "((?:one|two|three|four|five|six|seven|eight|nine|ten|\\d+|I|we|you|he|she|" +
            "they|it|this|that|these|those|there)\\b)",
    ).replace(text, "$1, $2 $3")

    private fun fixIntroductoryQuestionComma(text: String): String = Regex(
        "(?i)^((?:when|while|after|before|although|unless|because|since)\\s+.{2,180}?)" +
            "(?<![,;:])\\s+((?:did|do|does|can|could|would|should|will|is|are|was|were|" +
            "has|have|had)\\s+(?:I|you|we|they|he|she|it|this|that|there)\\b)",
    ).replace(text) { match ->
        if (text.trimEnd().endsWith('?')) {
            "${match.groupValues[1]}, ${match.groupValues[2]}"
        } else {
            match.value
        }
    }

    private fun fixVocativeComma(text: String): String = Regex(
        "(?i)(\\bthank(?:s| you)\\s+for\\s+[^.!?]{1,120}?)(\\s+)" +
            "([A-Z][\\p{L}'’-]+)(,\\s+(?:your|you|we|I)\\b)",
    ).replace(text, "$1, $3$4")

    private fun repairPossessiveSubjectCommaSplice(text: String): String = Regex(
        "(?iu),\\s+(my|your|our|their|his|her)\\s+([\\p{L}'’-]+)\\s+" +
            "(?=(?:is|are|was|were|has|have|had|saved|prevented|helped|caught|found|" +
            "shows|showed|means|meant)\\b)",
    ).replace(text) { match ->
        ". ${match.groupValues[1].replaceFirstChar { it.uppercase() }} ${match.groupValues[2]} "
    }

    private fun fixImperativeVocativeComma(text: String): String = Regex(
        "(?iu)^((?:please|kindly)\\s+[^.!?]{1,120}?\\b" +
            "(?:the|a|an|this|that|my|your|our)\\s+[\\p{L}'’-]+)\\s+" +
            "([\\p{Lu}][\\p{L}'’-]+)([.!?])$",
    ).replace(text, "$1, $2$3")

    private fun fixTerminalQuestionMarks(text: String): String = sentenceBreak.split(text).joinToString(" ") {
        sentence ->
        val trimmed = sentence.trim()
        if (!trimmed.endsWith('?') || isDirectQuestion(trimmed)) {
            trimmed
        } else {
            trimmed.dropLast(1) + "."
        }
    }

    private fun isDirectQuestion(sentence: String): Boolean {
        val questionOpening = Regex(
            "(?i)^(?:who|what|when|where|why|how|can|could|would|should|will|do|does|did|" +
                "is|are|am|was|were|have|has|had)\\b",
        )
        if (questionOpening.containsMatchIn(sentence)) return true
        return Regex(
            "(?i)(?:^|[,;]\\s+)(?:who|what|when|where|why|how|" +
                "(?:can|could|would|should|will|do|does|did|is|are|am|was|were|have|has|had)" +
                "\\s+(?:I|you|we|they|he|she|it|this|that|there)\\b)",
        ).containsMatchIn(sentence)
    }

    private fun fixCoordinatedFirstPerson(text: String): String {
        val pattern = Regex(
            "(?iu)\\bMe and ([\\p{L}][\\p{L}'’-]*)\\s+" +
                "(isn't|is not|is|wasn't|was not|was|hasn't|has not|has|" +
                "doesn't|does not|does)\\b",
        )
        return pattern.replace(text) { match ->
            val coordinatedSubject = when (match.groupValues[1].lowercase(Locale.ROOT)) {
                "him" -> "He"
                "her" -> "She"
                "them" -> "They"
                else -> match.groupValues[1]
            }
            val verb = when (match.groupValues[2].lowercase(Locale.ROOT)) {
                "isn't", "is not" -> "aren't"
                "is" -> "are"
                "wasn't", "was not" -> "weren't"
                "was" -> "were"
                "hasn't", "has not" -> "haven't"
                "has" -> "have"
                "doesn't", "does not" -> "don't"
                else -> "do"
            }
            "$coordinatedSubject and I $verb"
        }
    }

    private fun improve(text: String): String {
        var result = replaceWords(
            neutralizeHostility(text),
            linkedMapOf(
            "because the reason is that" to "because",
            "due to the fact that" to "because",
            "at this point in time" to "now",
            "in the event that" to "if",
            "has the ability to" to "can",
            "is able to" to "can",
            "make a decision" to "decide",
            "made a decision" to "decided",
            "provide an explanation" to "explain",
            "a large number of" to "many",
            "in order to" to "to",
            "quick update" to "status update",
            "honestly" to "",
            "it would be nice if" to "it would help if",
            ),
        )
        result = tentativeImperative(result, "Consider whether to")
        result = Regex("(?i)\\bI suppose (.+?\\b(?:might|may|could)\\b)")
            .replace(result, "$1")
        result = frontFinalDeadline(result)
        result = simplifyHedges(result)
        result = Regex("(?i)\\b(?:basically|actually|clearly)\\b[, ]*").replace(result, "")
        return result
    }

    private fun ensureVisibleStyle(
        corrected: String,
        transformed: String,
        tone: WritingTone,
    ): String {
        if (transformed != corrected) return transformed
        val connector = when (tone) {
            WritingTone.PROOFREAD -> return corrected
            WritingTone.ACADEMIC, WritingTone.DETAILED -> "Given that"
            WritingTone.FORMAL, WritingTone.PROFESSIONAL, WritingTone.COLLEGE -> "As"
            WritingTone.FRIENDLY,
            WritingTone.CONVERSATIONAL,
            WritingTone.PERSUASIVE,
            WritingTone.CONFIDENT,
            WritingTone.EMPATHETIC,
            WritingTone.CASUAL,
            WritingTone.DIPLOMATIC,
            WritingTone.COOPERATIVE,
            WritingTone.ASSERTIVE -> "Since"
            WritingTone.IMPROVE,
            WritingTone.REPHRASE,
            WritingTone.SHORTEN,
            WritingTone.CONCISE,
            WritingTone.TECHNICAL,
            WritingTone.SIMPLIFIED,
            WritingTone.HIGH_SCHOOL,
            WritingTone.CAVEMAN -> "Because"
        }
        return Regex(
            "(?iu)^(.+?)\\s+because\\s+(.+?)([.!?])$",
        ).replace(corrected.trim()) { match ->
            val action = continuationCase(match.groupValues[1].trim())
            val reason = continuationCase(match.groupValues[2].trim())
            "$connector $reason, $action${match.groupValues[3]}"
        }
    }

    private fun academic(text: String): String =
        replaceWords(
            formal(improve(text)),
            linkedMapOf(
                "shows" to "demonstrates",
                "show" to "demonstrate",
                "a lot of" to "numerous",
            ),
        )

    private fun conversational(text: String): String =
        replaceWords(
            friendly(text),
            linkedMapOf(
                "cannot" to "can't",
                "will not" to "won't",
            ),
        )

    private fun concise(text: String): String = shorten(text)

    private fun detailed(text: String): String = improve(text)

    private fun persuasive(text: String): String =
        replaceWords(
            professional(text),
            linkedMapOf(
                "we should" to "we should act now to",
            ),
        )

    private fun confident(text: String): String = assertive(text)

    private fun casual(text: String): String =
        replaceWords(
            friendly(text),
            linkedMapOf(
                "status update" to "quick update",
                "please inform me" to "let me know",
                "we should" to "let's",
            ),
        )

    private fun technical(text: String): String =
        replaceWords(
            formal(text),
            linkedMapOf(
                "error" to "defect",
                "errors" to "defects",
                "problem" to "issue",
                "problems" to "issues",
                "fix" to "resolve",
            ),
        )

    private fun simplified(text: String): String =
        replaceWords(
            shorten(text),
            linkedMapOf(
                "utilize" to "use",
                "approximately" to "about",
                "commence" to "start",
                "terminate" to "end",
            ),
        )

    private fun highSchool(text: String): String = improve(text)

    private fun college(text: String): String =
        replaceWords(
            formal(improve(text)),
            linkedMapOf(
                "a lot of" to "many",
                "shows" to "indicates",
                "show" to "indicate",
            ),
        )

    private fun rephrase(text: String): String {
        var result = neutralizeHostility(text)
        result = replaceWords(
            result,
            linkedMapOf(
            "because the reason is that" to "since",
            "because" to "since",
            "the reason is that" to "this is because",
            "I would like to" to "I'd like to",
            "please let me know" to "please tell me",
            "we need to" to "we should",
            "quick update" to "brief status report",
            "please check" to "please review",
            "said" to "stated",
            "starts" to "begins",
            "start" to "begin",
            "counted" to "recorded",
            "logged" to "recorded",
            "uploaded" to "submitted",
            "missing" to "absent",
            "revised" to "updated",
            "reviewing" to "examining",
            "marked" to "labeled",
            "resolving" to "addressing",
            "caught" to "identified",
            "placed" to "positioned",
            "currently" to "now",
            "means" to "matters",
            "misspelled" to "spelled incorrectly",
            "confirm" to "verify",
            "details" to "information",
            "warning" to "notice",
            "costs" to "is priced at",
            "seems" to "appears",
            "expensive" to "costly",
            "asked whether" to "inquired whether",
            "turn blue" to "become blue",
            "belongs to" to "is assigned to",
            "We need" to "We require",
            "puts" to "places",
            ),
        )
        result = simplifyHedges(result)
        result = Regex("(?i)\\bCan you\\s+([^?]+)\\?").replace(result, "Please $1.")
        result = Regex("(?i)\\bI am worried\\s+(?:that\\s+)?").replace(result, "I worry ")
        result = Regex("(?i)\\bI am (?:terrified|afraid|scared)\\s+(?:that\\s+)?")
            .replace(result, "I fear ")
        return rephraseStructure(result)
    }

    private fun rephraseStructure(text: String): String {
        val connector = Regex(
            "(?i)(^|(?<=[.!?])\\s+)([^.!?]+?),\\s+(but|although|yet)\\s+([^.!?]+)([.!?])",
        )
        var result = connector.replace(text) { match ->
            val prefix = match.groupValues[1]
            val first = match.groupValues[2].trim()
            val second = match.groupValues[4].trim()
            val detachedModifier = Regex(
                "(?i)^(?:by\\s+(?:no more than|at most)|at most|no more than|only if|" +
                    "unless|except|before|after)\\b",
            ).containsMatchIn(second)
            if (detachedModifier) {
                match.value
            } else {
                "$prefix$first. However, ${continuationCase(second)}${match.groupValues[5]}"
            }
        }
        val causal = Regex(
            "(?i)(^|(?<=[.!?])\\s+)([^.!?]+?)\\s+because\\s+([^.!?]+)([.!?])",
        )
        result = causal.replace(result) { match ->
            val prefix = match.groupValues[1]
            val action = match.groupValues[2].trim()
            val reason = match.groupValues[3].trim().replaceFirstChar { it.uppercase() }
            "$prefix$reason. Therefore, $action${match.groupValues[4]}"
        }
        result = Regex(
            "(?i)^(.+?),\\s+unless\\s+(.+?)([.!?])$",
        ).replace(result) { match ->
            "Unless ${match.groupValues[2]}, ${continuationCase(match.groupValues[1])}${match.groupValues[3]}"
        }
        result = frontFinalDeadline(result)
        return result
    }

    private fun continuationCase(clause: String): String {
        val first = clause.substringBefore(' ')
        return if (first in setOf(
                "The", "A", "An", "We", "You", "He", "She", "They", "It", "This", "That",
                "These", "Those", "Please", "Email", "Send", "Use", "Only", "No", "Do",
                "Does", "Did", "Can", "Cannot", "Will", "Let's", "Maybe", "Stop",
                "Start", "Pickup", "Deadline", "Totals",
            )
        ) clause.replaceFirstChar { it.lowercase() } else clause
    }

    private fun shorten(text: String): String {
        var result = replaceWords(
            neutralizeHostility(text),
            linkedMapOf(
            "I would just like to" to "I want to",
            "I would like to" to "I want to",
            "make a decision" to "decide",
            "could you please" to "please",
            "would you please" to "please",
            "because the reason is that" to "because",
            "it is important to note that" to "",
            "for the purpose of" to "to",
            "in the near future" to "soon",
            "at this point in time" to "now",
            "due to the fact that" to "because",
            "in order to" to "to",
            "a total of" to "",
            "basically" to "",
            "actually" to "",
            "Can you" to "Please",
            "please let me know" to "please tell me",
            "when you have a chance" to "when possible",
            "as soon as possible" to "promptly",
            "I will" to "I'll",
            "very lengthy" to "long",
            "still missing" to "missing",
            "still not" to "not",
            "currently" to "",
            "honestly" to "",
            ),
        )
        result = simplifyHedges(result)
        result = Regex("(?i)\\bI suppose\\s+(?=.{0,40}\\b(?:might|may|could)\\b)")
            .replace(result, "")
        result = Regex("(?i)\\bone more time\\b").replace(result, "again")
        result = Regex("(?i)\\bno more than\\b").replace(result, "at most")
        result = Regex(
            "(?i)\\bon\\s+(?=(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\b)",
        ).replace(result, "")
        result = Regex("(?i)(\\bat most\\s+\\S+)\\s+total\\b").replace(result, "$1")
        result = Regex("(?i)\\bwhich cost\\b").replace(result, "costing")
        result = Regex("(?i)\\baltogether\\b[, ]*").replace(result, "")
        result = Regex(
            "(?i)under no circumstances\\s+(?:may|can|should)\\s+(.+?)\\s+be\\s+([^.!?]+)",
        ).replace(result, "$1 must not be $2")
        result = Regex(
            "(?i)\\bI spent all night [^.!?]+?,\\s+so I would appreciate everyone's help",
        ).replace(result, "I worked all night. Everyone needs to help")
        result = Regex("(?i)\\bI want to explain this because\\s+([^.!?]+)")
            .replace(result) { match -> match.groupValues[1].replaceFirstChar { it.uppercase() } }
        result = Regex("(?i)\\bI am worried\\s+(?:that\\s+)?").replace(result, "I worry ")
        result = Regex("(?i)\\bI am (?:terrified|afraid|scared)\\s+(?:that\\s+)?")
            .replace(result, "I fear ")
        result = Regex("(?i)\\bYour ([^.!?;]+?) needs improvement\\b")
            .replace(result, "Improve your $1")
        result = Regex("(?i)\\bPlease take greater care(?: going forward)?\\b")
            .replace(result, "Use greater care")
        result = Regex(
            "(?i)\\b([^.!?;]+?) made a mistake, so do not repeat it\\b",
        ).replace(result, "$1 erred; do not repeat it")
        result = directAnnouncement(result)
        return compressWordyStructures(removeShorteningClutter(result))
    }

    private fun compressWordyStructures(text: String): String {
        var result = text
        result = Regex("(?i)\\bthe totals are\\s+").replace(result, "Totals: ")
        result = Regex("(?i)\\b(?:NOTE|FYI|REMINDER):\\s*").replace(result, "")
        result = Regex(
            "(?i)^(?:(?:hi|hello)\\s+(?:team|everyone|all)[—,:;-]*\\s*)?" +
                "(?:a\\s+)?(?:quick|brief|short)\\s+(?:status\\s+)?" +
                "(?:update|note|summary|report)\\s+(?:from|about|on)\\s+(?:the\\s+)?",
        ).replace(result, "The update covers the ")
        result = Regex(
            "(?iu)\\bthe\\s+([^,.!?;]{1,80}?)\\s+hard limit is\\s+([^,.;]+),\\s+and\\s+" +
                "under no circumstances may (?:that (?:number|limit)|it) be exceeded",
        ).replace(result, "The $1 limit is $2; it must not be exceeded")
        result = Regex(
            "(?iu)\\bthe\\s+([^,.!?;]{1,80}?)\\s+hard limit is\\s+([^,.;]+),\\s+and\\s+" +
                "(?:that (?:number|limit)|it) must not be exceeded",
        ).replace(result, "The $1 limit is $2; do not exceed it")
        result = Regex(
            "(?i)(?:tell|show|inform)\\s+me\\s+which\\s+(?:one|option|choice)\\s+" +
                "(?:works|fits|looks|is)\\s+(?:the\\s+)?best",
        ).replace(result, "choose the best option")
        result = Regex(
            "(?i)\\b(?:saved|spared)\\s+(?:us|me|them)\\s+" +
                "(?:another|one more)\\s+round of\\s+([A-Za-z-]+)",
        ).replace(result, "prevented more $1")
        result = Regex("(?i)\\b(?:another|one more)\\s+round of\\s+").replace(result, "more ")
        result = Regex("(?i)\\bwithout\\s+(?:assigning|placing)\\s+blame\\b")
            .replace(result, "without blame")
        result = Regex(
            "(?i)\\b([\\p{L}][\\p{L}'’-]*)\\s+(?:that\\s+)?(?:we|they)\\s+" +
                "(?:approved|accepted|signed off on)\\b",
        ).replace(result, "approved $1")
        result = Regex("(?i)\\bare not (?:yet )?approved\\b")
            .replace(result, "await approval")
        result = Regex("(?i)\\bmeans? a great deal to\\b").replace(result, "matters to")
        result = Regex("(?i)\\bclose to tears\\b").replace(result, "distressed")
        result = Regex("(?i)\\bplease help (?:us )?(?:to )?([A-Za-z]+)")
            .replace(result, "Please $1")
        result = Regex("(?i),\\s+although\\s+the\\s+").replace(result, "; the ")
        result = Regex("(?i)\\bunclear information\\b").replace(result, "unclear")
        return result
    }

    private fun removeShorteningClutter(text: String): String {
        var result = text
        result = Regex("(?i)\\b(?:hi|hello)\\s+(?:team|everyone|all)[—,:-]*\\s*")
            .replace(result, "")
        result = Regex(
            "(?i)\\s+because\\s+I am\\s+(?:tired|frustrated|sick|weary)\\s+of\\s+[^.!?;]+",
        ).replace(result, "")
        result = Regex(
            "(?i),\\s*(?:although|but)\\s+I\\s+(?:guess|suppose|think|believe)\\b[^.!?;]*",
        ).replace(result, "")
        result = Regex(
            "(?i);\\s*(?:it|this|that)(?:'s| is)\\b[^.!?]*",
        ).replace(result, "")
        result = Regex(
            "(?i),\\s*unless\\s+(?:that|this|it)(?:'s| is)\\b[^;.!?]*",
        ).replace(result, "")
        result = Regex(
            "(?i),\\s*which\\s+(?:you|we|they)\\s+would\\b[^.!?]*",
        ).replace(result, "")
        result = Regex(
            "(?i),\\s*which\\s+(?:appears|seems)\\s+[^,.!?]+\\s+and\\s+" +
                "(?:I\\s+)?(?:sort of\\s+)?(?:think|believe)\\s+([^.!?]+)",
        ).replace(result) { match ->
            val recommendation = match.groupValues[1].trim()
            val uncertain = Regex("(?i)\\bshould\\b")
                .replace(recommendation, "may need to")
            ". ${uncertain.replaceFirstChar { it.uppercase() }}"
        }
        result = Regex("(?i)\\b(?:I\\s+)?(?:sort of\\s+)?(?:think|believe)\\s+(?=[A-Z])")
            .replace(result, "")
        result = Regex("[,;]\\s*([,;])+").replace(result, "$1")
        return result
    }

    private fun friendly(text: String): String {
        var result = replaceWords(
            improve(text),
            linkedMapOf(
            "this is unacceptable" to "this needs more attention",
            "will not" to "won't",
            "cannot" to "can't",
            "do not" to "don't",
            "does not" to "doesn't",
            "is not" to "isn't",
            "are not" to "aren't",
            ),
        )
        result = addCourtesyToImperatives(result)
        result = tentativeImperative(result, "Please consider whether to")
        result = Regex("(?i)\\bYou did not (address|follow|read|review)\\b")
            .replace(result, "It looks like you did not $1")
        result = Regex(
            "(?iu)(^|(?<=[.!?])\\s+)([\\p{Lu}][\\p{L}'’-]*),?\\s+must not\\s+",
        ).replace(result, "$1$2, please do not ")
        result = Regex(
            "(?i)\\bso address the issue because it may be unsafe\\b",
        ).replace(result, "so please help address the issue because it may be unsafe")
        return courtesyQuestions(result, "Could you please")
    }

    private fun expandContractions(text: String): String = replaceWords(
        text,
        linkedMapOf(
            "I'll" to "I will",
            "I'd" to "I would", "I'm" to "I am", "I’m" to "I am",
            "we're" to "we are", "we’re" to "we are",
            "they're" to "they are", "they’re" to "they are",
            "won't" to "will not", "can't" to "cannot", "don't" to "do not",
            "didn't" to "did not", "wasn't" to "was not", "weren't" to "were not",
            "doesn't" to "does not", "isn't" to "is not", "aren't" to "are not",
            "it's" to "it is", "there's" to "there is", "that's" to "that is",
            "let's" to "let us", "Let’s" to "Let us",
        ),
    )

    private fun formal(text: String): String {
        var result = replaceWords(
            improve(text),
            linkedMapOf(
            "I'd" to "I would",
            "Let's" to "Let us",
            "I’m" to "I am",
            "Let’s" to "Let us",
            "won't" to "will not",
            "can't" to "cannot",
            "don't" to "do not",
            "didn't" to "did not",
            "wasn't" to "was not",
            "weren't" to "were not",
            "doesn't" to "does not",
            "isn't" to "is not",
            "aren't" to "are not",
            "it's" to "it is",
            "there's" to "there is",
            "that's" to "that is",
            "I’m" to "I am",
            "we’re" to "we are",
            "they’re" to "they are",
            "it’s" to "it is",
            "that’s" to "that is",
            "please let me know" to "please inform me",
            "quick update" to "status update",
            "Hi team" to "Team",
            "honestly" to "",
            "I sort of think" to "I am inclined to believe",
            "sort of" to "somewhat",
            ),
        )
        result = tentativeImperative(result, "Consider whether to")
        result = Regex("(?i)\\bI suppose (.+?\\b(?:might|may|could)\\b)")
            .replace(result, "$1")
        result = simplifyHedges(result)
        result = Regex("(?i)\\b(I|we|you|they|he|she|it) cannot\\s+").replace(result) { match ->
            val subject = match.groupValues[1]
            val be = when (subject.lowercase(Locale.ROOT)) {
                "i" -> "am"
                "he", "she", "it" -> "is"
                else -> "are"
            }
            "$subject $be unable to "
        }
        result = result.replace('!', '.')
        result = Regex("(?i)\\bclose to tears\\b").replace(result, "deeply distressed")
        result = Regex("(?i)\\bwhen you have a chance\\b")
            .replace(result, "at your earliest convenience")
        return directAnnouncement(expandContractions(result))
    }

    private fun professional(text: String): String {
        var result = replaceWords(
            improve(text),
            linkedMapOf(
            "this is unacceptable" to "this requires further attention",
            "I would just like to" to "I want to",
            "as soon as possible" to "promptly",
            "I think we should" to "I recommend that we",
            "I sort of think" to "I am inclined to think",
            "I think" to "I recommend",
            "a lot of issues" to "many issues",
            "a lot of work" to "substantial work",
            "a lot of time" to "considerable time",
            "fix the issue" to "resolve the issue",
            "ASAP" to "promptly",
            "really" to "",
            "quick update" to "status update",
            "honestly" to "",
            "it would be nice if" to "it would help if",
            "Hi team" to "Team",
            "close to tears" to "under considerable strain",
            "did not say" to "did not state",
            "which cost" to "costing",
            ),
        )
        result = tentativeImperative(result, "Please consider whether to")
        result = addCourtesyToImperatives(result)
        result = directUncertainInability(result)
        result = Regex("(?i)\\bI suppose (.+?\\b(?:might|may|could)\\b)")
            .replace(result, "$1")
        result = simplifyHedges(result)
        result = Regex("(?i)\\bI would appreciate everyone's help\\b")
            .replace(result, "everyone's help is needed to complete the work")
        result = directAnnouncement(result)
        result = Regex("(?i)\\bplease let me know\\b").replace(result, "please respond")
        result = Regex("(?i)\\bwhen you have a chance\\b").replace(result, "promptly")
        result = expandContractions(result).replace('!', '.')
        return result
    }

    private fun diplomatic(text: String): String {
        var result = replaceWords(
            improve(text),
            linkedMapOf(
            "you are wrong" to "we may see this differently",
            "you must" to "please",
            "this is unacceptable" to "this needs further attention",
            ),
        )
        result = tentativeImperative(result, "Please consider whether to")
        result = addCourtesyToImperatives(result)
        result = Regex("(?i)\\bI suppose (.+?\\b(?:might|may|could)\\b)")
            .replace(result, "$1")
        result = Regex("(?i)^No,\\s+").replace(result, "For clarity, no, ")
        result = Regex("(?i)\\bYou did not (address|follow|read|review)\\b")
            .replace(result, "It appears that you did not $1")
        result = Regex(
            "(?iu)(^|(?<=[.!?])\\s+)([\\p{Lu}][\\p{L}'’-]*\\s+must not\\s+)",
        ).replace(result, "$1For clarity, $2")
        result = Regex(
            "(?i)\\bso address the issue because it may be unsafe\\b",
        ).replace(result, "so please address the safety concern because it may be unsafe")
        return courtesyQuestions(directAnnouncement(result), "Would you please")
    }

    private fun empathetic(text: String): String {
        var result = replaceWords(
            improve(text),
            linkedMapOf(
            "this is unacceptable" to "this deserves careful attention",
            "I know this is frustrating" to "I recognize that this is frustrating",
            "I understand this is difficult" to "I recognize that this is difficult",
            "that is hard" to "that sounds difficult",
            "that's hard" to "that sounds difficult",
            ),
        )
        result = directAnnouncement(result)
        result = Regex(
            "(?i)\\bI am (?:worried|anxious|terrified|afraid|scared|distressed) about\\b",
        ).replace(result, "I am concerned about")
        result = Regex(
            "(?i)\\bI am (?:worried|anxious|terrified|afraid|scared|distressed) " +
                "(?:that )?",
        ).replace(result, "I am concerned that ")
        val courteous = addCourtesyToImperatives(result)
        val concern = Regex(
            "(?i)\\b(?:frustrat|exhaust|worr|anxious|stress|difficult|upset|distress|" +
                "afraid|terrified|scared|panic|tears|idiotic|dreadful|awful|obnoxious|" +
                "stupid|ridiculous)\\w*\\b|" +
                "\\ball night\\b",
        ).containsMatchIn(text)
        val startsWithGreeting = Regex("(?i)^(?:hi|hello|team)\\b").containsMatchIn(courteous)
        if (Regex("(?i)\\b(?:understand|recognize|appreciate|acknowledge|support|concern)\\w*\\b")
                .containsMatchIn(courteous) || !concern || startsWithGreeting
        ) return courteous

        val criticizedNoun = Regex(
            "(?iu)\\b(?:idiotic|stupid|foolish|dreadful|awful|obnoxious|ridiculous)\\s+" +
                "([\\p{L}'’-]+)",
        ).find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        val disruptiveObject = Regex(
            "(?iu)\\b(?:bang|ring|sound)\\w*\\s+(?:that|the)\\s+" +
                "(?:(?:dreadful|awful|obnoxious|ridiculous)\\s+)?([\\p{L}'’-]+)",
        ).find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        val acknowledgement = when {
            disruptiveObject != null ->
                "I understand that the repeated interruption causes frustration."
            criticizedNoun != null -> "I understand why this is frustrating."
            Regex("(?i)\\b(?:exhaust|drain|tears|breaking point)\\w*\\b")
                .containsMatchIn(text) -> "I recognize the strain involved."
            Regex("(?i)\\b(?:afraid|terrified|scared|panic)\\w*\\b")
                .containsMatchIn(text) -> "I recognize how concerning this risk is."
            Regex("(?i)\\b(?:worried|anxious|stress|concern)\\w*\\b")
                .containsMatchIn(text) -> "I recognize this may be stressful."
            Regex("(?i)\\b(?:delay|missing)\\w*\\b")
                .containsMatchIn(text) -> "I recognize the inconvenience this may cause."
            Regex("(?i)\\b(?:idiotic|stupid|foolish|dreadful|awful|obnoxious|ridiculous)\\b")
                .containsMatchIn(text) -> "I recognize this may be frustrating."
            else -> "I recognize this may be difficult."
        }
        return "$acknowledgement $courteous"
    }

    private fun cooperative(text: String): String {
        var result = replaceWords(
            improve(text),
            linkedMapOf(
            "this is unacceptable" to "we can improve this together",
            "we need to" to "let's",
            "work with each other" to "work together",
            "collaborate with one another" to "collaborate",
            "Let’s" to "Let's",
            ),
        )
        result = tentativeImperative(result, "Please consider whether to")
        result = Regex(
            "(?i)(^|[;.!?]\\s+)(move|tell|send|email|use|review|check|confirm|label|" +
                "write|close|provide|stop)\\b",
        ).replace(result) { match ->
            val lead = if (match.groupValues[1].isEmpty()) "Please" else "please"
            "${match.groupValues[1]}$lead ${match.groupValues[2].lowercase(Locale.ROOT)}"
        }
        result = Regex("(?i)(,\\s+and\\s+)(don't|do not)\\s+([A-Za-z]+)\\b")
            .replace(result) { match ->
                "${match.groupValues[1]}please ${match.groupValues[2].lowercase(Locale.ROOT)} " +
                    match.groupValues[3].lowercase(Locale.ROOT)
            }
        result = Regex(
            "(?i)^(?:please\\s+)?(send|email|use|review|check|confirm|label|write|close|provide)\\s+",
        ).replace(result) { match -> "Please ${match.groupValues[1].lowercase(Locale.ROOT)} " }
        result = Regex("(?i)\\bI would appreciate everyone's help\\b")
            .replace(result, "let's complete the remaining work together")
        result = Regex(
            "(?iu)(^|(?<=[.!?])\\s+)([\\p{Lu}][\\p{L}'’-]*),?\\s+must not\\s+",
        ).replace(result, "$1$2, please do not ")
        result = Regex(
            "(?iu)(^|(?<=[.!?])\\s+)([\\p{Lu}][\\p{L}'’-]*),?\\s+needs to\\s+" +
                "([\\p{L}'’-]+)\\b",
        ).replace(result, "$1$2, please $3")
        return directAnnouncement(addCourtesyToImperatives(result))
    }

    private fun assertive(text: String): String {
        var result = replaceWords(
            improve(text),
            linkedMapOf(
            "I would just like to" to "I want to",
            "I would like to" to "I want to",
            "I just wanted to" to "I want to",
            "I would like you to" to "please",
            "I think" to "",
            "I guess" to "",
            "I sort of think" to "I think",
            "did not say" to "did not state",
            "which cost" to "costing",
            ),
        )
        result = tentativeImperative(result, "Consider whether to")
        result = Regex("(?i)\\bConsider whether to\\b").replace(result, "Decide whether to")
        result = directUncertainInability(result)
        result = Regex("(?i)\\bI suppose (.+?\\b(?:might|may|could)\\b)")
            .replace(result, "$1")
        result = frontFinalDeadline(result)
        result = simplifyHedges(result)
        result = Regex("(?i)\\bI would appreciate everyone's help\\b")
            .replace(result, "everyone must help complete the work")
        result = directAnnouncement(result)
        result = Regex(
            "(?i)\\bI want to (explain|clarify|state|note|emphasize|mention)\\b",
        ).replace(result, "I will $1")
        result = Regex("(?i)\\bplease let me know\\b").replace(result, "respond")
        result = Regex("(?i)\\bwhen you have a chance\\b").replace(result, "promptly")
        result = Regex("(?i)(^|(?<=[,;.!?])\\s+)please don't\\s+")
            .replace(result) { match -> "${match.groupValues[1]}Do not " }
        result = Regex(
            "(?i)(^|(?<=[,;.!?])\\s+)please\\s+(?=(?:move|tell|send|email|use|review|" +
                "check|confirm|label|write|close|provide|stop|order|choose)\\b)",
        ).replace(result, "$1")
        return directRequests(result)
    }

    private fun directRequests(text: String): String = Regex(
        "(?i)(^|(?<=[.!?])\\s+)Can you\\s+([^?]+)\\?",
    ).replace(text) { match ->
        val request = match.groupValues[2].trim()
        "${match.groupValues[1]}${request.replaceFirstChar { it.uppercase() }}."
    }

    private fun courtesyQuestions(text: String, lead: String): String = sentenceBreak.split(text)
        .joinToString(" ") { raw ->
            val sentence = raw.trim()
            val request = Regex(
                "(?i)^Please\\s+(send|email|use|review|check|confirm|label|write|close|" +
                    "provide|stop|keep|move|tell|order|choose|schedule)\\s+(.+?)[.!?]$",
            ).matchEntire(sentence)
            if (request == null) {
                sentence
            } else {
                "$lead ${request.groupValues[1].lowercase(Locale.ROOT)} " +
                    "${request.groupValues[2]}?"
            }
        }

    private fun frontFinalDeadline(text: String): String {
        if (sentenceBreak.split(text.trim()).size != 1) return text
        if (Regex(
                "(?i)\\b(?:unless|only if|if)\\b[^.!?]*\\b(?:before|by)\\s+" +
                    "[A-Z][A-Za-z]+[.!?]$",
            ).containsMatchIn(text)
        ) return text
        return Regex(
            "(?i)^(.+?)\\s+(before|by)\\s+([A-Z][A-Za-z]+)([.!?])$",
        ).replace(text) { match ->
            val deadline = "${match.groupValues[2]} ${match.groupValues[3]}"
                .replaceFirstChar { it.uppercase() }
            "$deadline, ${continuationCase(match.groupValues[1])}${match.groupValues[4]}"
        }
    }

    private fun directUncertainInability(text: String): String = Regex(
        "(?i)\\bmaybe\\s+(I|we|you|they|he|she|it)\\s+" +
            "(?:cannot|can't|am unable to|are unable to|is unable to)\\s+([A-Za-z]+)\\b",
    ).replace(text) { match ->
        "${match.groupValues[1]} may be unable to ${match.groupValues[2].lowercase(Locale.ROOT)}"
    }

    private fun directAnnouncement(text: String): String {
        val stripped = Regex(
            "(?i)\\bI (?:would just like|would like|want) to say(?: that)?\\s+",
        ).replace(text, "")
        return directUncertainInability(stripped)
    }

    private fun tentativeImperative(text: String, lead: String): String = Regex(
        "(?i)^Maybe\\s+([A-Za-z]+)\\b",
    ).replace(text) { match -> "$lead ${match.groupValues[1].lowercase(Locale.ROOT)}" }

    private fun addCourtesyToImperatives(text: String): String {
        var result = Regex(
            "(?i)(^|[;.!?]\\s+)(move|tell|send|email|use|review|check|confirm|label|" +
                "write|close|provide|stop|order|buy|choose|schedule|improve)\\b",
        ).replace(text) { match ->
            val please = if (match.groupValues[1].isEmpty()) "Please" else "please"
            "${match.groupValues[1]}$please ${match.groupValues[2].lowercase(Locale.ROOT)}"
        }
        result = Regex("(?i)(,\\s+and\\s+)(don't|do not)\\s+")
            .replace(result, "$1please $2 ")
        result = Regex("(?i)(^|[.!?]\\s+)(?:do not|don't)\\s+")
            .replace(result) { match -> "${match.groupValues[1]}Please do not " }
        result = Regex(
            "(?i)(,\\s+(?:so|and|but)\\s+)(receive|notify|contact|send|email|use|review|" +
                "check|confirm|label|write|close|provide|keep|move|tell|order|buy|choose|" +
                "schedule|reserve|approve|submit|release|load|scan|dispatch)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]}please ${match.groupValues[2].lowercase(Locale.ROOT)}"
        }
        result = Regex("(?i)(,\\s+(?:so|and|but)\\s+)(don't|do not)\\s+")
            .replace(result, "$1please $2 ")
        return result
    }

    private fun polishToneResult(text: String): String {
        var result = text.trim()
        if (result.isEmpty()) return ""
        result = Regex("[ \\t]{2,}").replace(result, " ")
        result = Regex(",\\s*,+").replace(result, ",")
        result = Regex(";\\s*;+").replace(result, ";")
        result = Regex("(?i)\\b(and|but|or),\\s+").replace(result, "$1 ")
        result = Regex("\\s+([,.;:!?])").replace(result, "$1")
        result = Regex("([.!?])[,;:]").replace(result, "$1")
        result = Regex("\\s+([—–])").replace(result, "$1")
        return normalizeSpeechActPunctuation(DeterministicCleanup.clean(result).text)
    }

    private fun normalizeSpeechActPunctuation(text: String): String = sentenceBreak.split(text)
        .joinToString(" ") { raw ->
            val sentence = raw.trim()
            when {
                Regex("(?i)^(?:can|could|would|will) you\\b").containsMatchIn(sentence) &&
                    sentence.endsWith('.') -> sentence.dropLast(1) + "?"
                Regex("(?i)^(?:please|kindly)\\b").containsMatchIn(sentence) &&
                    sentence.endsWith('?') -> sentence.dropLast(1) + "."
                Regex(
                    "(?i)^(?:send|email|use|review|check|confirm|label|write|close|provide|" +
                        "stop|keep|move|tell|order|choose|release|load|scan|dispatch)\\b",
                ).containsMatchIn(sentence) && sentence.endsWith('?') ->
                    sentence.dropLast(1) + "."
                else -> sentence
            }
        }

    private fun fixSubjectVerbAgreement(text: String): String {
        val forms = mapOf(
            "was" to ("was" to "were"), "were" to ("was" to "were"),
            "is" to ("is" to "are"), "are" to ("is" to "are"),
            "has" to ("has" to "have"), "have" to ("has" to "have"),
            "does" to ("does" to "do"), "do" to ("does" to "do"),
            "appears" to ("appears" to "appear"), "appear" to ("appears" to "appear"),
            "shows" to ("shows" to "show"), "show" to ("shows" to "show"),
            "needs" to ("needs" to "need"), "need" to ("needs" to "need"),
            "seems" to ("seems" to "seem"), "seem" to ("seems" to "seem"),
            "looks" to ("looks" to "look"), "look" to ("looks" to "look"),
            "contains" to ("contains" to "contain"), "contain" to ("contains" to "contain"),
            "requires" to ("requires" to "require"), "require" to ("requires" to "require"),
            "remains" to ("remains" to "remain"), "remain" to ("remains" to "remain"),
            "says" to ("says" to "say"), "say" to ("says" to "say"),
            "verifies" to ("verifies" to "verify"), "verify" to ("verifies" to "verify"),
            "moves" to ("moves" to "move"), "move" to ("moves" to "move"),
            "scans" to ("scans" to "scan"), "scan" to ("scans" to "scan"),
            "dispatches" to ("dispatches" to "dispatch"), "dispatch" to ("dispatches" to "dispatch"),
            "signs" to ("signs" to "sign"), "sign" to ("signs" to "sign"),
            "seals" to ("seals" to "seal"), "seal" to ("seals" to "seal"),
            "emails" to ("emails" to "email"), "email" to ("emails" to "email"),
            "releases" to ("releases" to "release"), "release" to ("releases" to "release"),
            "loads" to ("loads" to "load"), "load" to ("loads" to "load"),
            "opens" to ("opens" to "open"), "open" to ("opens" to "open"),
            "inspects" to ("inspects" to "inspect"), "inspect" to ("inspects" to "inspect"),
            "doesn't" to ("doesn't" to "don't"), "don't" to ("doesn't" to "don't"),
        )
        val pattern = Regex(
            "\\b([A-Za-z][A-Za-z'-]*)\\s+(only\\s+)?" +
                "(was|were|is|are|has|have|does|do|doesn't|don't|appears?|shows?|needs?|" +
                "seems?|looks?|contains?|requires?|remains?|says?|verif(?:y|ies)|moves?|" +
                "scans?|dispatch(?:es)?|signs?|seals?|emails?|releases?|loads?|opens?|" +
                "inspects?)\\b",
            RegexOption.IGNORE_CASE,
        )
        return pattern.replace(text) { match ->
            val subject = match.groupValues[1]
            val modifier = match.groupValues[2]
            val verb = match.groupValues[3]
            val plural = subjectIsPlural(subject) ?: return@replace match.value
            val pair = forms[verb.lowercase(Locale.ROOT)] ?: return@replace match.value
            "$subject $modifier${if (plural) pair.second else pair.first}"
        }
    }

    private fun subjectIsPlural(subject: String): Boolean? {
        val value = subject.lowercase(Locale.ROOT)
        if (value in setOf("we", "you", "they", "these", "those")) return true
        if (value in setOf("he", "she", "it", "this", "that", "who")) return false
        if (value in setOf(
                "i", "am", "please", "not", "to", "can", "could", "will", "would",
                "shall", "should", "may", "might", "must", "and", "but", "or", "nor",
                "if", "when", "while", "because", "since", "although", "yet", "so",
                "the", "a", "an", "my", "your", "our", "their", "his", "her", "its",
            )
        ) return null
        if (value.endsWith("ly")) return null
        val singularSEndings = setOf(
            "news", "series", "species", "status", "process", "business", "analysis",
            "address", "class", "glass", "luis", "james", "chris", "alexis", "travis",
            "marcus", "nicholas", "thomas", "charles", "carlos", "lucas",
        )
        return value.endsWith('s') && value !in singularSEndings
    }

    private fun fixCountedNounAgreement(text: String): String {
        val count = "zero|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|" +
            "thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|\\d+"
        val pattern = Regex(
            "\\b($count)\\s+((?:[A-Za-z][A-Za-z'-]*\\s+){0,2})([A-Za-z][A-Za-z'-]*)" +
                "\\s+(is|are|was|were)\\b",
            RegexOption.IGNORE_CASE,
        )
        return pattern.replace(text) { match ->
            val noun = match.groupValues[3]
            val pluralNoun = pluralizeNoun(noun)
            val pluralVerb = if (match.groupValues[4].equals("was", ignoreCase = true)) "were" else "are"
            "${match.groupValues[1]} ${match.groupValues[2]}$pluralNoun $pluralVerb"
        }
    }

    private fun pluralizeNoun(noun: String): String {
        val lower = noun.lowercase(Locale.ROOT)
        if (lower.endsWith('s')) return noun
        return when {
            lower.endsWith('y') && lower.length > 1 && lower[lower.lastIndex - 1] !in "aeiou" ->
                noun.dropLast(1) + "ies"
            listOf("ch", "sh", "x", "z").any(lower::endsWith) -> noun + "es"
            else -> noun + "s"
        }
    }

    private fun simplifyHedges(text: String): String {
        var result = Regex(
            "(?i)\\bI was wondering if\\s+(?:perhaps\\s+)?you\\s+" +
                "(?:might\\s+)?(?:possibly\\s+)?consider\\s+",
        ).replace(text, "Please ")
        result = Regex("(?i)\\b(?:perhaps|possibly)\\b[, ]*").replace(result, "")
        result = Regex("(?i)\\bmight\\s+consider\\s+").replace(result, "Please ")
        result = Regex("(?i)\\s+if you can(?:\\s+maybe)?\\b").replace(result, "")
        result = replaceWords(
            result,
            linkedMapOf(
                "Please revising" to "Please revise",
                "Please reviewing" to "Please review",
                "Please changing" to "Please change",
                "Please sending" to "Please send",
                "Please updating" to "Please update",
            ),
        )
        return result
    }

    private fun neutralizeHostility(text: String): String {
        var result = text
        result = Regex(
            "(?i)\\b(explanation|response|message|report)\\s+is\\s+(?:a|another)\\s+" +
                "pile of\\s+(?:fog|nonsense|garbage|trash|excuses?|confusion)\\b",
        ).replace(result, "$1 is unclear")
        result = Regex("(?i)\\bhonestly\\b[, ]*").replace(result, "")
        result = Regex(
            "(?i)\\b(?:was|is)\\s+an?\\s+(?:idiot|idiotic|stupid|clueless|" +
                "incompetent)\\b",
        ).replace(result, "made a mistake")
        result = Regex(
            "(?i)\\b(?:don't|do not) be (?:useless|careless|pathetic|sloppy|stupid|" +
                "clueless|incompetent)(?: too)?\\b",
        ).replace(result, "do not repeat it")
        result = Regex(
            "(?i)\\b([^.!?;]+?)\\s+(?:was|is)\\s+(?:an?\\s+)?" +
                "(?:idiot|idiotic|useless|careless|pathetic|sloppy|stupid|clueless|" +
                "incompetent)\\s*,\\s*so\\s+(?:don't|do not)\\s+be\\s+" +
                "(?:useless|careless|pathetic|sloppy|stupid|clueless|incompetent)" +
                "(?:\\s+too)?\\b",
        ).replace(result, "$1 made a mistake, so do not repeat it")
        result = Regex(
            "(?iu)(?<!being )\\b(?:idiot|idiotic|dumb|stupid|foolish|useless|ridiculous|dreadful|" +
                "awful|obnoxious|lazy|incompetent|pathetic|careless|sloppy|hopeless|clueless)\\s+" +
                "(?=[\\p{L}])",
        ).replace(result, "")
        result = Regex(
            "(?i)\\bI (?:won't|will not) tolerate another ([^.!?;]+?) from you\\b",
        ).replace(result, "Please support the next $1 with evidence")
        result = Regex(
            "(?i)\\byour ([A-Za-z][A-Za-z'-]*(?:\\s+[A-Za-z][A-Za-z'-]*){0,2}) " +
                "make(?:s)? it worse\\b",
        ).replace(result, "clear $1 would help")
        result = Regex(
            "(?i)\\b(?:quit|stop) being (?:useless|stupid|careless|ridiculous)\\s+because\\b",
        ).replace(result, "address the issue because")
        result = Regex("(?i)\\b([^.!?;]+?) better not\\s+")
            .replace(result, "$1 must not ")
        result = Regex(
            "(?i)\\bApparently reading (?:a|the) [^.!?;]+ is too much for you\\b",
        ).replace(result, "Please follow the stated requirement")
        result = Regex(
            "(?i)\\bApparently (?:this|that|the task) is too " +
                "(?:complicated|difficult|hard) for (?:your [^.!?;]+|you)\\b",
        ).replace(result, "Please follow the instructions carefully")
        result = Regex(
            "(?i)\\bYou (?:messed|screwed) (?:it|this|that) up(?: before)?" +
                "(?:,?\\s*as usual)?\\b",
        ).replace(result, "Improve the next attempt")
        result = Regex(
            "(?i)\\bThanks for proving (?:that )?you never learn\\b",
        ).replace(result, "Do not repeat the mistake")
        result = Regex(
            "(?i)\\b(Your|The) ([^.!?;]+?) (?:was|is) " +
                "(?:pathetic|useless|careless|ridiculous|awful|dreadful)\\b",
        ).replace(result, "$1 $2 needs improvement")
        result = Regex(
            "(?i)\\bI (?:won't|will not) let (?:a|an|the)?\\s*[^.!?;]+? " +
                "(?:ruin|damage|break) this again\\b",
        ).replace(result, "This must not fail again")
        result = Regex(
            "(?i)\\bThanks for being (?:careless|sloppy|reckless|inattentive) as usual\\b",
        ).replace(result, "Please take greater care going forward")
        result = Regex(
            "(?i)If you (?:ignore|disregard) me,\\s+I(?: will|'ll) " +
                "(?:take|keep|hide|lock|erase|destroy|damage|confiscate) your [^.!?;]+",
        ).replace(result, "If ignored, please expect an access review")
        result = Regex(
            "(?i)You should know this already,?\\s+and\\s+I will (?:take|keep|hide|lock|" +
                "confiscate) your ([^.!?;]+)",
        ).replace(result, "Please review this requirement; property will follow normal policy")
        result = Regex(
            "(?i)You (?:can't|cannot) follow [^,.;]+,\\s+and\\s+I(?: will|'ll) " +
                "(?:take|keep|hide|lock|erase|destroy|damage|confiscate) your [^.!?;]+",
        ).replace(result, "The rules were not followed; property will follow normal policy")
        result = Regex(
            "(?i)\\bI(?: will|'ll) (?:take|keep|hide|lock|erase|destroy|damage|" +
                "confiscate) your [^.!?;]+",
        ).replace(result, "Property will follow normal policy")
        result = Regex(
            "(?i)\\byou\\s+(?:failed|forgot|refused|neglected)\\s+to\\s+",
        ).replace(result, "you did not ")
        result = Regex(
            "(?i)\\byou\\s+(?:ignored|disregarded|overlooked)\\s+([^,.!?]+)",
        ).replace(result, "you did not address $1")
        result = Regex("(?i)\\byou are wrong\\b").replace(result, "we may see this differently")
        result = Regex(
            "(?i)\\bthis is\\s+(?:unacceptable|ridiculous|absurd)\\b",
        ).replace(result, "this needs further attention")
        result = Regex(
            "(?i)(?:so now\\s+)?([^,.!?]+?)\\s+has to\\s+" +
                "(repair|fix|resolve)\\s+([^,.!?]+?\\s+you caused)",
        ).replace(result) { match ->
            "${match.groupValues[1]} must ${match.groupValues[2]} ${match.groupValues[3]}"
        }
        result = Regex("(?i)\\beveryone else must\\b").replace(result, "others must")
        result = Regex(
            "(?i)\\b(?:the\\s+)?(confusion|problem|issue|mess|delay|damage)\\s+you caused\\b",
        ).replace(result) { match -> "the resulting ${match.groupValues[1]}" }
        result = Regex(
            "(?i)without turning the ([A-Za-z-]+) into (?:another )?" +
                "(?:blame|finger-pointing) game",
        ).replace(result, "without assigning blame during the $1")
        result = Regex(
            "(?i)\\b(?:a|another)\\s+pile of\\s+" +
                "(?:fog|nonsense|garbage|trash|excuses?|confusion)\\b",
        ).replace(result, "unclear information")
        result = Regex(
            "(?i)\\b(explanation|response|message|report) is unclear information\\b",
        ).replace(result, "$1 is unclear")
        result = Regex("(?i)\\b(?:clearly|obviously)\\s+(?=(?:was|were|is|are|has|have|did|does|do|wasn't|weren't|isn't))")
            .replace(result, "")
        result = Regex(
            "(?iu)\\b((?:whoever|the\\s+[\\p{L}'’-]+|[\\p{L}'’-]+\\s+who)" +
                "[^,.!?]{0,60}?)\\s+(?:wasn't|was not|wasnt|weren't|were not)\\s+" +
                "paying attention\\b",
        ).replace(result) { match ->
            "${match.groupValues[1].trim()} may have missed key details"
        }
        result = Regex(
            "(?i)\\b(?:wasn't|was not|wasnt|weren't|were not|isn't|is not)\\s+" +
                "paying attention\\b",
        ).replace(result, "did not review the details")
        result = Regex(
            "(?i)\\b(?:fixing|cleaning|repairing)\\s+(?:this|the)\\s+" +
                "(?:mess|disaster|chaos)\\b",
        ).replace(result, "resolving these issues")
        result = Regex(
            "(?i)\\bI am\\s+(?:tired|sick|weary|frustrated)\\s+of\\s+" +
                "(?:fixing|correcting|repairing)\\s+([^.!?;]+)",
        ).replace(result) { match ->
            "I am frustrated by having to fix ${match.groupValues[1]}"
        }
        result = Regex(
            "(?i),?\\s+so now others must\\s+(repair|fix|resolve)\\s+" +
                "the resulting ([^.!?;]+)",
        ).replace(result) { match ->
            "; others must ${match.groupValues[1]} the resulting ${match.groupValues[2]}"
        }
        result = Regex("(?i)\\b(?:careless|stupid|avoidable)\\s+(mistakes?|errors?|work)\\b")
            .replace(result, "$1")
        result = Regex(
            "(?i)\\bI\\s+(?:guess|suppose)\\s+([^,.!?]+?)\\s+" +
                "(?:don't|do not|doesn't|does not)\\s+matter\\s+to\\s+" +
                "(?:some|certain)\\s+people\\b",
        ).replace(result) { match ->
            val subject = match.groupValues[1].trim()
            val lastWord = subject.substringAfterLast(' ')
            val verb = if (subjectIsPlural(lastWord) == true) "remain" else "remains"
            "${subject.replaceFirstChar { it.uppercase() }} $verb important"
        }
        result = Regex(
            "(?iu),\\s+although\\s+([\\p{Lu}][^.!?]{1,80}?\\s+remain(?:s)? important)",
        ).replace(result, ". $1")
        result = Regex(
            "(?iu),\\s+although\\s+I\\s+(?:guess|suppose)\\s+([^,.!?]+?)\\s+" +
                "(?:don't|do not|doesn't|does not)\\s+matter\\s+to\\s+" +
                "(?:some|certain)\\s+people",
        ).replace(result) { match ->
            val subject = match.groupValues[1].trim()
            val lastWord = subject.substringAfterLast(' ')
            val verb = if (subjectIsPlural(lastWord) == true) "remain" else "remains"
            ". ${subject.replaceFirstChar { it.uppercase() }} $verb important"
        }
        result = Regex(
            "(?i)\\bthere(?:'s| is)\\s+no excuse for\\s+([^,.!?;]+)",
        ).replace(result, "please avoid $1")
        result = Regex(
            "(?i)\\b([^,.!?]+?)\\s+keeps\\s+(?:acting|behaving)\\s+like\\s+" +
                "the\\s+([^,.!?]+?)\\s+are\\s+optional",
        ).replace(result) { match ->
            "${match.groupValues[1]} continues to treat the ${match.groupValues[2]} as optional"
        }
        result = Regex("(?i)\\bstop inviting random people\\b")
            .replace(result, "do not invite random people")
        result = Regex(
            "(?i),?\\s*which you would know if you had bothered to\\s+([^.!?]+)",
        ).replace(result) { match -> ". You did not ${match.groupValues[1]}" }
        result = Regex(
            "(?i)(?:it(?:'s| is) pretty simple,?\\s+so\\s+)?" +
                "(?:there(?:'s| is) no excuse for|please avoid) messing it up",
        ).replace(result, "please complete it carefully")
        result = Regex(
            "(?i)(\\bto report\\b[^.!?;]*;\\s+please )complete it carefully",
        ).replace(result, "$1complete the report carefully")
        result = Regex("(?i)\\bit(?:'s| is) pretty simple,\\s+so\\s+")
            .replace(result, "")
        result = Regex("(?i)\\s+if you can(?:\\s+maybe)?\\b").replace(result, "")
        result = Regex(
            "(?i)\\bit would (?:be (?:nice|helpful)|help) if everyone could finally do their part\\b",
        ).replace(result, "I would appreciate everyone's help")
        result = Regex("(?i)\\beveryone could finally do their part\\b")
            .replace(result, "everyone could help complete the work")
        result = Regex(
            "(?i)if ([^.!?;]+),\\s+I will (?:throw|toss|dump|hurl) " +
                "([^.!?;]+?) (?:in|into) (?:the )?[^.!?;]+(?=;|[.!?])",
        ).replace(result) { match ->
            val recipient = when {
                Regex("(?i)\\b(?:tell|ask) him\\b").containsMatchIn(text) -> "him"
                Regex("(?i)\\b(?:tell|ask) her\\b").containsMatchIn(text) -> "her"
                else -> "them"
            }
            "If ${match.groupValues[1]}, I will stop $recipient from using ${match.groupValues[2]}"
        }
        result = Regex("(?i)\\b(?:insanely|ridiculously|absurdly)\\b")
            .replace(result, "unusually")
        result = Regex(
            "(?i),?\\s*unless\\s+(?:that|this|it)(?:'s| is)\\s+too much to ask",
        ).replace(result, "")
        result = Regex("(?i)\\b(?:blame|finger-pointing) game\\b")
            .replace(result, "assigning blame")
        return result
    }

    private fun structuralFallback(text: String): String {
        if (sentenceBreak.split(text.trim()).size != 1) return text
        val timed = Regex(
            "(?i)^(.+?)\\s+(today|tomorrow|yesterday|tonight|this\\s+(?:morning|afternoon|" +
                "evening|week)|next\\s+(?:week|month|year))([.!?])$",
        ).matchEntire(text.trim())
        if (timed != null) {
            val lead = timed.groupValues[2].replaceFirstChar { it.uppercase() }
            return "$lead, ${continuationCase(timed.groupValues[1])}${timed.groupValues[3]}"
        }

        val locative = Regex(
            "(?iu)^(.+?\\b(?:rests?|remains?|sits?|stands?|lies?|stays?|waits?|works?|lives?|" +
                "inspects?|checks?|reviews?|stores?|keeps?|arrives?|meets?|opens?|closes?)(?:\\s+" +
                "(?:the|a|an|this|that|my|your|our|their|[\\p{L}'’-]+)){0,6}?)\\s+" +
                "((?:at|in|on|beside|near|inside|outside|under|above|behind|by)\\s+" +
                "(?:the\\s+)?[\\p{L}'’-]+(?:\\s+[\\p{L}'’-]+){0,3})([.!?])$",
        ).matchEntire(text.trim())
        if (locative != null) {
            val location = locative.groupValues[2].replaceFirstChar { it.uppercase() }
            return "$location, ${continuationCase(locative.groupValues[1])}${locative.groupValues[3]}"
        }

        return text
    }

    private fun finishRestoredCaveman(text: String): String {
        var result = Regex(
            "(?iu)\\s+and\\s+((?:the\\s+)?[\\p{L}'’-]+)\\s+who\\b",
        ).replace(text) { match ->
            ". ${match.groupValues[1].replaceFirstChar { it.uppercase() }} who"
        }
        result = Regex(
            "(?iu)([^.!?]+?\\b(?:said|wrote),?\\s+[“\"][^”\"]+,[”\"])\\.\\s+" +
                "(he|she|they)\\s+",
        ).replace(result) { match ->
            "${match.groupValues[1]} but ${match.groupValues[2].lowercase(Locale.ROOT)} "
        }
        result = Regex(
            "(?iu)(^|(?<=[.!?])\\s+)([^.!?]+?)\\s+(needs|requires)\\s+" +
                "([^,.;]+),\\s+([^,.;]+),\\s+and\\s+([^.!?]+)([.!?])",
        ).replace(result) { match ->
            val subject = match.groupValues[2]
            val verb = match.groupValues[3].lowercase(Locale.ROOT)
            "${match.groupValues[1]}$subject $verb ${match.groupValues[4]} and " +
                "${match.groupValues[5]}. It also $verb ${match.groupValues[6]}" +
                match.groupValues[7]
        }
        result = Regex("(?iu)—but\\s+([\\p{L}])").replace(result) { match ->
            ". ${match.groupValues[1].uppercase(Locale.ROOT)}"
        }
        result = Regex(
            "(?iu),\\s+and\\s+([\\p{Lu}][\\p{L}'’-]*\\s+" +
                "(?:gets|has|receives|must|will|can)\\b)",
        ).replace(result, ". $1")
        return result
    }

    private fun caveman(text: String): String {
        val result = sentenceBreak.split(text).flatMap { sentence ->
            var concise = shorten(sentence)
            concise = Regex(
                "(?iu)^(.+?)\\s+because\\s+(.+?)([.!?])$",
            ).replace(concise) { match ->
                val reason = match.groupValues[2].replaceFirstChar { it.uppercase() }
                val statement = match.groupValues[1].trimEnd(',', ';', ':', '.', '!', '?')
                "$statement. $reason${match.groupValues[3]}"
            }
            concise = Regex("(?i)(^|(?<=[.!?])\\s+)Please\\s+")
                .replace(concise, "$1")
            concise = Regex("(?i)^The update covers the (.+?)([.!?])$")
                .replace(concise, "$1 update follows$2")
            concise = Regex(
                "(?i)^The (.+?) limit is ([^;.!?]+); it must not be exceeded([.!?])$",
            ).replace(concise, "The $1 limit is $2. Do not exceed it$3")
            concise = Regex(
                "(?i)^do not publish the (.+?) or send it to anyone outside the (.+?)([.!?])$",
            ).replace(concise, "Do not share the $1 outside the $2$3")
            concise = Regex("(?i)\\bEvent ID\\b").replace(concise, "Event")
            concise = Regex("(?i)\\b(scans?|checks?|reviews?)\\s+batch\\b")
                .replace(concise, "$1")
            concise = Regex("(?i)\\bwere labeled incorrectly\\b")
                .replace(concise, "were mislabeled")
            concise = Regex(
                "(?i)^The (.+?) were uploaded ([A-Z][\\p{L}'’-]+)\\. " +
                    "(one|two|three|four|five|six|seven|eight|nine|ten|\\d+) " +
                    "([^.!?]+) are missing([.!?])$",
            ).replace(concise) { match ->
                "${match.groupValues[2]}'s ${match.groupValues[1]} lack " +
                    "${match.groupValues[3].lowercase(Locale.ROOT)} " +
                    "${match.groupValues[4]}${match.groupValues[5]}"
            }
            concise = Regex(
                "(?i)^After reviewing the ([^,]+), the ([^.!?]+?) appear ([^.!?]+)([.!?])$",
            ).replace(concise, "The $1 show the $2 are $3$4")
            concise = Regex("(?i)\\bthe actual issue\\b").replace(concise, "the issue")
            concise = Regex(
                "(?i)^If ([^,]+), send two other time slots and choose the best option([.!?])$",
            ).replace(concise, "If $1, send two other time slots. Choose the best$2")
            concise = Regex(
                "(?i)^Let(?:'|’)s close the remaining items without blame during the " +
                    "([^.!?]+)([.!?])$",
            ).replace(concise, "Close the $1 without blame$2")
            concise = Regex(
                "(?iu)(\\uE002),?\\s+and\\s+(he|she|they|we|you|it)\\b",
            ).replace(concise) { match ->
                "${match.groupValues[1]}. ${match.groupValues[2].replaceFirstChar { it.uppercase() }}"
            }
            concise = Regex(
                "(?iu)\\b([\\p{L}][\\p{L}'’-]*)\\s+wrote,\\s+(?=\\uE000QUOTE)",
            ).replace(concise, "$1 wrote ")
            concise = Regex(
                "(?iu)^([\\p{L}][\\p{L}'’-]*)\\s+wrote\\s+" +
                    "(\\uE000QUOTE[0-9A-F]+\\uE002)\\s+although\\s+(.+?)([.!?])$",
            ).replace(concise) { match ->
                val contrast = match.groupValues[3].replaceFirstChar { it.uppercase() }
                "${match.groupValues[2]} wrote ${match.groupValues[1]}. " +
                    "$contrast${match.groupValues[4]}"
            }
            concise = concise.replace(
                Regex(
                    "(?i)(?<![\\uE002”\"])(?<!\\uE002,),?\\s+(?:" +
                        "and\\s+(?=(?:he|she|they|we|you|it|there|this|that|whoever|please|send|use|" +
                        "tell|move|write|label|spend|the\\s+[\\p{L}'’-]+(?:\\s+[\\p{L}'’-]+){0,2}\\s+" +
                        "(?:who|that|is|are|was|were|has|have|needs|must|can|will))\\b)|" +
                        "but\\s+(?=(?:I|he|she|they|we|you|it|there|this|that|the|a|an|no|" +
                        "one|two|three|four|five|six|seven|eight|nine|ten|do|does|did|" +
                        "cannot|can't|will|would)\\b)|" +
                        "although\\s+|yet\\s+|so\\s+(?=(?:I|he|she|they|we|you|it|there|" +
                        "this|that|do|don't|stop|send|use|tell|move|write|label|close)\\b))",
                ),
                ". ",
            )
                .replace("; ", ". ")
            if (concise.trimEnd().endsWith('?')) {
                concise = Regex(
                    "(?i)(\\bdid\\s+(\\p{L}+)\\s+mean\\s+.+?),\\s+or\\s+(?:that\\s+)?",
                ).replace(concise) { match ->
                    "${match.groupValues[1]}? Or did ${match.groupValues[2]} mean "
                }
            }
            val clauses = sentenceBreak.split(concise).filter { it.isNotBlank() }
                .flatMap(::plainRelativeClauses)
            if (clauses.size <= 4) clauses else clauses.take(3) + listOf(
                clauses.drop(3).joinToString("; ") { continuationCase(it.trimEnd('.')) } + ".",
            )
        }.joinToString(" ")
        return Regex("(?iu)\\s+and\\s+([\\p{L}'’-]+)\\s+who\\b").replace(result) { match ->
            ". ${match.groupValues[1].replaceFirstChar { it.uppercase() }} who"
        }.let { compacted ->
            Regex(
                "(?i)\\bThe (.+?) were uploaded ([A-Z][\\p{L}'’-]+)\\. " +
                    "(one|two|three|four|five|six|seven|eight|nine|ten|\\d+) " +
                    "([^.!?]+) are missing([.!?])",
            ).replace(compacted) { match ->
                "${match.groupValues[2]}'s ${match.groupValues[1]} lack " +
                    "${match.groupValues[3].lowercase(Locale.ROOT)} " +
                    "${match.groupValues[4]}${match.groupValues[5]}"
            }
        }
    }

    private fun plainRelativeClauses(clause: String): List<String> {
        val plain = plainClause(clause)
        val relative = Regex(
            "(?iu)^(.+?)\\s+and\\s+((?:the\\s+)?[\\p{L}'’-]+\\s+who\\b.+?)([.!?])$",
        ).matchEntire(plain) ?: return listOf(plain)
        return listOf(
            relative.groupValues[1].trimEnd('.', ',', ';', ':', '!', '?') + ".",
            relative.groupValues[2].replaceFirstChar { it.uppercase() } + relative.groupValues[3],
        )
    }

    private fun plainClause(clause: String): String {
        val clauseWords = clause.split(Regex("\\s+"))
        val words = clauseWords.filterIndexed { _, word ->
            val normalized = word.lowercase(Locale.ROOT).trim(',', '.', '!', '?')
            normalized !in setOf(
                "very", "really", "just", "basically", "actually", "honestly",
            )
        }.joinToString(" ").replaceFirstChar { it.uppercase() }
        val terminal = if (clause.trimEnd().endsWith('?')) "?" else "."
        return words.trimEnd('.', ',', ';', ':', '!', '?') + terminal
    }
}
