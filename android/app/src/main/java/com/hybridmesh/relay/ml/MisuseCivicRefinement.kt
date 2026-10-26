package com.hybridmesh.relay.ml

/** Tester-parity civic rules on MU-v1 raw label (display only). */
object MisuseCivicRefinement {
    private val traffic = Regex("""\b(traffic police|alternate route|bridge closed|diversion)\b""", RegexOption.IGNORE_CASE)
    private val school = Regex("""\b(help.*school|school.*closed|fire drill)\b""", RegexOption.IGNORE_CASE)
    private val coercePay = Regex("""\b(send money now|pay now or)\b""", RegexOption.IGNORE_CASE)
    private val quote = Regex("""\b(witness reported|someone reported)\b""", RegexOption.IGNORE_CASE)
    private val threat = Regex("""\b(i will kill|hurt your family)\b""", RegexOption.IGNORE_CASE)

    fun refine(text: String, rawLabel: String): String {
        val norm = NeyraTextPreprocessor.cleanMisuse(text)
        if (coercePay.containsMatchIn(norm) && !traffic.containsMatchIn(norm)) return "coercion_or_extortion"
        if (quote.containsMatchIn(norm) && norm.contains("threaten")) return "quoted_or_historical_threat"
        if ((traffic.containsMatchIn(norm) || school.containsMatchIn(norm)) && !threat.containsMatchIn(norm)) {
            return "benign_emergency_information"
        }
        if (threat.containsMatchIn(norm)) return "direct_threat_or_intimidation"
        if ((rawLabel == "direct_threat_or_intimidation" || rawLabel == "coercion_or_extortion") &&
            (traffic.containsMatchIn(norm) || school.containsMatchIn(norm))
        ) {
            return "benign_emergency_information"
        }
        return rawLabel
    }
}
