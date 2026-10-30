package com.nexus.agent.domain.router

import com.nexus.agent.domain.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Domain-level contract for a chat-model provider. Today only [DeepSeekClient] implements it
 * (adapted at the composition root); tomorrow a Gemini or Mistral client plugs in by
 * implementing these two methods and declaring the model ids it serves.
 *
 * Kept in `domain` (not `data`) so the router never depends on concrete HTTP clients.
 */
interface ModelClient {
    /** Model ids this provider can serve (e.g. "deepseek-flash", "deepseek-v4-pro"). */
    val supportedModels: Set<String>

    /** Non-streaming completion. */
    suspend fun complete(history: List<ChatMessage>, model: String): Result<String>

    /** Streaming completion. Calls [onDelta] with each text fragment and returns the full text. */
    suspend fun completeStream(
        history: List<ChatMessage>,
        model: String,
        onDelta: suspend (String) -> Unit
    ): Result<String>
}

/** Thrown (and surfaced as a failed [Result]) when no provider serves the requested model. */
class UnsupportedModelException(model: String?) :
    IllegalArgumentException("No provider registered for model: $model")

/**
 * Decides which [ModelClient] should serve a request, based on the requested model id.
 *
 * The registry is built once from a list of providers; every provider contributes the set of
 * model ids it supports. Adding a new provider later is a one-line change at the composition
 * root — no routing logic needs to change.
 */
class AiModelRouter(
    providers: List<ModelClient>,
    private val defaultModel: String = MODEL_DEEPSEEK_FLASH
) {
    private val clients: Map<String, ModelClient> = buildMap {
        for (provider in providers) {
            for (model in provider.supportedModels) {
                put(model, provider)
            }
        }
    }

    /**
     * Routes a non-streaming request. A null/blank [requestedModel] falls back to
     * [defaultModel]. Returns [Result.failure] with [UnsupportedModelException] when the
     * requested model has no registered provider.
     */
    suspend fun routeRequest(
        messages: List<ChatMessage>,
        requestedModel: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        val resolved = resolve(requestedModel)
            ?: return@withContext Result.failure(UnsupportedModelException(requestedModel))
        resolved.first.complete(messages, resolved.second)
    }

    /** Routes a streaming request. Same resolution rules as [routeRequest]. */
    suspend fun routeStream(
        messages: List<ChatMessage>,
        requestedModel: String?,
        onDelta: suspend (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val resolved = resolve(requestedModel)
            ?: return@withContext Result.failure(UnsupportedModelException(requestedModel))
        resolved.first.completeStream(messages, resolved.second, onDelta)
    }

    /**
     * Resolves the effective model id and its provider. Returns null when the model is
     * unsupported, letting callers surface a typed failure instead of throwing.
     */
    fun resolve(requestedModel: String?): Pair<ModelClient, String>? {
        val model = requestedModel?.trim()?.takeIf { it.isNotEmpty() } ?: defaultModel
        val client = clients[model] ?: return null
        return client to model
    }

    companion object {
        const val MODEL_DEEPSEEK_FLASH = "deepseek-flash"
        const val MODEL_DEEPSEEK_V4_PRO = "deepseek-v4-pro"
    }
}
