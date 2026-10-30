package com.nexus.agent.domain.wellness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreathingCompanionTest {

    @Test
    fun boxPatternHasFourPhases() {
        val guide = BreathingCompanion.buildGuide(BreathingPattern.BOX, "en")
        assertEquals(4, guide.phases.size)
        assertEquals(PhaseType.INHALE, guide.phases[0].type)
        assertEquals(PhaseType.EXHALE, guide.phases[2].type)
    }

    @Test
    fun boxCycleIs16Seconds() {
        val guide = BreathingCompanion.buildGuide(BreathingPattern.BOX)
        assertEquals(16, guide.cycleSeconds)
    }

    @Test
    fun relaxingCycleIs19Seconds() {
        val guide = BreathingCompanion.buildGuide(BreathingPattern.RELAXING)
        assertEquals(19, guide.cycleSeconds) // 4 + 7 + 8
    }

    @Test
    fun arabicInstructionsUsedForAr() {
        val guide = BreathingCompanion.buildGuide(BreathingPattern.CALM, "ar")
        assertEquals("ابدأ التنفس العميق. استرخِ وركز على أنفاسك.", guide.startInstruction)
        assertTrue(guide.phases[0].instruction.contains("شهيق"))
    }

    @Test
    fun englishInstructionsUsedForEn() {
        val guide = BreathingCompanion.buildGuide(BreathingPattern.ENERGIZING, "en")
        assertTrue(guide.startInstruction.contains("Begin deep breathing"))
        assertTrue(guide.phases[0].instruction.contains("Inhale"))
    }

    @Test
    fun unknownLanguageFallsBackToArabic() {
        val guide = BreathingCompanion.buildGuide(BreathingPattern.BOX, "fr")
        assertEquals("ابدأ التنفس العميق. استرخِ وركز على أنفاسك.", guide.startInstruction)
    }

    @Test
    fun patternFromId() {
        assertEquals(BreathingPattern.BOX, BreathingPattern.fromId("box"))
        assertEquals(BreathingPattern.ENERGIZING, BreathingPattern.fromId("energizing"))
        assertEquals(BreathingPattern.BOX, BreathingPattern.fromId(null))
        assertEquals(BreathingPattern.BOX, BreathingPattern.fromId("bogus"))
    }
}
