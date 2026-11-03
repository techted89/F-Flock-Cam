package com.techted89.flockcam.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.techted89.flockcam.data.model.Plan
import java.util.Locale

private val FALLBACK_PLANS = listOf(
    Plan(
        id = "premium_4_months",
        label = "Premium — 4 months",
        priceCents = 300,
        currency = "USD",
        durationSeconds = 4L * 30L * 24L * 60L * 60L,
    ),
    Plan(
        id = "premium_1_year",
        label = "Premium — 1 year",
        priceCents = 900,
        currency = "USD",
        durationSeconds = 12L * 30L * 24L * 60L * 60L,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(
    viewModel: FlockMapViewModel,
    state: FlockMapUiState,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val plans = state.plans.ifEmpty { FALLBACK_PLANS }
    val signedIn = state.session.isSignedIn
    val verified = state.session.user?.emailVerified == true

    LaunchedEffect(Unit) {
        viewModel.loadPlans()
        viewModel.prepareBilling()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flock Cam Premium") },
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
            Text(
                "Unlimited awareness, fewer distractions",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Premium removes ads and raises the camera radius so you can plan around " +
                    "Flock/ALPR cameras on the whole route, not just the next few kilometres.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Benefit("Unlimited camera radius (up to 50 km per request)")
                    Benefit("No ads or CPA tasks")
                    Benefit("Cameras along your full route")
                    Benefit("Camera descriptions, operator, and view direction")
                    Benefit("Google Maps directions hand-off")
                    Benefit("Priority server capacity during peak load")
                }
            }

            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (!signedIn) {
                Text(
                    "Sign in first so your purchase can be verified and restored on any device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign in or create an account")
                }
            } else if (!verified) {
                Text(
                    "Verify your email before purchasing. Open Account to resend the code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            plans.forEach { plan ->
                PlanCard(
                    plan = plan,
                    enabled = signedIn && verified && activity != null,
                    onGooglePlay = {
                        activity?.let { viewModel.purchase(it, plan.id) }
                    },
                    onPayPal = { viewModel.createPayPalOrder(plan.id) },
                )
            }

            state.paypalApprovalUrl?.let { approvalUrl ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("PayPal", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Approve the payment in your browser, then come back and confirm it here.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { openUrl(context, approvalUrl) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Open PayPal")
                        }
                        OutlinedButton(
                            onClick = viewModel::capturePayPalOrder,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("I approved it — confirm payment")
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = viewModel::restorePurchases,
                modifier = Modifier.fillMaxWidth(),
                enabled = signedIn,
            ) {
                Text("Restore purchases")
            }

            Text(
                "Subscriptions renew automatically unless cancelled in Google Play. " +
                    "PayPal purchases are one-time periods and do not auto-renew. " +
                    "Manage or cancel Play subscriptions in the Play Store.",
                style = MaterialTheme.typography.bodySmall,
            )

            state.statusMessage?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Not now")
            }
        }
    }
}

@Composable
private fun PlanCard(
    plan: Plan,
    enabled: Boolean,
    onGooglePlay: () -> Unit,
    onPayPal: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(plan.label, style = MaterialTheme.typography.titleMedium)
                Text(priceLabel(plan), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onGooglePlay,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            ) {
                Text("Subscribe with Google Play")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onPayPal,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            ) {
                Text("Pay with PayPal")
            }
        }
    }
}

@Composable
private fun Benefit(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun priceLabel(plan: Plan): String {
    val amount = plan.priceCents / 100.0
    return if (plan.currency.equals("USD", ignoreCase = true)) {
        String.format(Locale.US, "\$%.2f", amount)
    } else {
        String.format(Locale.US, "%.2f %s", amount, plan.currency)
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
