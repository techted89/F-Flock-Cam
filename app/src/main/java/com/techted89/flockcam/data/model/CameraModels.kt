package com.techted89.flockcam.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CameraInfo(
    val id: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val name: String? = null,
    val source: String? = null,
    val distanceMeters: Double? = null,
)

@Serializable
data class CameraListResponse(
    val cameras: List<CameraInfo> = emptyList(),
    val source: String? = null,
    val cached: Boolean = false,
)

@Serializable
data class CameraEnvelope(
    val cameras: List<CameraInfo> = emptyList(),
    val source: String? = null,
    val cached: Boolean = false,
)

@Serializable
data class LatLng(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class SuggestedCamera(
    val deviceId: String? = null,
    val latitude: Double,
    val longitude: Double,
    val source: String? = null,
    val note: String? = null,
)

@Serializable
data class RoutePredictionResponse(
    val origin: LatLng? = null,
    val destination: LatLng? = null,
    val route: List<LatLng> = emptyList(),
    val cameras: List<CameraInfo> = emptyList(),
)

@Serializable
data class RoutePredictionRequest(
    val originLatitude: Double,
    val originLongitude: Double,
    val destinationLatitude: Double,
    val destinationLongitude: Double,
    val radiusMeters: Int,
)

@Serializable
data class PendingAction(
    val action: String,
    val source: String? = null,
    val createdAtMillis: Long? = null,
)

@Serializable
data class ActionListResponse(
    val actions: List<PendingAction> = emptyList(),
)

@Serializable
data class ActionEnvelope(
    val actions: List<PendingAction> = emptyList(),
)

@Serializable
data class EnqueueActionRequest(
    val deviceId: String,
    val action: String,
    val source: String? = null,
)

@Serializable
data class ActionResponse(
    val status: String? = null,
    val actionId: String? = null,
)

@Serializable
data class ActionAck(
    val status: String? = null,
    val actionId: String? = null,
)
