package com.nexus.agent.domain.tool

import com.nexus.agent.domain.model.WebFetchPage
import com.nexus.agent.domain.safety.UrlSafetyValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Exposes "fetch and read a web page" through the generic [Tool] interface, so the model-driven
 * [ToolLoop] can propose it when the user asks to open/read a specific URL. Read-only (no
 * mutation, no scopes); the fetch + SSRF validation lives behind the injected [fetch] function.
 */
class WebFetchTool(
    private val fetch: suspend (String) -> Result<WebFetchPage>
) : Tool {

    override val name: String = "web_fetch"
    override val description: String =
        "Fetch a web page at the given URL and return its title and readable text."

    override val parameterSchema: JsonObject =
        ToolSchema.parameters(
            listOf(ToolSchema.Parameter("url", "The full http(s) URL of the page to read", required = true))
        )

    override suspend fun execute(input: JsonObject): ToolResult {
        val url = (input["url"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?: return ToolResult.Failure("empty")

        return fetch(url).fold(
            onSuccess = { page ->
                ToolResult.Success(
                    content = page.text,
                    sources = listOf(
                        ToolSource(
                            title = page.title,
                            snippet = page.text.take(400),
                            url = page.url
                        )
                    )
                )
            },
            onFailure = { e -> ToolResult.Failure(WebFetchError.codeOf(e)) }
        )
    }
}

/** Maps a web-fetch failure to a stable, user-facing error code. */
object WebFetchError {
    fun codeOf(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains(UrlSafetyValidator.ERR_BLOCKED) -> "blocked_url"
            msg.contains(UrlSafetyValidator.ERR_MALFORMED) -> "malformed_url"
            msg.contains(UrlSafetyValidator.ERR_UNRESOLVED) -> "unresolved"
            msg.contains("Too many redirects") -> "redirect_loop"
            msg.contains("Body too large") -> "too_large"
            msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true) -> "timeout"
            msg.contains("Unable to resolve host") || msg.contains("Failed to connect") -> "network"
            msg.contains("HTTP ") -> "http_${msg.substringAfter("HTTP ").take(3)}"
            else -> "generic:${e.javaClass.simpleName}"
        }
    }
}
