package com.techted89.flockcam.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.PolylineState
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.google.maps.android.compose.rememberPolylineState
import com.techted89.flockcam.data.model.CameraInfo
import com.techted89.flockcam.data.model.LatLng as FlockLatLng
import com.techted89.flockcam.ui.theme.FlockCamTheme

private const val DEFAULT_ORIGIN_LAT = 52.3676
private const val DEFAULT_ORIGIN_LNG = 4.9041

@Composable
fun FlockMapScreen(
    viewModel: FlockMapViewModel = viewModel(),
) {
    val userLocation by viewModel.userLocation.collectAsState()
    val nearbyCameras by viewModel.nearbyCameras.collectAsState()
    val route by viewModel.route.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val context = LocalContext.current
    val locationPermissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val fineGranted = results[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineGranted) {
            // Nearby camera refresh can now run with a granted permission.
        }
    }
    LaunchedEffect(Unit) {
        if (!locationPermissionGranted) {
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    val cameraPositionState: CameraPositionState = rememberCameraPositionState(
        CameraPosition.fromLatLngZoom(
            userLocation?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(DEFAULT_ORIGIN_LAT, DEFAULT_ORIGIN_LNG),
            12f,
        ),
    )
    val polylineState: PolylineState = rememberPolylineState(
        points = route?.map { FlockLatLng(latitude = it.latitude, longitude = it.longitude) } ?: emptyList(),
    )

    FlockCamTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("F Flock Cam") })
            },
        ) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                GoogleMap(
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(isMyLocationEnabled = userLocation != null),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    userLocation?.let { location ->
                        Marker(
                            state = rememberMarkerState(position = LatLng(location.latitude, location.longitude)),
                            title = "You",
                        )
                    }
                    Polyline(
                        polylineState = polylineState,
                        width = 6f,
                        color = android.graphics.Color.RED,
                    )
                    nearbyCameras.forEach { camera ->
                        camera.latitude?.let { latitude ->
                            camera.longitude?.let { longitude ->
                                Marker(
                                    state = rememberMarkerState(position = LatLng(latitude, longitude)),
                                    title = camera.name ?: "Flock camera",
                                    snippet = camera.source ?: "",
                                )
                            }
                        }
                    }
                }

                androidx.compose.material3.FloatingActionButton(
                    onClick = {
                        val target = userLocation ?: return@FloatingActionButton
                        viewModel.refreshNearby(
                            latitude = target.latitude,
                            longitude = target.longitude,
                            radiusMeters = 500,
                        )
                    },
                ) {
                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Refresh cameras")
                }
            }

            statusMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
