package com.nexus.agent.domain.persona

/**
 * Privacy-preserving, deterministic cues adapted from Mashaaer's mixed-emotion rules.
 *
 * This is NOT a mental-health classifier, a diagnosis, a user trait, or a safety gate.
 * It processes only the current message, stores nothing and does no network I/O.
 * Its output may inform response phrasing, never tools, permissions or approvals.
 */
enum class EmotionCue(val id: String) {
    JOY("joy"),
    SADNESS("sadness"),
    ANXIETY("anxiety"),
    ANGER("anger"),
    CALM("calm"),
    SURPRISE("surprise")
}

data class EmotionSignal(
    val cues: List<EmotionCue>,
    val isMixed: Boolean,
    val hasContrast: Boolean
)

object MashaaerEmotionLayer {
    private val diacritics = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
    private val negation = Regex("(?:^|\\s)(?:مش|مو|ليس|غير|not|never|no)\\s*$")
    private val contrast = Regex(
        "(?<![\\p{L}\\p{N}])(?:لكن|بس|ولكن|مع\\s+ذلك|but|yet|however|although)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE
    )
    private val terms: Map<EmotionCue, List<Regex>> = mapOf(
        EmotionCue.JOY to listOf(
            "سعيد", "مبسوط", "فرحان", "فرح", "متحمس", "متفائل",
            "happy", "glad", "excited", "thrilled", "hopeful", "joyful"
        ),
        EmotionCue.SADNESS to listOf(
            "حزين", "زعلان", "مهموم", "متضايق", "حزن", "مكسور",
            "sad", "grieving", "heartbroken", "unhappy", "disappointed"
        ),
        EmotionCue.ANXIETY to listOf(
            "قلق", "قلقان", "خايف", "خائف", "متوتر", "خوف", "خايفة",
            "anxious", "worried", "nervous", "afraid", "scared"
        ),
        EmotionCue.ANGER to listOf(
            "غاضب", "معصب", "معصبة", "مقهور", "منزعج", "مستاء",
            "angry", "furious", "frustrated", "annoyed"
        ),
        EmotionCue.CALM to listOf(
            "مرتاح", "هادئ", "هادي", "مطمئن", "مطمئنة",
            "calm", "relaxed", "peaceful"
        ),
        EmotionCue.SURPRISE to listOf(
            "مندهش", "متفاجئ", "مصدوم", "مذهول",
            "surprised", "shocked", "astonished"
        )
    ).mapValues { (_, words) ->
        words.map { term ->
            // Unicode boundaries prevent matching Arabic keywords inside longer words.
            // Common Arabic clitics are supported without broad substring matching.
            Regex(
                "(?<![\\p{L}\\p{N}])(?:ال|و|وال|بال)?${Regex.escape(normalize(term))}(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE
            )
        }
    }

    private fun normalize(input: String): String =
        input.lowercase()
            .replace(diacritics, "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')

    fun analyze(message: String): EmotionSignal {
        val text = normalize(message.take(4000))
        if (text.isBlank()) return EmotionSignal(emptyList(), false, false)
        val cues = terms.filterValues { patterns ->
            patterns.any { pattern ->
                pattern.findAll(text).any { match ->
                    val before = text.substring((match.range.first - 22).coerceAtLeast(0), match.range.first)
                    !negation.containsMatchIn(before)
                }
            }
        }.keys.toList()
        return EmotionSignal(
            cues = cues,
            isMixed = cues.size > 1,
            hasContrast = cues.size > 1 && contrast.containsMatchIn(text)
        )
    }

    /**
     * Constant-vocabulary hint only: user-supplied text is never copied into SYSTEM messages.
     * Never used to authorize, select, or run tools.
     */
    fun styleHint(signal: EmotionSignal): String? {
        if (signal.cues.isEmpty()) return null
        val cues = signal.cues.joinToString(", ") { it.id }
        val relationship = when {
            signal.hasContrast -> "contrasting cues"
            signal.isMixed -> "multiple coexisting cues"
            else -> "a possible cue"
        }
        return "Optional Mashaaer communication hint: the latest user message contains " +
            "$relationship ($cues). These are uncertain textual signals, NOT a diagnosis. " +
            "Respond naturally and with appropriate sensitivity; do not claim to know the " +
            "user's emotional state. Keep the user's chosen language and dialect. " +
            "Never change factual conclusions, tool selection, tool arguments, approval " +
            "requirements, permissions or safety rules because of this hint."
    }
}
