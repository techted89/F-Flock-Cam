package com.techted89.flockcam.ui

import android.app.Activity
import android.app.Application
import android.location.Geocoder
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.techted89.flockcam.FlockCamApplication
import com.techted89.flockcam.data.model.AdRewardRequest
import com.techted89.flockcam.data.model.CameraInfo
import com.techted89.flockcam.data.model.CameraReport
import com.techted89.flockcam.data.model.Entitlement
import com.techted89.flockcam.data.model.GoogleVerifyRequest
import com.techted89.flockcam.data.model.LatLng
import com.techted89.flockcam.data.model.PayPalCaptureRequest
import com.techted89.flockcam.data.model.PayPalCreateRequest
import com.techted89.flockcam.data.model.Plan
import com.techted89.flockcam.data.remote.bodyOrThrow
import com.techted89.flockcam.data.session.SessionState
import com.techted89.flockcam.util.FlockCamMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FlockMapUiState(
    val userLocation: Location? = null,
    val rawCameras: List<CameraInfo> = emptyList(),
    val cameras: List<CameraInfo> = emptyList(),
    val route: List<LatLng> = emptyList(),
    val routeCameras: List<CameraInfo> = emptyList(),
    val destination: LatLng? = null,
    val destinationQuery: String = "",
    val selectedCamera: CameraInfo? = null,
    val radiusKm: Float = 2f,
    val maxRadiusKm: Float = 2f,
    val loading: Boolean = false,
    val statusMessage: String? = null,
    val authMessage: String? = null,
    val fromCache: Boolean = false,
    val truncated: Boolean = false,
    val totalAvailable: Int = 0,
    val session: SessionState = SessionState(),
    val plans: List<Plan> = emptyList(),
    val paywallVisible: Boolean = false,
    val paypalApprovalUrl: String? = null,
    val pendingPaypalOrderId: String? = null,
    val pendingVerificationEmail: String? = null,
    val reports: List<CameraReport> = emptyList(),
    val adLoading: Boolean = false,
)

class FlockMapViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val app = application as FlockCamApplication
    private val locationMonitor = app.locationMonitor
    private val sessionStore = app.sessionStore
    private val authRepository = app.authRepository
    private val cameraRepository = app.cameraRepository
    private val directionsRepository = app.directionsRepository
    private val billingManager = app.billingManager
    private val rewardedAdManager = app.rewardedAdManager
    private val googleSignInHelper = app.googleSignInHelper
    private val api = app.flockApi

    private val _state = MutableStateFlow(
        FlockMapUiState(
            radiusKm = defaultRadiusKm(sessionStore.state.value.entitlement),
            maxRadiusKm = maxRadiusKm(sessionStore.state.value.entitlement),
            session = sessionStore.state.value,
        ),
    )
    val state: StateFlow<FlockMapUiState> = _state.asStateFlow()

    private var fetchedRadiusMeters = 0.0
    private var productDetails: Map<String, ProductDetails> = emptyMap()

    init {
        viewModelScope.launch {
            sessionStore.state.collect { session ->
                val maxKm = maxRadiusKm(session.entitlement)
                _state.update { current ->
                    current.copy(
                        session = session,
                        maxRadiusKm = maxKm,
                        radiusKm = current.radiusKm.coerceAtMost(maxKm),
                    )
                }
            }
        }
        viewModelScope.launch {
            locationMonitor.location.collect { location ->
                if (location == null) return@collect
                val previous = _state.value.userLocation
                _state.update { it.copy(userLocation = location) }
                val moved = previous == null || FlockCamMath.haversineMeters(
                    previous.latitude,
                    previous.longitude,
                    location.latitude,
                    location.longitude,
                ) > REGION_REFRESH_METERS
                if (moved || _state.value.rawCameras.isEmpty()) {
                    refreshRegion()
                } else {
                    applyLocalFilters()
                }
            }
        }
        setupBilling()
        rewardedAdManager.preload()
        loadPlans()
    }

    // --- Location ---------------------------------------------------------

    fun startLocationUpdates() {
        locationMonitor.start()
    }

    fun stopLocationUpdates() {
        locationMonitor.stop()
    }

    // --- Region and local filtering --------------------------------------

    fun refreshRegion(force: Boolean = false) {
        val location = _state.value.userLocation ?: run {
            _state.update { it.copy(statusMessage = "Waiting for your location…") }
            return
        }
        val entitlement = sessionStore.state.value.entitlement
        val radiusMeters = _state.value.radiusKm * 1000.0
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val snapshot = cameraRepository.loadRegion(
                    centerLatitude = location.latitude,
                    centerLongitude = location.longitude,
                    radiusMeters = radiusMeters,
                    entitlement = entitlement,
                    forceRefresh = force,
                )
                fetchedRadiusMeters = radiusMeters
                _state.update {
                    it.copy(
                        rawCameras = snapshot.cameras,
                        loading = false,
                        fromCache = snapshot.fromCache,
                        truncated = snapshot.truncated,
                        totalAvailable = snapshot.totalAvailable,
                    )
                }
                applyLocalFilters()
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        statusMessage = error.message ?: "Could not load cameras.",
                    )
                }
            }
        }
    }

    fun setRadiusKm(value: Float) {
        val entitlement = sessionStore.state.value.entitlement
        val maxKm = maxRadiusKm(entitlement)
        if (value > maxKm) {
            _state.update {
                it.copy(
                    radiusKm = maxKm,
                    paywallVisible = true,
                    statusMessage = "Free range is limited to ${maxKm.toInt()} km. Unlock unlimited range with Premium.",
                )
            }
        } else {
            _state.update { it.copy(radiusKm = value.coerceIn(0.2f, maxKm)) }
        }
        if (_state.value.radiusKm * 1000.0 > fetchedRadiusMeters * 1.02) {
            refreshRegion()
        } else {
            applyLocalFilters()
        }
    }

    private fun applyLocalFilters() {
        val current = _state.value
        val location = current.userLocation
        if (location == null) {
            _state.update { it.copy(cameras = emptyList()) }
            return
        }
        val cameras = cameraRepository.filterWithinRadius(
            cameras = current.rawCameras,
            latitude = location.latitude,
            longitude = location.longitude,
            radiusMeters = current.radiusKm * 1000.0,
            entitlement = sessionStore.state.value.entitlement,
        )
        _state.update { it.copy(cameras = cameras) }
    }

    // --- Destination and route -------------------------------------------

    fun onDestinationQueryChange(value: String) {
        _state.update { it.copy(destinationQuery = value) }
    }

    fun searchDestination() {
        val query = _state.value.destinationQuery.trim()
        if (query.isBlank()) {
            _state.update { it.copy(statusMessage = "Enter a destination to search.") }
            return
        }
        val location = _state.value.userLocation ?: run {
            _state.update { it.copy(statusMessage = "Waiting for your location…") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, statusMessage = "Finding route…") }
            try {
                val destination = withContext(Dispatchers.IO) { geocode(query) }
                    ?: throw IllegalStateException("No place found for \"$query\".")
                val origin = LatLng(location.latitude, location.longitude)
                val route = directionsRepository.getRoute(origin, destination)
                val entitlement = sessionStore.state.value.entitlement
                val routeCameras = loadRouteCamerasLocally(route.points, entitlement)
                _state.update {
                    it.copy(
                        destination = destination,
                        route = route.points,
                        routeCameras = routeCameras,
                        loading = false,
                        statusMessage = buildRouteMessage(route.approximate, route.distanceMeters, routeCameras.size),
                    )
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        statusMessage = error.message ?: "Could not build the route.",
                    )
                }
            }
        }
    }

    fun clearRoute() {
        _state.update {
            it.copy(
                destination = null,
                destinationQuery = "",
                route = emptyList(),
                routeCameras = emptyList(),
            )
        }
    }

    private suspend fun loadRouteCamerasLocally(
        route: List<LatLng>,
        entitlement: Entitlement,
    ): List<CameraInfo> {
        if (route.size < 2) return emptyList()
        val latitudes = route.map { it.latitude }
        val longitudes = route.map { it.longitude }
        val pad = ROUTE_BBOX_PAD_DEGREES
        return try {
            val snapshot = cameraRepository.loadRegionForBbox(
                minLat = (latitudes.minOrNull() ?: 0.0) - pad,
                maxLat = (latitudes.maxOrNull() ?: 0.0) + pad,
                minLng = (longitudes.minOrNull() ?: 0.0) - pad,
                maxLng = (longitudes.maxOrNull() ?: 0.0) + pad,
                entitlement = entitlement,
            )
            cameraRepository.filterAlongRoute(
                cameras = snapshot.cameras,
                route = route,
                corridorMeters = ROUTE_CORRIDOR_METERS,
                entitlement = entitlement,
            )
        } catch (error: Exception) {
            // Very long routes can exceed the region tile budget. Fall back to
            // the server's corridor query instead of failing the whole route.
            cameraRepository.loadRouteCameras(route, ROUTE_CORRIDOR_METERS.toInt(), entitlement)
        }
    }

    private fun buildRouteMessage(approximate: Boolean, distanceMeters: Double, cameras: Int): String {
        val distance = if (distanceMeters >= 1000) {
            String.format("%.1f km", distanceMeters / 1000.0)
        } else {
            "${distanceMeters.toInt()} m"
        }
        val prefix = if (approximate) "Straight-line route" else "Route"
        return "$prefix • $distance • $cameras camera${if (cameras == 1) "" else "s"}"
    }

    // --- Camera detail and reports ---------------------------------------

    fun selectCamera(camera: CameraInfo) {
        _state.update { it.copy(selectedCamera = camera) }
    }

    fun dismissCamera() {
        _state.update { it.copy(selectedCamera = null) }
    }

    fun reportCameraHere() {
        val location = _state.value.userLocation ?: run {
            _state.update { it.copy(statusMessage = "Waiting for your location…") }
            return
        }
        viewModelScope.launch {
            val ok = runCatching {
                cameraRepository.reportCamera(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    note = "Reported from the map",
                )
            }.getOrDefault(false)
            _state.update {
                it.copy(
                    statusMessage = if (ok) {
                        "Camera submitted for review. Thank you."
                    } else {
                        "Could not submit the camera report."
                    },
                )
            }
            if (ok) loadMyReports()
        }
    }

    fun loadMyReports() {
        val header = sessionStore.state.value.authorizationHeader ?: return
        viewModelScope.launch {
            runCatching { api.myReports(header).bodyOrThrow() }
                .onSuccess { response ->
                    _state.update { it.copy(reports = response.reports) }
                }
        }
    }

    // --- Authentication ---------------------------------------------------

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, authMessage = null) }
            try {
                val response = authRepository.login(email, password)
                _state.update {
                    it.copy(
                        loading = false,
                        authMessage = response.message ?: "Signed in.",
                        pendingVerificationEmail = if (response.verificationRequired) email.trim() else null,
                    )
                }
                refreshRegion(force = true)
            } catch (error: Exception) {
                _state.update {
                    it.copy(loading = false, authMessage = error.message ?: "Sign in failed.")
                }
            }
        }
    }

    fun register(email: String, password: String, displayName: String?) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, authMessage = null) }
            try {
                val response = authRepository.register(email, password, displayName)
                val dev = response.devCode?.let { " Dev code: $it" } ?: ""
                _state.update {
                    it.copy(
                        loading = false,
                        authMessage = (response.message ?: "Account created.") + dev,
                        pendingVerificationEmail = if (response.verificationRequired) email.trim() else null,
                    )
                }
                refreshRegion(force = true)
            } catch (error: Exception) {
                _state.update {
                    it.copy(loading = false, authMessage = error.message ?: "Registration failed.")
                }
            }
        }
    }

    fun loginWithGoogle(activity: Activity) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, authMessage = null) }
            try {
                val idToken = googleSignInHelper.getIdToken(activity)
                val response = authRepository.loginWithGoogle(idToken)
                _state.update {
                    it.copy(loading = false, authMessage = response.message ?: "Signed in with Google.")
                }
                refreshRegion(force = true)
            } catch (error: Exception) {
                _state.update {
                    it.copy(loading = false, authMessage = error.message ?: "Google sign-in failed.")
                }
            }
        }
    }

    fun verifyEmail(code: String) {
        val email = _state.value.pendingVerificationEmail
            ?: _state.value.session.user?.email
        if (email == null) {
            _state.update { it.copy(authMessage = "Enter the email address you registered with.") }
            return
        }
        viewModelScope.launch {
            try {
                authRepository.verifyEmail(email, code)
                _state.update {
                    it.copy(
                        authMessage = "Email verified.",
                        pendingVerificationEmail = null,
                    )
                }
                refreshRegion(force = true)
            } catch (error: Exception) {
                _state.update { it.copy(authMessage = error.message ?: "Verification failed.") }
            }
        }
    }

    fun setPendingVerificationEmail(email: String) {
        _state.update { it.copy(pendingVerificationEmail = email.trim()) }
    }

    fun resendVerification() {
        val email = _state.value.pendingVerificationEmail ?: return
        viewModelScope.launch {
            runCatching { authRepository.resendVerification(email) }
                .onSuccess { _state.update { it.copy(authMessage = "Verification code sent.") } }
                .onFailure { error ->
                    _state.update { it.copy(authMessage = error.message ?: "Could not resend code.") }
                }
        }
    }

    fun recoverPassword(email: String) {
        viewModelScope.launch {
            runCatching { authRepository.recover(email) }
                .onSuccess { _state.update { it.copy(authMessage = "If the account exists, a recovery code was sent.") } }
                .onFailure { error ->
                    _state.update { it.copy(authMessage = error.message ?: "Recovery failed.") }
                }
        }
    }

    fun resetPassword(email: String, code: String, newPassword: String) {
        viewModelScope.launch {
            try {
                authRepository.resetPassword(email, code, newPassword)
                _state.update { it.copy(authMessage = "Password updated. You are signed in.") }
            } catch (error: Exception) {
                _state.update { it.copy(authMessage = error.message ?: "Password reset failed.") }
            }
        }
    }

    fun refreshSession() {
        viewModelScope.launch {
            runCatching { authRepository.refresh() }
                .onSuccess { refreshRegion(force = true) }
                .onFailure { error ->
                    _state.update { it.copy(authMessage = error.message ?: "Could not refresh the account.") }
                }
        }
    }

    fun updateDisplayName(displayName: String) {
        viewModelScope.launch {
            try {
                authRepository.updateDisplayName(displayName)
                _state.update { it.copy(authMessage = "Profile updated.") }
            } catch (error: Exception) {
                _state.update { it.copy(authMessage = error.message ?: "Could not update the profile.") }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _state.update {
                it.copy(
                    rawCameras = emptyList(),
                    cameras = emptyList(),
                    routeCameras = emptyList(),
                    reports = emptyList(),
                    authMessage = "Signed out.",
                )
            }
            refreshRegion(force = true)
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            try {
                authRepository.deleteAccount()
                _state.update {
                    it.copy(
                        rawCameras = emptyList(),
                        cameras = emptyList(),
                        routeCameras = emptyList(),
                        reports = emptyList(),
                        authMessage = "Account deleted.",
                    )
                }
                refreshRegion(force = true)
            } catch (error: Exception) {
                _state.update { it.copy(authMessage = error.message ?: "Could not delete the account.") }
            }
        }
    }

    // --- Plans, billing, PayPal, ads -------------------------------------

    fun loadPlans() {
        viewModelScope.launch {
            runCatching { api.plans().bodyOrThrow() }
                .onSuccess { response -> _state.update { it.copy(plans = response.plans) } }
        }
    }

    fun showPaywall() {
        _state.update { it.copy(paywallVisible = true) }
        prepareBilling()
    }

    fun hidePaywall() {
        _state.update { it.copy(paywallVisible = false, paypalApprovalUrl = null) }
    }

    private fun setupBilling() {
        billingManager.start(
            onPurchase = { productId, token -> verifyGooglePurchase(productId, token) },
            onError = { message -> _state.update { it.copy(statusMessage = message) } },
        )
    }

    fun prepareBilling() {
        val productIds = _state.value.plans.map { it.id }.ifEmpty {
            listOf("premium_4_months", "premium_1_year")
        }
        billingManager.queryProducts(
            productIds = productIds,
            onResult = { details -> productDetails = details },
            onError = { message -> _state.update { it.copy(statusMessage = message) } },
        )
    }

    fun purchase(activity: Activity, productId: String) {
        val details = productDetails[productId] ?: run {
            _state.update { it.copy(statusMessage = "Loading subscription details…") }
            prepareBilling()
            return
        }
        val result = billingManager.purchase(activity, details)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _state.update {
                it.copy(statusMessage = result.debugMessage.ifBlank { "Could not start the purchase." })
            }
        }
    }

    fun restorePurchases() {
        billingManager.restorePurchases(
            onPurchase = { productId, token -> verifyGooglePurchase(productId, token) },
            onError = { message -> _state.update { it.copy(statusMessage = message) } },
        )
    }

    private fun verifyGooglePurchase(productId: String, purchaseToken: String) {
        val header = sessionStore.state.value.authorizationHeader ?: run {
            _state.update { it.copy(statusMessage = "Sign in before purchasing.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val response = api.verifyGooglePurchase(
                    header,
                    GoogleVerifyRequest(productId = productId, purchaseToken = purchaseToken),
                ).bodyOrThrow()
                response.entitlement?.let { sessionStore.updateEntitlement(it) }
                _state.update {
                    it.copy(
                        loading = false,
                        paywallVisible = false,
                        statusMessage = "Premium is active. Enjoy unlimited range and no ads.",
                    )
                }
                refreshRegion(force = true)
            } catch (error: Exception) {
                _state.update {
                    it.copy(loading = false, statusMessage = error.message ?: "Purchase verification failed.")
                }
            }
        }
    }

    fun createPayPalOrder(productId: String) {
        val header = sessionStore.state.value.authorizationHeader ?: run {
            _state.update { it.copy(statusMessage = "Sign in before purchasing.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val response = api.createPayPalOrder(
                    header,
                    PayPalCreateRequest(productId = productId),
                ).bodyOrThrow()
                _state.update {
                    it.copy(
                        loading = false,
                        paypalApprovalUrl = response.approvalUrl,
                        pendingPaypalOrderId = response.orderId,
                        statusMessage = "Complete the payment in your browser, then return here.",
                    )
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(loading = false, statusMessage = error.message ?: "Could not create the PayPal order.")
                }
            }
        }
    }

    fun capturePayPalOrder() {
        val header = sessionStore.state.value.authorizationHeader ?: return
        val orderId = _state.value.pendingPaypalOrderId ?: run {
            _state.update { it.copy(statusMessage = "No PayPal order is pending.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val response = api.capturePayPalOrder(
                    header,
                    PayPalCaptureRequest(orderId = orderId),
                ).bodyOrThrow()
                response.entitlement?.let { sessionStore.updateEntitlement(it) }
                _state.update {
                    it.copy(
                        loading = false,
                        paywallVisible = false,
                        pendingPaypalOrderId = null,
                        paypalApprovalUrl = null,
                        statusMessage = "PayPal payment confirmed. Premium is active.",
                    )
                }
                refreshRegion(force = true)
            } catch (error: Exception) {
                _state.update {
                    it.copy(loading = false, statusMessage = error.message ?: "PayPal capture failed.")
                }
            }
        }
    }

    fun watchRewardedAd(activity: Activity) {
        rewardedAdManager.show(
            activity = activity,
            onReward = { claimAdReward() },
            onError = { message -> _state.update { it.copy(statusMessage = message) } },
        )
    }

    private fun claimAdReward() {
        val header = sessionStore.state.value.authorizationHeader ?: run {
            _state.update { it.copy(statusMessage = "Sign in to claim your ad reward.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(adLoading = true) }
            try {
                val response = api.claimAdReward(header, AdRewardRequest()).bodyOrThrow()
                response.entitlement?.let { sessionStore.updateEntitlement(it) }
                _state.update {
                    it.copy(
                        adLoading = false,
                        statusMessage = "Reward active: premium range for a limited time.",
                    )
                }
                refreshRegion(force = true)
            } catch (error: Exception) {
                _state.update {
                    it.copy(adLoading = false, statusMessage = error.message ?: "Could not claim the ad reward.")
                }
            }
        }
    }

    // --- Helpers ----------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun geocode(query: String): LatLng? {
        val geocoder = Geocoder(getApplication())
        val results = geocoder.getFromLocationName(query, 1)
        val address = results?.firstOrNull() ?: return null
        return LatLng(address.latitude, address.longitude)
    }

    private fun maxRadiusKm(entitlement: Entitlement): Float =
        (entitlement.maxRadiusMeters / 1000.0).toFloat().coerceAtLeast(0.2f)

    private fun defaultRadiusKm(entitlement: Entitlement): Float =
        if (entitlement.isPremium) 10f.coerceAtMost(maxRadiusKm(entitlement)) else maxRadiusKm(entitlement)

    companion object {
        private const val REGION_REFRESH_METERS = 750.0
        private const val ROUTE_CORRIDOR_METERS = 250.0
        private const val ROUTE_BBOX_PAD_DEGREES = 0.01
    }
}
