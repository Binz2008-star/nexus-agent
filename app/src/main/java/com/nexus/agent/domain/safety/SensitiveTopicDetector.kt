package com.nexus.agent.domain.safety

/**
 * Detects sensitive topics (religion, provocation, personal questions) that get a curated,
 * culturally-adapted neutral reply rather than a hard block. Ported from Mashaaer's
 * `intent_filter.py` PROHIBITED_KEYWORDS, using Unicode word boundaries so "دين" does not
 * match inside "مدينة".
 */
object SensitiveTopicDetector {

    private val patterns: Map<NeutralCategory, Regex> = buildMap {
        put(
            NeutralCategory.RELIGION,
            buildKeywordRegex(
                listOf("دين", "طائفة", "مذهب", "إله", "الله", "نبي", "محمد", "عيسى",
                    "religion", "god", "إسلام", "مسيحية", "يهودية")
            )
        )
        put(
            NeutralCategory.PROVOCATION,
            buildKeywordRegex(
                listOf("غبي", "احمق", "stupid", "idiot", "أكرهك", "hate you", "أنت سيء", "you are bad")
            )
        )
        put(
            NeutralCategory.PERSONAL_QUESTION,
            buildKeywordRegex(
                listOf("عمرك", "تسكن", "عنوانك", "زوجتك", "أطفالك", "your age", "where do you live", "are you married")
            )
        )
    }

    /** Returns the first sensitive category matched by [text], or null when none match. */
    fun detect(text: String): NeutralCategory? {
        val normalized = text.trim().lowercase()
        for ((category, pattern) in patterns) {
            if (pattern.containsMatchIn(normalized)) return category
        }
        return null
    }

    private fun buildKeywordRegex(keywords: List<String>): Regex {
        val parts = keywords.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .map { keyword ->
                val escaped = Regex.escape(keyword)
                "(?<![\\p{L}\\p{N}_])(?:$escaped)(?![\\p{L}\\p{N}_])"
            }
            .distinct()
            .toList()
        return Regex(parts.joinToString("|"), RegexOption.IGNORE_CASE)
    }
}
