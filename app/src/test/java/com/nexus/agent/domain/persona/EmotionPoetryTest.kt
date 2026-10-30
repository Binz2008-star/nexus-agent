package com.nexus.agent.domain.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionPoetryTest {

    @Test
    fun emotionFromArabic() {
        assertEquals(Emotion.JOY, Emotion.fromArabic("فرح"))
        assertEquals(Emotion.SADNESS, Emotion.fromArabic("حزن"))
        assertNull(Emotion.fromArabic("غير معروف"))
    }

    @Test
    fun poetryStyleAndLengthFromId() {
        assertEquals(PoetryStyle.CLASSICAL, PoetryStyle.fromId("classical"))
        assertEquals(PoetryStyle.MODERN, PoetryStyle.fromId(null))
        assertEquals(PoetryLength.LONG, PoetryLength.fromId("long"))
        assertEquals(PoetryLength.MEDIUM, PoetryLength.fromId("bogus"))
    }

    @Test
    fun buildPoetryPromptIncludesEmotionStyleAndLength() {
        val prompt = EmotionPoetry.buildPoetryPrompt(
            emotion = Emotion.JOY,
            style = PoetryStyle.CLASSICAL,
            length = PoetryLength.LONG
        )
        assertTrue(prompt.contains("فرح"))
        assertTrue(prompt.contains("joy"))
        assertTrue(prompt.contains("classical"))
        assertTrue(prompt.contains("long"))
    }

    @Test
    fun buildPoetryPromptCosmicAndRegionAndTheme() {
        val prompt = EmotionPoetry.buildPoetryPrompt(
            emotion = Emotion.SADNESS,
            includeCosmicImagery = true,
            region = "gulf",
            theme = "الفراق"
        )
        assertTrue(prompt.contains("صور كونية"))
        assertTrue(prompt.contains("gulf"))
        assertTrue(prompt.contains("الفراق"))
    }

    @Test
    fun buildPoetryPromptNoCosmicImagery() {
        val prompt = EmotionPoetry.buildPoetryPrompt(
            emotion = Emotion.NEUTRAL,
            includeCosmicImagery = false
        )
        assertTrue(!prompt.contains("صور كونية"))
    }
}
