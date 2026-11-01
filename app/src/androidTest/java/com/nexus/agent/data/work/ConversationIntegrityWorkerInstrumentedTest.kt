package com.nexus.agent.data.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two read-only worker stages against the isolated emulator database.
 * Checkpoint outputs contain counts only; no conversation text or account data.
 */
@RunWith(AndroidJUnit4::class)
class ConversationIntegrityWorkerInstrumentedTest {
    @Test fun snapshotThenVerificationSucceeds() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshot = TestListenableWorkerBuilder<ConversationSnapshotWorker>(context).build()
            .doWork()
        assertTrue(snapshot is ListenableWorker.Result.Success)
        val snapshotData = (snapshot as ListenableWorker.Result.Success).outputData
        val verified = TestListenableWorkerBuilder<ConversationIntegrityWorker>(context)
            .setInputData(snapshotData)
            .build()
            .doWork()
        assertTrue(verified is ListenableWorker.Result.Success)
        val data = (verified as ListenableWorker.Result.Success).outputData
        assertEquals(0, data.getInt(ConversationIntegrityWorker.KEY_ORPHAN_COUNT, -1))
        assertTrue(data.getInt(ConversationIntegrityWorker.KEY_CHAT_COUNT, -1) >= 0)
        assertTrue(data.getInt(ConversationIntegrityWorker.KEY_MESSAGE_COUNT, -1) >= 0)
    }

    @Test fun changedSnapshotCannotBeReportedAsVerified() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wrongData = workDataOf(
            ConversationIntegrityWorker.KEY_INITIAL_CHAT_COUNT to Int.MAX_VALUE,
            ConversationIntegrityWorker.KEY_INITIAL_MESSAGE_COUNT to Int.MAX_VALUE
        )
        val verified = TestListenableWorkerBuilder<ConversationIntegrityWorker>(context)
            .setInputData(wrongData)
            .build()
            .doWork()
        assertTrue(verified is ListenableWorker.Result.Failure)
        assertEquals(
            "data_changed_during_audit",
            (verified as ListenableWorker.Result.Failure).outputData
                .getString(ConversationIntegrityWorker.KEY_ERROR_CODE)
        )
    }
}
