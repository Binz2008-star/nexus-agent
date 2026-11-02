package com.nexus.agent.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64

/** One explicit send per approval; no automatic retry after ambiguous network failures. */
class GmailSendApiClient(
    private val tokenProvider: suspend () -> String?,
    client: OkHttpClient = OkHttpClient()
) {
    private val hardenedClient = GmailSendHttpPolicy.harden(client)

    suspend fun send(to: String, subject: String, body: String): Result<String> =
        withContext(Dispatchers.IO) {
            val raw = GmailMessageComposer.compose(to, subject, body)
                ?: return@withContext Result.failure(IllegalArgumentException("invalid_message"))
            val token = tokenProvider()?.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(SecurityException("gmail_send_not_authorized"))
            try {
                val payload = JSONObject().put("raw", raw).toString()
                val request = Request.Builder()
                    .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
                    .header("Authorization", "Bearer $token")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                hardenedClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(IOException("gmail_http_${response.code}"))
                    }
                    val id = JSONObject(response.peekBody(64L * 1024L).string()).optString("id")
                    if (id.isBlank()) Result.failure(IOException("gmail_missing_sent_id"))
                    else Result.success(id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

/** Pure MIME builder: rejects header injection and caps message size before encoding. */
object GmailMessageComposer {
    private val address = Regex("^[A-Za-z0-9.!#%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?\\.[A-Za-z]{2,63}$")

    fun compose(to: String, subject: String, body: String): String? {
        val recipient = to.trim()
        val headline = subject.trim()
        if (recipient.length > 254 || !address.matches(recipient) ||
            headline.isBlank() || headline.length > 200 ||
            headline.any { it == '\r' || it == '\n' || it == '\u0000' } ||
            body.isBlank() || body.length > 40_000 || '\u0000' in body) return null
        val subjectHeader = Base64.getEncoder()
            .encodeToString(headline.toByteArray(StandardCharsets.UTF_8))
        val mime = "To: $recipient\r\n" +
            "Subject: =?UTF-8?B?$subjectHeader?=\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n" +
            Base64.getMimeEncoder().encodeToString(body.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mime.toByteArray(StandardCharsets.UTF_8))
    }
}
