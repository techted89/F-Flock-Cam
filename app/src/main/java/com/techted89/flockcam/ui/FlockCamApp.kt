package com.techted89.flockcam.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.techted89.flockcam.ui.theme.FlockCamTheme

private const val SCREEN_MAP = "map"
private const val SCREEN_AUTH = "auth"
private const val SCREEN_ACCOUNT = "account"
private const val SCREEN_PAYWALL = "paywall"

@Composable
fun FlockCamApp() {
    val viewModel: FlockMapViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf(SCREEN_MAP) }

    LaunchedEffect(state.paywallVisible) {
        if (state.paywallVisible && screen != SCREEN_PAYWALL) {
            screen = SCREEN_PAYWALL
        }
    }

    // Leave the auth screen once a session exists and no verification step is
    // pending. Unverified accounts stay so they can enter the emailed code.
    LaunchedEffect(state.session.isSignedIn, state.pendingVerificationEmail) {
        if (
            screen == SCREEN_AUTH &&
            state.session.isSignedIn &&
            state.pendingVerificationEmail == null
        ) {
            screen = SCREEN_MAP
        }
    }

    FlockCamTheme {
        when (screen) {
            SCREEN_AUTH -> AuthScreen(
                viewModel = viewModel,
                state = state,
                onBack = { screen = SCREEN_MAP },
            )

            SCREEN_ACCOUNT -> AccountScreen(
                viewModel = viewModel,
                state = state,
                onBack = { screen = SCREEN_MAP },
                onUpgrade = {
                    viewModel.showPaywall()
                    screen = SCREEN_PAYWALL
                },
                onSignIn = { screen = SCREEN_AUTH },
            )

            SCREEN_PAYWALL -> PaywallScreen(
                viewModel = viewModel,
                state = state,
                onBack = {
                    viewModel.hidePaywall()
                    screen = SCREEN_MAP
                },
                onSignIn = { screen = SCREEN_AUTH },
            )

            else -> FlockMapScreen(
                viewModel = viewModel,
                state = state,
                onOpenAuth = { screen = SCREEN_AUTH },
                onOpenAccount = { screen = SCREEN_ACCOUNT },
                onOpenPaywall = {
                    viewModel.showPaywall()
                    screen = SCREEN_PAYWALL
                },
            )
        }
    }
}
