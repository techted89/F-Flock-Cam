package com.techted89.flockcam.ui

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import com.techted89.flockcam.FlockCamApplication
import com.techted89.flockcam.data.location.FlockLocationMonitor
import com.techted89.flockcam.data.model.CameraInfo
import com.techted89.flockcam.data.model.LatLng
import com.techted89.flockcam.data.model.RoutePredictionRequest
import com.techted89.flockcam.data.model.SuggestedCamera
import com.techted89.flockcam.data.remote.FlockApi
import com.techted89.flockcam.util.FlockCamMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FlockMapViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val app = application as FlockCamApplication
    private val locationMonitor: FlockLocationMonitor = app.locationMonitor
    private val api: FlockApi = app.flockApi

    private val _userLocation = MutableStateFlow<Location?>(null)
    val userLocation: StateFlow<Location?> = _userLocation.asStateFlow()

    private val _nearbyCameras = MutableStateFlow<List<CameraInfo>>(emptyList())
    val nearbyCameras: StateFlow<List<CameraInfo>> = _nearbyCameras.asStateFlow()

    private val _route = MutableStateFlow<List<LatLng>?>(null)
    val route: StateFlow<List<LatLng>?> = _route.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val deviceId = "anonymous-device"

    init {
        locationMonitor.location.collect { location ->
            location?.let { _userLocation.value = it }
        }
    }

    fun refreshNearby(latitude: Double, longitude: Double, radiusMeters: Int) {
        viewModelScope.launch {
            try {
                val response = api.getNearby(latitude = latitude, longitude = longitude, radius = radiusMeters)
                if (response.isSuccessful) {
                    _nearbyCameras.value = response.body()?.cameras ?: emptyList()
                    _statusMessage.value = "Loaded ${response.body()?.cameras?.size ?: 0} cameras within ${radiusMeters}m"
                }
            } catch (error: Exception) {
                _statusMessage.value = "Failed to load nearby cameras: ${error.message}"
            }
        }
    }

    suspend fun predictRoute(
        origin: LatLng,
        destination: LatLng,
        radiusMeters: Int,
    ): List<CameraInfo> {
        val request = RoutePredictionRequest(
            originLatitude = origin.latitude,
            originLongitude = origin.longitude,
            destinationLatitude = destination.latitude,
            destinationLongitude = destination.longitude,
            radiusMeters = radiusMeters,
        )
        return try {
            val response = api.predictRoute(request)
            if (response.isSuccessful) {
                response.body()?.cameras ?: emptyList()
            } else {
                emptyList()
            }
        } catch (error: Exception) {
            emptyList()
        }
    }

    suspend fun avoidRoute(
        origin: LatLng,
        destination: LatLng,
        radiusMeters: Int,
    ): List<LatLng>? {
        val request = RoutePredictionRequest(
            originLatitude = origin.latitude,
            originLongitude = origin.longitude,
            destinationLatitude = destination.latitude,
            destinationLongitude = destination.longitude,
            radiusMeters = radiusMeters,
        )
        return try {
            val response = api.avoidRoute(request)
            if (response.isSuccessful) {
                response.body()?.route
            } else {
                null
            }
        } catch (error: Exception) {
            null
        }
    }

    suspend fun suggestCamera(
        latitude: Double,
        longitude: Double,
        source: String?,
        note: String?,
    ): Boolean {
        return try {
            val response = api.suggestCamera(
                SuggestedCamera(
                    deviceId = deviceId,
                    latitude = latitude,
                    longitude = longitude,
                    source = source,
                    note = note,
                ),
            )
            response.isSuccessful
        } catch (error: Exception) {
            false
        }
    }
}
