package com.nexus.agent.data.remote

import com.nexus.agent.domain.hermes.HermesRunEvent
import com.nexus.agent.domain.hermes.HermesSafety
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An HTTP-level failure from the Hermes API server, carrying the status code + raw body. */
class HermesHttpException(val code: Int, val body: String) :
    IOException("Hermes API error $code")

/**
 * Contract for talking to a Hermes Agent API server (OpenAI-compatible + Runs API). Kept as an
 * interface so the repository can be unit-tested against a fake without a real HTTP client.
 */
interface HermesRemoteClient {
    suspend fun capabilities(): Result<HermesCapabilities>
    suspend fun health(): Result<HermesHealth>

    /** Non-streaming `/v1/chat/completions` — returns the assistant text. */
    suspend fun chat(request: HermesChatRequest): Result<String>

    suspend fun createRun(request: HermesRunRequest): Result<HermesRunCreated>
    suspend fun getRun(runId: String): Result<HermesRunStatus>
    suspend fun approveRun(runId: String, request: HermesApprovalRequest): Result<HermesApprovalResult>
    suspend fun stopRun(runId: String): Result<HermesStopResult>

    /**
     * Streams `/v1/runs/{id}/events` as SSE, emitting typed [HermesRunEvent]s until a terminal
     * event is observed. Reconnects (bounded, with backoff) after a network drop and reconciles
     * against `GET /v1/runs/{id}` so a run that finished while disconnected still yields its
     * terminal event. Returns success once a terminal event has been observed (or reconciled).
     */
    suspend fun streamRunEvents(runId: String, onEvent: suspend (HermesRunEvent) -> Unit): Result<String>
}

/**
 * OkHttp implementation of [HermesRemoteClient]. The endpoint and bearer key are resolved on
 * every call via injected suspend providers (so the latest encrypted/configured values are used)
 * and validated by [HermesSafety] — TLS is required for any non-loopback host, and no privileged
 * key is embedded in the APK.
 */
