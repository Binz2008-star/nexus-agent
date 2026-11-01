package com.nexus.agent.data.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses the real Room database on the isolated CI emulator, not the user's installed database.
 * Only aggregate counts are returned, and neither a secret nor a message body is stored.
 */
@RunWith(AndroidJUnit4::class)
class ConversationIntegrityWorkerInstrumentedTest {

    @Test fun readOnlyAuditCompletesAgainstLocalRoomDatabase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<ConversationIntegrityWorker>(context).build()
        val result = worker.doWork()
        assertTrue("Local integrity audit must succeed on a healthy Room DB",
            result is ListenableWorker.Result.Success)
        if (result is ListenableWorker.Result.Success) {
            assertEquals(0, result.outputData.getInt(
                ConversationIntegrityWorker.KEY_ORPHAN_COUNT, -1))
            assertTrue(result.outputData.getInt(
                ConversationIntegrityWorker.KEY_CHAT_COUNT, -1) >= 0)
            assertTrue(result.outputData.getInt(
                ConversationIntegrityWorker.KEY_MESSAGE_COUNT, -1) >= 0)
        }
    }
}
