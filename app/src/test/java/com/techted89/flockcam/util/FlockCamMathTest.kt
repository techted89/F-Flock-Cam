package com.techted89.flockcam.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlockCamMathTest {

    @Test
    fun `haversine distance between same point is zero`() {
        assertEquals(0.0, FlockCamMath.distanceMeters(52.3676, 4.9041, 52.3676, 4.9041), 0.0)
    }

    @Test
    fun `haversine distance of one degree latitude is roughly one hundred kilometers`() {
        val distance = FlockCamMath.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertTrue(distance in 110_000.0..112_000.0)
    }

    @Test
    fun `point on segment reports zero distance`() {
        val distance = FlockCamMath.distancePointToSegmentMeters(
            latitude = 52.3700,
            longitude = 4.8970,
            segmentStartLat = 52.3676,
            segmentStartLng = 4.9041,
            segmentEndLat = 52.3700,
            segmentEndLng = 4.8970,
        )
        assertEquals(0.0, distance, 0.0001)
    }

    @Test
    fun `point outside segment distance is positive`() {
        val distance = FlockCamMath.distancePointToSegmentMeters(
            latitude = 52.4000,
            longitude = 4.9500,
            segmentStartLat = 52.3676,
            segmentStartLng = 4.9041,
            segmentEndLat = 52.3700,
            segmentEndLng = 4.8970,
        )
        assertTrue(distance > 0.0)
    }

    @Test
    fun `within range checks use radius boundary`() {
        assertTrue(FlockCamMath.isWithinRange(52.3680, 4.9041, 52.3676, 4.9041, 100))
        assertFalse(FlockCamMath.isWithinRange(52.3770, 4.9041, 52.3676, 4.9041, 100))
    }
}
