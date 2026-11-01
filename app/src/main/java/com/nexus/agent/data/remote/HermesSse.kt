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
    private var dataChars: Int = 0

    /**
     * Feed one line (without the trailing `\n`). Returns a complete [SseEvent] when a dispatch
     * boundary is reached, or null otherwise. Lines may carry a trailing `\r` (CRLF); it is
     * stripped defensively.
     */
    fun feed(line: String): SseEvent? {
        require(line.length <= MAX_LINE_CHARS) { "hermes_sse_line_too_large" }
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
                val nextType = t.removePrefix("event:").trim()
                require(nextType.length <= MAX_EVENT_TYPE_CHARS) {
                    "hermes_sse_event_type_too_large"
                }
                eventType = nextType
                null
            }
            t.startsWith("data:") -> {
                val nextData = t.removePrefix("data:").trimStart()
                val nextChars = dataChars + nextData.length + if (dataLines.isEmpty()) 0 else 1
                require(dataLines.size < MAX_DATA_LINES && nextChars <= MAX_EVENT_CHARS) {
                    "hermes_sse_event_too_large"
                }
                dataChars = nextChars
                dataLines += nextData
                null
            }
            else -> null
        }
    }

    private fun reset() {
        eventType = DEFAULT_TYPE
        dataLines.clear()
        dataChars = 0
    }

    companion object {
        const val DEFAULT_TYPE = "message"
        const val MAX_LINE_CHARS = 65_536
        const val MAX_EVENT_CHARS = 262_144
        private const val MAX_EVENT_TYPE_CHARS = 128
        private const val MAX_DATA_LINES = 256
    }
}
