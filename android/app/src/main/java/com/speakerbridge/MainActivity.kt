package com.speakerbridge

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.speakerbridge.ui.ScanScreen
import com.speakerbridge.ui.ScanViewModel
import com.speakerbridge.ui.theme.SpeakerBridgeTheme

class MainActivity : ComponentActivity() {
    private val vm: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Always dark, with light status/nav icons, to match the editorial look.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            SpeakerBridgeTheme { ScanScreen(vm) }
        }
    }
}
