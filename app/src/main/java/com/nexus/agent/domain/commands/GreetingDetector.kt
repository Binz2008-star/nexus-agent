package com.nexus.agent.domain.commands

/**
 * Detects casual conversational messages — greetings and small talk — that must be answered
 * directly and concisely, WITHOUT web/knowledge grounding (which otherwise turns "hi" into a
 * dictionary-style explanation).
 *
 * Requests for explanation ("what does HI mean", "اشرح لي…") are NOT conversational: they still
 * go through the grounded path so they get a real answer. Pure + unit-testable.
 */
object GreetingDetector {

    private val GREETINGS = listOf(
        "hi", "hello", "hey", "howdy", "yo", "hola", "hiya", "heya",
        "مرحبا", "مرحباً", "أهلا", "أهلاً", "اهلا", "هلا", "أهلين",
        "السلام عليكم", "سلام", "وعليكم السلام",
        "صباح الخير", "صباح النور", "مساء الخير", "مساء النور"
    )

    private val SMALL_TALK = listOf(
        "كيف حالك", "كيف حالكم", "شلونك", "شخبارك", "كيفك", "عامل ايه", "ازيك", "أخبارك",
        "how are you", "how r u", "how's it going", "hows it going",
        "what's up", "whats up", "wassup", "how are things"
    )

    private val EXPLANATION_MARKERS = listOf(
        "explain", "mean", "meaning", "define", "definition",
        "معنى", "تعني", "شرح", "اشرح", "عرف", "وش يعني", "وش معنى", "ما معنى"
    )

    fun isConversational(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LEN) return false
        val t = trimmed.lowercase()
        if (EXPLANATION_MARKERS.any { t.contains(it) }) return false
        val core = t.trimEnd('!', '؟', '?', '.', '،', ',')
        return GREETINGS.any { core == it || core.startsWith("$it ") } ||
            SMALL_TALK.any { core == it || core.startsWith("$it ") }
    }

    private const val MAX_LEN = 50
}
