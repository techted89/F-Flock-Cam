package com.techted89.flockcam.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CameraInfo(
    val id: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val name: String? = null,
    val source: String? = null,
    val description: String? = null,
    val manufacturer: String? = null,
    val operator: String? = null,
    val directionDegrees: Double? = null,
    val viewDirection: String? = null,
    val confidence: Double? = null,
    val status: String? = null,
    val distanceMeters: Double? = null,
    val distanceFromRouteMeters: Double? = null,
)

@Serializable
data class CameraListResponse(
    val cameras: List<CameraInfo> = emptyList(),
    val source: String? = null,
    val cached: Boolean = false,
)

@Serializable
data class BoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
)

@Serializable
data class RegionResponse(
    val status: String? = null,
    val source: String? = null,
    val bbox: BoundingBox? = null,
    val radiusMeters: Double? = null,
    val tier: String? = null,
    val maxRadiusMeters: Double? = null,
    val visibleCameras: Int? = null,
    val adsRequired: Boolean = true,
    val cameras: List<CameraInfo> = emptyList(),
    val totalAvailable: Int = 0,
    val truncated: Boolean = false,
    val generatedAt: Long? = null,
)

@Serializable
data class RouteCameraResponse(
    val status: String? = null,
    val source: String? = null,
    val bbox: BoundingBox? = null,
    val corridorMeters: Double? = null,
    val routeDistanceMeters: Double? = null,
    val tier: String? = null,
    val maxRadiusMeters: Double? = null,
    val visibleCameras: Int? = null,
    val adsRequired: Boolean = true,
    val cameras: List<CameraInfo> = emptyList(),
    val totalAvailable: Int = 0,
    val truncated: Boolean = false,
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
    val description: String? = null,
    val directionDegrees: Double? = null,
    val viewDirection: String? = null,
)

@Serializable
data class RoutePredictionResponse(
    val origin: LatLng? = null,
    val destination: LatLng? = null,
    val route: List<LatLng> = emptyList(),
    val cameras: List<CameraInfo> = emptyList(),
)

@Serializable
data class RouteRequest(
    val route: List<LatLng>,
    val corridorMeters: Int = 250,
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

// --- Accounts and entitlements -------------------------------------------

@Serializable
data class UserProfile(
    val id: Long? = null,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val displayName: String? = null,
    val authProvider: String? = null,
    val createdAt: Long? = null,
)

@Serializable
data class Entitlement(
    val tier: String = "free",
    val source: String? = null,
    val expiresAt: Long? = null,
    val productId: String? = null,
    val maxRadiusMeters: Double = 2_000.0,
    val visibleCameras: Int? = 25,
    val regionMaxCameras: Int = 250,
    val adsRequired: Boolean = true,
) {
    val isPremium: Boolean get() = tier == "premium"
}

@Serializable
data class AuthResponse(
    val status: String? = null,
    val token: String? = null,
    val user: UserProfile? = null,
    val entitlement: Entitlement? = null,
    val verificationRequired: Boolean = false,
    val message: String? = null,
    val devCode: String? = null,
)

@Serializable
data class MeResponse(
    val status: String? = null,
    val user: UserProfile? = null,
    val entitlement: Entitlement? = null,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val deviceId: String? = null,
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String? = null,
    val deviceId: String? = null,
)

@Serializable
data class GoogleLoginRequest(
    val idToken: String,
    val deviceId: String? = null,
)

@Serializable
data class VerifyEmailRequest(
    val email: String,
    val code: String,
    val deviceId: String? = null,
)

@Serializable
data class EmailOnlyRequest(
    val email: String,
)

@Serializable
data class ResetPasswordRequest(
    val email: String,
    val code: String,
    val newPassword: String,
    val deviceId: String? = null,
)

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
)

@Serializable
data class StatusResponse(
    val status: String? = null,
    val message: String? = null,
    val devCode: String? = null,
)

@Serializable
data class Plan(
    val id: String,
    val label: String,
    val priceCents: Int,
    val currency: String,
    val durationSeconds: Long,
)

@Serializable
data class PlansResponse(
    val status: String? = null,
    val plans: List<Plan> = emptyList(),
)

@Serializable
data class GoogleVerifyRequest(
    val productId: String,
    val purchaseToken: String,
)

@Serializable
data class EntitlementResponse(
    val status: String? = null,
    val entitlement: Entitlement? = null,
    val alreadyCaptured: Boolean = false,
)

@Serializable
data class PayPalCreateRequest(
    val productId: String,
)

@Serializable
data class PayPalOrderResponse(
    val status: String? = null,
    val orderId: String? = null,
    val approvalUrl: String? = null,
)

@Serializable
data class PayPalCaptureRequest(
    val orderId: String,
)

@Serializable
data class AdRewardRequest(
    val provider: String = "admob",
    val proof: String? = null,
)

@Serializable
data class AdRewardResponse(
    val status: String? = null,
    val entitlement: Entitlement? = null,
    val grantedUntil: Long? = null,
    val retryAfterSeconds: Long? = null,
    val message: String? = null,
)

@Serializable
data class CameraReport(
    val id: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val source: String? = null,
    val note: String? = null,
    val description: String? = null,
    val directionDegrees: Double? = null,
    val viewDirection: String? = null,
    val manufacturer: String? = null,
    val operator: String? = null,
    val status: String? = null,
    val createdAt: Long? = null,
)

@Serializable
data class MyReportsResponse(
    val status: String? = null,
    val reports: List<CameraReport> = emptyList(),
)
