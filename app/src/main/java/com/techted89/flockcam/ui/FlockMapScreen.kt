package com.techted89.flockcam.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.techted89.flockcam.data.model.CameraInfo
import com.techted89.flockcam.util.FlockCamMath
import kotlin.math.roundToInt

private const val DEFAULT_ORIGIN_LAT = 52.3676
private const val DEFAULT_ORIGIN_LNG = 4.9041
private const val RECENTER_METERS = 250.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlockMapScreen(
    viewModel: FlockMapViewModel,
    state: FlockMapUiState,
    onOpenAuth: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenPaywall: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permissionGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (permissionGranted) viewModel.startLocationUpdates()
    }

    LaunchedEffect(Unit) {
        if (permissionGranted) {
            viewModel.startLocationUpdates()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopLocationUpdates() }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            state.userLocation?.let { LatLng(it.latitude, it.longitude) }
                ?: LatLng(DEFAULT_ORIGIN_LAT, DEFAULT_ORIGIN_LNG),
            12f,
        )
    }
    var lastCentered by remember { mutableStateOf<LatLng?>(null) }

    LaunchedEffect(state.userLocation) {
        val location = state.userLocation ?: return@LaunchedEffect
        val target = LatLng(location.latitude, location.longitude)
        val last = lastCentered
        val moved = last == null || FlockCamMath.haversineMeters(
            last.latitude,
            last.longitude,
            target.latitude,
            target.longitude,
        ) > RECENTER_METERS
        if (moved) {
            lastCentered = target
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(target, 13f),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("F Flock Cam") },
                actions = {
                    if (state.session.isPremium) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Premium",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { viewModel.refreshRegion(force = true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh cameras")
                    }
                    IconButton(
                        onClick = {
                            if (state.session.isSignedIn) onOpenAccount() else onOpenAuth()
                        },
                    ) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = "Account")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.refreshRegion(force = true) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh region")
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = permissionGranted),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                ),
            ) {
                state.userLocation?.let { location ->
                    Marker(
                        state = rememberMarkerState(
                            position = LatLng(location.latitude, location.longitude),
                        ),
                        title = "You",
                    )
                }
                if (state.route.size >= 2) {
                    Polyline(
                        points = state.route.map { LatLng(it.latitude, it.longitude) },
                        color = Color(0xFF1B5E20),
                        width = 8f,
                    )
                }
                state.cameras.forEach { camera ->
                    val latitude = camera.latitude
                    val longitude = camera.longitude
                    if (latitude != null && longitude != null) {
                        key(camera.id ?: "$latitude,$longitude") {
                            Marker(
                                state = rememberMarkerState(position = LatLng(latitude, longitude)),
                                title = camera.name ?: "Flock camera",
                                snippet = camera.source ?: "",
                                onClick = {
                                    viewModel.selectCamera(camera)
                                    false
                                },
                            )
                        }
                    }
                }
            }

            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            DestinationSearchCard(
                state = state,
                onQueryChange = viewModel::onDestinationQueryChange,
                onSearch = viewModel::searchDestination,
                onClearRoute = viewModel::clearRoute,
                onOpenMaps = {
                    val destination = state.destination
                    val uri = if (destination != null) {
                        Uri.parse(
                            "https://www.google.com/maps/dir/?api=1&destination=" +
                                "${destination.latitude},${destination.longitude}",
                        )
                    } else {
                        Uri.parse("https://www.google.com/maps")
                    }
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
            )

            RadiusCard(
                state = state,
                onRadiusChange = viewModel::setRadiusKm,
                onWatchAd = { activity?.let(viewModel::watchRewardedAd) },
                onUpgrade = onOpenPaywall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        }
    }

    val selected = state.selectedCamera
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = viewModel::dismissCamera) {
            CameraDetailContent(camera = selected, onClose = viewModel::dismissCamera)
        }
    }
}

@Composable
private fun DestinationSearchCard(
    state: FlockMapUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearRoute: () -> Unit,
    onOpenMaps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = state.destinationQuery,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search destination") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.destinationQuery.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { onSearch() },
                ),
            )
            if (state.destination != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onOpenMaps) {
                        Text("Open in Google Maps")
                    }
                    TextButton(onClick = onClearRoute) {
                        Text("Clear route")
                    }
                }
            }
        }
    }
}

@Composable
private fun RadiusCard(
    state: FlockMapUiState,
    onRadiusChange: (Float) -> Unit,
    onWatchAd: () -> Unit,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Radius", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = String.format("%.1f km", state.radiusKm),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Slider(
                value = state.radiusKm,
                onValueChange = onRadiusChange,
                valueRange = 0.2f..state.maxRadiusKm.coerceAtLeast(0.3f),
            )
            Text(
                text = "${state.cameras.size} cameras in view" +
                    if (state.fromCache) " (cached)" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.truncated) {
                Text(
                    text = "Showing a capped subset of ${state.totalAvailable} nearby cameras.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!state.session.isPremium) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Free mode has a shorter range and fewer cameras. A short ad or CPA task " +
                        "unlocks premium range temporarily — ads keep the free tier available.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onWatchAd,
                        modifier = Modifier.weight(1f),
                        enabled = !state.adLoading,
                    ) {
                        Text(if (state.adLoading) "Claiming…" else "Watch ad")
                    }
                    Button(
                        onClick = onUpgrade,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Go Premium")
                    }
                }
            }
            state.statusMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(text = message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CameraDetailContent(camera: CameraInfo, onClose: () -> Unit) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = camera.name ?: "Flock / ALPR camera",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = camera.description ?: "No description has been reported for this camera.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        DetailRow("Source", camera.source ?: "Unknown")
        DetailRow("View direction", viewDirectionLabel(camera))
        DetailRow("Operator", camera.operator ?: "Not reported")
        DetailRow("Manufacturer", camera.manufacturer ?: "Not reported")
        camera.distanceMeters?.let { distance ->
            DetailRow("Distance", String.format("%.0f m", distance))
        }
        camera.status?.let { status ->
            DetailRow("Moderation", status)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Close")
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun viewDirectionLabel(camera: CameraInfo): String {
    val explicit = camera.viewDirection
    if (!explicit.isNullOrBlank()) return explicit
    val degrees = camera.directionDegrees ?: return "Not reported"
    val labels = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val normalized = ((degrees % 360.0) + 360.0) % 360.0
    val index = ((normalized / 45.0).roundToInt()) % 8
    return "${labels[index]} (${degrees.roundToInt()}°)"
}
