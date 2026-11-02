package com.techted89.flockcam.data.location

import android.content.Context
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FlockLocationMonitor(
    private val context: Context,
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    private var request: LocationRequest? = null
    private var callback: LocationCallback? = null

    fun start(minAccuracyMeters: Int = DEFAULT_ACCURACY_M) {
        val locationRequest = LocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setInterval(1_000)
            .setMinUpdateIntervalMillis(500)
            .setMaxUpdateIntervalMillis(5_000)
            .build()
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { _location.value = it }
            }
        }
        callback = locationCallback
        request = locationRequest
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, android.os.Looper.getMainLooper())
    }

    fun stop() {
        val currentCallback = callback
        if (currentCallback != null) {
            fusedLocationClient.removeLocationUpdates(currentCallback)
        }
        callback = null
        request = null
    }

    companion object {
        const val DEFAULT_ACCURACY_M = 50
    }
}
