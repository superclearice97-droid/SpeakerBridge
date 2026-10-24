package com.speakerbridge.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.speakerbridge.model.Device
import com.speakerbridge.playback.PhoneStream

/**
 * Returns an action that asks for what Android needs (the recording permission, then the
 * capture consent) and starts streaming the phone's audio to [picked] speakers.
 */
@Composable
fun rememberStreamStarter(picked: () -> List<Device>): () -> Unit {
    val context = LocalContext.current
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) PhoneStream.start(context, r.resultCode, data, picked())
        else PhoneStream.note("Streaming didn't start: Android's prompt to share was cancelled.")
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) consent.launch(captureIntent(context))
        else PhoneStream.note(
            "Streaming needs Android's audio recording permission (only app audio is captured, never the " +
                "microphone). Allow it in Settings → Apps → SpeakerBridge → Permissions.",
        )
    }
    return {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        // Optional: lets the "Streaming" notification with its Stop button show.
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(wanted.toTypedArray())
    }
}

private fun captureIntent(context: Context): Intent {
    val mpm = context.getSystemService(MediaProjectionManager::class.java)
    // Whole screen rather than one app, so audio from any app is included.
    return if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    else mpm.createScreenCaptureIntent()
}
