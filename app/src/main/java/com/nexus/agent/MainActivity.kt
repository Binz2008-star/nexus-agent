package com.nexus.agent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nexus.agent.data.connectors.GoogleAuthManager
import com.nexus.agent.data.repository.SecretStorageRepository
import com.nexus.agent.security.AndroidKeystoreKeyProvider
import com.nexus.agent.security.CryptoEngine
import com.nexus.agent.ui.ChatScreen
import com.nexus.agent.ui.theme.NexusAgentTheme

class MainActivity : ComponentActivity() {

    private lateinit var googleAuthManager: GoogleAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val crypto = CryptoEngine(AndroidKeystoreKeyProvider())
        val secrets = SecretStorageRepository.create(applicationContext, crypto)
        googleAuthManager = GoogleAuthManager(applicationContext, secrets)

        // Handle a cold-start launch straight into the OAuth redirect.
        handleRedirect(intent)

        setContent {
            NexusAgentTheme {
                ChatScreen(secrets = secrets, googleAuthManager = googleAuthManager)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(intent: Intent?) {
        if (intent?.data != null) {
            googleAuthManager.handleAuthorizationResponse(intent)
        }
    }
}
