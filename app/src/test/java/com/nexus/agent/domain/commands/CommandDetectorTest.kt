package com.nexus.agent.domain.commands

import com.nexus.agent.domain.persona.Emotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandDetectorTest {

    @Test
    fun detectsPoetryByWord() {
        assertEquals(Command.Poetry, CommandDetector.detect("اكتب لي شعراً"))
        assertEquals(Command.Poetry, CommandDetector.detect("write a poem"))
        assertEquals(Command.Poetry, CommandDetector.detect("اكتب قصيدة"))
    }

    @Test
    fun doesNotMatchFeelVerb() {
        // "أشعر" (I feel) must not be treated as the poetry command.
        assertNull(CommandDetector.detect("أشعر بالحزن اليوم"))
        assertNull(CommandDetector.detect("هل تشعر بالتعب؟"))
    }

    @Test
    fun detectsBreathing() {
        assertEquals(Command.Breathing, CommandDetector.detect("أريد أن أتنفس"))
        assertEquals(Command.Breathing, CommandDetector.detect("breathe"))
    }

    @Test
    fun detectsEmail() {
        assertEquals(Command.Email, CommandDetector.detect("check my email"))
        assertEquals(Command.Email, CommandDetector.detect("search my inbox for invoice"))
        assertEquals(Command.Email, CommandDetector.detect("تفقد بريدي"))
        assertEquals(Command.Email, CommandDetector.detect("اقرأ رسائلي"))
    }

    @Test
    fun emailDoesNotMatchUnrelatedMailWords() {
        // "blackmail" must not trigger the email command (word boundary).
        assertNull(CommandDetector.detect("what is blackmail"))
    }

    @Test
    fun benignTextReturnsNull() {
        assertNull(CommandDetector.detect("ما عاصمة فرنسا؟"))
    }

    @Test
    fun poetryEmotionDetection() {
        assertEquals(Emotion.SADNESS, CommandDetector.poetryEmotion("اكتب قصيدة عن الحزن"))
        assertEquals(Emotion.JOY, CommandDetector.poetryEmotion("قصيدة فرح"))
        assertEquals(Emotion.NEUTRAL, CommandDetector.poetryEmotion("اكتب قصيدة"))
    }
}
