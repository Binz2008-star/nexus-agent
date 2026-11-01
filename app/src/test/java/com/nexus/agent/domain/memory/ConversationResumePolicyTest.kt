package com.nexus.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationResumePolicyTest {
    @Test fun storedIdIsPreferredOnlyWhenVerified() {
        assertEquals(42L, ConversationResumePolicy.resolve(42L, true, 51L))
        assertEquals(51L, ConversationResumePolicy.resolve(42L, false, 51L))
    }

    @Test fun corruptedOrDeletedSelectionFallsBackSafely() {
        assertEquals(7L, ConversationResumePolicy.resolve(-1L, false, 7L))
        assertEquals(7L, ConversationResumePolicy.resolve(0L, true, 7L))
        assertNull(ConversationResumePolicy.resolve(99L, false, null))
        assertNull(ConversationResumePolicy.resolve(-1L, false, 0L))
    }

    @Test fun explicitSelectionInvalidatesPendingRestore() {
        val guard = ConversationResumeGuard()
        val start = guard.snapshot()
        assertTrue(guard.canApplyRestore(start))
        guard.userChangedSelection()
        assertFalse(guard.canApplyRestore(start))
        assertTrue(guard.canApplyRestore(guard.snapshot()))
    }

    @Test fun repeatedActionsNeverAllowStaleRestore() {
        val guard = ConversationResumeGuard()
        val snapshot = guard.snapshot()
        guard.userChangedSelection() // New chat requested.
        guard.userChangedSelection() // Older chat selected.
        guard.userChangedSelection() // Deletion initiated.
        assertFalse(guard.canApplyRestore(snapshot))
    }
}
