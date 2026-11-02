package com.techted89.flockcam.data.remote

import com.techted89.flockcam.data.model.ActionAck
import com.techted89.flockcam.data.model.CameraListResponse
import com.techted89.flockcam.data.model.EnqueueActionRequest
import com.techted89.flockcam.data.model.RoutePredictionRequest
import com.techted89.flockcam.data.model.RoutePredictionResponse
import com.techted89.flockcam.data.model.SuggestedCamera
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface FlockApi {

    @GET("api/cameras/nearby")
    suspend fun getNearby(
        @Query("lat") latitude: Double,
        @Query("lng") longitude: Double,
        @Query("radius") radius: Int,
    ): Response<CameraListResponse>

    @POST("api/cameras")
    suspend fun suggestCamera(@Body request: SuggestedCamera): Response<ActionAck>

    @POST("api/routes/predict")
    suspend fun predictRoute(@Body request: RoutePredictionRequest): Response<RoutePredictionResponse>

    @POST("api/routes/avoid")
    suspend fun avoidRoute(@Body request: RoutePredictionRequest): Response<RoutePredictionResponse>

    @GET("api/actions/pending")
    suspend fun getPendingActions(@Query("deviceId") deviceId: String): Response<ActionListResponse>

    @POST("api/actions")
    suspend fun enqueueAction(@Body request: EnqueueActionRequest): Response<ActionAck>
}
