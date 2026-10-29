package com.nexus.agent.data.remote

import com.nexus.agent.domain.model.KnowledgeResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class KnowledgeSearchRequest(
    val query: String,
    @SerialName("top_k") val topK: Int
)

@Serializable
data class KnowledgeSearchResponse(
    val query: String = "",
    val results: List<KnowledgeResultDto> = emptyList()
)

@Serializable
data class KnowledgeResultDto(
    val project: String,
    val file: String,
    val chunk: String? = null,
    val content: String = "",
    val score: Double = 0.0
)

/**
 * OkHttp client for the Second Brain `POST /api/search` endpoint (read-only).
 * The base URL is supplied by [baseUrlProvider] and is never hardcoded.
 */
class KnowledgeApiClient(
    private val baseUrlProvider: suspend () -> String?,
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun search(query: String, topK: Int): Result<List<KnowledgeResult>> {
        val base = baseUrlProvider()?.trim()?.trimEnd('/')
            ?: return Result.failure(IOException("No knowledge base URL configured"))

        val body = json.encodeToString(KnowledgeSearchRequest(query, topK))
        val request = Request.Builder()
            .url("$base/api/search")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // Do not include the body — it may contain document excerpts.
                    Result.failure(IOException("Knowledge API error ${response.code}"))
                } else {
                    val parsed = json.decodeFromString<KnowledgeSearchResponse>(text)
                    Result.success(parsed.results.map { it.toDomain() })
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun KnowledgeResultDto.toDomain() = KnowledgeResult(
        project = project,
        file = file,
        chunk = chunk,
        content = content,
        score = score
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
