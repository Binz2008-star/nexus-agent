package com.nexus.agent.data.work

import com.nexus.agent.domain.tool.TaskStatus
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTaskRunnerTest {

    @Test
    fun runSetsRunningThenCancelSetsIdle() = runBlocking {
        val runner = BackgroundTaskRunner(this) // inject the runBlocking scope for determinism
        runner.run { _ ->
            awaitCancellation() // suspend until cancelled
        }
        // run() sets Running synchronously before launching
        assertTrue(runner.status.value is TaskStatus.Running)
        runner.cancel()
        assertEquals(TaskStatus.Idle, runner.status.value)
    }

    @Test
    fun taskCanReportCompleted() = runBlocking {
        val runner = BackgroundTaskRunner(this)
        runner.run { update ->
            update(TaskStatus.Completed(emptyList()))
        }
        // The task completes immediately; poll the status (bounded).
        repeat(50) {
            if (runner.status.value is TaskStatus.Completed) return@runBlocking
            kotlinx.coroutines.delay(10)
        }
        assertTrue(runner.status.value is TaskStatus.Completed)
    }
}
