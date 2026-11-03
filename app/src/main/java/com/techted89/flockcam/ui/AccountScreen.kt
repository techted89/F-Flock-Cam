package com.techted89.flockcam.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.techted89.flockcam.data.model.Entitlement
import com.techted89.flockcam.data.model.UserProfile
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    viewModel: FlockMapViewModel,
    state: FlockMapUiState,
    onBack: () -> Unit,
    onUpgrade: () -> Unit,
    onSignIn: () -> Unit,
) {
    val activity = LocalContext.current as? Activity
    var displayName by rememberSaveable(state.session.user?.id) {
        mutableStateOf(state.session.user?.displayName.orEmpty())
    }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshSession()
        viewModel.loadMyReports()
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!state.session.isSignedIn) {
                Text("You are browsing as a guest.", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Sign in to sync your premium entitlement, reports, and purchase history " +
                        "across devices. We only store the minimum data needed for sign-in, " +
                        "recovery, and purchases.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign in or create an account")
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Keep browsing")
                }
            } else {
                ProfileCard(
                    user = state.session.user,
                    displayName = displayName,
                    onDisplayNameChange = { displayName = it },
                    onSave = { viewModel.updateDisplayName(displayName) },
                    onResendVerification = viewModel::resendVerification,
                )

                EntitlementCard(
                    entitlement = state.session.entitlement,
                    onUpgrade = onUpgrade,
                    onWatchAd = { activity?.let(viewModel::watchRewardedAd) },
                    onRestore = viewModel::restorePurchases,
                    onRefresh = viewModel::refreshSession,
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Your camera reports", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (state.reports.isEmpty()) {
                                "You have not submitted any cameras yet. Reports are moderated " +
                                    "before they appear on the public map."
                            } else {
                                "${state.reports.size} report(s) submitted. Approved reports help " +
                                    "other drivers and are shown as user-reported cameras."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                state.authMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                    )
                }

                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.logout()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sign out")
                }
                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete my account", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "Deleting your account revokes all sessions and removes your profile, " +
                        "entitlement, and pending verification codes. Approved camera reports " +
                        "may remain as anonymized map data.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete account?") },
            text = {
                Text(
                    "This permanently signs you out everywhere and deletes your profile and " +
                        "entitlement. This cannot be undone.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        viewModel.deleteAccount()
                        onBack()
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProfileCard(
    user: UserProfile?,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onResendVerification: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Profile", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            InfoRow("Email", user?.email ?: "Not available")
            InfoRow(
                "Verification",
                if (user?.emailVerified == true) "Verified" else "Not verified",
            )
            InfoRow("Sign-in method", user?.authProvider ?: "password")
            user?.createdAt?.let { created ->
                InfoRow("Member since", formatDate(created))
            }
            if (user?.emailVerified == false) {
                Text(
                    "Verify your email to enable purchases and account recovery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onResendVerification) {
                    Text("Resend verification code")
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = onDisplayNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Display name") },
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text("Save profile")
            }
        }
    }
}

@Composable
private fun EntitlementCard(
    entitlement: Entitlement,
    onUpgrade: () -> Unit,
    onWatchAd: () -> Unit,
    onRestore: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Plan", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (entitlement.isPremium) "Premium" else "Free",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (entitlement.isPremium) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            InfoRow("Range", "${(entitlement.maxRadiusMeters / 1000.0).toInt()} km")
            InfoRow(
                "Cameras in view",
                entitlement.visibleCameras?.toString() ?: "Unlimited",
            )
            InfoRow(
                "Region cache",
                "${entitlement.regionMaxCameras} cameras per request",
            )
            entitlement.source?.let { source -> InfoRow("Source", source) }
            entitlement.expiresAt?.let { expires ->
                InfoRow("Active until", formatDate(expires))
            }

            Spacer(Modifier.height(10.dp))
            if (!entitlement.isPremium) {
                Button(onClick = onUpgrade, modifier = Modifier.fillMaxWidth()) {
                    Text("Upgrade to Premium")
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onWatchAd, modifier = Modifier.fillMaxWidth()) {
                    Text("Watch a short ad for premium range")
                }
                Text(
                    "Free mode shows a shorter range and fewer cameras. Ads and CPA tasks " +
                        "keep the free tier available for everyone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
            }
            OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                Text("Restore purchases")
            }
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh entitlement")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatDate(epochSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(epochSeconds * 1000L))
