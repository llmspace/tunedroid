# Changelog

All notable changes to TuneDroid will be documented in this file.

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
