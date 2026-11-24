package com.bigbrein.bear

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections

/** App-wide download queue. [DownloadService] works through it one task at a time. */
object DownloadQueue {
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val cancelRequested = Collections.synchronizedSet(mutableSetOf<String>())

    val isBusy: Boolean get() = _tasks.value.any { it.state.isActive || it.state == TaskState.QUEUED }

    fun get(id: String) = _tasks.value.firstOrNull { it.id == id }
    fun nextQueued() = _tasks.value.firstOrNull { it.state == TaskState.QUEUED }
    fun queuedCount() = _tasks.value.count { it.state == TaskState.QUEUED }

    fun enqueue(context: Context, task: DownloadTask) {
        _tasks.update { it + task }
        DownloadService.start(context)
    }

    fun update(id: String, change: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    fun cancel(id: String) {
        val task = get(id) ?: return
        when (task.state) {
            TaskState.QUEUED -> update(id) { it.copy(state = TaskState.CANCELLED) }
            TaskState.DOWNLOADING -> {
                cancelRequested += id
                Engine.cancel(id)
            }
            else -> Unit // saving can't be interrupted; finished tasks have nothing to cancel
        }
    }

    fun cancelAll() {
        _tasks.value.filter { it.state == TaskState.QUEUED || it.state == TaskState.DOWNLOADING }
            .forEach { cancel(it.id) }
    }

    fun isCancelRequested(id: String) = id in cancelRequested
    fun clearCancelRequest(id: String) { cancelRequested -= id }

    fun retry(context: Context, id: String) {
        update(id) {
            it.copy(state = TaskState.QUEUED, progress = 0f, etaSeconds = 0, playlistItem = null,
                message = null, savedFiles = emptyList())
        }
        DownloadService.start(context)
    }

    fun remove(id: String) {
        if (get(id)?.state?.isActive == true) return
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        _tasks.update { list -> list.filterNot { it.state.isFinished } }
    }
}
