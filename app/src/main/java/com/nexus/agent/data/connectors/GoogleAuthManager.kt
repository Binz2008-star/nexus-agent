package com.nexus.agent.data.connectors

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.nexus.agent.BuildConfig
import com.nexus.agent.data.repository.ApiKeyReadResult
import com.nexus.agent.data.repository.SecretStorageRepository
import com.nexus.agent.domain.connectors.GoogleAuthSession
import com.nexus.agent.domain.connectors.PendingOAuthSession
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.ResponseTypeValues
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
private data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("token_type") val tokenType: String = "",
    @SerialName("scope") val scope: String? = null
)

/**
 * Owns the Google OAuth 2.0 lifecycle for the connectors: launches the Custom-Tabs
 * authorization (PKCE), exchanges the code, persists the **refresh token encrypted** via
 * [SecretStorageRepository] (never plaintext), and refreshes access tokens on demand.
 *
 * AppAuth is used only for building the authorization request (PKCE + scopes); the token
 * endpoint calls are plain OkHttp (suspend-friendly and testable).
 *
 * A single refresh token + account is shared by all Google connectors (Drive, Gmail, …).
 * Each `startAuthorization` requests only the connector's own scopes (incremental auth), so
 * Google returns a cumulative refresh token; disconnecting any connector disconnects the
 * shared session.
 *
 * NOTE: [CLIENT_ID] and the redirect URI must be registered in Google Cloud Console before
 * this works end-to-end — see the companion object.
 */
