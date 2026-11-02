package com.techted89.flockcam.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object FlockCamMath {

    const val EARTH_RADIUS_METERS = 6_371_000.0

    fun haversineMeters(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double,
    ): Double {
        val radLat1 = Math.toRadians(latitude1)
        val radLat2 = Math.toRadians(latitude2)
        val dLat = Math.toRadians(latitude2 - latitude1)
        val dLng = Math.toRadians(longitude2 - longitude1)
        val a =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(radLat1) * cos(radLat2) * sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    fun distanceMeters(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double,
    ): Double = haversineMeters(latitude1, longitude1, latitude2, longitude2)

    fun distancePointToSegmentMeters(
        latitude: Double,
        longitude: Double,
        segmentStartLat: Double,
        segmentStartLng: Double,
        segmentEndLat: Double,
        segmentEndLng: Double,
    ): Double {
        val midLat = Math.toRadians((segmentStartLat + segmentEndLat) / 2.0)
        val cosMid = cos(midLat)
        fun x(longitude: Double): Double = Math.toRadians(longitude) * cosMid * Math.PI / 180.0 * EARTH_RADIUS_METERS
        fun y(latitude: Double): Double = Math.toRadians(latitude) * Math.PI / 180.0 * EARTH_RADIUS_METERS

        val ax = x(segmentStartLng)
        val ay = y(segmentStartLat)
        val bx = x(segmentEndLng)
        val by = y(segmentEndLat)
        val px = x(longitude)
        val py = y(latitude)

        val abx = bx - ax
        val aby = by - ay
        val abLengthSq = abx * abx + aby * aby
        val t = if (abLengthSq == 0.0) 0.0 else ((px - ax) * abx + (py - ay) * aby) / abLengthSq
        val clampedT = t.coerceIn(0.0, 1.0)
        val closestX = ax + clampedT * abx
        val closestY = ay + clampedT * aby
        val dx = px - closestX
        val dy = py - closestY
        return sqrt(dx * dx + dy * dy)
    }

    fun isWithinRange(
        latitude: Double,
        longitude: Double,
        centerLatitude: Double,
        centerLongitude: Double,
        radiusMeters: Int,
    ): Boolean = distanceMeters(latitude, longitude, centerLatitude, centerLongitude) <= radiusMeters.toDouble()
}
