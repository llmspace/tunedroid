# TuneDroid

<p align="center">
  <img src="tunedroid-branded.png" alt="TuneDroid" width="400">
</p>

Android "audio-from-video-url" extraction app.

## Features
- Extract audio from video URLs
- Multiple format presets (MP3 128/256/320 kbps, or original format)
- Material You design with dark mode support
- No ads, no tracking, no analytics
- Open source and transparent

## Architecture
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 35 (Android 15)
- **Language**: Kotlin 2.1.0
- **UI Framework**: Jetpack Compose
- **Audio Extraction**: (junkfood02 fork) + FFmpeg
- **Database**: Room 2.6.1
- **Navigation**: Jetpack Navigation Compose

## Key Dependencies
(junkfood02 fork) + FFmpeg
- Room, WorkManager, Coil, DataStore

## Download

Don't want to build it yourself? Grab the latest APK from [tunedroid-releases](https://github.com/llmspace/tunedroid-releases/releases).

## Build Instructions

### Prerequisites
1. Android Studio Ladybug or later (2024.2.1+)
2. JDK 21 (bundled with Android Studio recommended)
3. Android SDK 35

### Environment Setup

Set the following environment variables:

**macOS/Linux:**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

**Windows:**
```cmd
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=%LOCALAPPDATA%\Android\sdk
```

### Build from Command Line

**Debug APK:**
```bash
./gradlew assembleDebug
```

**Release APK (unsigned):**
```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (or `/release/`)

### Build in Android Studio

1. Clone the repository:
   ```bash
   git clone https://github.com/llmspace/tunedroid.git
   cd tunedroid
   ```

2. Open in Android Studio: `File > Open > Select tunedroid directory`

3. Wait for Gradle sync to complete

4. Build APK: `Build > Build Bundle(s) / APK(s) > Build APK(s)`

5. Install on device: `Run > Run 'app'`

### Expected APK Size
- **Debug**: ~136 MB (includes Python runtime + FFmpeg native libraries)
- **Release (with ProGuard)**: ~120-130 MB

## Project Structure

```
app/src/main/java/com/tunedroid/app/
├── engine/          # Core audio extraction logic
│   ├── MediaUrlParser.kt
│   ├── MetadataFetcher.kt
│   ├── StreamSelector.kt
│   ├── AudioDownloader.kt
│   ├── AudioConverter.kt
│   ├── EngineUpdater.kt
│   ├── FormatPreset.kt
│   └── FileNameGenerator.kt
├── data/
│   ├── database/    # Room database (downloads history)
│   ├── repository/  # Data access layer
│   └── PreferencesManager.kt
├── service/         # DownloadService (foreground service)
├── ui/
│   ├── screens/     # Compose UI screens
│   └── theme/       # Material Design theme
├── updater/         # App update checker (GitHub Releases API)
├── MainActivity.kt
└── TuneDroidApp.kt  # Application class
```

## Development

### Code Style
- Kotlin official style guide
- 4-space indentation
- Material Design principles

### Important Notes
- **Library Imports**: `com.yausername.*` imports are necessary despite containing platform terms - this is NOT a branding violation
- **BuildConfig**: `buildConfig = true` must be set in `buildFeatures` for version checking to work

## Gotchas

- Local suspend functions inside `@Composable` can cause "Unresolved reference" — extract to file-level private functions
- Use `dependencyResolutionManagement` not `dependencyResolution` in `settings.gradle.kts`

## License
GPL-3.0 - See LICENSE file for details

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

## Contributing
Fork it and do whatever. 

## Disclaimer
This app is for educational purposes. Users are responsible for complying with the terms of service of any websites they use with this app.
