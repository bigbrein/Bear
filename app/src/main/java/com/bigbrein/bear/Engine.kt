package com.bigbrein.bear

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class Mode { VIDEO, AUDIO }

/**
 * Thin wrapper around yt-dlp (via youtubedl-android) and FFmpeg.
 * yt-dlp does the extraction/downloading; FFmpeg merges video+audio and converts audio.
 */
object Engine {
    private val initLock = Mutex()
    @Volatile private var ready = false

    private const val PREFS = "engine"
    private const val KEY_LAST_UPDATE = "last_update_check"
    private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Unpacks Python, yt-dlp and FFmpeg on first run (takes a few seconds). */
    suspend fun ensureReady(context: Context) = initLock.withLock {
        if (ready) return@withLock
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
        }
        ready = true
    }

    suspend fun version(context: Context): String = withContext(Dispatchers.IO) {
        YoutubeDL.getInstance().version(context.applicationContext) ?: "unknown"
    }

    /**
     * Updates yt-dlp to the latest nightly build. Sites (especially YouTube) change often,
     * and an outdated yt-dlp is the most common reason downloaders stop working.
     * Returns true if a new version was installed.
     */
    suspend fun updateYtDlp(context: Context): Boolean = withContext(Dispatchers.IO) {
        ensureReady(context)
        val status = YoutubeDL.getInstance()
            .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
        status == YoutubeDL.UpdateStatus.DONE
    }

    /** Updates at most once a day. Failures are ignored (e.g. offline). */
    suspend fun autoUpdateIfDue(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_UPDATE, 0L)
        if (System.currentTimeMillis() - last < UPDATE_INTERVAL_MS) return false
        return runCatching { updateYtDlp(context) }.getOrDefault(false)
    }

    /**
     * Downloads [url] into a fresh folder in the app cache and returns that folder.
     * Single videos and whole playlists both work; playlist items go into a subfolder.
     */
    suspend fun download(
        context: Context,
        url: String,
        mode: Mode,
        processId: String,
        onProgress: (percent: Float, etaSeconds: Long, line: String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        ensureReady(context)
        val workDir = File(context.cacheDir, "downloads/$processId").apply { mkdirs() }

        val request = YoutubeDLRequest(url).apply {
            addOption("-P", workDir.absolutePath)
            // Playlist items go into a folder named after the playlist; single items don't.
            addOption("-o", "%(playlist_title&{}/|)s%(title).150B [%(id)s].%(ext)s")
            // A "watch?v=...&list=..." link downloads just that video. A pure playlist link
            // still downloads the whole playlist.
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--embed-metadata")
            addOption("--windows-filenames") // avoid characters Android storage rejects
            when (mode) {
                Mode.VIDEO -> {
                    // Best quality, preferring formats that play everywhere (mp4/m4a).
                    addOption("-f", "bv*+ba/b")
                    addOption("-S", "res,ext:mp4:m4a")
                    addOption("--merge-output-format", "mp4")
                }
                Mode.AUDIO -> {
                    addOption("-f", "ba/b")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                    addOption("--embed-thumbnail")
                    addOption("--convert-thumbnails", "jpg")
                }
            }
        }

        YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
            onProgress(progress, eta, line)
        }
        workDir
    }

    fun cancel(processId: String) {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }
}
