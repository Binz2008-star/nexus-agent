package com.nexus.agent.domain.orchestration

/** Task priority. Ported from Mashaaer's orchestrator_engine.py PriorityLevel. */
enum class PriorityLevel(val level: Int) {
    CRITICAL(0),
    HIGH(1),
    NORMAL(2),
    LOW(3),
    BACKGROUND(4)
}

/** Lifecycle events the system can emit. Ported from Mashaaer's OrchestrationEvent. */
enum class OrchestrationEvent(val id: String) {
    EMOTION_DETECTED("emotion_detected"),
    USER_INPUT_RECEIVED("user_input_received"),
    RESPONSE_GENERATED("response_generated"),
    AUDIO_SYNTHESIS_COMPLETED("audio_synthesis_completed"),
    CONTEXT_UPDATED("context_updated"),
    MOOD_SOUNDTRACK_CREATED("mood_soundtrack_created"),
    ERROR_OCCURRED("error_occurred"),
    SYSTEM_READY("system_ready"),
    SESSION_STARTED("session_started"),
    SESSION_ENDED("session_ended"),
    DIMENSION_BRIDGE_READY("dimension_bridge_ready");

    companion object {
        fun fromId(id: String?): OrchestrationEvent? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Context for a single request. Ported from Mashaaer's ExecutionContext — carries the
 * dialect/language/source that the persona and safety layers already use.
 */
data class ExecutionContext(
    val userId: String = "",
    val sessionId: String = "",
    val requestId: String = "",
    val dialect: String = "gulf",
    val language: String = "ar",
    val source: String = "api",
    val emotionData: Map<String, String> = emptyMap()
)
