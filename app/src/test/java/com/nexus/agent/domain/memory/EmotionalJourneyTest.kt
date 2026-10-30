package com.nexus.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionalJourneyTest {

    @Test
    fun addMilestoneAppendsToJourney() {
        val journey = EmotionalJourney()
            .addMilestone("فرح", intensity = 0.8, title = "بداية سعيدة")
            .addMilestone("حزن", intensity = 0.5)
        assertEquals(2, journey.milestones.size)
        assertEquals("فرح", journey.milestones[0].emotion)
        assertEquals(0.8, journey.milestones[0].intensity, 0.0)
    }

    @Test
    fun dominantEmotionReturnsMostFrequent() {
        val journey = EmotionalJourney()
            .addMilestone("فرح")
            .addMilestone("فرح")
            .addMilestone("حزن")
        assertEquals("فرح", journey.dominantEmotion())
    }

    @Test
    fun dominantEmotionDefaultsToNeutralWhenEmpty() {
        assertEquals("neutral", EmotionalJourney().dominantEmotion())
    }

    @Test
    fun completeDeactivatesJourney() {
        val journey = EmotionalJourney().complete()
        assertFalse(journey.isActive)
    }

    @Test
    fun defaultVisualizationIsCosmic() {
        val journey = EmotionalJourney()
        assertEquals("cosmic", journey.visualization.theme)
        assertEquals("vibrant", journey.visualization.colorScheme)
        assertEquals(0.7, journey.visualization.particleDensity, 0.0)
    }

    @Test
    fun emotionColorMapsEmotions() {
        assertEquals("#FFD700", emotionColor("فرح"))
        assertEquals("#1E90FF", emotionColor("حزن"))
        assertEquals("#FF4500", emotionColor("غضب"))
        assertEquals("#A9A9A9", emotionColor("غير معروف"))
    }
}
