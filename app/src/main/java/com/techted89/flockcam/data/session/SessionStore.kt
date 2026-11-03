package com.techted89.flockcam.data.session

import android.content.Context
import com.techted89.flockcam.data.model.AuthResponse
import com.techted89.flockcam.data.model.Entitlement
import com.techted89.flockcam.data.model.MeResponse
import com.techted89.flockcam.data.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.UUID

data class SessionState(
    val token: String? = null,
    val user: UserProfile? = null,
    val entitlement: Entitlement = Entitlement(),
) {
    val isSignedIn: Boolean get() = token != null
    val isPremium: Boolean get() = entitlement.isPremium
    val authorizationHeader: String? get() = token?.let { "Bearer $it" }
}

class SessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString(KEY_DEVICE_ID, it).apply()
    }

    private fun load(): SessionState {
        val token = prefs.getString(KEY_TOKEN, null)
        val user = prefs.getString(KEY_USER, null)?.let {
            runCatching { json.decodeFromString(UserProfile.serializer(), it) }.getOrNull()
        }
        val entitlement = prefs.getString(KEY_ENTITLEMENT, null)?.let {
            runCatching { json.decodeFromString(Entitlement.serializer(), it) }.getOrNull()
        } ?: Entitlement()
        return SessionState(token = token, user = user, entitlement = entitlement)
    }

    fun saveAuth(response: AuthResponse) {
        val token = response.token ?: return
        val user = response.user
        val entitlement = response.entitlement ?: Entitlement()
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER, user?.let { json.encodeToString(UserProfile.serializer(), it) })
            .putString(KEY_ENTITLEMENT, json.encodeToString(Entitlement.serializer(), entitlement))
            .apply()
        _state.value = SessionState(token = token, user = user, entitlement = entitlement)
    }

    fun saveMe(response: MeResponse) {
        val current = _state.value
        val user = response.user ?: current.user
        val entitlement = response.entitlement ?: current.entitlement
        prefs.edit()
            .putString(KEY_USER, user?.let { json.encodeToString(UserProfile.serializer(), it) })
            .putString(KEY_ENTITLEMENT, json.encodeToString(Entitlement.serializer(), entitlement))
            .apply()
        _state.value = current.copy(user = user, entitlement = entitlement)
    }

    fun updateEntitlement(entitlement: Entitlement) {
        prefs.edit()
            .putString(KEY_ENTITLEMENT, json.encodeToString(Entitlement.serializer(), entitlement))
            .apply()
        _state.value = _state.value.copy(entitlement = entitlement)
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER)
            .remove(KEY_ENTITLEMENT)
            .apply()
        _state.value = SessionState()
    }

    companion object {
        private const val PREFS_NAME = "flockcam_session"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "user"
        private const val KEY_ENTITLEMENT = "entitlement"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
