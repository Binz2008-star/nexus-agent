package com.nexus.agent.data.remote

import com.nexus.agent.domain.model.GmailMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class GmailMessagesListResponse(
    val messages: List<GmailMessageRef> = emptyList(),
    @SerialName("resultSizeEstimate") val resultSizeEstimate: Int = 0
)

@Serializable
data class GmailMessageRef(
    val id: String = "",
    @SerialName("threadId") val threadId: String = ""
)

@Serializable
data class GmailMessageResponse(
    val id: String = "",
    @SerialName("threadId") val threadId: String = "",
    val snippet: String = "",
    val payload: GmailPayload? = null
)

@Serializable
data class GmailPayload(
    val headers: List<GmailHeader> = emptyList()
)

@Serializable
data class GmailHeader(
    val name: String = "",
    val value: String = ""
)

/**
 * OkHttp client for the Gmail API — read-only inbox search.
 *
 *  1. `GET /gmail/v1/users/me/messages?q=<query>&maxResults=<n>` lists matching message ids.
 *  2. For each id, `GET /gmail/v1/users/me/messages/{id}?format=metadata` fetches the
 *     subject/from headers + snippet.
 *
 * This client never sends, deletes, labels, or drafts — search/read metadata only.
 * Auth is a bearer OAuth token supplied by [tokenProvider] (never hardcoded).
 */
class GmailApiClient(
    private val tokenProvider: suspend () -> String?,
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun searchMessages(query: String, maxResults: Int): Result<List<GmailMessage>> {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IOException("No Google Gmail access token configured"))

        return try {
            val refs = listMessages(token, query, maxResults)
            val messages = refs.take(maxResults).mapNotNull { ref ->
                getMessage(token, ref.id)
            }
            Result.success(messages)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun listMessages(token: String, query: String, maxResults: Int): List<GmailMessageRef> {
        val url = baseUrl().addPathSegment("users")
            .addPathSegment("me")
            .addPathSegment("messages")
            .addQueryParameter("q", query)
            .addQueryParameter("maxResults", maxResults.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Gmail API error ${response.code}")
            return json.decodeFromString<GmailMessagesListResponse>(text).messages
        }
    }

    private fun getMessage(token: String, id: String): GmailMessage? {
        val url = baseUrl().addPathSegment("users")
            .addPathSegment("me")
            .addPathSegment("messages")
            .addPathSegment(id)
            .addQueryParameter("format", "metadata")
            .addQueryParameter("metadataHeaders", "Subject")
            .addQueryParameter("metadataHeaders", "From")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return null
                val parsed = json.decodeFromString<GmailMessageResponse>(text)
                GmailMessage(
                    id = parsed.id,
                    threadId = parsed.threadId,
                    subject = parsed.payload?.headers?.firstOrNull { it.name.equals("Subject", true) }?.value.orEmpty(),
                    from = parsed.payload?.headers?.firstOrNull { it.name.equals("From", true) }?.value.orEmpty(),
                    snippet = parsed.snippet
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun baseUrl(): HttpUrl.Builder = HttpUrl.Builder()
        .scheme("https")
        .host("gmail.googleapis.com")
        .addPathSegment("gmail")
        .addPathSegment("v1")

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
