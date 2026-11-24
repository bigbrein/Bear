# Bear

An Android app (Android 10+) that downloads video, audio or whole playlists from a link
and saves them on your device. It uses [yt-dlp](https://github.com/yt-dlp/yt-dlp) and
FFmpeg under the hood, via [youtubedl-android](https://github.com/JunkFood02/youtubedl-android).

Target sites: YouTube, TikTok, Instagram, X, Pinterest, Reddit (anything yt-dlp supports should work).

## Status: phase 2 (MVP)

- Paste a link, or share one to Bear from another app
- Preview of the link (title, thumbnail, length, playlist size) before downloading
- Video: Best, 4K, 1440p, 1080p, 720p, 480p or 360p (only resolutions the link has), saved as mp4
- Audio: MP3, M4A or Opus at Best/320/192/128 kbps, with tags (and cover art for MP3/M4A)
- Your last-used options are remembered
- Download queue that runs in the background (foreground service) with a progress
  notification, a Cancel action and a "finished"/"failed" notification per download
- Cancel, retry and remove per download; "Clear finished"
- Playlist links download every item into a folder named after the playlist, showing
  "Item 3/20"; unavailable items are skipped and everything else is kept
  (a `watch?v=…&list=…` link downloads just that one video)
- yt-dlp updates itself to the nightly build once a day, plus a manual "Update yt-dlp" button
- Prompt to exempt Bear from battery optimization so the phone doesn't kill downloads
- Files are saved to `Download/Bear`

Known limits for now: the queue isn't saved if Android kills the app, one download at a
time, no history after the queue is cleared, no cookies/login, no playlist item selection.

## Roadmap

1. ~~Proof of concept~~
2. ~~MVP: background downloads with notifications, queue, format/quality picker~~
3. Playlists: choose which items to download
4. Polish: history, presets, cookies (for Instagram/X/private content), subtitles, settings
5. Signed release APKs on GitHub Releases

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```
./gradlew assembleDebug
```

APKs land in `app/build/outputs/apk/debug/`. Use `app-arm64-v8a-debug.apk` for almost
every modern phone. GitHub Actions also builds them on every push (see the Actions tab → artifacts).

## Install notes (Xiaomi / HyperOS)

- Allow installing from unknown sources for your browser or file manager.
- Tap "Allow" on Bear's battery card. On HyperOS also check Settings → Apps → Bear →
  Battery saver → "No restrictions" so background downloads aren't killed.
- Bear isn't on Google Play: Play doesn't allow YouTube downloaders.
- Only download content you have the right to download.
