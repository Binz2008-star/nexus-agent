package com.nexus.agent.domain.commands

/**
 * Explicit, model-independent Second Brain search intent.
 *
 * An empty return means the message is not a knowledge-search command; a blank
 * topic means the user asked to search without giving a search term.
 * This deliberately does not classify general questions as searches.
 */
object KnowledgeSearchIntent {
    private val patterns = listOf(
        Regex(
            "^search\\s+(?:in\\s+)?(?:(?:my|the)\\s+)?(?:knowledge\\s+base|second\\s+brain)(?:\\s+(?:for|about|on))?\\s*[:：-]?\\s*(.*)$",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "^(?:ابحث|أبحث)\\s+(?:لي\\s+)?(?:في\\s+)?(?:قاعدة\\s+المعرفة|الذاكرة\\s+الثانية|الدماغ\\s+الثاني)(?:\\s+(?:عن|حول|بخصوص))?\\s*[:：-]?\\s*(.*)$"
        )
    )

    fun topicOrNull(message: String): String? {
        val cleaned = message.trim()
        for (pattern in patterns) {
            val match = pattern.matchEntire(cleaned) ?: continue
            return match.groupValues[1].trim().trim('?', '؟', ':', '،').trim()
        }
        return null
    }
}
