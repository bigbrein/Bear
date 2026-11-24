package com.bigbrein.bear

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps downloads running when the app is closed or the screen is off.
 * Works through [DownloadQueue] one task at a time, then stops itself.
 */
class DownloadService : Service() {
    companion object {
        const val ACTION_CANCEL = "com.bigbrein.bear.CANCEL"
        const val EXTRA_ID = "id"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }

        private val itemRegex = Regex("""Downloading item (\d+) of (\d+)""")
        private const val NOTIFY_INTERVAL_MS = 1000L
    }

    // Main thread: queue checks and start/stop decisions never interleave.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    private var lastStartId = 0
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bear:download")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Must go foreground promptly after startForegroundService().
        goForeground(DownloadQueue.tasks.value.firstOrNull { it.state.isActive })

        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_ID)?.let(DownloadQueue::cancel)
        }
        if (worker?.isActive != true) {
            worker = scope.launch { runQueue() }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(task: DownloadTask?) {
        ServiceCompat.startForeground(
            this,
            Notifications.FOREGROUND_ID,
            Notifications.progress(this, task, DownloadQueue.queuedCount()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private suspend fun runQueue() {
        wakeLock.acquire(6 * 60 * 60 * 1000L)
        try {
            while (true) {
                val task = DownloadQueue.nextQueued() ?: break
                runTask(task)
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Only stops if no newer start request arrived; otherwise onStartCommand restarts the worker.
        stopSelfResult(lastStartId)
    }

    private suspend fun runTask(task: DownloadTask) {
        val id = task.id
        DownloadQueue.clearCancelRequest(id)
        DownloadQueue.update(id) { it.copy(state = TaskState.DOWNLOADING, progress = 0f, message = null) }
        notifyProgress(id)

        var lastNotify = 0L
        try {
            val dir = Engine.download(this, task.url, task.options, id) { p, eta, line ->
                if (DownloadQueue.isCancelRequested(id)) Engine.cancel(id)
                val item = itemRegex.find(line)?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
                DownloadQueue.update(id) {
                    it.copy(
                        progress = (p / 100f).coerceIn(0f, 1f),
                        etaSeconds = eta,
                        playlistItem = item ?: it.playlistItem,
                    )
                }
                val now = System.currentTimeMillis()
                if (now - lastNotify > NOTIFY_INTERVAL_MS) {
                    lastNotify = now
                    notifyProgress(id)
                }
            }
            if (DownloadQueue.isCancelRequested(id)) throw YoutubeDL.CanceledException()
            finish(id, saveFrom(dir, id), errorMessage = null)
        } catch (e: YoutubeDL.CanceledException) {
            Engine.workDir(this, id).deleteRecursively()
            DownloadQueue.update(id) { it.copy(state = TaskState.CANCELLED) }
        } catch (e: Exception) {
            if (DownloadQueue.isCancelRequested(id)) {
                Engine.workDir(this, id).deleteRecursively()
                DownloadQueue.update(id) { it.copy(state = TaskState.CANCELLED) }
            } else {
                // yt-dlp exits with an error if any playlist item failed; keep what did download.
                val saved = saveFrom(Engine.workDir(this, id), id)
                finish(id, saved, errorMessage = friendlyError(e))
            }
        } finally {
            DownloadQueue.clearCancelRequest(id)
        }
    }

    private suspend fun saveFrom(dir: java.io.File, id: String): List<String> {
        if (!dir.exists()) return emptyList()
        DownloadQueue.update(id) { it.copy(state = TaskState.SAVING) }
        notifyProgress(id)
        return Exporter.exportAll(this, dir)
    }

    private fun finish(id: String, saved: List<String>, errorMessage: String?) {
        DownloadQueue.update(id) {
            when {
                saved.isNotEmpty() && errorMessage == null ->
                    it.copy(state = TaskState.DONE, progress = 1f, savedFiles = saved,
                        message = if (saved.size == 1) "Saved to ${saved[0]}" else "Saved ${saved.size} files")
                saved.isNotEmpty() ->
                    it.copy(state = TaskState.DONE, progress = 1f, savedFiles = saved,
                        message = "Saved ${saved.size} file(s); some items failed: $errorMessage")
                else ->
                    it.copy(state = TaskState.FAILED, message = errorMessage ?: "No file was produced")
            }
        }
        DownloadQueue.get(id)?.let { Notifications.finished(this, it) }
    }

    private fun notifyProgress(id: String) {
        val task = DownloadQueue.get(id) ?: return
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(Notifications.FOREGROUND_ID, Notifications.progress(this, task, DownloadQueue.queuedCount()))
    }

    /** Android 15+ limits dataSync services to 6 hours a day. Stop cleanly if we hit it. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadQueue.cancelAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
}

/** yt-dlp prints "ERROR: ..." lines; show the last one instead of the whole log. */
fun friendlyError(e: Exception): String {
    val msg = e.message.orEmpty()
    return msg.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim()
        ?: msg.lines().firstOrNull { it.isNotBlank() }?.take(300)
        ?: e.javaClass.simpleName
}
