package com.nexus.agent.domain.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveToneTest {

    @Test
    fun dialectFromId() {
        assertEquals(Dialect.EGYPT, Dialect.fromId("egypt"))
        assertEquals(Dialect.LEVANT, Dialect.fromId("levant"))
        assertEquals(Dialect.EN_US, Dialect.fromId("en-us"))
        assertEquals(Dialect.GULF, Dialect.fromId(null))
        assertEquals(Dialect.GULF, Dialect.fromId("unknown"))
    }

    @Test
    fun toneFromId() {
        assertEquals(Tone.FRIENDLY, Tone.fromId("friendly"))
        assertEquals(Tone.PROFESSIONAL, Tone.fromId("professional"))
        assertEquals(Tone.CALM, Tone.fromId(null))
        assertEquals(Tone.CALM, Tone.fromId("bogus"))
    }

    @Test
    fun buildRewriteInstructionContainsDialectAndTone() {
        val instruction = AdaptiveTone.buildRewriteInstruction(Dialect.EGYPT, Tone.FRIENDLY)
        assertTrue(instruction.contains(Dialect.EGYPT.arabicLabel))
        assertTrue(instruction.contains(Tone.FRIENDLY.arabicLabel))
    }

    @Test
    fun buildRewriteInstructionEnglish() {
        val instruction = AdaptiveTone.buildRewriteInstructionEnglish(Dialect.LEVANT, Tone.CALM)
        assertTrue(instruction.contains("levant"))
        assertTrue(instruction.contains("calm"))
    }
}
