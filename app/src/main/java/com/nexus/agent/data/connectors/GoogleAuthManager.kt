package com.nexus.agent.data.connectors

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.nexus.agent.BuildConfig
import com.nexus.agent.data.repository.SecretStorageRepository
import com.nexus.agent.domain.connectors.GmailConnector
import com.nexus.agent.domain.connectors.GoogleAuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Android client-side Gmail authorization via Google Identity Services (AuthorizationClient).
 *
 * This replaces the unsupported desktop-style AppAuth/custom-scheme exchange. Android client
 * identification is performed by Google Play services using the registered package name and
 * app-signing SHA-1 in Google Cloud. The public OAuth client ID remains a CI configuration
 * marker, NOT a credential and NOT an authorization grant.
 *
 * Deliberately no offline access: never persist access tokens, refresh tokens, server auth
 * codes or OAuth callback data. The user starts consent explicitly from Settings. Background
 * tool calls may only silently reauthorize an already-connected Gmail account; they cannot
 * display consent UI or increase scopes. Google may require re-consent after process restart.
 */
class GoogleAuthManager(
    context: Context,
    private val secretStorage: SecretStorageRepository,
    private val launchConsent: (PendingIntent) -> Unit,
    private val httpClient: OkHttpClient = defaultClient()
) : GoogleAuthSession {

    private val client = Identity.getAuthorizationClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenMutex = Mutex()
    private val generation = AtomicLong(0L)

    @Volatile private var awaitingConsent = false

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _email = MutableStateFlow<String?>(null)
    override val email: StateFlow<String?> = _email.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun dismissError() {
        _error.value = null
    }

    /** This method is called only from the user's explicit Connect button. */
    fun startAuthorization(scopes: List<String>) {
        if (BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isBlank()) {
            _error.value = "Google OAuth client ID is missing from this build"
            return
        }
        if (GmailAuthorizationPolicy.allowedScopes(scopes).isEmpty()) {
            _error.value = "Unsupported Gmail permission request"
            return
        }

        val current = generation.incrementAndGet()
        awaitingConsent = false
        _error.value = null
        // Do not carry authorization across an account switch without rechecking its identity.
        _isConnected.value = false
        _email.value = null
        scope.launch {
            try {
                val result = client.authorize(createRequest()).await()
                if (generation.get() != current) return@launch
                if (result.hasResolution()) {
                    val pending = result.pendingIntent
                    if (pending == null) {
                        _error.value = "Google authorization could not start"
                        return@launch
                    }
                    awaitingConsent = true
                    withContext(Dispatchers.Main) {
                        if (generation.get() == current) {
                            try {
                                launchConsent(pending)
                            } catch (_: Exception) {
                                awaitingConsent = false
                                _error.value = "Could not open Google consent screen"
                            }
                        }
                    }
                } else {
                    acceptResult(result, current)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation.get() == current) _error.value =
                    "Google Play services authorization failed"
            }
        }
    }

    /** Called only by MainActivity's registered Google authorization result launcher. */
    fun handleAuthorizationResult(resultCode: Int, data: Intent?) {
        if (!awaitingConsent) return // A stale or unsolicited result cannot connect the app.
        awaitingConsent = false
        val current = generation.get()
        if (resultCode != Activity.RESULT_OK || data == null) {
            _error.value = "Google authorization was cancelled"
            return
        }
        scope.launch {
            try {
                val result = client.getAuthorizationResultFromIntent(data)
                acceptResult(result, current)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation.get() == current) _error.value =
                    "Google authorization result was rejected"
            }
        }
    }

    private fun createRequest(accountEmail: String? = null): AuthorizationRequest {
        val request = AuthorizationRequest.builder().setRequestedScopes(
            listOf(Scope(GmailConnector.GMAIL_READONLY_SCOPE))
        )
        accountEmail?.let { request.setAccount(Account(it, "com.google")) }
        return request.build()
    }

    private suspend fun acceptResult(result: AuthorizationResult, current: Long) {
        if (generation.get() != current) return
        val token = result.accessToken?.takeIf { it.isNotBlank() }
        if (token == null || !GmailAuthorizationPolicy.hasRequiredGrant(result.grantedScopes)) {
            _isConnected.value = false
            _error.value = "Gmail read-only permission was not granted"
            return
        }
        // Check the actual Gmail account via a read-only Gmail API endpoint. This prevents
        // displaying "Connected" merely because a token exists when Gmail API is disabled.
        val verifiedEmail = fetchGmailProfileEmail(token)
        if (generation.get() != current) return
        if (verifiedEmail == null) {
            _isConnected.value = false
            _error.value = "Gmail profile verification failed; enable Gmail API and retry"
            return
        }
        _email.value = verifiedEmail
        _isConnected.value = true
        _error.value = null
    }

    override suspend fun accessToken(): String? {
        if (!_isConnected.value) return null
        return tokenMutex.withLock {
            if (!_isConnected.value) return@withLock null
            val current = generation.get()
            val email = _email.value ?: return@withLock null
            try {
                val result = client.authorize(createRequest(email)).await()
                if (generation.get() != current) return@withLock null
                if (result.hasResolution() ||
                    !GmailAuthorizationPolicy.hasRequiredGrant(result.grantedScopes)
                ) {
                    _isConnected.value = false
                    _email.value = null
                    return@withLock null // Never launch consent from a model/tool call.
                }
                result.accessToken?.takeIf { it.isNotBlank() } ?: run {
                    _isConnected.value = false
                    _email.value = null
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // Network/Play services error is not permission to start interactive UI.
            }
        }
    }

    override suspend fun disconnect() {
        generation.incrementAndGet()
        awaitingConsent = false
        val email = _email.value
        _isConnected.value = false
        _email.value = null
        _error.value = null
        // Clear legacy AppAuth tokens from local encrypted storage; never reuse them as
        // Google Identity Services tokens (different Android authorization model).
        secretStorage.clearGoogleRefreshToken()
        secretStorage.clearGoogleEmail()
        secretStorage.clearPendingOAuth()

        if (email != null) {
            try {
                client.revokeAccess(
                    RevokeAccessRequest.builder()
                        .setAccount(Account(email, "com.google"))
                        .setScopes(listOf(Scope(GmailConnector.GMAIL_READONLY_SCOPE)))
                        .build()
                ).await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The local session stays disconnected. Revocation may need to be
                // completed in Google Account > Third-party access if offline.
                _error.value = "Disconnected locally; Google permission revocation was not confirmed"
            }
        }
    }

    private suspend fun fetchGmailProfileEmail(token: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://gmail.googleapis.com/gmail/v1/users/me/profile")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    JSONObject(response.body?.string().orEmpty())
                        .optString("emailAddress").takeIf { it.contains('@') }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    companion object {
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** Pure fail-closed scope policy, independently tested without Google Play services. */
object GmailAuthorizationPolicy {
    fun allowedScopes(requested: List<String>): List<String> =
        requested.distinct().filter { it == GmailConnector.GMAIL_READONLY_SCOPE }

    fun hasRequiredGrant(granted: List<String>?): Boolean =
        granted?.contains(GmailConnector.GMAIL_READONLY_SCOPE) == true
}
