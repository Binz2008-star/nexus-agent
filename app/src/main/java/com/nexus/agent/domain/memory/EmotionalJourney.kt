package com.nexus.agent.domain.memory

/** A single emotional milestone in a journey. Ported from Mashaaer's EmotionMilestone. */
data class EmotionMilestone(
    val emotion: String,
    val intensity: Double = 1.0,
    val title: String = "",
    val note: String = "",
    val timestampMillis: Long = 0L,
    val isSignificant: Boolean = false
)

/** Visualization settings for the cosmic journey. Ported from create_journey defaults. */
data class VisualizationSettings(
    val theme: String = "cosmic",
    val colorScheme: String = "vibrant",
    val particleDensity: Double = 0.7
)

/**
 * A user's emotional journey: a sequence of milestones over time. Ported from Mashaaer's
 * EmotionalJourneyService (add_milestone + dominant emotion + visualization color).
 */
data class EmotionalJourney(
    val title: String = "رحلتي العاطفية",
    val description: String = "",
    val milestones: List<EmotionMilestone> = emptyList(),
    val isActive: Boolean = true,
    val visualization: VisualizationSettings = VisualizationSettings()
) {
    fun addMilestone(
        emotion: String,
        intensity: Double = 1.0,
        title: String = "",
        note: String = "",
        timestampMillis: Long = 0L,
        isSignificant: Boolean = false
    ): EmotionalJourney = copy(
        milestones = milestones + EmotionMilestone(emotion, intensity, title, note, timestampMillis, isSignificant)
    )

    fun complete(): EmotionalJourney = copy(isActive = false)

    /** Most frequent emotion across milestones, or "neutral" when empty. */
    fun dominantEmotion(): String {
        val emotions = milestones.map { it.emotion }
        if (emotions.isEmpty()) return "neutral"
        return emotions.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "neutral"
    }
}

/** Maps an Arabic emotion name to its visualization color. Ported from emotion_colors. */
fun emotionColor(emotion: String): String = when (emotion) {
    "فرح" -> "#FFD700"
    "حزن" -> "#1E90FF"
    "غضب" -> "#FF4500"
    "خوف" -> "#9932CC"
    "دهشة" -> "#00FA9A"
    "حياد" -> "#A9A9A9"
    else -> "#A9A9A9"
}
