package com.nexus.agent.data.remote

import com.nexus.agent.domain.hermes.HermesRunEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Maps raw Hermes run SSE events (and `pending_controls` from the status endpoint) into the
 * typed [HermesRunEvent] domain model. Pure and unit-testable; unknown payloads degrade to
 * [HermesRunEvent.Unknown] rather than throwing.
 */
object HermesRunEventMapper {

    private val json = Json { ignoreUnknownKeys = true }

    fun map(raw: SseEvent): HermesRunEvent = when (raw.type) {
        "message.delta" -> HermesRunEvent.Delta(obj(raw.data).str("text"))
        "message.interim" -> {
            val o = obj(raw.data)
            HermesRunEvent.Interim(o.str("text"), o.bool("already_streamed"))
        }
        "tool.started" -> {
            val o = obj(raw.data)
            HermesRunEvent.ToolStarted(o.str("tool"), o.strOrNull("preview"))
        }
        "tool.completed" -> {
            val o = obj(raw.data)
            HermesRunEvent.ToolCompleted(o.str("tool"), o.doubleOrNull("duration"), o.bool("error"), o.strOrNull("preview"))
        }
        "tool.failed" -> {
            val o = obj(raw.data)
            HermesRunEvent.ToolCompleted(o.str("tool"), o.doubleOrNull("duration"), true, o.strOrNull("preview") ?: o.strOrNull("error"))
        }
        "approval.request" -> approvalFromJson(obj(raw.data))
        "run.completed" -> HermesRunEvent.Completed(obj(raw.data).str("output"))
        "run.failed" -> HermesRunEvent.Failed(messageOf(obj(raw.data)))
        "run.cancelled" -> HermesRunEvent.Cancelled(messageOf(obj(raw.data)))
        "run.interrupted" -> HermesRunEvent.Interrupted(messageOf(obj(raw.data)))
        else -> HermesRunEvent.Unknown(raw.type, raw.data)
    }

    /** Converts a `pending_controls` entry (from `GET /v1/runs/{id}`) to an approval event. */
    fun fromPendingControl(control: HermesPendingControl): HermesRunEvent =
        approvalFromFields(
            requestId = control.identity,
            generation = control.executionGeneration,
            tool = control.tool,
            arguments = control.arguments,
            prompt = control.prompt
        )

    private fun approvalFromJson(o: JsonObject): HermesRunEvent =
        approvalFromFields(
            requestId = o.strOrNull("request_id") ?: o.strOrNull("prompt_id"),
            generation = o.intOrZero("execution_generation"),
            tool = o.strOrNull("tool"),
            arguments = o.strOrNull("arguments"),
            prompt = o.strOrNull("prompt")
        )

    private fun approvalFromFields(
        requestId: String?,
        generation: Int,
        tool: String?,
        arguments: String?,
        prompt: String?
    ): HermesRunEvent = HermesRunEvent.ApprovalRequested(
        requestId = requestId,
        executionGeneration = generation,
        tool = tool,
        arguments = arguments,
        prompt = prompt
    )

    private fun messageOf(o: JsonObject): String? =
        o.strOrNull("message") ?: o.strOrNull("error") ?: o.strOrNull("output")

    // ---- helpers ----

    private fun obj(data: String): JsonObject =
        try {
            json.parseToJsonElement(data).jsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""

    private fun JsonObject.strOrNull(key: String): String? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.intOrZero(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
}
