# ZipGallery

[![Release](https://img.shields.io/github/v/release/maxxNcode/zipgallery?include_prereleases&label=latest)](https://github.com/maxxNcode/zipgallery/releases)
[![License](https://img.shields.io/github/license/maxxNcode/zipgallery)](LICENSE)

Browse images and videos inside ZIP, 7Z, and TAR archives seamlessly — just like a gallery app.

## Features

- Open ZIP, 7Z, TAR, TGZ, TBZ2, TXZ archives
- Password-protected archive support
- Grid gallery with thumbnails
- Image viewer with pinch-to-zoom
- Video playback with ExoPlayer
- Sort by name, size, or type
- Filter by images/videos
- Search files by name
- Theme modes: system, dark, light
- Share files via any app
- Supports all common image formats (JPEG, PNG, WebP, GIF, BMP, HEIC, HEIF)
- Supports all common video formats (MP4, MKV, WebM, AVI, 3GP, MOV)

## Download

[![Download APK](https://img.shields.io/badge/Download-v2.0.0--beta-blue?logo=android)](https://github.com/maxxNcode/zipgallery/releases/tag/v2.0.0-beta)

## Building

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Tech Stack

- Kotlin + Jetpack Compose
- zip4j (ZIP reading)
- Apache Commons Compress (7Z, TAR)
- Coil (image loading)
- ExoPlayer/Media3 (video playback)

## License

MIT
