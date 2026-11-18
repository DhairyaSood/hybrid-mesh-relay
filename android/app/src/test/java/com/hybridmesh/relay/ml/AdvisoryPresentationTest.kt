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

    @Test
    fun sosAlertsAreNeverClassified() {
        val sos = com.hybridmesh.relay.sos.SosPayload.format(12.97, 77.59, 8f, 1000L, null, 1_700_000_000_000L)
        val noLoc = com.hybridmesh.relay.sos.SosPayload.format(null, null, null, null, "permission denied", 1_700_000_000_000L)
        for (type in listOf("NORMAL", "PRIORITY", "EMERGENCY")) {
            assertFalse(AdvisoryPresentation.shouldClassify(type, sos))
            assertFalse(AdvisoryPresentation.shouldClassify(type, noLoc))
            assertFalse(AdvisoryPresentation.shouldClassify(type, "SOS v1 CANCELLED | 2026-11-13T09:16:40Z | 14:46 IST"))
        }
    }

    @Test
    fun normalTextStillClassified() {
        assertTrue(AdvisoryPresentation.shouldClassify("NORMAL", "We need water at the shelter"))
        assertTrue(AdvisoryPresentation.shouldClassify("EMERGENCY", "flood rising"))
        assertFalse(AdvisoryPresentation.shouldClassify("LOCATION", "hello"))
    }

    @Test
    fun sosMentionedMidTextIsStillClassified() {
        assertTrue(AdvisoryPresentation.shouldClassify("NORMAL", "send SOS supplies"))
        assertTrue(AdvisoryPresentation.shouldClassify("NORMAL", "Please read SOS v1 docs"))
    }
}
