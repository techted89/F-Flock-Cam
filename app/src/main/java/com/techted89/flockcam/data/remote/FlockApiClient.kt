package com.techted89.flockcam.data.remote

import android.content.Context
import com.techted89.flockcam.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object FlockApiClient {

    private const val TIMEOUT_SECONDS = 15L
    private const val HTTP_CACHE_BYTES = 10L * 1024 * 1024

    @JvmStatic
    fun create(context: Context): FlockApi {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .cache(Cache(context.applicationContext.cacheDir.resolve("flockcam-http"), HTTP_CACHE_BYTES))
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.SERVER_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(FlockApi::class.java)
    }
}
