package com.bigbrein.bear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class UiState(
    val url: String = "",
    val mode: Mode = Mode.VIDEO,
    val engineReady: Boolean = false,
    val ytDlpVersion: String = "",
    val busy: Boolean = false,
    val updating: Boolean = false,
    val progress: Float = 0f,
    val status: String = "Starting engine…",
    val lastLine: String = "",
    val saved: List<String> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var currentId: String? = null

    init {
        viewModelScope.launch {
            try {
                Engine.ensureReady(app)
                _state.update { it.copy(engineReady = true, status = "Ready", ytDlpVersion = Engine.version(app)) }
                if (Engine.autoUpdateIfDue(app)) {
                    _state.update { it.copy(ytDlpVersion = Engine.version(app), status = "Ready (yt-dlp updated)") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(status = "Engine failed to start: ${e.message}") }
            }
        }
    }

    fun setUrl(url: String) = _state.update { it.copy(url = url) }
    fun setMode(mode: Mode) = _state.update { it.copy(mode = mode) }

    /** Pulls the first http(s) link out of shared text like "Check this out https://…". */
    fun receiveSharedText(text: String) {
        val link = Regex("""https?://\S+""").find(text)?.value ?: text.trim()
        setUrl(link)
    }

    fun download() {
        val s = _state.value
        if (s.busy || !s.engineReady || s.url.isBlank()) return
        val id = UUID.randomUUID().toString()
        currentId = id
        _state.update { it.copy(busy = true, progress = 0f, status = "Fetching…", lastLine = "", saved = emptyList()) }
        viewModelScope.launch {
            try {
                val dir = Engine.download(getApplication(), s.url.trim(), s.mode, id) { p, eta, line ->
                    _state.update {
                        it.copy(
                            progress = (p / 100f).coerceIn(0f, 1f),
                            status = if (p > 0) "Downloading… ${p.toInt()}%" + (if (eta > 0) " · ${eta}s left" else "") else it.status,
                            lastLine = line,
                        )
                    }
                }
                _state.update { it.copy(status = "Saving…", progress = 1f) }
                val saved = Exporter.exportAll(getApplication(), dir)
                _state.update {
                    it.copy(
                        busy = false,
                        status = if (saved.isEmpty()) "Finished, but no file was produced" else "Saved ${saved.size} file(s)",
                        saved = saved,
                    )
                }
            } catch (e: Exception) {
                val cancelled = e is com.yausername.youtubedl_android.YoutubeDL.CanceledException
                _state.update {
                    it.copy(busy = false, status = if (cancelled) "Cancelled" else "Failed: ${friendlyError(e)}")
                }
            } finally {
                currentId = null
            }
        }
    }

    fun cancel() {
        currentId?.let { Engine.cancel(it) }
    }

    fun updateYtDlp() {
        if (_state.value.updating) return
        _state.update { it.copy(updating = true) }
        viewModelScope.launch {
            val msg = try {
                if (Engine.updateYtDlp(getApplication())) "yt-dlp updated" else "yt-dlp is already up to date"
            } catch (e: Exception) {
                "Update failed: ${e.message}"
            }
            val version = runCatching { Engine.version(getApplication()) }.getOrNull()
            _state.update { it.copy(updating = false, status = msg, ytDlpVersion = version ?: it.ytDlpVersion) }
        }
    }

    private fun friendlyError(e: Exception): String {
        val msg = e.message.orEmpty()
        // yt-dlp prints "ERROR: ..." lines; show the last one instead of the full stack.
        return msg.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim()
            ?: msg.lines().firstOrNull { it.isNotBlank() }
            ?: e.javaClass.simpleName
    }
}
