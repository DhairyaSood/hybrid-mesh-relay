package com.hybridmesh.relay.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisoryPresentationTest {
    private fun result(misuse: String = "benign_emergency_information", raw: String = misuse, disaster: String = "non_disaster") =
        MessageLocalClassifier.AdvisoryResult(
            disasterTypeDcT2 = disaster,
            disasterKeywordHint = "unknown_or_insufficient_information",
            humanitarianTopic = "Food and Medicine",
            misuseLabel = misuse,
            misuseRawLabel = raw,
            disclaimer = MessageLocalClassifier.DISCLAIMER,
        )

    @Test
    fun ordinaryMessageShowsNoChip() {
        assertNull(AdvisoryPresentation.chipText(result()))
        assertNull(AdvisoryPresentation.chipText(result(misuse = "ambiguous_needs_review")))
        assertNull(AdvisoryPresentation.chipText(result(misuse = "quoted_or_historical_threat")))
        assertNull(AdvisoryPresentation.chipText(result(misuse = "report_or_warning_about_misconduct")))
    }

    @Test
    fun misuseAndDisasterAreCombined() {
        assertEquals(
            "⚠ Possible fraud/impersonation · Flood-related · advisory",
            AdvisoryPresentation.chipText(result(misuse = "possible_relief_worker_impersonation_or_fraud", disaster = "flood"))
        )
    }

    @Test
    fun disasterAloneHasNoWarningSign() {
        assertEquals("Earthquake-related · advisory", AdvisoryPresentation.chipText(result(disaster = "earthquake")))
    }

    @Test
    fun chipUsesRefinedMisuseLabelNotRaw() {
        // Civic refinement turned a raw "threat" into benign information: no warning.
        assertNull(AdvisoryPresentation.chipText(result(misuse = "benign_emergency_information", raw = "direct_threat_or_intimidation")))
    }

    @Test
    fun helpTopicOnlyInDetailRows() {
        val r = result(misuse = "coercion_or_extortion", disaster = "flood")
        assertFalse(AdvisoryPresentation.chipText(r)!!.contains("Food"))
        assertTrue(AdvisoryPresentation.detailRows(r).contains("Help topic (DC-T3)" to "Food and Medicine"))
    }

    @Test
    fun onlyTypedTextIsClassified() {
        assertTrue(AdvisoryPresentation.isClassifiable("NORMAL"))
        assertTrue(AdvisoryPresentation.isClassifiable("EMERGENCY"))
        assertFalse(AdvisoryPresentation.isClassifiable("LOCATION"))
        assertFalse(AdvisoryPresentation.isClassifiable("ATTACHMENT"))
    }
}
