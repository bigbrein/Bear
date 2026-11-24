package com.bigbrein.bear

enum class Mode { VIDEO, AUDIO }

enum class AudioFormat(val ext: String, val label: String, val embedsThumbnail: Boolean) {
    MP3("mp3", "MP3", true),
    M4A("m4a", "M4A", true),
    OPUS("opus", "Opus", false),
}

enum class AudioQuality(val ytdlpValue: String, val label: String) {
    BEST("0", "Best"),
    K320("320K", "320 kbps"),
    K192("192K", "192 kbps"),
    K128("128K", "128 kbps"),
}

/** [maxHeight] is the video's short side (e.g. 1080 for 1080p); null means best available. */
data class DownloadOptions(
    val mode: Mode = Mode.VIDEO,
    val maxHeight: Int? = null,
    val audioFormat: AudioFormat = AudioFormat.MP3,
    val audioQuality: AudioQuality = AudioQuality.BEST,
) {
    fun describe(): String = when (mode) {
        Mode.VIDEO -> "Video · " + (maxHeight?.let { "${it}p" } ?: "Best")
        Mode.AUDIO -> "Audio · ${audioFormat.label} · ${audioQuality.label}"
    }
}

data class MediaInfo(
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val durationSeconds: Int?,
    val site: String?,
    val isPlaylist: Boolean,
    val entryCount: Int,
    /** Available video resolutions (short side), highest first. Empty if unknown. */
    val heights: List<Int>,
)

enum class TaskState { QUEUED, DOWNLOADING, SAVING, DONE, FAILED, CANCELLED;
    val isActive get() = this == DOWNLOADING || this == SAVING
    val isFinished get() = this == DONE || this == FAILED || this == CANCELLED
}

data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val options: DownloadOptions,
    val state: TaskState = TaskState.QUEUED,
    val progress: Float = 0f,
    val etaSeconds: Long = 0,
    /** "3/20" while downloading a playlist. */
    val playlistItem: String? = null,
    val message: String? = null,
    val savedFiles: List<String> = emptyList(),
)

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
