# ZipGallery

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

## Screenshots

*(Add screenshots here)*

## Download

[Latest APK](https://github.com/maxxNcode/zipgallery/releases)

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
