package com.nexus.agent.domain.hermes

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Append-only, JSON-lines audit log for Hermes connector activity.
 *
 * Safety contract (Phase 4):
 *  - only whitelisted, non-secret fields are ever written ([ALLOWED_FIELDS]);
 *  - an injected [redactor] scrubs any known secret value as a last line of defense;
 *  - writes are best-effort and synchronized — a failed write never crashes the chat flow;
 *  - request bodies, tool arguments, key material, and full message content are never logged.
 */
class HermesAuditLog(
    private val logFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val redactor: (String) -> String = { it }
) {

    @Synchronized
    fun append(event: String, runId: String? = null, extra: Map<String, String> = emptyMap()) {
        val line = buildJsonObject {
            put("ts", JsonPrimitive(clock()))
            put("event", JsonPrimitive(sanitize(event)))
            if (!runId.isNullOrBlank()) put("run_id", JsonPrimitive(sanitize(runId)))
            extra.filterKeys { it in ALLOWED_FIELDS }.forEach { (k, v) ->
                put(k, JsonPrimitive(sanitize(v)))
            }
        }.toString()
        try {
            logFile.parentFile?.mkdirs()
            logFile.appendText(line + "\n")
        } catch (_: Exception) {
            // Best-effort: audit failure must never break the user flow.
        }
    }

    private fun sanitize(value: String): String = redactor(value)

    companion object {
        /** The only `extra` keys the log will persist. Anything else is dropped. */
        val ALLOWED_FIELDS = setOf("tool", "status", "error_code", "reason", "endpoint")
    }
}
