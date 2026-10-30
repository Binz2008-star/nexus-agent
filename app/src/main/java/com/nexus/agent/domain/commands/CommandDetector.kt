package com.nexus.agent.domain.commands

import com.nexus.agent.domain.persona.Emotion

/** Interactive commands triggered by simple keywords. */
enum class Command { Poetry, Breathing }

/**
 * Detects user commands (poetry / breathing) and the requested poetry emotion. Extracted from
 * the ViewModel so it is unit-testable — the "شعر" false-positive (matching "أشعر") is guarded
 * here by a Unicode word boundary.
 */
object CommandDetector {

    // "شعر" (poetry) via word boundary so it doesn't match "أشعر" (I feel) / "يشعر".
    // The optional "ا" handles the accusative/tanwin form "شعراً" (alef suffix).
    private val POETRY_WORD = Regex("(?<![\\p{L}])شعر(?:ا)?(?![\\p{L}])")

    fun detect(text: String): Command? {
        val t = text.trim().lowercase()
        return when {
            t.contains("قصيدة") || POETRY_WORD.containsMatchIn(t) || t.contains("poem") || t.contains("poetry") -> Command.Poetry
            t.contains("تنفس") || t.contains("تنفّس") || t.contains("breathe") || t.contains("breathing") -> Command.Breathing
            else -> null
        }
    }

    fun poetryEmotion(text: String): Emotion = when {
        text.contains("حزن") || text.contains("sad") -> Emotion.SADNESS
        text.contains("فرح") || text.contains("سعا") || text.contains("happy") || text.contains("joy") -> Emotion.JOY
        text.contains("غضب") || text.contains("angry") || text.contains("anger") -> Emotion.ANGER
        text.contains("خوف") || text.contains("fear") -> Emotion.FEAR
        text.contains("دهشة") || text.contains("surprise") -> Emotion.SURPRISE
        else -> Emotion.NEUTRAL
    }
}
