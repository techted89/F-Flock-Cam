package com.techted89.flockcam.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.techted89.flockcam.ui.theme.FlockCamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlockCamTheme {
                FlockCamApp()
            }
        }
    }
}
