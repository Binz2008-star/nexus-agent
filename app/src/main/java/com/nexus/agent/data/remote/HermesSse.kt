package com.nexus.agent.data.remote

/**
 * Minimal, line-oriented Server-Sent Events (SSE) parser. Pure and unit-testable: it consumes
 * raw UTF-8 lines (exactly as read from an OkHttp response body) and emits complete
 * [SseEvent]s at each blank-line dispatch boundary, per the SSE wire format.
 *
 * Handles the Hermes-specific conventions documented for `/v1/runs/{id}/events`:
 *  - `: keepalive` comment lines (sent every ~10s) are ignored;
 *  - `event:` sets the event type, `data:` accumulates (multi-line data joined with `\n`);
 *  - other fields (`id:`, `retry:`) are ignored.
 */
data class SseEvent(
    val type: String,
    val data: String
)

class HermesSseParser {

    private var eventType: String = DEFAULT_TYPE
    private val dataLines = mutableListOf<String>()

    /**
     * Feed one line (without the trailing `\n`). Returns a complete [SseEvent] when a dispatch
     * boundary is reached, or null otherwise. Lines may carry a trailing `\r` (CRLF); it is
     * stripped defensively.
     */
    fun feed(line: String): SseEvent? {
        val t = line.trimEnd('\r')
        return when {
            t.isEmpty() -> {
                val type = eventType
                val data = dataLines.joinToString("\n")
                reset()
                // A blank line with no `event:` and no `data:` is a separator/keepalive — ignore.
                if (data.isEmpty() && type == DEFAULT_TYPE) null else SseEvent(type, data)
            }
            t.startsWith(":") -> null
            t.startsWith("event:") -> {
                eventType = t.removePrefix("event:").trim()
                null
            }
            t.startsWith("data:") -> {
                dataLines += t.removePrefix("data:").trimStart()
                null
            }
            else -> null
        }
    }

    private fun reset() {
        eventType = DEFAULT_TYPE
        dataLines.clear()
    }

    companion object {
        const val DEFAULT_TYPE = "message"
    }
}
