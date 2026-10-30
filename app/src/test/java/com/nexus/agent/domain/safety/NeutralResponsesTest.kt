package com.nexus.agent.domain.safety

import com.nexus.agent.domain.culture.Region
import com.nexus.agent.domain.culture.ToneSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeutralResponsesTest {

    @Test
    fun religionResponseMatchesRegionAndTone() {
        val reply = NeutralResponses.neutralResponse(
            NeutralCategory.RELIGION, Region.EGYPT, ToneSensitivity.SOFT
        )
        // Egyptian soft reply starts with the Egyptian vocative.
        assertEquals("يا فندم، إحنا هنا نحترم الكل من غير جدال ❤️", reply)
    }

    @Test
    fun differentRegionYieldsDifferentReply() {
        val gulf = NeutralResponses.neutralResponse(NeutralCategory.PROVOCATION, Region.GULF, ToneSensitivity.SOFT)
        val iraq = NeutralResponses.neutralResponse(NeutralCategory.PROVOCATION, Region.IRAQ, ToneSensitivity.SOFT)
        assertNotEquals(gulf, iraq)
    }

    @Test
    fun unknownRegionFallsBackToGulf() {
        val reply = NeutralResponses.neutralResponse(
            NeutralCategory.PERSONAL_QUESTION, Region.MAGHREB, ToneSensitivity.ASSERTIVE
        )
        val gulf = NeutralResponses.neutralResponse(
            NeutralCategory.PERSONAL_QUESTION, Region.GULF, ToneSensitivity.ASSERTIVE
        )
        assertEquals(gulf, reply)
    }

    @Test
    fun categoryFromId() {
        assertEquals(NeutralCategory.RELIGION, NeutralCategory.fromId("religion"))
        assertEquals(NeutralCategory.PERSONAL_QUESTION, NeutralCategory.fromId("personal_question"))
        assertNull(NeutralCategory.fromId("nope"))
    }
}
