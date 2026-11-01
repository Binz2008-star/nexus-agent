package com.nexus.agent.data.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalConversationCountsTest {
    @Test fun emptyDatabaseIsValid() {
        assertTrue(LocalConversationCounts(0, 0, 0).isHealthy())
    }

    @Test fun populatedDatabaseWithoutOrphansIsValid() {
        assertTrue(LocalConversationCounts(2, 17, 0).isHealthy())
    }

    @Test fun orphanedMessagesAreReportedAsFailure() {
        assertFalse(LocalConversationCounts(2, 17, 1).isHealthy())
    }

    @Test fun invalidCountsFailClosed() {
        assertFalse(LocalConversationCounts(-1, 4, 0).isHealthy())
        assertFalse(LocalConversationCounts(4, -1, 0).isHealthy())
    }
}
