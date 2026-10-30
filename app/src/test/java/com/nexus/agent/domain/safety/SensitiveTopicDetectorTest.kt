package com.nexus.agent.domain.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensitiveTopicDetectorTest {

    @Test
    fun detectsReligion() {
        assertEquals(NeutralCategory.RELIGION, SensitiveTopicDetector.detect("ما هو رأيك في الدين؟"))
        assertEquals(NeutralCategory.RELIGION, SensitiveTopicDetector.detect("Tell me about religion"))
    }

    @Test
    fun detectsProvocation() {
        assertEquals(NeutralCategory.PROVOCATION, SensitiveTopicDetector.detect("أنت غبي"))
        assertEquals(NeutralCategory.PROVOCATION, SensitiveTopicDetector.detect("you are stupid"))
    }

    @Test
    fun detectsPersonalQuestion() {
        assertEquals(NeutralCategory.PERSONAL_QUESTION, SensitiveTopicDetector.detect("كم عمرك؟"))
        assertEquals(NeutralCategory.PERSONAL_QUESTION, SensitiveTopicDetector.detect("where do you live"))
    }

    @Test
    fun doesNotMatchInsideOtherWords() {
        // "دين" inside "مدينة" (city) must not match religion.
        assertNull(SensitiveTopicDetector.detect("ما هي أكبر مدينة في مصر؟"))
    }

    @Test
    fun benignTextReturnsNull() {
        assertNull(SensitiveTopicDetector.detect("ما عاصمة فرنسا؟"))
        assertNull(SensitiveTopicDetector.detect("What is the capital of France?"))
    }
}
