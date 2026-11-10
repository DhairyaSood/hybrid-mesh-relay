package com.hybridmesh.relay.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class MisuseCivicRefinementTest {
    @Test
    fun trafficNotCoercion() {
        val text = "Traffic police have closed the bridge; use alternate route."
        assertEquals(
            "benign_emergency_information",
            MisuseCivicRefinement.refine(text, "coercion_or_extortion"),
        )
    }

    @Test
    fun coercionKept() {
        val text = "Send money now or we will hurt your family."
        assertEquals(
            "coercion_or_extortion",
            MisuseCivicRefinement.refine(text, "possible_relief_worker_impersonation_or_fraud"),
        )
    }
}
