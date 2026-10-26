package com.hybridmesh.relay.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class HazardKeywordDetectorTest {
    @Test
    fun fireBuilding() {
        assertEquals("fire", HazardKeywordDetector.detect("Fire in the building.").primaryHint)
    }

    @Test
    fun fireDrillBenign() {
        assertEquals("non_disaster", HazardKeywordDetector.detect("The fire drill is tomorrow.").primaryHint)
    }

    @Test
    fun trafficDiversion() {
        assertEquals(
            "non_disaster",
            HazardKeywordDetector.detect("Traffic police closed the bridge; use alternate route.").primaryHint,
        )
    }
}
