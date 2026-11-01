package com.nexus.agent.domain.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MashaaerEmotionLayerTest {
    @Test fun arabicMixedWithContrast() {
        val signal = MashaaerEmotionLayer.analyze("أنا متحمس للمشروع لكن قلق من النتيجة")
        assertTrue(signal.cues.contains(EmotionCue.JOY))
        assertTrue(signal.cues.contains(EmotionCue.ANXIETY))
        assertTrue(signal.isMixed)
        assertTrue(signal.hasContrast)
    }

    @Test fun englishMixedWithContrast() {
        val signal = MashaaerEmotionLayer.analyze("I am excited but nervous")
        assertEquals(2, signal.cues.size)
        assertTrue(signal.hasContrast)
    }

    @Test fun oneEmotionIsNotMixed() {
        val signal = MashaaerEmotionLayer.analyze("أنا حزين اليوم")
        assertEquals(listOf(EmotionCue.SADNESS), signal.cues)
        assertFalse(signal.isMixed)
    }

    @Test fun emptyAndUnrelatedTextAreNeutral() {
        assertTrue(MashaaerEmotionLayer.analyze("").cues.isEmpty())
        assertTrue(MashaaerEmotionLayer.analyze("ما عاصمة فرنسا؟").cues.isEmpty())
    }

    @Test fun negatedFeelingNotAsserted() {
        assertFalse(MashaaerEmotionLayer.analyze("أنا مش قلق").cues.contains(EmotionCue.ANXIETY))
        assertFalse(MashaaerEmotionLayer.analyze("I am not sad").cues.contains(EmotionCue.SADNESS))
    }

    @Test fun noSubstringFalsePositive() {
        assertTrue(MashaaerEmotionLayer.analyze("سعيدان").cues.isEmpty())
    }

    @Test fun hintContainsNoRawUserMessageOrAuthority() {
        val raw = "أنا سعيد لكن خايف. secret-token-123"
        val hint = MashaaerEmotionLayer.styleHint(MashaaerEmotionLayer.analyze(raw))!!
        assertFalse(hint.contains("secret-token-123"))
        assertTrue(hint.contains("NOT a diagnosis"))
        assertTrue(hint.contains("approval"))
    }

    @Test fun noCuesMeansNoHint() {
        assertEquals(null, MashaaerEmotionLayer.styleHint(MashaaerEmotionLayer.analyze("مرحبا")))
    }
}
