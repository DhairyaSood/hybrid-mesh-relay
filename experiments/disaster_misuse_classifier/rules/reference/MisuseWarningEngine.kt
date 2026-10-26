package com.hybridmesh.relay.rules.reference

/**
 * Reference port of [experiments/disaster_misuse_classifier/rules/misuse_warning_engine.py].
 * NOT wired into MeshEngine or delivery — for future UI-only hints.
 */
data class MisuseWarningResult(
    val mayWarrantReview: Boolean,
    val categories: List<String> = emptyList(),
    val userMessage: String = "",
    val confidence: String = "none",
)

object MisuseWarningEngine {
    private val debunk = Regex(
        """\b(hoax|false\s+rumou?r|fake\s+news|don'?t\s+share|debunk|not\s+true)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val report = Regex(
        """\b(reports?\s+say|allegedly|unconfirmed|according\s+to|rt\s+@)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val threat = Regex(
        """\b(kill\s+(you|them)|i\s+will\s+kill|death\s+threat)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val coercion = Regex(
        """\b(pay\s+for\s+rescue|blackmail|pay\s+or\s+die)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val impersonation = Regex(
        """\b(fake\s+ngo|scam\s+donation|impersonat(e|ing)\s+official)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val benign = Regex(
        """\b(evacuate\s+to|shelter\s+at|call\s+112|official\s+helpline)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun analyze(text: String): MisuseWarningResult {
        val norm = text.lowercase().replace(Regex("""\s+"""), " ").trim()
        if (norm.length < 4) return MisuseWarningResult(false)
        if (benign.containsMatchIn(norm) && !threat.containsMatchIn(norm)) {
            return MisuseWarningResult(false)
        }
        if (debunk.containsMatchIn(norm)) return MisuseWarningResult(false)
        val reportish = report.containsMatchIn(norm)
        val cats = mutableListOf<String>()
        if (threat.containsMatchIn(norm) && !reportish) cats += "possible_threat"
        if (coercion.containsMatchIn(norm)) cats += "possible_coercion"
        if (impersonation.containsMatchIn(norm)) cats += "possible_impersonation_fraud"
        if (cats.isEmpty()) return MisuseWarningResult(false)
        val msg =
            "This message may contain concerning language. " +
                "This is an automated hint only — not proof of wrongdoing."
        return MisuseWarningResult(
            mayWarrantReview = true,
            categories = cats,
            userMessage = msg,
            confidence = if (cats.size > 1) "medium" else "low",
        )
    }
}
