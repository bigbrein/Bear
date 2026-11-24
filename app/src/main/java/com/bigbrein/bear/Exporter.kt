package com.bigbrein.bear

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Moves finished files from the app's private cache into the public
 * Download/Bear folder using MediaStore (no storage permission needed on Android 10+).
 */
object Exporter {
    private const val ROOT = "Download/Bear"

    // Leftovers yt-dlp may leave behind that the user doesn't want.
    private val skipExtensions = setOf("part", "ytdl", "jpg", "webp", "png", "json", "temp")

    suspend fun exportAll(context: Context, workDir: File): List<String> = withContext(Dispatchers.IO) {
        val saved = mutableListOf<String>()
        workDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() !in skipExtensions }
            .forEach { file ->
                val subDir = file.parentFile!!.relativeTo(workDir).path
                val relativePath = if (subDir.isEmpty()) "$ROOT/" else "$ROOT/$subDir/"
                if (copyToDownloads(context, file, relativePath) != null) {
                    saved += relativePath + file.name
                }
            }
        workDir.deleteRecursively()
        saved
    }

    private fun copyToDownloads(context: Context, file: File, relativePath: String): Uri? {
        val resolver = context.contentResolver
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
