package com.nexus.agent.domain.hermes

/**
 * The AI engine powering a message. DeepSeek is the existing local engine; Hermes is the optional
 * autonomous agent-execution backend (Beta). Selection is persisted by id and defaulted to
 * DeepSeek, so an unknown/stale id can never leave the app engine-less.
 */
enum class AiEngine(val id: String) {
    DEEPSEEK("deepseek"),
    HERMES("hermes");

    companion object {
        fun fromId(id: String?): AiEngine = entries.firstOrNull { it.id == id } ?: DEEPSEEK
    }
}
