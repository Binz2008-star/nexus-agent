package com.nexus.agent.domain.memory

import java.util.concurrent.atomic.AtomicLong

/**
 * Protects a slow Room/DataStore cold-start restore from overwriting a more recent user action.
 *
 * A selection, new-chat request, or delete invalidates any earlier restore snapshot.
 * This is in-memory coordination only; the selected conversation ID remains stored in DataStore.
 */
class ConversationResumeGuard {
    private val generation = AtomicLong(0L)

    fun snapshot(): Long = generation.get()

    fun userChangedSelection(): Long = generation.incrementAndGet()

    fun canApplyRestore(snapshot: Long): Boolean = generation.get() == snapshot
}

/** Pure selection policy: prefer a validated stored ID, otherwise the newest surviving chat. */
object ConversationResumePolicy {
    fun resolve(
        persistedId: Long,
        persistedExists: Boolean,
        newestId: Long?
    ): Long? = when {
        persistedId > 0 && persistedExists -> persistedId
        newestId != null && newestId > 0 -> newestId
        else -> null
    }
}
