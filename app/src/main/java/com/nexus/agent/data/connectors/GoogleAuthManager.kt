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
    @SerialName("token_type") val tokenType: String = ""
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

    // In-flight PKCE verifier (memory only; never persisted).
    @Volatile private var pendingVerifier: String? = null

    // In-flight CSRF `state` binding the redirect to the request (memory only).
    @Volatile private var pendingState: String? = null

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
        pendingVerifier = verifier
        pendingState = state

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
            .build()

        CustomTabsIntent.Builder().build().launchUrl(context, request.toUri())
    }

    /**
     * Called from the Activity's `onNewIntent`/`onCreate` with the redirect intent for
     * [REDIRECT_URI]. Verifies the CSRF [state], then extracts the authorization code and
     * exchanges it in the background.
     */
    fun handleAuthorizationResponse(intent: Intent) {
        val data = intent.data ?: return
        val state = data.getQueryParameter("state")
        if (state == null || state != pendingState) return
        pendingState = null
        val code = data.getQueryParameter("code") ?: return
        scope.launch { exchangeCode(code) }
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
        cachedAccessToken = null
        cachedExpiryMs = 0L
        _isConnected.value = false
        _email.value = null
    }

    private suspend fun exchangeCode(code: String) {
        val verifier = pendingVerifier ?: return
        pendingVerifier = null
        val resp = postToken(
            FormBody.Builder()
                .add("code", code)
                .add("client_id", CLIENT_ID)
                .add("redirect_uri", REDIRECT_URI)
                .add("grant_type", "authorization_code")
                .add("code_verifier", verifier)
                .build()
        ) ?: return

        resp.refreshToken?.let { secretStorage.saveGoogleRefreshToken(it) }
        val email = resp.idToken?.let { emailFromIdToken(it) }
        email?.let { secretStorage.saveGoogleEmail(it) }

        cachedAccessToken = resp.accessToken
        cachedExpiryMs = System.currentTimeMillis() + resp.expiresIn * 1000L
        _email.value = email
        _isConnected.value =
            resp.refreshToken != null ||
                secretStorage.readGoogleRefreshToken() is ApiKeyReadResult.Present
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
    private fun emailFromIdToken(idToken: String): String? = try {
        val parts = idToken.split(".")
        if (parts.size < 2) return null
        val payload = String(
            Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
            Charsets.UTF_8
        )
        json.parseToJsonElement(payload).jsonObject["email"]?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
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

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
