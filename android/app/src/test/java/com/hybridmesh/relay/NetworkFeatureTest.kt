package com.hybridmesh.relay

import com.hybridmesh.relay.location.LocationActions
import com.hybridmesh.relay.location.LocationPayload
import com.hybridmesh.relay.network.percentile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkFeatureTest {
    @Test
    fun mapsUriUsesExactCoordinates() {
        val url = LocationActions.mapsUrl(
            LocationPayload(28.6139, 77.2090, null, 0L)
        )

        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=28.6139,77.209",
            url
        )
    }

    @Test
    fun mapsUriRejectsInvalidCoordinates() {
        assertNull(LocationActions.mapsUrl(LocationPayload(91.0, 0.0, null, 0L)))
        assertNull(LocationActions.mapsUrl(LocationPayload(0.0, -181.0, null, 0L)))
    }

    @Test
    fun percentileUsesSortedMeasuredSamples() {
        assertEquals(20L, percentile(listOf(30L, 10L, 20L), 0.5))
        assertEquals(30L, percentile(listOf(30L, 10L, 20L), 0.95))
        assertNull(percentile(emptyList(), 0.5))
    }
}
