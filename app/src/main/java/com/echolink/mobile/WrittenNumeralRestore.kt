package com.echolink.mobile

/**
 * Puts back a numeral the model spelled out.
 *
 * The writer's "Imagine buying 80 packs" came back as "eighty packs", and
 * "sticking out 20 feet" as "twenty feet". Spelling a number out is a house
 * style, not a correction, and Proofread was not asked for one.
 *
 * This does NOT touch the validator. `AiSafetyValidator` compares numbers by
 * VALUE on purpose - its own comment says written and numeric forms of the
 * same value compare equal, so that a changed value, order or meridiem stays a
 * hard failure while "three" and "3" do not. That check is right and stays.
 * Making it form-sensitive would turn a harmless restyling into a hard
 * failure, and a hard failure here rejects the WHOLE paragraph, throwing away
 * every other correction in it. That trade is the worst outcome this codebase
 * recognises.
 *
 * So the value check keeps its job and the form is restored afterwards, which
 * preserves both properties instead of trading one for the other. It runs
 * inside `residualRepair`, which already has the writer's text and the
 * candidate side by side and is model-free and meaning-neutral by
 * construction.
 *
 * Only a numeral the writer actually typed is restored. A number the model
 * introduced is left alone, because there is nothing to restore it to.
 */
object WrittenNumeralRestore {
    private val numeral = Regex("(?<![\\p{L}\\p{N}])(\\d[\\d,]*)(?![\\p{L}\\p{N}])")

    /** Enough to cover what a model actually spells out in ordinary prose. */
    private val units = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven",
        "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
        "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = mapOf(
        20 to "twenty", 30 to "thirty", 40 to "forty", 50 to "fifty",
        60 to "sixty", 70 to "seventy", 80 to "eighty", 90 to "ninety",
    )

    /**
     * How the model would have written [value] as words, or null when it is
     * outside the range worth guessing at. Kept deliberately small: a wrong
     * guess here simply means no restoration, which is the old behaviour.
     */
    private fun spelled(value: Int): List<String> {
        if (value < 0) return emptyList()
        if (value < units.size) return listOf(units[value])
        tens[value]?.let { return listOf(it) }
        if (value in 21..99) {
            val ten = tens[value / 10 * 10] ?: return emptyList()
            val unit = units.getOrNull(value % 10) ?: return emptyList()
            // Both spellings a model reaches for.
            return listOf("$ten-$unit", "$ten $unit")
        }
        if (value == 100) return listOf("one hundred", "a hundred")
        if (value == 1000) return listOf("one thousand", "a thousand")
        return emptyList()
    }

    fun apply(original: String, candidate: String): String {
        if (candidate.isBlank()) return candidate
        var result = candidate
        numeral.findAll(original).forEach { match ->
            val digits = match.groupValues[1]
            val value = digits.replace(",", "").toIntOrNull() ?: return@forEach
            // Already there in the writer's form, so nothing was spelled out.
            if (wholeWord(Regex.escape(digits)).containsMatchIn(result)) return@forEach
            spelled(value).forEach inner@{ words ->
                val written = wholeWord(Regex.escape(words))
                if (!written.containsMatchIn(result)) return@inner
                // The writer's digits, exactly as typed. Safe as a literal
                // replacement: the group only ever holds digits and commas,
                // so there is no $ or backslash for the replacer to read.
                result = written.replaceFirst(result, digits)
                return@forEach
            }
        }
        return result
    }

    private fun wholeWord(escaped: String): Regex =
        Regex("(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
}
