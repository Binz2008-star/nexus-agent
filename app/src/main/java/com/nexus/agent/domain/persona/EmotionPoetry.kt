package com.nexus.agent.domain.persona

/**
 * Emotional states used for creative generation. Ported from Mashaaer's
 * `emotion_poetry_generator.py` emotion map.
 */
enum class Emotion(val arabic: String, val english: String) {
    JOY("فرح", "joy"),
    SADNESS("حزن", "sadness"),
    ANGER("غضب", "anger"),
    SURPRISE("دهشة", "surprise"),
    FEAR("خوف", "fear"),
    NEUTRAL("حياد", "neutral");

    companion object {
        fun fromArabic(arabic: String): Emotion? = entries.firstOrNull { it.arabic == arabic }
    }
}

enum class PoetryStyle(val id: String) {
    CLASSICAL("classical"),
    MODERN("modern"),
    FREE("free");

    companion object {
        fun fromId(id: String?): PoetryStyle = entries.firstOrNull { it.id == id } ?: MODERN
    }
}

enum class PoetryLength(val id: String) {
    SHORT("short"),
    MEDIUM("medium"),
    LONG("long");

    companion object {
        fun fromId(id: String?): PoetryLength = entries.firstOrNull { it.id == id } ?: MEDIUM
    }
}

/**
 * Builds the prompt that asks the model to generate emotion-themed poetry. Ported from
 * Mashaaer's `generate_emotion_poetry` (emotion/style/length/cosmic-imagery/region/theme).
 */
object EmotionPoetry {

    fun buildPoetryPrompt(
        emotion: Emotion,
        style: PoetryStyle = PoetryStyle.MODERN,
        length: PoetryLength = PoetryLength.MEDIUM,
        includeCosmicImagery: Boolean = true,
        region: String? = null,
        theme: String? = null
    ): String = buildString {
        append("اكتب قصيدة تعبّر عن مشاعر: ${emotion.arabic} (${emotion.english}). ")
        append("النمط الشعري: ${style.id}. الطول: ${length.id}. ")
        if (includeCosmicImagery) {
            append("قم بتضمين صور كونية (النجوم، المجرات، الأفلاك، السماء) تتناسب مع المشاعر. ")
        }
        if (!region.isNullOrBlank()) append("استخدم سياقًا ثقافيًا لمنطقة $region. ")
        if (!theme.isNullOrBlank()) append("الموضوع: $theme.")
    }
}
