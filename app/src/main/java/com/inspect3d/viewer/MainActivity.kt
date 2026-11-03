package com.inspect3d.viewer

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.inspect3d.viewer.ui.ViewerScreen
import com.inspect3d.viewer.ui.theme.Inspect3DTheme

class MainActivity : ComponentActivity() {

    /** A model handed to us by another app through ACTION_VIEW, waiting to be opened. */
    private val incomingModel = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The UI is always dark, so the system bar icons must be light regardless of the
        // device's light/dark setting — otherwise they are dark-on-dark and invisible.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        incomingModel.value = modelUriFrom(intent)

        setContent {
            Inspect3DTheme {
                ViewerScreen(
                    incomingModelUri = incomingModel.value,
                    onIncomingModelHandled = { incomingModel.value = null },
                )
            }
        }
    }

    /** Opening a second model from a file manager while the app is already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        modelUriFrom(intent)?.let { incomingModel.value = it }
    }

    private fun modelUriFrom(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_VIEW) intent.data else null
}
