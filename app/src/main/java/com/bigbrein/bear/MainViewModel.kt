package com.bigbrein.bear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class UiState(
    val url: String = "",
    val info: MediaInfo? = null,
    val fetching: Boolean = false,
    val fetchError: String? = null,
    val options: DownloadOptions = DownloadOptions(),
    val engineReady: Boolean = false,
    val engineError: String? = null,
    val ytDlpVersion: String = "",
    val updating: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState(options = Prefs.load(app)))
    val state: StateFlow<UiState> = _state
    val tasks = DownloadQueue.tasks

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var fetchJob: Job? = null
    private var fetchProcessId: String? = null

    init {
        viewModelScope.launch {
            try {
                Engine.ensureReady(app)
                _state.update { it.copy(engineReady = true, ytDlpVersion = Engine.version(app)) }
                // A link may have been shared to us before the engine was ready.
                if (_state.value.url.isNotBlank() && _state.value.info == null) scheduleFetch(0)
                if (Engine.autoUpdateIfDue(app)) {
                    _state.update { it.copy(ytDlpVersion = Engine.version(app)) }
                    _messages.tryEmit("yt-dlp updated")
                }
            } catch (e: Exception) {
                _state.update { it.copy(engineError = "Engine failed to start: ${e.message}") }
            }
        }
    }

    fun setUrl(url: String) {
        if (url == _state.value.url) return
        _state.update { it.copy(url = url, info = null, fetchError = null) }
        scheduleFetch(600)
    }

    /** Pulls the first http(s) link out of shared text like "Check this out https://…". */
    fun receiveSharedText(text: String) {
        val link = Regex("""https?://\S+""").find(text)?.value ?: text.trim()
        setUrl(link)
    }

    private fun scheduleFetch(debounceMs: Long) {
        fetchJob?.cancel()
        fetchProcessId?.let { Engine.cancel(it) }
        val url = _state.value.url.trim()
        if (!url.startsWith("http")) {
            _state.update { it.copy(fetching = false) }
            return
        }
        fetchJob = viewModelScope.launch {
            delay(debounceMs)
            if (!_state.value.engineReady) return@launch // retried once the engine is ready
            val pid = "info-" + UUID.randomUUID()
            fetchProcessId = pid
            _state.update { it.copy(fetching = true, fetchError = null) }
            try {
                val info = Engine.fetchInfo(getApplication(), url, pid)
                if (_state.value.url.trim() == url) _state.update { it.copy(info = info, fetching = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.url.trim() == url) {
                    _state.update { it.copy(fetching = false, fetchError = friendlyError(e)) }
                }
            } finally {
                if (fetchProcessId == pid) fetchProcessId = null
            }
        }
    }

    fun setOptions(change: (DownloadOptions) -> DownloadOptions) {
        _state.update { it.copy(options = change(it.options)) }
        Prefs.save(getApplication(), _state.value.options)
    }

    fun download() {
        val s = _state.value
        val url = s.url.trim()
        if (!s.engineReady || url.isBlank()) return
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = url,
            title = s.info?.title ?: url,
            thumbnail = s.info?.thumbnail,
            options = s.options,
        )
        DownloadQueue.enqueue(getApplication(), task)
        fetchJob?.cancel()
        fetchProcessId?.let { Engine.cancel(it) }
        _state.update { it.copy(url = "", info = null, fetching = false, fetchError = null) }
        _messages.tryEmit("Added to queue")
    }

    fun cancel(id: String) = DownloadQueue.cancel(id)
    fun retry(id: String) = DownloadQueue.retry(getApplication(), id)
    fun remove(id: String) = DownloadQueue.remove(id)
    fun clearFinished() = DownloadQueue.clearFinished()

    fun updateYtDlp() {
        if (_state.value.updating) return
        if (DownloadQueue.isBusy) {
            _messages.tryEmit("Wait for downloads to finish before updating")
            return
        }
        _state.update { it.copy(updating = true) }
        viewModelScope.launch {
            val msg = try {
                if (Engine.updateYtDlp(getApplication())) "yt-dlp updated" else "yt-dlp is already up to date"
            } catch (e: Exception) {
                "Update failed: ${e.message}"
            }
            val version = runCatching { Engine.version(getApplication()) }.getOrNull()
            _state.update { it.copy(updating = false, ytDlpVersion = version ?: it.ytDlpVersion) }
            _messages.tryEmit(msg)
        }
    }
}
