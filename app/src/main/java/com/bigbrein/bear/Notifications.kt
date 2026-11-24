package com.bigbrein.bear

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    private const val CH_PROGRESS = "downloads"
    private const val CH_FINISHED = "finished"
    const val FOREGROUND_ID = 1

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_FINISHED, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(context: Context, task: DownloadTask?, waiting: Int): Notification {
        val b = NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (task == null) {
            return b.setContentTitle("Preparing download…").setProgress(0, 0, true).build()
        }

        val parts = mutableListOf<String>()
        when (task.state) {
            TaskState.SAVING -> parts += "Saving…"
            else -> {
                task.playlistItem?.let { parts += "Item $it" }
                if (task.progress > 0f) parts += "${(task.progress * 100).toInt()}%"
                if (task.etaSeconds > 0) parts += "${formatDuration(task.etaSeconds)} left"
            }
        }
        if (waiting > 0) parts += "$waiting more queued"

        val cancel = PendingIntent.getService(
            context, task.id.hashCode(),
            Intent(context, DownloadService::class.java)
                .setAction(DownloadService.ACTION_CANCEL)
                .putExtra(DownloadService.EXTRA_ID, task.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val indeterminate = task.state == TaskState.SAVING || task.progress <= 0f
        return b.setContentTitle(task.title)
            .setContentText(parts.joinToString(" · ").ifEmpty { "Starting…" })
            .setProgress(100, (task.progress * 100).toInt(), indeterminate)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    @SuppressLint("MissingPermission") // checked via areNotificationsEnabled()
    fun finished(context: Context, task: DownloadTask) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val (title, text) = when (task.state) {
            TaskState.DONE -> "Downloaded" to (task.message ?: task.title)
            TaskState.FAILED -> "Download failed" to "${task.title}: ${task.message ?: "unknown error"}"
            else -> return
        }
        val n = NotificationCompat.Builder(context, CH_FINISHED)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        nm.notify(task.id.hashCode(), n)
    }
}