class HermesRemoteClientImpl(
    private val endpointProvider: suspend () -> String?,
    private val apiKeyProvider: suspend () -> String?,
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : HermesRemoteClient {

    override suspend fun capabilities(): Result<HermesCapabilities> =
        decode(call("/v1/capabilities"))

    override suspend fun health(): Result<HermesHealth> =
        decode(call("/health"))

    override suspend fun chat(request: HermesChatRequest): Result<String> =
        decode<HermesChatResponse>(call("/v1/chat/completions", "POST", json.encodeToString(request)))
            .map { it.choices.firstOrNull()?.message?.content.orEmpty() }

    override suspend fun createRun(request: HermesRunRequest): Result<HermesRunCreated> =
        decode(call("/v1/runs", "POST", json.encodeToString(request)))

    override suspend fun getRun(runId: String): Result<HermesRunStatus> =
        decode(call("/v1/runs/$runId"))

    override suspend fun approveRun(
        runId: String,
        request: HermesApprovalRequest
    ): Result<HermesApprovalResult> =
        decode(call("/v1/runs/$runId/approval", "POST", json.encodeToString(request)))

    override suspend fun stopRun(runId: String): Result<HermesStopResult> =
        decode(call("/v1/runs/$runId/stop", "POST", "{}"))

    override suspend fun streamRunEvents(
        runId: String,
        onEvent: suspend (HermesRunEvent) -> Unit
    ): Result<String> {
        val base = resolveBase().getOrElse { return Result.failure(it) }
        val auth = resolveAuth().getOrElse { return Result.failure(it) }

        var attempt = 0
        while (attempt < MAX_RECONNECT_ATTEMPTS) {
            attempt++
            currentCoroutineContext().ensureActive()

            val terminalSeen = try {
                streamOnce(base, auth, runId, onEvent)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (terminalSeen) return Result.success("ok")

            // No terminal event observed — reconcile against the status endpoint.
            val status = getRun(runId).getOrNull()
            if (status != null) {
                if (status.isTerminal) {
                    emitTerminal(status, onEvent)
                    return Result.success(status.status)
                }
                emitPendingControls(status, onEvent)
            }
            if (attempt >= MAX_RECONNECT_ATTEMPTS) break
            delay(RECONNECT_BACKOFF_MS * attempt)
        }
        return Result.failure(IOException("Hermes run stream ended without a terminal event"))
    }

    // ---- internals ----

    private suspend fun resolveBase(): Result<String> =
        HermesSafety.normalizeEndpoint(endpointProvider())

    private suspend fun resolveAuth(): Result<String> =
        HermesSafety.validateApiKey(apiKeyProvider()).map { "Bearer $it" }

    /** Opens the SSE stream and emits events; returns true when a terminal event was observed. */
    private suspend fun streamOnce(
        base: String,
        auth: String,
        runId: String,
        onEvent: suspend (HermesRunEvent) -> Unit
    ): Boolean {
        val request = Request.Builder()
            .url("$base/v1/runs/$runId/events")
            .header("Authorization", auth)
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HermesHttpException(response.code, boundedResponseBody(response))
            }
            val body = response.body ?: throw IOException("Empty body")
            val parser = HermesSseParser()
            var terminal = false
            body.source().use { source ->
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    // Bound each wire line BEFORE allocating a String; an oversized or
                    // unterminated SSE line must not exhaust the Android process heap.
                    val line = try {
                        source.readUtf8LineStrict(MAX_SSE_LINE_BYTES)
                    } catch (e: EOFException) {
                        if (source.exhausted()) break
                        throw IOException("hermes_sse_line_too_large", e)
                    }
                    val sse = parser.feed(line) ?: continue
                    val event = mapper.map(sse)
                    if (event.isTerminal) terminal = true
                    onEvent(event)
                }
            }
            terminal
        }
    }

    private suspend fun emitTerminal(status: HermesRunStatus, onEvent: suspend (HermesRunEvent) -> Unit) {
        val event = when (status.status) {
            "completed" -> HermesRunEvent.Completed(status.output.orEmpty())
            "failed" -> HermesRunEvent.Failed(status.error?.message)
            "cancelled" -> HermesRunEvent.Cancelled(status.error?.message)
            "interrupted" -> HermesRunEvent.Interrupted(status.error?.message)
            else -> HermesRunEvent.Failed(status.error?.message ?: "unknown:${status.status}")
        }
        onEvent(event)
    }

    private suspend fun emitPendingControls(status: HermesRunStatus, onEvent: suspend (HermesRunEvent) -> Unit) {
        status.pendingControls.forEach { control ->
            if (!control.identity.isNullOrBlank()) {
                onEvent(mapper.fromPendingControl(control))
            }
        }
    }

    /** Executes a single REST call and returns the raw response body (or a typed failure). */
    private suspend fun call(path: String, method: String = "GET", bodyJson: String? = null): Result<String> {
        val base = resolveBase().getOrElse { return Result.failure(it) }
        val auth = resolveAuth().getOrElse { return Result.failure(it) }
        val builder = Request.Builder()
            .url("$base$path")
            .header("Authorization", auth)
            .header("Content-Type", "application/json")
        if (method == "POST") {
            builder.post((bodyJson ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
        }
        return try {
            httpClient.newCall(builder.build()).execute().use { response ->
                val body = boundedResponseBody(response)
                if (response.isSuccessful) Result.success(body)
                else Result.failure(HermesHttpException(response.code, body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Bounded response read: both success JSON and error messages come from a remote endpoint. */
    private fun boundedResponseBody(response: Response): String {
        val bytes = response.peekBody(MAX_JSON_RESPONSE_BYTES + 1).bytes()
        if (bytes.size > MAX_JSON_RESPONSE_BYTES) throw IOException("hermes_response_too_large")
        return String(bytes, Charsets.UTF_8)
    }

    private inline fun <reified T> decode(result: Result<String>): Result<T> =
        result.mapCatching { json.decodeFromString<T>(it) }

    companion object {
        private val mapper = HermesRunEventMapper
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val MAX_SSE_LINE_BYTES = 65_536L
        private const val MAX_JSON_RESPONSE_BYTES = 1_048_576L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BACKOFF_MS = 1_000L

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}
