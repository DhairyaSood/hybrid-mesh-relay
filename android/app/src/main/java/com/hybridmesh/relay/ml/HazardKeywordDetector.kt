package com.hybridmesh.relay.ml

/** Heuristic hazard hints — not event verification. */
object HazardKeywordDetector {
    data class Result(val primaryHint: String, val evidence: String?)

    private val drill = Regex("""\b(fire drill|collapse drill|अग्निशमन अभ्यास)\b""", RegexOption.IGNORE_CASE)
    private val negation = Regex("""\b(no flooding|denied the cyclone|hoax|not true)\b""", RegexOption.IGNORE_CASE)
    private val traffic = Regex("""\b(traffic police|alternate route|diversion|detour|road closed)\b""", RegexOption.IGNORE_CASE)
    private val fire = Regex("""\b(fire|flames|building on fire|आग)\b""", RegexOption.IGNORE_CASE)
    private val flood = Regex("""\b(flood|flooded|बाढ़)\b""", RegexOption.IGNORE_CASE)
    private val eq = Regex("""\b(earthquake|भूकंप)\b""", RegexOption.IGNORE_CASE)

    fun detect(text: String): Result {
        val norm = NeyraTextPreprocessor.cleanDisaster(text)
        if (norm.isBlank()) return Result("unknown_or_insufficient_information", null)
        if (negation.containsMatchIn(norm)) return Result("unknown_or_insufficient_information", "negated")
        if (drill.containsMatchIn(norm)) return Result("non_disaster", "drill")
        if (traffic.containsMatchIn(norm) && !fire.containsMatchIn(norm) && !flood.containsMatchIn(norm)) {
            return Result("non_disaster", "traffic routing")
        }
        when {
            fire.containsMatchIn(norm) -> return Result("fire", fire.find(norm)?.value)
            flood.containsMatchIn(norm) -> return Result("flood", flood.find(norm)?.value)
            eq.containsMatchIn(norm) -> return Result("earthquake", eq.find(norm)?.value)
        }
        if (norm.contains("help") && norm.contains("school")) return Result("non_disaster", "school help")
        return Result("unknown_or_insufficient_information", null)
    }
}
