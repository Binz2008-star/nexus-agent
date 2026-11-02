package com.nexus.agent.data.repository

import com.nexus.agent.domain.commands.EmailDraftCodec
import com.nexus.agent.domain.commands.EmailDraftStore
import com.nexus.agent.domain.commands.PendingEmailDraft

/**
 * Encrypted at rest with the same Android Keystore AES-GCM backed storage as
 * other NEXUS secrets. No draft bodies, approvals or account data go to logs.
 */
class EncryptedEmailDraftStore(private val secrets: SecretStorageRepository) : EmailDraftStore {
    override suspend fun load(): PendingEmailDraft? =
        when (val result = secrets.readSecret(KEY)) {
            is ApiKeyReadResult.Present -> EmailDraftCodec.decode(result.value)
            else -> null // Never re-approve or auto-send on corrupt/invalidated storage.
        }

    override suspend fun save(draft: PendingEmailDraft) {
        secrets.saveSecret(KEY, EmailDraftCodec.encode(draft))
    }

    override suspend fun clear() {
        secrets.clearSecret(KEY)
    }

    companion object {
        private const val KEY = "encrypted_pending_email_draft_v1"
    }
}
