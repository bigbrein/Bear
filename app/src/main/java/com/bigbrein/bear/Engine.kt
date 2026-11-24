package com.bigbrein.bear

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Thin wrapper around yt-dlp (via youtubedl-android) and FFmpeg.
 * yt-dlp does the extraction/downloading; FFmpeg merges video+audio and converts audio.
 */
object Engine {
    private val initLock = Mutex()
    /** Held while yt-dlp is being replaced, so no download starts mid-update. */
    private val updateLock = Mutex()
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
        val status = updateLock.withLock {
            YoutubeDL.getInstance()
                .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
        status == YoutubeDL.UpdateStatus.DONE
    }

    /** Updates at most once a day, and never while downloads are running. Failures are ignored. */
    suspend fun autoUpdateIfDue(context: Context): Boolean {
        if (DownloadQueue.isBusy) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_UPDATE, 0L)
        if (System.currentTimeMillis() - last < UPDATE_INTERVAL_MS) return false
        return runCatching { updateYtDlp(context) }.getOrDefault(false)
    }

    /** Reads title, thumbnail, available resolutions etc. without downloading. */
    suspend fun fetchInfo(context: Context, url: String, processId: String): MediaInfo =
        withContext(Dispatchers.IO) {
            ensureReady(context)
            val request = YoutubeDLRequest(url).apply {
                addOption("-J")              // print everything as one JSON object
                addOption("--flat-playlist") // don't resolve every playlist item (fast)
                addOption("--no-playlist")
                addOption("--no-warnings")
            }
            val response = YoutubeDL.getInstance().execute(request, processId)
            parseInfo(JSONObject(response.out))
        }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun parseInfo(j: JSONObject): MediaInfo {
        val isPlaylist = j.optString("_type") == "playlist"
        val entries = j.optJSONArray("entries")

        val heights = mutableSetOf<Int>()
        j.optJSONArray("formats")?.let { formats ->
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                if (f.optString("vcodec", "none") == "none") continue
                val w = f.optInt("width", 0)
                val h = f.optInt("height", 0)
                // yt-dlp's "res" is the smaller side, so vertical 1080x1920 counts as 1080p.
                val res = if (w > 0 && h > 0) minOf(w, h) else h
                if (res > 0) heights += res
            }
        }

        var thumbnail = j.str("thumbnail")
        if (thumbnail == null) {
            val thumbs = j.optJSONArray("thumbnails")
            if (thumbs != null && thumbs.length() > 0) {
                thumbnail = thumbs.getJSONObject(thumbs.length() - 1).str("url")
            }
        }
        if (thumbnail == null && entries != null && entries.length() > 0) {
            val first = entries.getJSONObject(0)
            thumbnail = first.str("thumbnail")
                ?: first.optJSONArray("thumbnails")?.let { t ->
                    if (t.length() > 0) t.getJSONObject(t.length() - 1).str("url") else null
                }
        }

        val duration = j.optDouble("duration", Double.NaN)
        return MediaInfo(
            title = j.str("title") ?: j.str("id") ?: "Untitled",
            uploader = j.str("uploader") ?: j.str("channel") ?: j.str("uploader_id"),
            thumbnail = thumbnail,
            durationSeconds = if (duration.isNaN()) null else duration.toInt(),
            site = j.str("extractor_key"),
            isPlaylist = isPlaylist,
            entryCount = entries?.length() ?: j.optInt("playlist_count", 0),
            heights = heights.sortedDescending(),
        )
    }

    fun workDir(context: Context, processId: String) =
        File(context.cacheDir, "downloads/$processId")

    /**
     * Downloads [url] into a fresh folder in the app cache and returns that folder.
     * Single videos and whole playlists both work; playlist items go into a subfolder.
     */
    suspend fun download(
        context: Context,
        url: String,
        options: DownloadOptions,
        processId: String,
        onProgress: (percent: Float, etaSeconds: Long, line: String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        ensureReady(context)
        val workDir = workDir(context, processId).apply { mkdirs() }

        val request = YoutubeDLRequest(url).apply {
            addOption("-P", workDir.absolutePath)
            // Playlist items go into a folder named after the playlist; single items don't.
            addOption("-o", "%(playlist_title&{}/|)s%(title).150B [%(id)s].%(ext)s")
            // A "watch?v=...&list=..." link downloads just that video. A pure playlist link
            // still downloads the whole playlist.
            addOption("--no-playlist")
            // Skip unavailable playlist items instead of stopping.
            addOption("--ignore-errors")
            addOption("--no-mtime")
            addOption("--embed-metadata")
            addOption("--windows-filenames") // avoid characters Android storage rejects
            when (options.mode) {
                Mode.VIDEO -> {
                    // Best quality up to the chosen resolution, preferring mp4/m4a.
                    addOption("-f", "bv*+ba/b")
                    val res = options.maxHeight?.let { "res:$it" } ?: "res"
                    addOption("-S", "$res,ext:mp4:m4a")
                    addOption("--merge-output-format", "mp4")
                }
                Mode.AUDIO -> {
                    addOption("-f", "ba/b")
                    addOption("-x")
                    addOption("--audio-format", options.audioFormat.ext)
                    addOption("--audio-quality", options.audioQuality.ytdlpValue)
                    if (options.audioFormat.embedsThumbnail) {
                        addOption("--embed-thumbnail")
                        addOption("--convert-thumbnails", "jpg")
                    }
                }
            }
        }

        updateLock.withLock { } // wait for any running update to finish
        YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
            onProgress(progress, eta, line)
        }
        workDir
    }

    fun cancel(processId: String): Boolean =
        YoutubeDL.getInstance().destroyProcessById(processId)
}
