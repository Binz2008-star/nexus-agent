package com.nexus.agent.domain.memory

/**
 * Detects an explicit "remember this" instruction and extracts the fact to store.
 * Pure and unit-testable. Returns null when the message is not a remember command.
 */
object RememberCommandDetector {

    data class Remember(val fact: String)

    // "تذكر أن ...", "احفظ ...", "سجّل ..." (and optional "هذا"/colon after the verb).
    private val AR = Regex(
        "^\\s*(?:تذكر|تذكّر|احفظ|سجّل|خزّن|دوّن)\\s+(?:أن\\s+|هذا\\s*[:：]?\\s*)?(.+?)\\s*$",
        RegexOption.IGNORE_CASE
    )

    // "remember that ...", "remember this: ..."
    private val EN = Regex(
        "^\\s*remember\\s+(?:that\\s+|this\\s*[:：]?\\s*)?(.+?)\\s*$",
        RegexOption.IGNORE_CASE
    )

    fun detect(text: String): Remember? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        AR.find(trimmed)?.let { match ->
            val fact = match.groupValues[1].trim()
            if (fact.isNotEmpty()) return Remember(fact)
        }
        EN.find(trimmed)?.let { match ->
            val fact = match.groupValues[1].trim()
            if (fact.isNotEmpty()) return Remember(fact)
        }
        return null
    }
}
