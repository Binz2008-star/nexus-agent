package com.nexus.agent.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nexus.agent.data.local.NexusDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A bounded, offline and READ-ONLY durable task. WorkManager persists task status across
 * process death, but it NEVER executes model-proposed tools or reuses approval receipts.
 *
 * Output includes aggregate local counts only; messages, credentials and user identifiers are
 * never placed into WorkManager's internal database, exposed in logs or sent over a network.
 */
class ConversationIntegrityWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val database = NexusDatabase.getInstance(applicationContext)
            val chats = database.conversationDao().countConversations()
            val messages = database.messageDao().countMessages()
            val orphaned = database.messageDao().countOrphanedMessages()
            val counts = LocalConversationCounts(chats, messages, orphaned)
            val data = workDataOf(
                KEY_CHAT_COUNT to chats,
                KEY_MESSAGE_COUNT to messages,
                KEY_ORPHAN_COUNT to orphaned,
                KEY_COMPLETED_AT_MS to System.currentTimeMillis()
            )
            if (counts.isHealthy()) Result.success(data)
            else Result.failure(data)
        } catch (_: Exception) {
            // Do not write arbitrary exception messages: SQL/driver messages may contain PII.
            if (runAttemptCount < MAX_RETRIES) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR_CODE to "local_database_unavailable"))
        }
    }

    companion object {
        const val KEY_CHAT_COUNT = "chat_count"
        const val KEY_MESSAGE_COUNT = "message_count"
        const val KEY_ORPHAN_COUNT = "orphan_count"
        const val KEY_COMPLETED_AT_MS = "completed_at_ms"
        const val KEY_ERROR_CODE = "error_code"
        private const val MAX_RETRIES = 2
    }
}

/** Keep the integrity rule independently unit-testable without Android dependencies. */
data class LocalConversationCounts(val chats: Int, val messages: Int, val orphaned: Int) {
    fun isHealthy(): Boolean = chats >= 0 && messages >= 0 && orphaned == 0
}

/** Only the explicit history-screen action can enqueue this worker. */
object ConversationIntegrityScheduler {
    const val UNIQUE_WORK = "nexus-local-conversation-integrity-v1"

    fun enqueueReadOnlyAudit(context: Context) {
        val request = OneTimeWorkRequestBuilder<ConversationIntegrityWorker>()
            .addTag("nexus-readonly-local-audit")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
