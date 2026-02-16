# Changelog

All notable changes to TuneDroid will be documented in this file.

## [1.0.12] - 2026-02-16

### Fixed
- **HomeScreen state preserved on navigation**: Accidentally tapping the bottom nav no longer loses fetched metadata and audio quality selection — state is now hoisted to the parent and survives tab switching

### Changed
- **Bottom nav "Downloads" renamed to "Library"**: Eliminates confusion between the "Download Now" action button and the bottom navigation tab; icon changed to Library Music
- **"Download Now" blinking button**: When metadata is loaded, the download button now blinks between fire orange and neon green with bold "Download Now" text to clearly stand out from the navigation

## [1.0.11] - 2026-02-16

### Fixed
- **Narrowed file permissions**: Removed broad `MANAGE_EXTERNAL_STORAGE` permission — file deletion on restrictive ROMs (MIUI, etc.) now uses Android's per-file `createDeleteRequest()` system dialog instead, which requires no extra permissions
- **Delete dialog UX**: Checking "Delete file from device" now auto-checks "Remove from app list" (can't uncheck it while device-delete is selected)

### Added
- **Update download progress bar**: "Check for updates" in Settings now shows a slim animated progress bar with status text (downloading % → installing → done) instead of just a Toast

### Removed
- Removed `StoragePermissionHelper` utility and "File management access" from Settings — no longer needed since broad file access permission was removed

## [1.0.10] - 2026-02-16

### Fixed
- **Delete file from device on MIUI/Xiaomi**: File deletion now works on restrictive OEM ROMs (MIUI, ColorOS, etc.) that block standard file operations in shared storage
- Added `MANAGE_EXTERNAL_STORAGE` permission for full file management access on Android 11+
- Permission is requested only when the user tries to delete a file from device — not on first launch

### Added
- **"File management access" in Settings**: Shows permission status and allows granting it proactively — helpful for troubleshooting on devices with restrictive storage policies

### Technical
- New `StoragePermissionHelper` utility for checking/requesting All Files Access permission
- Permission gate in DownloadsScreen: checks before device file deletion, shows dialog to open Settings if not granted
- After user grants permission in Settings and returns, the pending delete executes automatically
- SettingsScreen uses `LifecycleEventObserver` to refresh permission status on resume

## [1.0.9] - 2026-02-16

### Fixed
- **Delete file from device now works**: File deletion tries direct File.delete() first (works for app-created files), then falls back to MediaStore delete — covers all Android versions
- Added `requestLegacyExternalStorage` for API 29 compatibility with direct file operations
- Extended WRITE_EXTERNAL_STORAGE to API 29 for full file management on Android 10
- After direct file delete, also cleans up stale MediaStore entry

### Technical
- Reversed delete strategy order: File.delete() first, MediaStore second — fixes ownership issue where MediaStore found the URI but couldn't delete files the app didn't "own"
- Added logging for each delete strategy to aid debugging
- Final existence check after all strategies as safety net

## [1.0.8] - 2026-02-16

### Fixed
- **Play button not working**: Tapping play on completed downloads now opens music apps correctly — uses MediaStore content URIs on Android 10+ instead of direct file paths
- **Share button not working**: Share now correctly sends audio files to other apps via MediaStore URIs
- **"Could not delete file from device"**: Delete from device now works on Android 10+ by using MediaStore delete instead of direct File.delete()
- Files not in MediaStore are automatically scanned in before play/share/delete operations

### Technical
- Added MediaStore-based URI resolution for play, share, and delete on API 29+ (scoped storage)
- DownloadService now triggers MediaScannerConnection.scanFile() after successful download so files are immediately available in MediaStore
- Fallback to FileProvider for devices below API 29
- Error messages now shown via Snackbar instead of being silently swallowed

## [1.0.7] - 2026-02-16

### Changed
- **Unified Fetch/Download button**: Single button changes from "Fetch" to "Download" after metadata loads — cleaner UI with no separate download button
- **Settings**: Renamed "Delete original after conversion" to "Delete original audio file after conversion" for clarity

### Fixed
- **Delete not working**: Trash icon on Downloads screen now correctly deletes files from app list and/or device — fixed race condition where dialog state was cleared before async delete executed
- **Original format cleanup**: Original audio files are never deleted when user chose "Original" format (no conversion) — delete-original preference only applies to MP3 conversions

### Technical
- HomeScreen: merged fetch/download into single stateful Button with label based on HomeState
- DownloadsScreen: captured downloadToDelete and checkbox states in local variables before clearing dialog state, preventing null reference in coroutine
- DownloadService: wired deleteOriginal DataStore preference into cleanup logic — skips cleanup when preset.requiresConversion is false

## [1.0.6] - 2026-02-16

### Changed
- **HomeScreen format picker**: All MP3 quality options now visible after fetching metadata — ineligible bitrates are greyed out based on source quality
- **New branding image**: Replaced home screen artwork with TuneDroid branded logo
- **Layout**: Branding image fills available space, tip text anchored to bottom
- Removed "TuneDroid" heading text from home screen

### Removed
- **Default format setting**: Removed from Settings screen — format is now chosen per-download on the Home screen

### Technical
- FormatPreset.isEligible() helper for bitrate-aware format availability
- HomeScreen shows all FormatPreset.entries with disabled state for ineligible options
- HomeScreen layout restructured: scrollable only in MediaLoaded state, weight-based in Empty/Loading

## [1.0.5] - 2026-02-16

### Fixed
- **Download recovery after reinstall/update**: Previously downloaded files now correctly reappear after reinstalling or updating the app
- Added storage read permissions (READ_EXTERNAL_STORAGE for Android 9-12, READ_MEDIA_AUDIO for Android 13+) so the app can rediscover audio files in the TuneDroid folder
- Recovery now triggers at app startup instead of only when visiting the Downloads tab

### Improved
- Downloads screen shows a clear permission prompt if storage access is needed for file recovery
- Handles permanently denied permission by directing users to app settings

### Technical
- Added READ_EXTERNAL_STORAGE (maxSdkVersion 32) and READ_MEDIA_AUDIO permissions to manifest
- Early recovery logic in TuneDroidNavHost with runtime permission request
- DownloadsScreen permission-aware fallback UI with SharedPreferences tracking for denial state

## [1.0.4] - 2026-02-15

### Improved
- **In-app updates**: APK downloads happen in-app via DownloadManager instead of opening the browser
- **Storage location**: Now display-only — shows the current path without a non-functional picker

### Fixed
- Auto-update engine toggle showing ON after upgrading from older versions (one-time preference migration)
- Download recovery after reinstall — files in TuneDroid folder are now correctly rediscovered

### Technical
- AppUpdateChecker uses DownloadManager + FileProvider for seamless APK install
- Added REQUEST_INSTALL_PACKAGES permission and external-cache-path to FileProvider config
- PreferencesManager.runMigrations() resets AUTO_UPDATE_ENGINE for upgrade users
- Fixed LaunchedEffect cancellation bug in DownloadsScreen recovery logic

## [1.0.3] - 2026-02-15

### Improved
- **Settings layout**: App version and "Check for updates" button now at the top — no scrolling needed
- **Default format**: Chevron icon makes it obvious the setting is tappable
- **Orphan scanner**: Compact settings row instead of large button with description
- **Auto-update engine**: Now defaults to OFF — users opt in manually

### Added
- **Download recovery**: After reinstall, existing audio files in the TuneDroid folder are automatically rediscovered and shown in the Downloads screen

### Fixed
- In-app update check now correctly shows update dialog when a new version is available
- Auto-update engine preference is now respected on startup (was previously ignored)

### Technical
- TuneDroidApp reads `autoUpdateEngine` preference before calling engine update
- DownloadRepository.recoverExistingFiles() scans storage folder when DB is empty
- SettingsItem composable supports optional trailing chevron icon

## [1.0.2] - 2026-02-15

### Improved
- **Home screen layout**: Fetch button now sits directly under the URL input field for quicker access
- **Branding image visible during fetch**: Cosmic rabbit stays on screen while metadata is loading
- **Tip text**: Updated to "Share a video directly to TuneDroid from your browser or other apps" and no longer cut off
- **Original format label**: Simplified to "Original" (removed redundant "no conversion" text)
- **Auto-navigate to Downloads**: After starting a download, the app automatically switches to the Downloads screen

### Fixed
- Downloads getting stuck at "Converting to MP3 100%" and never moving to Completed
- Original audio files not being cleaned up after MP3 conversion
- FINALIZING status not tracked in active downloads queries, causing UI gaps
- Converting status showing misleading percentage (now shows "Converting...")

### Technical
- Added intermediate file cleanup after successful MP3 conversion
- Improved post-processing detection with broader log line matching
- Increased file system flush delay for more reliable file detection
- DAO queries now include FINALIZING in active download counts

## [1.0.1] - 2025-02-15

### Added
- **Marquee loading animation**: Replaced static loading spinner with animated scrolling text "Working on it..." and linear progress bar
- **Cosmic rabbit branding**: Added custom branding image to empty home screen state
- **FINALIZING status**: New download status that appears after conversion completes while verifying the output file
- **Two-option delete dialog**: Users can now choose to remove downloads from the app list, delete files from device, or both
- **Cleanup utility**: New "Scan for Orphaned Files" tool in Settings to find and remove files without database records or orphaned database entries
- **Dynamic format label**: "Original (no conversion)" option now shows the detected format and bitrate (e.g., "Original — WEBM 130 kbps")

### Improved
- **Audio quality filtering**: Strictly filters quality options based on source bitrate - no longer shows MP3 256kbps for 130kbps sources
- **File finding logic**: Enhanced with 3-strategy approach (exact match, fuzzy match, recent file) with comprehensive logging
- **First launch screen**: Reduced font sizes and spacing to fit all content without scrolling on smaller screens
- **Download completion reliability**: Added delay and better file verification to prevent "stuck at 100%" issues

### Fixed
- Downloads getting stuck at 100% progress
- UI not updating after conversion completes
- Files disappearing from UI but remaining in filesystem
- Confusing bitrate warnings when selecting higher quality than source

### Technical
- Improved error handling in file deletion operations
- Added comprehensive logging for debugging download issues
- Better handling of yt-dlp filename sanitization differences

## [1.0.0] - 2025-02-14

### Initial Release
- YouTube audio extraction using yt-dlp
- MP3 conversion with quality presets (128, 256, 320 kbps)
- Original format preservation (Opus, M4A, WebM)
- Material You design with dark mode support
- Download history with progress tracking
- Automatic engine updates (yt-dlp + FFmpeg)
- GPL-3.0 licensed, open source
- Zero tracking, no ads, privacy-focused
