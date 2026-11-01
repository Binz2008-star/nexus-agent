package com.nexus.agent.data.remote

import com.nexus.agent.domain.model.WebFetchPage
import com.nexus.agent.domain.safety.UrlSafetyValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches a single web page and extracts its readable text, with SSRF protection.
 *
 * Redirects are followed manually (OkHttp redirects are disabled) so every hop is re-validated
 * through [UrlSafetyValidator] — a redirect to a private host is refused, not followed. The
 * response body is bounded ([maxBytes]) and the HTML is reduced to title + text via
 * [HtmlTextExtractor].
 */
class WebFetchClient(
    private val validator: UrlSafetyValidator = UrlSafetyValidator(),
    private val extractor: HtmlTextExtractor = HtmlTextExtractor(),
    private val httpClient: OkHttpClient = defaultClient(),
    private val maxBytes: Int = MAX_BODY_BYTES
) {

    suspend fun fetch(url: String): Result<WebFetchPage> {
        var current = validator.validate(url).getOrElse { return Result.failure(it) }
        var redirects = 0

        while (redirects <= MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()

            val request = Request.Builder()
                .url(current)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                .build()

            val response = try {
                httpClient.newCall(request).execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(e)
            }

            try {
                if (response.code in 300..399) {
                    val location = response.header("Location")
                        ?: return Result.failure(IOException("Redirect without Location"))
                    val next = current.resolve(location)
                        ?: return Result.failure(IOException("Malformed redirect URL"))
                    // Re-validate the redirect target before following it.
                    current = validator.validate(next.toString()).getOrElse { return Result.failure(it) }
                    redirects++
                    continue
                }
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code}"))
                }
                val body = response.body ?: return Result.failure(IOException("Empty body"))
                val html = readBounded(body, maxBytes)
                    ?: return Result.failure(IOException("Body too large"))
                val extracted = extractor.extract(html)
                return Result.success(
                    WebFetchPage(
                        title = extracted.title.ifBlank { current.host },
                        url = current.toString(),
                        text = extracted.text
                    )
                )
            } finally {
                response.close()
            }
        }
        return Result.failure(IOException("Too many redirects"))
    }

    /** Reads at most [maxBytes] from [body]; returns null if the body is larger. */
    private fun readBounded(body: ResponseBody, maxBytes: Int): String? {
        val source = body.source()
        val buffer = ByteArray(maxBytes + 1)
        var total = 0
        while (total < buffer.size) {
            val read = source.read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
        }
        if (total > maxBytes) return null
        return String(buffer, 0, total, Charsets.UTF_8)
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val MAX_BODY_BYTES = 1_000_000 // 1 MiB cap on fetched HTML
        private const val USER_AGENT = "NexusAgent/1.0 (+android web_fetch tool)"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false) // manual, so every hop is re-validated for SSRF
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
