package com.techted89.flockcam.data.remote

import com.techted89.flockcam.data.model.StatusResponse
import kotlinx.serialization.json.Json
import retrofit2.Response

class ApiException(
    message: String,
    val statusCode: Int? = null,
) : Exception(message)

private val errorJson = Json { ignoreUnknownKeys = true }

fun <T> Response<T>.bodyOrThrow(): T {
    if (isSuccessful) {
        return body() ?: throw ApiException("The server returned an empty response.", code())
    }
    val raw = runCatching { errorBody()?.string() }.getOrNull()
    val message = raw?.let {
        runCatching { errorJson.decodeFromString(StatusResponse.serializer(), it).message }.getOrNull()
    }
    throw ApiException(message ?: "Request failed (${code()}).", code())
}
