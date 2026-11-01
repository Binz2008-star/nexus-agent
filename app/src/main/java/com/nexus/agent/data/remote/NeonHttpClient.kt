package com.nexus.agent.data.remote

import com.nexus.agent.domain.hermes.HermesSafety
import com.nexus.agent.domain.model.KnowledgeResult
import com.nexus.agent.domain.neon.NeonBackendClient
import com.nexus.agent.domain.neon.NeonMemoryItem
import com.nexus.agent.domain.tool.KnowledgeSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// ---- DTOs (mirror backend/main.py) ----

@Serializable
data class BrainSearchRequest(val query: String, @SerialName("top_k") val topK: Int)

@Serializable
data class BrainSearchResponse(val results: List<BrainSearchResultDto> = emptyList())

@Serializable
data class BrainSearchResultDto(
    val project: String = "",
    val file: String = "",
    @SerialName("chunk_type") val chunkType: String? = null,
    val content: String = "",
    val score: Double = 0.0
)

@Serializable
data class MemorySaveRequest(val type: String, val content: String, val tags: List<String> = emptyList())

@Serializable
data class MemoryListResponse(val items: List<MemoryItemDto> = emptyList())

@Serializable
data class MemoryItemDto(val type: String = "", val content: String = "")

@Serializable
data class MirrorRequest(
    @SerialName("session_id") val sessionId: String,
    val role: String,
    val content: String
)

@Serializable
data class ErrorResponse(val detail: String = "")

/**
 * HTTP client for the NEXUS Neon backend (the HTTPS replacement for direct JDBC). Mirrors the
 * four endpoints in `backend/main.py` and returns the SAME domain types as the JDBC path so it
 * can be dropped in behind a feature flag with no behavioral change.
 *
 * The base URL and bearer key are supplied by suspend providers (encrypted/configured at runtime)
 * and never hardcoded, so no Neon credential or backend token lives in the APK.
 */
class NeonHttpClient(
    private val baseUrlProvider: suspend () -> String?,
    private val apiKeyProvider: suspend () -> String?,
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : NeonBackendClient {

    override suspend fun search(query: String, topK: Int): KnowledgeSearchResult {
        val base = base() ?: return KnowledgeSearchResult.Failure(ERROR_NO_CONNECTION)
        val key = key() ?: return KnowledgeSearchResult.Failure(ERROR_NO_CONNECTION)
        val body = json.encodeToString(BrainSearchRequest(query, topK))
        val request = requestBuilder("$base/v1/brain/search", key).post(body.toRequestBody(JSON)).build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return KnowledgeSearchResult.Failure(errorCodeFrom(resp))
                val parsed = json.decodeFromString<BrainSearchResponse>(resp.body?.string().orEmpty())
                KnowledgeSearchResult.Success(query, parsed.results.map { it.toDomain() })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KnowledgeSearchResult.Failure(networkErrorCode(e))
        }
    }

    override suspend fun saveMemory(type: String, content: String, tags: List<String>): Result<Unit> {
        val base = base() ?: return Result.failure(IOException(ERROR_NO_CONNECTION))
        val key = key() ?: return Result.failure(IOException(ERROR_NO_CONNECTION))
        val body = json.encodeToString(MemorySaveRequest(type, content, tags))
        val request = requestBuilder("$base/v1/memory/save", key).post(body.toRequestBody(JSON)).build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(IOException(errorCodeFrom(resp)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun loadMemory(limit: Int): Result<List<NeonMemoryItem>> {
        val base = base() ?: return Result.failure(IOException(ERROR_NO_CONNECTION))
        val key = key() ?: return Result.failure(IOException(ERROR_NO_CONNECTION))
        val request = requestBuilder("$base/v1/memory/recent?limit=$limit", key).get().build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return Result.failure(IOException(errorCodeFrom(resp)))
                val parsed = json.decodeFromString<MemoryListResponse>(resp.body?.string().orEmpty())
                Result.success(parsed.items.map { NeonMemoryItem(it.type, it.content) })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun mirror(sessionId: String, role: String, content: String): Result<Unit> {
        val base = base() ?: return Result.failure(IOException(ERROR_NO_CONNECTION))
        val key = key() ?: return Result.failure(IOException(ERROR_NO_CONNECTION))
        val body = json.encodeToString(MirrorRequest(sessionId, role, content))
        val request = requestBuilder("$base/v1/transcript/mirror", key).post(body.toRequestBody(JSON)).build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(IOException(errorCodeFrom(resp)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun BrainSearchResultDto.toDomain() = KnowledgeResult(
        project = project,
        file = file,
        chunk = chunkType,
        content = content,
        score = score
    )

    private suspend fun base(): String? =
        baseUrlProvider()?.let { HermesSafety.normalizeEndpoint(it).getOrNull() }

    private suspend fun key(): String? = apiKeyProvider()?.takeIf { it.isNotEmpty() }

    private fun requestBuilder(url: String, key: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $key")

    private fun httpErrorCode(code: Int): String = when (code) {
        401, 403 -> "auth"
        429 -> "rate"
        else -> "http_$code"
    }

    /** Prefers the backend's stable, non-sensitive `detail` code; otherwise maps the HTTP status. */
    private fun errorCodeFrom(resp: Response): String {
        val detail = try {
            json.decodeFromString<ErrorResponse>(resp.body?.string().orEmpty()).detail
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
        return if (detail in KNOWN_BACKEND_CODES) detail else httpErrorCode(resp.code)
    }

    private fun networkErrorCode(e: Exception): String = when {
        e.message?.contains("timeout", ignoreCase = true) == true -> "timeout"
        e.message?.contains("resolve host", ignoreCase = true) == true ||
            e.message?.contains("Failed to connect") == true -> "network"
        else -> "server"
    }

    companion object {
        const val ERROR_NO_CONNECTION = "no_connection"

        /** Backend `error_code()` values that are safe to surface verbatim (mirror the JDBC path). */
        private val KNOWN_BACKEND_CODES = setOf("auth", "not_found", "network", "server")

        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
