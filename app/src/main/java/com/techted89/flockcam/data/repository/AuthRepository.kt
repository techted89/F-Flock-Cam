package com.techted89.flockcam.data.repository

import com.techted89.flockcam.data.model.AuthResponse
import com.techted89.flockcam.data.model.EmailOnlyRequest
import com.techted89.flockcam.data.model.GoogleLoginRequest
import com.techted89.flockcam.data.model.LoginRequest
import com.techted89.flockcam.data.model.MeResponse
import com.techted89.flockcam.data.model.RegisterRequest
import com.techted89.flockcam.data.model.ResetPasswordRequest
import com.techted89.flockcam.data.model.UpdateProfileRequest
import com.techted89.flockcam.data.model.VerifyEmailRequest
import com.techted89.flockcam.data.remote.FlockApi
import com.techted89.flockcam.data.remote.bodyOrThrow
import com.techted89.flockcam.data.session.SessionStore
import com.techted89.flockcam.data.session.SessionState

class AuthRepository(
    private val api: FlockApi,
    private val sessionStore: SessionStore,
) {
    val session: SessionState get() = sessionStore.state.value
    val deviceId: String get() = sessionStore.deviceId

    private fun authHeader(): String =
        sessionStore.state.value.authorizationHeader
            ?: throw IllegalStateException("You need to sign in first.")

    suspend fun register(email: String, password: String, displayName: String?): AuthResponse {
        val response = api.register(
            RegisterRequest(
                email = email.trim(),
                password = password,
                displayName = displayName?.trim()?.ifBlank { null },
                deviceId = deviceId,
            ),
        ).bodyOrThrow()
        sessionStore.saveAuth(response)
        return response
    }

    suspend fun login(email: String, password: String): AuthResponse {
        val response = api.login(
            LoginRequest(email = email.trim(), password = password, deviceId = deviceId),
        ).bodyOrThrow()
        sessionStore.saveAuth(response)
        return response
    }

    suspend fun loginWithGoogle(idToken: String): AuthResponse {
        val response = api.loginWithGoogle(
            GoogleLoginRequest(idToken = idToken, deviceId = deviceId),
        ).bodyOrThrow()
        sessionStore.saveAuth(response)
        return response
    }

    suspend fun verifyEmail(email: String, code: String): AuthResponse {
        val response = api.verifyEmail(
            VerifyEmailRequest(email = email.trim(), code = code.trim(), deviceId = deviceId),
        ).bodyOrThrow()
        sessionStore.saveAuth(response)
        return response
    }

    suspend fun resendVerification(email: String) {
        api.resendVerification(EmailOnlyRequest(email.trim())).bodyOrThrow()
    }

    suspend fun recover(email: String) {
        api.recover(EmailOnlyRequest(email.trim())).bodyOrThrow()
    }

    suspend fun resetPassword(email: String, code: String, newPassword: String): AuthResponse {
        val response = api.resetPassword(
            ResetPasswordRequest(
                email = email.trim(),
                code = code.trim(),
                newPassword = newPassword,
                deviceId = deviceId,
            ),
        ).bodyOrThrow()
        sessionStore.saveAuth(response)
        return response
    }

    suspend fun refresh(): MeResponse? {
        val header = sessionStore.state.value.authorizationHeader ?: return null
        val response = api.me(header).bodyOrThrow()
        sessionStore.saveMe(response)
        return response
    }

    suspend fun updateDisplayName(displayName: String): MeResponse {
        val response = api.updateProfile(
            authHeader(),
            UpdateProfileRequest(displayName = displayName.trim().ifBlank { null }),
        ).bodyOrThrow()
        sessionStore.saveMe(response)
        return response
    }

    suspend fun logout() {
        val header = sessionStore.state.value.authorizationHeader
        if (header != null) {
            runCatching { api.logout(header) }
        }
        sessionStore.clear()
    }

    suspend fun deleteAccount() {
        val header = authHeader()
        api.deleteAccount(header).bodyOrThrow()
        sessionStore.clear()
    }
}
