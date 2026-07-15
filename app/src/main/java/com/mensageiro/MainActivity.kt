package com.mensageiro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mensageiro.app.MensageiroApplication
import com.mensageiro.core.MessagingRuntime
import com.mensageiro.core.MessagingService
import com.mensageiro.navigation.MensageiroApp
import com.mensageiro.ui.theme.MensageiroTheme

class MainActivity : ComponentActivity() {
    private var incomingMessage by mutableStateOf<String?>(null)
    private val container get() = (application as MensageiroApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingMessage = sharedText(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        MessagingService.start(this)
        enableEdgeToEdge()
        setContent {
            MensageiroTheme {
                MensageiroApp(container, incomingMessage) { incomingMessage = null }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MessagingRuntime.setAppVisible(true)
    }

    override fun onStop() {
        MessagingRuntime.setAppVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingMessage = sharedText(intent)
    }

    private fun sharedText(intent: Intent): String? =
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else null
}
