package com.bigbrein.bear

import android.content.Context

/** Remembers the last-used download options. */
object Prefs {
    private const val NAME = "options"

    fun load(context: Context): DownloadOptions {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return DownloadOptions(
            mode = runCatching { Mode.valueOf(p.getString("mode", null)!!) }.getOrDefault(Mode.VIDEO),
            maxHeight = p.getInt("maxHeight", 0).takeIf { it > 0 },
            audioFormat = runCatching { AudioFormat.valueOf(p.getString("audioFormat", null)!!) }
                .getOrDefault(AudioFormat.MP3),
            audioQuality = runCatching { AudioQuality.valueOf(p.getString("audioQuality", null)!!) }
                .getOrDefault(AudioQuality.BEST),
        )
    }

    fun save(context: Context, o: DownloadOptions) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("mode", o.mode.name)
            .putInt("maxHeight", o.maxHeight ?: 0)
            .putString("audioFormat", o.audioFormat.name)
            .putString("audioQuality", o.audioQuality.name)
            .apply()
    }
}
