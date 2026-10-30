package com.nexus.agent.domain.wellness

/** Guided breathing patterns. Ported from Mashaaer's `breathing_service.py`. */
enum class BreathingPattern(val id: String) {
    BOX("box"),
    RELAXING("relaxing"),
    CALM("calm"),
    ENERGIZING("energizing");

    companion object {
        fun fromId(id: String?): BreathingPattern = entries.firstOrNull { it.id == id } ?: BOX
    }
}

enum class PhaseType { INHALE, HOLD, EXHALE }

data class BreathingPhase(
    val type: PhaseType,
    val durationSeconds: Int,
    val instruction: String
)

/** A complete guided-breathing script (start message + timed phases + completion). */
data class BreathingGuide(
    val pattern: BreathingPattern,
    val startInstruction: String,
    val completeInstruction: String,
    val phases: List<BreathingPhase>
) {
    /** Total length of one full cycle, in seconds. */
    val cycleSeconds: Int get() = phases.sumOf { it.durationSeconds }
}

/**
 * Builds a guided-breathing script (text-based; the TTS layer is a separate concern).
 * Ported from Mashaaer's BreathingCompanionService DEFAULT_INSTRUCTIONS + breathing patterns.
 */
object BreathingCompanion {

    private val patternDurations: Map<BreathingPattern, List<Pair<PhaseType, Int>>> = mapOf(
        BreathingPattern.BOX to listOf(PhaseType.INHALE to 4, PhaseType.HOLD to 4, PhaseType.EXHALE to 4, PhaseType.HOLD to 4),
        BreathingPattern.RELAXING to listOf(PhaseType.INHALE to 4, PhaseType.HOLD to 7, PhaseType.EXHALE to 8),
        BreathingPattern.CALM to listOf(PhaseType.INHALE to 5, PhaseType.HOLD to 2, PhaseType.EXHALE to 5),
        BreathingPattern.ENERGIZING to listOf(PhaseType.INHALE to 6, PhaseType.EXHALE to 2)
    )

    private val instructions: Map<String, Map<String, String>> = mapOf(
        "ar" to mapOf(
            "start" to "ابدأ التنفس العميق. استرخِ وركز على أنفاسك.",
            "inhale" to "خذ نفساً عميقاً... شهيق...",
            "hold" to "امسك نفسك... احبس...",
            "exhale" to "أخرج النفس ببطء... زفير...",
            "complete" to "أحسنت! لقد أكملت تمرين التنفس الواعي. استمتع بالهدوء الذي يغمر جسدك وعقلك."
        ),
        "en" to mapOf(
            "start" to "Begin deep breathing. Relax and focus on your breath.",
            "inhale" to "Take a deep breath in... Inhale...",
            "hold" to "Hold your breath... Pause...",
            "exhale" to "Slowly let your breath out... Exhale...",
            "complete" to "Well done! You have completed the mindful breathing exercise. Enjoy the calm spreading through your body and mind."
        )
    )

    fun buildGuide(pattern: BreathingPattern, language: String = "ar"): BreathingGuide {
        val lang = if (instructions.containsKey(language)) language else "ar"
        val langInstructions = instructions.getValue(lang)

        val phases = patternDurations.getValue(pattern).map { (type, duration) ->
            val key = when (type) {
                PhaseType.INHALE -> "inhale"
                PhaseType.HOLD -> "hold"
                PhaseType.EXHALE -> "exhale"
            }
            BreathingPhase(type, duration, langInstructions.getValue(key))
        }

        return BreathingGuide(
            pattern = pattern,
            startInstruction = langInstructions.getValue("start"),
            completeInstruction = langInstructions.getValue("complete"),
            phases = phases
        )
    }
}
