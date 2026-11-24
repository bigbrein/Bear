# Bear

An Android app (Android 10+) that downloads video, audio or whole playlists from a link
and saves them on your device. It uses [yt-dlp](https://github.com/yt-dlp/yt-dlp) and
FFmpeg under the hood, via [youtubedl-android](https://github.com/JunkFood02/youtubedl-android).

Target sites: YouTube, TikTok, Instagram, X, Pinterest, Reddit (anything yt-dlp supports should work).

## Status: phase 1 (proof of concept)

- Paste a link, or share one to Bear from another app
- Video (best quality, mp4) or audio (mp3, with cover art and tags)
- Playlist links download every item into a folder named after the playlist
  (a `watch?v=…&list=…` link downloads just that one video)
- Progress, cancel
- yt-dlp updates itself to the nightly build once a day, plus a manual "Update yt-dlp" button
- Files are saved to `Download/Bear`

Known limits for now: downloads run while the app is open (no background service yet),
one download at a time, no format picker, no history, no cookies/login.

## Roadmap

1. ~~Proof of concept~~
2. MVP: background downloads with notifications, queue, format/quality picker
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
- Bear isn't on Google Play: Play doesn't allow YouTube downloaders.
- Only download content you have the right to download.
