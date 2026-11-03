package com.techted89.flockcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.app.Activity

private const val MODE_LOGIN = "login"
private const val MODE_REGISTER = "register"
private const val MODE_RECOVER = "recover"
private const val MODE_RESET = "reset"
private const val MODE_VERIFY = "verify"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    viewModel: FlockMapViewModel,
    state: FlockMapUiState,
    onBack: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(MODE_LOGIN) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    val activity = LocalContext.current as? Activity

    LaunchedEffect(state.pendingVerificationEmail) {
        val pending = state.pendingVerificationEmail
        if (!pending.isNullOrBlank()) {
            mode = MODE_VERIFY
            email = pending
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = when (mode) {
                    MODE_REGISTER -> "Create an account"
                    MODE_RECOVER -> "Recover your account"
                    MODE_RESET -> "Reset your password"
                    MODE_VERIFY -> "Verify your email"
                    else -> "Sign in"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Accounts keep your entitlement in sync across devices. We store only the " +
                    "minimum needed for sign-in, recovery, and purchases.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (mode != MODE_VERIFY) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Email") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                )
            }

            if (mode == MODE_REGISTER) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Display name (optional)") },
                )
            }

            if (mode == MODE_LOGIN || mode == MODE_REGISTER) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                )
            }

            if (mode == MODE_RESET) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Recovery code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("New password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }

            if (mode == MODE_VERIFY) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("6-digit code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            when (mode) {
                MODE_LOGIN -> {
                    Button(
                        onClick = { viewModel.login(email, password) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Sign in") }
                    OutlinedButton(
                        onClick = { activity?.let(viewModel::loginWithGoogle) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Continue with Google") }
                    TextButton(onClick = { mode = MODE_REGISTER }) {
                        Text("Create an account")
                    }
                    TextButton(onClick = { mode = MODE_RECOVER }) {
                        Text("Forgot password?")
                    }
                }

                MODE_REGISTER -> {
                    Button(
                        onClick = { viewModel.register(email, password, displayName) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Create account") }
                    TextButton(onClick = { mode = MODE_LOGIN }) {
                        Text("I already have an account")
                    }
                }

                MODE_RECOVER -> {
                    Button(
                        onClick = {
                            viewModel.recoverPassword(email)
                            mode = MODE_RESET
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Send recovery code") }
                    TextButton(onClick = { mode = MODE_LOGIN }) {
                        Text("Back to sign in")
                    }
                }

                MODE_RESET -> {
                    Button(
                        onClick = {
                            viewModel.resetPassword(email, code, newPassword)
                            mode = MODE_LOGIN
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Reset password") }
                }

                MODE_VERIFY -> {
                    Button(
                        onClick = { viewModel.verifyEmail(code) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Verify email") }
                    OutlinedButton(
                        onClick = { viewModel.setPendingVerificationEmail(email) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Use this email") }
                    TextButton(onClick = { viewModel.resendVerification() }) {
                        Text("Resend code")
                    }
                    TextButton(onClick = onBack) {
                        Text("Skip for now")
                    }
                }
            }

            state.authMessage?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
