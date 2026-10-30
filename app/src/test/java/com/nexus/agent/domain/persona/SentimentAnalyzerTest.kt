package com.nexus.agent.domain.persona

import org.junit.Assert.assertEquals
import org.junit.Test

class SentimentAnalyzerTest {

    @Test
    fun positiveArabic() {
        assertEquals(Sentiment.POSITIVE, SentimentAnalyzer.analyze("أنا سعيد اليوم"))
    }

    @Test
    fun positiveEnglish() {
        assertEquals(Sentiment.POSITIVE, SentimentAnalyzer.analyze("I love this, thank you!"))
    }

    @Test
    fun negativeArabic() {
        assertEquals(Sentiment.NEGATIVE, SentimentAnalyzer.analyze("أنا حزين ومتضايق"))
    }

    @Test
    fun negativeEnglish() {
        assertEquals(Sentiment.NEGATIVE, SentimentAnalyzer.analyze("I am so angry and tired"))
    }

    @Test
    fun neutralWhenNoKeywords() {
        assertEquals(Sentiment.NEUTRAL, SentimentAnalyzer.analyze("ما عاصمة فرنسا؟"))
    }

    @Test
    fun tieResolvesToNeutral() {
        assertEquals(Sentiment.NEUTRAL, SentimentAnalyzer.analyze("I love it but I am sad"))
    }
}
