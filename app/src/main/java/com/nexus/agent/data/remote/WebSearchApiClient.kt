package com.nexus.agent.data.remote

import com.nexus.agent.domain.model.WebSearchResult
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
data class WebSearchRequest(
    val query: String,
    val count: Int,
    @SerialName("need_content") val needContent: Boolean = false
)

@Serializable
data class WebSearchResponse(
    val results: List<WebSearchResultDto> = emptyList()
)

@Serializable
data class WebSearchResultDto(
    @SerialName("rerank_score") val rerankScore: Double = 0.0,
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val summary: String = "",
    val content: String = "",
    @SerialName("site_name") val siteName: String? = null,
    @SerialName("site_icon") val siteIcon: String? = null,
    val date: String? = null,
    val authority: Int = -1
)

/**
 * OkHttp client for DeepSeek's read-only Search API (`POST /v1/tools/search`).
 * Reuses the DeepSeek API key from [apiKeyProvider].
 */
class WebSearchApiClient(
    private val apiKeyProvider: suspend () -> String?,
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun search(query: String, count: Int): Result<List<WebSearchResult>> {
        val key = apiKeyProvider()
            ?: return Result.failure(IOException("No DeepSeek API key configured"))

        val body = json.encodeToString(WebSearchRequest(query, count))
        val request = Request.Builder()
            .url("$BASE_URL/v1/tools/search")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // No body in errors — it may contain page content.
                    Result.failure(IOException("DeepSeek search error ${response.code}"))
                } else {
                    val parsed = json.decodeFromString<WebSearchResponse>(text)
                    Result.success(parsed.results.map { it.toDomain() })
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun WebSearchResultDto.toDomain() = WebSearchResult(
        title = title,
        url = url,
        snippet = snippet,
        summary = summary,
        siteName = siteName,
        date = date,
        authority = authority,
        score = rerankScore
    )

    companion object {
        const val BASE_URL = "https://api.deepseek.com"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
