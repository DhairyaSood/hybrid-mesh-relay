package com.hybridmesh.relay.ml

import com.hybridmesh.relay.model.MessageType

/** What the UI shows for an advisory. Pure, so the wording rules are unit-tested. */
object AdvisoryPresentation {
    /** Only typed text is classified; locations and attachments carry encoded payloads. */
    private val CLASSIFIABLE_TYPES = setOf(MessageType.NORMAL.name, MessageType.PRIORITY.name, MessageType.EMERGENCY.name)

    private val MISUSE_CHIP = mapOf(
        "coercion_or_extortion" to "Possible extortion",
        "direct_threat_or_intimidation" to "Possible threat",
        "possible_relief_worker_impersonation_or_fraud" to "Possible fraud/impersonation",
    )

    private val DISASTER_CHIP = mapOf(
        "flood" to "Flood-related",
        "earthquake" to "Earthquake-related",
    )

    fun isClassifiable(messageType: String): Boolean = messageType in CLASSIFIABLE_TYPES

    /**
     * Chip under an incoming bubble: MU-v1 misuse (after civic refinement) and DC-T2
     * disaster type only. Null when neither is noteworthy, so ordinary messages show nothing.
     */
    fun chipText(result: MessageLocalClassifier.AdvisoryResult): String? {
        val misuse = MISUSE_CHIP[result.misuseLabel]
        val disaster = DISASTER_CHIP[result.disasterTypeDcT2]
        if (misuse == null && disaster == null) return null
        val parts = listOfNotNull(misuse?.let { "⚠ $it" }, disaster) + "advisory"
        return parts.joinToString(" · ")
    }

    /** Full advisory for the long-press dialog, including DC-T3 help topic. */
    fun detailRows(result: MessageLocalClassifier.AdvisoryResult): List<Pair<String, String>> = buildList {
        add("Misuse (MU-v1)" to humanize(result.misuseLabel))
        if (result.misuseRawLabel != result.misuseLabel) add("MU-v1 before civic rules" to humanize(result.misuseRawLabel))
        add("Disaster type (DC-T2)" to humanize(result.disasterTypeDcT2))
        add("Keyword hint" to humanize(result.disasterKeywordHint))
        add("Help topic (DC-T3)" to result.humanitarianTopic)
    }

    /** Dialog text when no advisory could be produced; null hides the section. */
    fun unavailableText(reason: String): String? = when (reason) {
        "NOT_TEXT", "EMPTY_TEXT" -> null
        "TIMEOUT" -> "Local advisory timed out on this device."
        else -> "Local advisory unavailable on this device."
    }

    private fun humanize(label: String): String = label.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
