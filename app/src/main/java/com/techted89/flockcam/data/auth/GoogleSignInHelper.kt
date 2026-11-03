package com.techted89.flockcam.data.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.techted89.flockcam.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GoogleSignInHelper {

    /**
     * Credential Manager shows a bottom sheet, so it needs the foreground
     * Activity rather than the application context.
     */
    suspend fun getIdToken(activity: Activity): String = withContext(Dispatchers.Main) {
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (clientId.isBlank()) {
            throw IllegalStateException(
                "Google sign-in is not configured. Set GOOGLE_WEB_CLIENT_ID for this build.",
            )
        }
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        val credentialManager = CredentialManager.create(activity)
        val response = credentialManager.getCredential(activity, request)
        val credential = response.credential
        if (
            credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } else {
            throw IllegalStateException("Google did not return an ID token.")
        }
    }
}
