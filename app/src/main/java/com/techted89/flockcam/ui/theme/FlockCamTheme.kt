package com.techted89.flockcam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FlockCamDarkColors = darkColorScheme(
    primary = Color(0xFF1B5E20),
    secondary = Color(0xFF2E7D32),
    background = Color(0xFFF1F3F4),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun FlockCamTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = FlockCamDarkColors,
        content = content,
    )
}
