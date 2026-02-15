package com.tunedroid.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.tunedroid.app.ui.TuneDroidNavHost
import com.tunedroid.app.ui.theme.TuneDroidTheme

class MainActivity : ComponentActivity() {

    val sharedUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            TuneDroidTheme {
                TuneDroidNavHost(
                    sharedUrl = sharedUrl.value,
                    onSharedUrlConsumed = { sharedUrl.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                // Shared via share sheet (browser "Share" button, etc.)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    sharedUrl.value = extractUrl(text)
                }
            }
            Intent.ACTION_VIEW -> {
                // Opened directly via URL (e.g. "Open with TuneDroid")
                val uri = intent.data
                if (uri != null) {
                    sharedUrl.value = uri.toString()
                }
            }
        }
    }

    private fun extractUrl(text: String): String {
        val urlRegex = Regex("https?://\\S+")
        return urlRegex.find(text)?.value ?: text.trim()
    }
}
