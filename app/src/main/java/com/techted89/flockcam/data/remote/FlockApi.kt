package com.techted89.flockcam.data.remote

import com.techted89.flockcam.data.model.ActionAck
import com.techted89.flockcam.data.model.ActionListResponse
import com.techted89.flockcam.data.model.AdRewardRequest
import com.techted89.flockcam.data.model.AdRewardResponse
import com.techted89.flockcam.data.model.AuthResponse
import com.techted89.flockcam.data.model.CameraListResponse
import com.techted89.flockcam.data.model.EmailOnlyRequest
import com.techted89.flockcam.data.model.EnqueueActionRequest
import com.techted89.flockcam.data.model.EntitlementResponse
import com.techted89.flockcam.data.model.GoogleLoginRequest
import com.techted89.flockcam.data.model.GoogleVerifyRequest
import com.techted89.flockcam.data.model.LoginRequest
import com.techted89.flockcam.data.model.MeResponse
import com.techted89.flockcam.data.model.MyReportsResponse
import com.techted89.flockcam.data.model.PayPalCaptureRequest
import com.techted89.flockcam.data.model.PayPalCreateRequest
import com.techted89.flockcam.data.model.PayPalOrderResponse
import com.techted89.flockcam.data.model.PlansResponse
import com.techted89.flockcam.data.model.RegionResponse
import com.techted89.flockcam.data.model.RegisterRequest
import com.techted89.flockcam.data.model.ResetPasswordRequest
import com.techted89.flockcam.data.model.RouteCameraResponse
import com.techted89.flockcam.data.model.RouteRequest
import com.techted89.flockcam.data.model.StatusResponse
import com.techted89.flockcam.data.model.SuggestedCamera
import com.techted89.flockcam.data.model.UpdateProfileRequest
import com.techted89.flockcam.data.model.VerifyEmailRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query

interface FlockApi {

    @GET("api/cameras/region")
    suspend fun getRegion(
        @Query("minLat") minLat: Double,
        @Query("maxLat") maxLat: Double,
        @Query("minLng") minLng: Double,
        @Query("maxLng") maxLng: Double,
        @Header("Authorization") authorization: String? = null,
    ): Response<RegionResponse>

    @GET("api/cameras/nearby")
    suspend fun getNearby(
        @Query("lat") latitude: Double,
        @Query("lng") longitude: Double,
        @Query("radius") radius: Int,
        @Header("Authorization") authorization: String? = null,
    ): Response<CameraListResponse>

    @POST("api/cameras/route")
    suspend fun getRouteCameras(
        @Body request: RouteRequest,
        @Header("Authorization") authorization: String? = null,
    ): Response<RouteCameraResponse>

    @POST("api/cameras")
    suspend fun suggestCamera(
        @Body request: SuggestedCamera,
        @Header("Authorization") authorization: String? = null,
    ): Response<ActionAck>

    @GET("api/cameras/reports/mine")
    suspend fun myReports(
        @Header("Authorization") authorization: String,
    ): Response<MyReportsResponse>

    @GET("api/actions/pending")
    suspend fun getPendingActions(@Query("deviceId") deviceId: String): Response<ActionListResponse>

    @POST("api/actions")
    suspend fun enqueueAction(@Body request: EnqueueActionRequest): Response<ActionAck>

    // --- Auth --------------------------------------------------------------

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("api/auth/google")
    suspend fun loginWithGoogle(@Body request: GoogleLoginRequest): Response<AuthResponse>

    @POST("api/auth/verify-email")
    suspend fun verifyEmail(@Body request: VerifyEmailRequest): Response<AuthResponse>

    @POST("api/auth/resend-verification")
    suspend fun resendVerification(@Body request: EmailOnlyRequest): Response<StatusResponse>

    @POST("api/auth/recover")
    suspend fun recover(@Body request: EmailOnlyRequest): Response<StatusResponse>

    @POST("api/auth/reset-password")
    suspend fun resetPassword(@Body request: ResetPasswordRequest): Response<AuthResponse>

    @GET("api/auth/me")
    suspend fun me(@Header("Authorization") authorization: String): Response<MeResponse>

    @PATCH("api/auth/me")
    suspend fun updateProfile(
        @Header("Authorization") authorization: String,
        @Body request: UpdateProfileRequest,
    ): Response<MeResponse>

    @POST("api/auth/me/logout")
    suspend fun logout(@Header("Authorization") authorization: String): Response<StatusResponse>

    @DELETE("api/auth/me")
    suspend fun deleteAccount(@Header("Authorization") authorization: String): Response<StatusResponse>

    // --- Payments ----------------------------------------------------------

    @GET("api/payments/plans")
    suspend fun plans(): Response<PlansResponse>

    @POST("api/payments/google/verify")
    suspend fun verifyGooglePurchase(
        @Header("Authorization") authorization: String,
        @Body request: GoogleVerifyRequest,
    ): Response<EntitlementResponse>

    @POST("api/payments/paypal/create-order")
    suspend fun createPayPalOrder(
        @Header("Authorization") authorization: String,
        @Body request: PayPalCreateRequest,
    ): Response<PayPalOrderResponse>

    @POST("api/payments/paypal/capture")
    suspend fun capturePayPalOrder(
        @Header("Authorization") authorization: String,
        @Body request: PayPalCaptureRequest,
    ): Response<EntitlementResponse>

    // --- Ads ---------------------------------------------------------------

    @POST("api/ads/reward")
    suspend fun claimAdReward(
        @Header("Authorization") authorization: String,
        @Body request: AdRewardRequest,
    ): Response<AdRewardResponse>
}
