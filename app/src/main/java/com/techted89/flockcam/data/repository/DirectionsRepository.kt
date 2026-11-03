package com.techted89.flockcam.data.repository

import com.techted89.flockcam.BuildConfig
import com.techted89.flockcam.data.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class RouteResult(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val approximate: Boolean,
)

class DirectionsRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getRoute(origin: LatLng, destination: LatLng): RouteResult =
        withContext(Dispatchers.IO) {
            val key = BuildConfig.MAPS_API_KEY
            if (key.isBlank()) return@withContext straightLine(origin, destination)
            val url = buildString {
                append("https://maps.googleapis.com/maps/api/directions/json")
                append("?origin=").append(origin.latitude).append(',').append(origin.longitude)
                append("&destination=").append(destination.latitude).append(',').append(destination.longitude)
                append("&mode=driving&key=").append(URLEncoder.encode(key, "UTF-8"))
            }
            runCatching {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    parseDirections(body)
                }
            }.getOrNull() ?: straightLine(origin, destination)
        }

    private fun parseDirections(body: String): RouteResult? {
        val root = json.parseToJsonElement(body).jsonObject
        val status = root["status"]?.jsonPrimitive?.content
        if (status != "OK") return null
        val route = root["routes"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val encoded = route["overview_polyline"]?.jsonObject?.get("points")?.jsonPrimitive?.content
            ?: return null
        val points = decodePolyline(encoded)
        if (points.size < 2) return null
        val leg = route["legs"]?.jsonArray?.firstOrNull()?.jsonObject
        val distance = leg?.get("distance")?.jsonObject?.get("value")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        val duration = leg?.get("duration")?.jsonObject?.get("value")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        return RouteResult(points, distance, duration, approximate = false)
    }

    private fun straightLine(origin: LatLng, destination: LatLng): RouteResult {
        val points = listOf(origin, destination)
        return RouteResult(
            points = points,
            distanceMeters = com.techted89.flockcam.util.FlockCamMath.haversineMeters(
                origin.latitude,
                origin.longitude,
                destination.latitude,
                destination.longitude,
            ),
            durationSeconds = 0.0,
            approximate = true,
        )
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val points = mutableListOf<LatLng>()
        var index = 0
        var latitude = 0
        var longitude = 0
        while (index < encoded.length) {
            val latResult = decodeChunk(encoded, index)
            index = latResult.first
            latitude += latResult.second
            if (index >= encoded.length) break
            val lngResult = decodeChunk(encoded, index)
            index = lngResult.first
            longitude += lngResult.second
            points.add(LatLng(latitude / 1E5, longitude / 1E5))
        }
        return points
    }

    private fun decodeChunk(encoded: String, start: Int): Pair<Int, Int> {
        var index = start
        var shift = 0
        var result = 0
        while (true) {
            if (index >= encoded.length) return index to result
            val byte = encoded[index].code - 63
            index += 1
            result = result or ((byte and 0x1f) shl shift)
            shift += 5
            if (byte < 0x20) break
        }
        val delta = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        return index to delta
    }
}
