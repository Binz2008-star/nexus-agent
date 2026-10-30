package com.nexus.agent.data.work

import com.nexus.agent.domain.tool.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs an agent task on a background IO coroutine (non-blocking for the UI) and exposes
 * its lifecycle via [status]. The UI can keep accepting input while the task runs and
 * call [cancel] to stop it.
 *
 * NOTE: a Foreground Service / WorkManager is intentionally NOT used. Those exist to
 * keep work alive after the process is killed or while the app is backgrounded — not
 * required for the in-app "Working for Ns" flow. A plain IO coroutine is the correct,
 * testable mechanism here; a Service/WorkManager layer can be layered on later if true
 * background survival is needed.
 */
class BackgroundTaskRunner(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _status = MutableStateFlow<TaskStatus>(TaskStatus.Idle)
    val status: StateFlow<TaskStatus> = _status.asStateFlow()
    private var job: Job? = null

    fun run(task: suspend (update: (TaskStatus) -> Unit) -> Unit) {
        cancel()
        val startedAtMs = System.currentTimeMillis()
        _status.value = TaskStatus.Running(startedAtMs, "starting")
        job = scope.launch {
            try {
                task { _status.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = TaskStatus.Failed("task_failed")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _status.value = TaskStatus.Idle
    }

    /** Cancels the current task and the backing scope. Call once when the owner is destroyed. */
    fun close() {
        job?.cancel()
        job = null
        _status.value = TaskStatus.Idle
        scope.cancel()
    }
}
