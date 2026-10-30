package com.nexus.agent.domain.persona

/** Coarse sentiment of a user message. */
enum class Sentiment { POSITIVE, NEGATIVE, NEUTRAL }

/**
 * Rule-based sentiment analysis (Arabic + English keywords). Ported from Mashaaer's
 * `analyze_emotion` in attached_assets/emotion_detector.py. Ties are resolved to NEUTRAL.
 */
object SentimentAnalyzer {

    private val positiveWords = listOf(
        "happy", "love", "great", "thank", "awesome", "good",
        "فرح", "سعيد", "جميل", "ممتاز", "حلو", "رائع", "أحب"
    )

    private val negativeWords = listOf(
        "sad", "bad", "angry", "hate", "terrible", "upset", "tired",
        "حزين", "تعبان", "زعلان", "مقهور", "غاضب", "متضايق", "أكره"
    )

    fun analyze(text: String): Sentiment {
        val t = text.lowercase()
        val positive = positiveWords.any { t.contains(it) }
        val negative = negativeWords.any { t.contains(it) }
        return when {
            negative && !positive -> Sentiment.NEGATIVE
            positive && !negative -> Sentiment.POSITIVE
            else -> Sentiment.NEUTRAL
        }
    }
}
