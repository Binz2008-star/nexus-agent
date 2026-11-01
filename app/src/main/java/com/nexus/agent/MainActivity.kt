package com.nexus.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.nexus.agent.data.connectors.GoogleAuthManager
import com.nexus.agent.data.repository.SecretStorageRepository
import com.nexus.agent.security.AndroidKeystoreKeyProvider
import com.nexus.agent.security.CryptoEngine
import com.nexus.agent.ui.ChatScreen
import com.nexus.agent.ui.theme.NexusAgentTheme

class MainActivity : ComponentActivity() {
    private lateinit var googleAuthManager: GoogleAuthManager

    // Google Identity Services returns its consent result via ActivityResult, not a custom
    // scheme browser redirect. Register before onCreate as required by the Activity Result API.
    private val googleConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (::googleAuthManager.isInitialized) {
            googleAuthManager.handleAuthorizationResult(result.resultCode, result.data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val crypto = CryptoEngine(AndroidKeystoreKeyProvider())
        val secrets = SecretStorageRepository.create(applicationContext, crypto)
        googleAuthManager = GoogleAuthManager(
            context = applicationContext,
            secretStorage = secrets,
            launchConsent = { pending ->
                googleConsentLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            }
        )

        setContent {
            NexusAgentTheme {
                ChatScreen(secrets = secrets, googleAuthManager = googleAuthManager)
            }
        }
    }
}
