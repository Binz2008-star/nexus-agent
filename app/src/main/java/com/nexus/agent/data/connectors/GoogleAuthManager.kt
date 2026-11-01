package com.nexus.agent.data.connectors

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
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

    private val _grantedScopes = MutableStateFlow<Set<String>>(emptySet())
    val grantedScopes: StateFlow<Set<String>> = _grantedScopes.asStateFlow()
    private val _driveConnected = MutableStateFlow(false)
    val driveConnected: StateFlow<Boolean> = _driveConnected.asStateFlow()
    private var requestedScopes: List<String> = emptyList()

    private val _email = MutableStateFlow<String?>(null)
    override val email: StateFlow<String?> = _email.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun dismissError() {
        _error.value = null
    }

    /** This method is called only from the user's explicit Connect button. */
    fun startAuthorization(scopes: List<String>) {
        if (!GoogleScopePolicy.isAllowedRequest(scopes)) {
            _error.value = "Unsupported Gmail permission request"
            return
        }

        val current = generation.incrementAndGet()
        awaitingConsent = false
        requestedScopes = (scopes + _grantedScopes.value).distinct()
        _error.value = null
        scope.launch {
            try {
                val result = client.authorize(createRequest(requestedScopes.toList(), _email.value)).await()
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
                    acceptResult(result, current, requestedScopes)
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
                acceptResult(result, current, requestedScopes)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation.get() == current) _error.value =
                    "Google authorization result was rejected"
            }
        }
    }

    private fun createRequest(scopes: List<String>, accountEmail: String? = null): AuthorizationRequest {
        val request = AuthorizationRequest.builder().setRequestedScopes(
            (scopes + listOf("openid", "email")).distinct().map(::Scope)
        )
        accountEmail?.let { request.setAccount(Account(it, "com.google")) }
        return request.build()
    }

    private suspend fun acceptResult(
        result: AuthorizationResult,
        current: Long,
        requested: List<String>
    ) {
        if (generation.get() != current) return
        val token = result.accessToken?.takeIf { it.isNotBlank() }
        if (token == null || !result.grantedScopes.containsAll(requested)) {
            _error.value = "Not all requested Google permissions were granted"
            return
        }
        // Gmail profile gives a verified account for Gmail grants; Drive about provides
        // account email for Drive-only authorization. No personal data is persisted.
        val email = result.toGoogleSignInAccount()?.email
            ?: fetchProfileEmail(token, "https://openidconnect.googleapis.com/v1/userinfo", "userinfo")
            ?: if (
            result.grantedScopes.contains(GmailConnector.GMAIL_READONLY_SCOPE)
        ) {
            fetchProfileEmail(token, "https://gmail.googleapis.com/gmail/v1/users/me/profile", "emailAddress")
        } else if (result.grantedScopes.any { it in setOf(GoogleScopePolicy.DRIVE_READ, GoogleScopePolicy.DRIVE_FILE) }) {
            fetchProfileEmail(token,
                "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)", "drive")
        } else {
            fetchProfileEmail(token, "https://openidconnect.googleapis.com/v1/userinfo", "userinfo")
        }
        if (generation.get() != current) return
        if (email == null || (_email.value != null && _email.value != email)) {
            _error.value = "Google account identity verification failed or account changed"
            return
        }
        _email.value = email
        updateGrants(result.grantedScopes.toSet())
        _error.value = null
    }

    private fun updateGrants(scopes: Set<String>) {
        _grantedScopes.value = scopes
        _isConnected.value = GmailConnector.GMAIL_READONLY_SCOPE in scopes
        _driveConnected.value = GoogleScopePolicy.DRIVE_READ in scopes &&
            GoogleScopePolicy.DRIVE_FILE in scopes
    }

    override suspend fun accessToken(): String? =
        accessTokenForScopes(listOf(GmailConnector.GMAIL_READONLY_SCOPE))

    /** Silent token renewal. Never invokes a consent screen from an agent tool call. */
    suspend fun accessTokenForScopes(required: List<String>): String? {
        if (!GoogleScopePolicy.isAllowedRequest(required) ||
            !_grantedScopes.value.containsAll(required)) return null
        return tokenMutex.withLock {
            if (!_grantedScopes.value.containsAll(required)) return@withLock null
            val current = generation.get()
            val accountEmail = _email.value ?: return@withLock null
            try {
                val result = client.authorize(createRequest(required, accountEmail)).await()
                if (generation.get() != current) return@withLock null
                if (result.hasResolution() || !result.grantedScopes.containsAll(required)) {
                    updateGrants(_grantedScopes.value - required.toSet())
                    return@withLock null
                }
                result.accessToken?.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun disconnect() {
        generation.incrementAndGet()
        awaitingConsent = false
        val email = _email.value
        updateGrants(emptySet())
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
                        .setScopes(GoogleScopePolicy.ALLOWED.map(::Scope))
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

    private suspend fun fetchProfileEmail(token: String, url: String, type: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url)
                    .header("Authorization", "Bearer $token").get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val obj = JSONObject(response.body?.string().orEmpty())
                    val email = when (type) {
                        "drive" -> obj.optJSONObject("user")?.optString("emailAddress")
                        "userinfo" -> {
                            if (!obj.optBoolean("email_verified", false)) null
                            else obj.optString("email")
                        }
                        else -> obj.optString("emailAddress")
                    }
                    email?.takeIf { it.contains('@') }
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

/**
 * Explicit scopes allowed by NEXUS. Gmail send and Drive changes additionally require
 * explicit COMMIT receipts in ToolOrchestrator. Never request the full-mailbox scope.
 */
object GoogleScopePolicy {
    const val GMAIL_READ = "https://www.googleapis.com/auth/gmail.readonly"
    const val GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send"
    const val DRIVE_READ = "https://www.googleapis.com/auth/drive.readonly"
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    const val CALENDAR_READ = "https://www.googleapis.com/auth/calendar.events.readonly"
    const val CALENDAR_EVENTS = "https://www.googleapis.com/auth/calendar.events"
    const val CONTACTS_READ = "https://www.googleapis.com/auth/contacts.readonly"
    const val TASKS_READ = "https://www.googleapis.com/auth/tasks.readonly"
    const val TASKS_WRITE = "https://www.googleapis.com/auth/tasks"
    val ALLOWED = listOf(GMAIL_READ, GMAIL_SEND, DRIVE_READ, DRIVE_FILE,
        CALENDAR_READ, CALENDAR_EVENTS, CONTACTS_READ, TASKS_READ, TASKS_WRITE)

    fun isAllowedRequest(scopes: List<String>): Boolean =
        scopes.isNotEmpty() && scopes.all { it in ALLOWED }

    fun hasRequiredGrant(granted: List<String>?, required: List<String>): Boolean =
        isAllowedRequest(required) && granted?.containsAll(required) == true
}

/** Existing read-only policy preserved for downstream unit tests and callers. */
object GmailAuthorizationPolicy {
    fun allowedScopes(requested: List<String>): List<String> =
        requested.distinct().filter { it == GoogleScopePolicy.GMAIL_READ }

    fun hasRequiredGrant(granted: List<String>?): Boolean =
        GoogleScopePolicy.hasRequiredGrant(granted, listOf(GoogleScopePolicy.GMAIL_READ))
}
