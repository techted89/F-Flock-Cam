package com.techted89.flockcam.data.repository

import com.techted89.flockcam.data.model.CameraInfo
import com.techted89.flockcam.data.model.Entitlement
import com.techted89.flockcam.data.model.LatLng
import com.techted89.flockcam.data.model.RegionResponse
import com.techted89.flockcam.data.model.RouteRequest
import com.techted89.flockcam.data.model.SuggestedCamera
import com.techted89.flockcam.data.remote.FlockApi
import com.techted89.flockcam.data.remote.bodyOrThrow
import com.techted89.flockcam.data.session.SessionStore
import com.techted89.flockcam.util.FlockCamMath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.cos
import kotlin.math.floor

data class RegionSnapshot(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
    val cameras: List<CameraInfo>,
    val tier: String,
    val maxRadiusMeters: Double,
    val visibleCameras: Int?,
    val adsRequired: Boolean,
    val totalAvailable: Int,
    val truncated: Boolean,
    val fromCache: Boolean,
)

class CameraRepository(
    private val api: FlockApi,
    private val sessionStore: SessionStore,
) {
    private data class CacheEntry(val snapshot: RegionSnapshot, val fetchedAtMillis: Long)

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, CacheEntry>()
    private val cacheTtlMillis = 5 * 60 * 1000L
    private val maxCacheEntries = 12

    private fun authHeader(): String? = sessionStore.state.value.authorizationHeader

    suspend fun loadRegion(
        centerLatitude: Double,
        centerLongitude: Double,
        radiusMeters: Double,
        entitlement: Entitlement,
        forceRefresh: Boolean = false,
    ): RegionSnapshot {
        val requestedRadius = radiusMeters.coerceAtMost(entitlement.maxRadiusMeters)
        val bbox = bboxAround(centerLatitude, centerLongitude, requestedRadius * 1.2)
        return loadRegionForBbox(
            bbox.minLat,
            bbox.maxLat,
            bbox.minLng,
            bbox.maxLng,
            entitlement,
            forceRefresh,
        )
    }

    suspend fun loadRegionForBbox(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        entitlement: Entitlement,
        forceRefresh: Boolean = false,
    ): RegionSnapshot {
        val bbox = BoundingBox(
            minLat = minLat.coerceAtLeast(-85.0),
            maxLat = maxLat.coerceAtMost(85.0),
            minLng = minLng.coerceAtLeast(-180.0),
            maxLng = maxLng.coerceAtMost(180.0),
        )
        val key = cacheKey(bbox, entitlement.tier)

        val cached = mutex.withLock { cache[key] }
        if (!forceRefresh && cached != null && isFresh(cached)) {
            return cached.snapshot.copy(fromCache = true)
        }

        return try {
            val response = api.getRegion(
                minLat = bbox.minLat,
                maxLat = bbox.maxLat,
                minLng = bbox.minLng,
                maxLng = bbox.maxLng,
                authorization = authHeader(),
            ).bodyOrThrow()
            val snapshot = response.toSnapshot(bbox, entitlement)
            mutex.withLock {
                cache[key] = CacheEntry(snapshot, System.currentTimeMillis())
                trimCache()
            }
            snapshot
        } catch (error: Exception) {
            if (cached != null) {
                cached.snapshot.copy(fromCache = true)
            } else {
                throw error
            }
        }
    }

    suspend fun loadRouteCameras(
        route: List<LatLng>,
        corridorMeters: Int,
        entitlement: Entitlement,
    ): List<CameraInfo> {
        if (route.size < 2) return emptyList()
        val response = api.getRouteCameras(
            RouteRequest(route = route, corridorMeters = corridorMeters),
            authHeader(),
        ).bodyOrThrow()
        return applyVisibleCap(response.cameras, entitlement)
    }

    suspend fun reportCamera(
        latitude: Double,
        longitude: Double,
        note: String? = null,
        description: String? = null,
        directionDegrees: Double? = null,
        viewDirection: String? = null,
    ): Boolean {
        val response = api.suggestCamera(
            SuggestedCamera(
                deviceId = sessionStore.deviceId,
                latitude = latitude,
                longitude = longitude,
                source = "USER",
                note = note,
                description = description,
                directionDegrees = directionDegrees,
                viewDirection = viewDirection,
            ),
            authHeader(),
        )
        return response.isSuccessful
    }

    /**
     * Local radius filter. The server returns a region once; the app decides
     * what is actually visible for the current radius and entitlement.
     */
    fun filterWithinRadius(
        cameras: List<CameraInfo>,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        entitlement: Entitlement,
    ): List<CameraInfo> {
        val filtered = cameras.asSequence()
            .filter { it.latitude != null && it.longitude != null }
            .map { camera ->
                camera.copy(
                    distanceMeters = FlockCamMath.haversineMeters(
                        latitude,
                        longitude,
                        camera.latitude!!,
                        camera.longitude!!,
                    ),
                )
            }
            .filter { (it.distanceMeters ?: Double.MAX_VALUE) <= radiusMeters }
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
            .toList()
        return applyVisibleCap(filtered, entitlement)
    }

    fun filterAlongRoute(
        cameras: List<CameraInfo>,
        route: List<LatLng>,
        corridorMeters: Double,
        entitlement: Entitlement,
    ): List<CameraInfo> {
        if (route.size < 2) return emptyList()
        val filtered = cameras.asSequence()
            .filter { it.latitude != null && it.longitude != null }
            .map { camera ->
                camera.copy(distanceFromRouteMeters = distanceToRoute(camera, route))
            }
            .filter { (it.distanceFromRouteMeters ?: Double.MAX_VALUE) <= corridorMeters }
            .sortedBy { it.distanceFromRouteMeters ?: Double.MAX_VALUE }
            .toList()
        return applyVisibleCap(filtered, entitlement)
    }

    private fun distanceToRoute(camera: CameraInfo, route: List<LatLng>): Double {
        val latitude = camera.latitude ?: return Double.MAX_VALUE
        val longitude = camera.longitude ?: return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (index in 1 until route.size) {
            val start = route[index - 1]
            val end = route[index]
            val distance = FlockCamMath.distancePointToSegmentMeters(
                latitude,
                longitude,
                start.latitude,
                start.longitude,
                end.latitude,
                end.longitude,
            )
            if (distance < best) best = distance
        }
        return best
    }

    private fun applyVisibleCap(cameras: List<CameraInfo>, entitlement: Entitlement): List<CameraInfo> {
        val cap = entitlement.visibleCameras ?: return cameras
        return cameras.take(cap)
    }

    private fun RegionResponse.toSnapshot(
        bbox: BoundingBox,
        entitlement: Entitlement,
    ): RegionSnapshot = RegionSnapshot(
        minLat = bbox.minLat,
        maxLat = bbox.maxLat,
        minLng = bbox.minLng,
        maxLng = bbox.maxLng,
        cameras = cameras,
        tier = tier ?: entitlement.tier,
        maxRadiusMeters = maxRadiusMeters ?: entitlement.maxRadiusMeters,
        visibleCameras = visibleCameras ?: entitlement.visibleCameras,
        adsRequired = adsRequired,
        totalAvailable = totalAvailable,
        truncated = truncated,
        fromCache = false,
    )

    private fun isFresh(entry: CacheEntry): Boolean =
        System.currentTimeMillis() - entry.fetchedAtMillis <= cacheTtlMillis

    private fun trimCache() {
        while (cache.size > maxCacheEntries) {
            val oldest = cache.entries.minByOrNull { it.value.fetchedAtMillis } ?: break
            cache.remove(oldest.key)
        }
    }

    private fun cacheKey(bbox: BoundingBox, tier: String): String {
        fun round(value: Double) = floor(value * 100.0) / 100.0
        return "${tier}:${round(bbox.minLat)}:${round(bbox.maxLat)}:${round(bbox.minLng)}:${round(bbox.maxLng)}"
    }

    private fun bboxAround(latitude: Double, longitude: Double, radiusMeters: Double): BoundingBox {
        val latDelta = radiusMeters / 111_320.0
        val lngDelta = radiusMeters / (111_320.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))
        return BoundingBox(
            minLat = (latitude - latDelta).coerceAtLeast(-85.0),
            maxLat = (latitude + latDelta).coerceAtMost(85.0),
            minLng = (longitude - lngDelta).coerceAtLeast(-180.0),
            maxLng = (longitude + lngDelta).coerceAtMost(180.0),
        )
    }
}

data class BoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
)
