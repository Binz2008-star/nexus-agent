package com.nexus.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RememberCommandDetectorTest {

    @Test
    fun detectsArabicRememberThat() {
        assertEquals("أحب القهوة", RememberCommandDetector.detect("تذكر أن أحب القهوة")?.fact)
    }

    @Test
    fun detectsArabicSave() {
        assertEquals("اسمي أحمد", RememberCommandDetector.detect("احفظ اسمي أحمد")?.fact)
    }

    @Test
    fun detectsArabicColonForm() {
        assertEquals("اللغة العربية", RememberCommandDetector.detect("سجّل هذا: اللغة العربية")?.fact)
    }

    @Test
    fun detectsEnglishRemember() {
        assertEquals("my birthday is May 1", RememberCommandDetector.detect("remember my birthday is May 1")?.fact)
    }

    @Test
    fun detectsEnglishRememberThat() {
        assertEquals("I like dark mode", RememberCommandDetector.detect("remember that I like dark mode")?.fact)
    }

    @Test
    fun ignoresNormalMessages() {
        assertNull(RememberCommandDetector.detect("ما هو الطقس اليوم؟"))
        assertNull(RememberCommandDetector.detect("what is the weather"))
        assertNull(RememberCommandDetector.detect(""))
    }

    @Test
    fun doesNotTreatRememberInsideSentenceAsCommand() {
        // "remember" is not at the start, so it is not an instruction.
        assertNull(RememberCommandDetector.detect("do you remember my name?"))
    }
}
