package com.nexus.agent.domain.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftPersonaPolicyTest {

    @Test
    fun detectsEnableCommands() {
        assertEquals(SoftPersonaPolicy.Command.Enable, SoftPersonaPolicy.detectCommand("الوضع الحنون تفعيل"))
        assertEquals(SoftPersonaPolicy.Command.Enable, SoftPersonaPolicy.detectCommand("فعّل الوضع الناعم"))
        assertEquals(SoftPersonaPolicy.Command.Enable, SoftPersonaPolicy.detectCommand("enable soft mode"))
    }

    @Test
    fun detectsDisableCommands() {
        assertEquals(SoftPersonaPolicy.Command.Disable, SoftPersonaPolicy.detectCommand("الوضع الحنون تعطيل"))
        assertEquals(SoftPersonaPolicy.Command.Disable, SoftPersonaPolicy.detectCommand("أوقف الوضع الناعم"))
        assertEquals(SoftPersonaPolicy.Command.Disable, SoftPersonaPolicy.detectCommand("disable soft mode"))
    }

    @Test
    fun normalizesCaseAndExtraWhitespace() {
        assertEquals(SoftPersonaPolicy.Command.Enable, SoftPersonaPolicy.detectCommand("Enable  Soft Mode"))
        assertEquals(SoftPersonaPolicy.Command.Disable, SoftPersonaPolicy.detectCommand("أوقف   الوضع الناعم"))
    }

    @Test
    fun nonCommandsReturnNull() {
        assertNull(SoftPersonaPolicy.detectCommand("hello"))
        assertNull(SoftPersonaPolicy.detectCommand("كيف حالك اليوم"))
        assertNull(SoftPersonaPolicy.detectCommand(""))
    }

    @Test
    fun styleInstructionUsesConstantVocabularyWithoutSafetyOverrides() {
        val inst = SoftPersonaPolicy.buildStyleInstruction(Dialect.GULF, Tone.CALM)
        assertTrue(inst.contains("حنون"))
        assertTrue(inst.contains("لا تغيّر أبدًا الحقائق"))
        // No user text placeholder, no tool/permission overrides.
        assertTrue(!inst.contains("%s"))
    }

    @Test
    fun englishStyleInstructionKeepsDialectAndTone() {
        val inst = SoftPersonaPolicy.buildStyleInstructionEnglish(Dialect.EGYPT, Tone.FRIENDLY)
        assertTrue(inst.contains("gentle"))
        assertTrue(inst.contains("egypt"))
        assertTrue(inst.contains("friendly"))
    }
}