class GoogleAuthManager(
    private val context: Context,
    private val secretStorage: SecretStorageRepository,
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : GoogleAuthSession {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, _ ->
                // A failed background write (keystore/DataStore) must not crash the app;
                // the session simply stays disconnected and the user can retry.
            }
    )

    private val tokenMutex = Mutex()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _email = MutableStateFlow<String?>(null)
    override val email: StateFlow<String?> = _email.asStateFlow()

    // In-memory access-token cache; refreshed from the stored refresh token when expired.
    @Volatile private var cachedAccessToken: String? = null
    @Volatile private var cachedExpiryMs: Long = 0L

    init {
        scope.launch {
            _isConnected.value =
                secretStorage.readGoogleRefreshToken() is ApiKeyReadResult.Present
            _email.value = secretStorage.readGoogleEmail()
        }
    }

    /** Launches the Google authorization flow in a Custom Tab for the given [scopes]. */
    fun startAuthorization(scopes: List<String>) {
        val verifier = CodeVerifierUtil.generateRandomCodeVerifier()
        val challenge = CodeVerifierUtil.deriveCodeVerifierChallenge(verifier)
        val state = UUID.randomUUID().toString()
        // Persist the in-flight session so a cold-start redirect (process death while the
        // Custom Tab is open) can still be validated and exchanged.
        scope.launch {
            secretStorage.savePendingOAuth(state, verifier, System.currentTimeMillis())
        }

        val allScopes = (listOf("openid", "email") + scopes).distinct()
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI)
        )
            .setScopes(*allScopes.toTypedArray())
            .setCodeVerifier(verifier, challenge, CodeVerifierUtil.getCodeVerifierChallengeMethod())
            .setState(state)
            // Always request offline access + force consent so a refresh token is returned on
            // every authorization (including after disconnect), not just the first.
            .setAdditionalParameters(mapOf("access_type" to "offline", "prompt" to "consent"))
            .build()

        CustomTabsIntent.Builder().build().launchUrl(context, request.toUri())
    }

    /**
     * Called from the Activity's `onNewIntent`/`onCreate` with the redirect intent for
     * [REDIRECT_URI]. Validates the CSRF [state] against the persisted session (recovering
     * after process death), enforces one-time use + expiry, then exchanges the code.
     */
    fun handleAuthorizationResponse(intent: Intent) {
        val data = intent.data ?: return
        val incomingState = data.getQueryParameter("state")
        val code = data.getQueryParameter("code") ?: return
        scope.launch {
            val session = secretStorage.readPendingOAuth() ?: return@launch
            // CSRF + replay protection: the state must match and must not have expired.
            if (!session.matchesState(incomingState)) return@launch
            if (session.isExpired(System.currentTimeMillis(), PENDING_OAUTH_TTL_MS)) {
                secretStorage.clearPendingOAuth()
                return@launch
            }
            // One-time use: clear before exchanging so the code/verifier can't be replayed.
            secretStorage.clearPendingOAuth()
            exchangeCode(code, session.verifier)
        }
    }

    override suspend fun accessToken(): String? = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        cachedAccessToken?.let { token ->
            if (now < cachedExpiryMs - 60_000L) return@withLock token
        }
        val refreshToken = readRefreshToken() ?: return@withLock null
        val resp = postToken(
            FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()
        ) ?: return@withLock null

        cachedAccessToken = resp.accessToken
        cachedExpiryMs = now + resp.expiresIn * 1000L
        // Google may rotate the refresh token on refresh.
        resp.refreshToken?.let { scope.launch { secretStorage.saveGoogleRefreshToken(it) } }
        resp.accessToken
    }

    override suspend fun disconnect() {
        secretStorage.clearGoogleRefreshToken()
        secretStorage.clearGoogleEmail()
        secretStorage.clearPendingOAuth()
        cachedAccessToken = null
        cachedExpiryMs = 0L
        _isConnected.value = false
        _email.value = null
    }

    private suspend fun exchangeCode(code: String, verifier: String) {
        val resp = postToken(
            FormBody.Builder()
                .add("code", code)
                .add("client_id", CLIENT_ID)
                .add("redirect_uri", REDIRECT_URI)
                .add("grant_type", "authorization_code")
                .add("code_verifier", verifier)
                .build()
        ) ?: return

        try {
            val email = resp.idToken?.let { emailFromIdToken(it) }
            // Account isolation: if a different Google account is authorized, drop the previous
            // account's token so it is never mixed into the new session.
            val existingEmail = secretStorage.readGoogleEmail()
            if (existingEmail != null && email != null && existingEmail != email) {
                secretStorage.clearGoogleRefreshToken()
                secretStorage.clearGoogleEmail()
                cachedAccessToken = null
                cachedExpiryMs = 0L
            }
            resp.refreshToken?.let { secretStorage.saveGoogleRefreshToken(it) }
            email?.let { secretStorage.saveGoogleEmail(it) }

            cachedAccessToken = resp.accessToken
            cachedExpiryMs = System.currentTimeMillis() + resp.expiresIn * 1000L
            _email.value = email
            _isConnected.value =
                resp.refreshToken != null ||
                    secretStorage.readGoogleRefreshToken() is ApiKeyReadResult.Present
        } catch (e: Exception) {
            // Keystore/DataStore write failed: stay disconnected and let the user retry rather
            // than leaving a half-connected session or crashing the coroutine.
            cachedAccessToken = null
            cachedExpiryMs = 0L
            _isConnected.value = false
            _email.value = null
        }
    }

    private suspend fun postToken(form: FormBody): TokenResponseDto? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(form)
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) return@withContext null
                    json.decodeFromString<TokenResponseDto>(text)
                }
            } catch (e: Exception) {
                null
            }
        }

    private suspend fun readRefreshToken(): String? =
        when (val r = secretStorage.readGoogleRefreshToken()) {
            is ApiKeyReadResult.Present -> r.value
            else -> null
        }

    /** Extracts the `email` claim from an OIDC id_token (JWT payload is not signature-verified here). */
    private fun emailFromIdToken(idToken: String): String? {
        return try {
            val parts = idToken.split(".")
            if (parts.size < 2) {
                null
            } else {
                val payload = String(
                    Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                    Charsets.UTF_8
                )
                val obj = json.parseToJsonElement(payload).jsonObject
                // Only trust the email claim when Google asserts the address is verified (R11).
                if (obj["email_verified"]?.jsonPrimitive?.content?.toBoolean() != true) return null
                obj["email"]?.jsonPrimitive?.content
            }
        } catch (e: Exception) {
            null
        }
    }

    private val serviceConfig: AuthorizationServiceConfiguration by lazy {
        AuthorizationServiceConfiguration(
            Uri.parse(AUTH_ENDPOINT),
            Uri.parse(TOKEN_ENDPOINT)
        )
    }

    companion object {
        /** Injected at CI build time; empty means authorization will not start until set. */
        val CLIENT_ID: String get() = BuildConfig.GOOGLE_OAUTH_CLIENT_ID
        const val REDIRECT_URI = "com.nexus.agent:/oauth2redirect"
        const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

        /** A redirect is only accepted within this window (replay protection). */
        const val PENDING_OAUTH_TTL_MS = 10 * 60 * 1000L

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
