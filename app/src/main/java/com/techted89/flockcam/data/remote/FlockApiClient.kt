package com.techted89.flockcam.data.remote

import android.content.Context
import com.techted89.flockcam.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.create
import java.util.concurrent.TimeUnit

object FlockApiClient {

    private const val TIMEOUT_SECONDS = 15L

    @JvmStatic
    fun create(context: Context): FlockApi {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.SERVER_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(retrofit2.converter.kotlinx.serialization.create(json))
            .build()
        return retrofit.create(FlockApi::class.java)
    }
}
