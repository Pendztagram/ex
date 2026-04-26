# TODO: Fix IdlixProvider Subtitle Indonesia

## Problem
- IdlixProvider menggunakan WebViewResolver yang menangkap URL .m3u8/.mp4 langsung
- Video di-emit tanpa memanggil extractor Majorplay/Jeniusplay
- Subtitle Indonesia dari Majorplay tidak terambil

## Solution
1. [x] Update IdlixProvider.kt - Extract subtitles from Majorplay embed page HTML

## Files Edited
- IdlixProvider/src/main/kotlin/com/hexated/IdlixProvider.kt
- IdlixProvider/src/main/kotlin/com/hexated/Extractor.kt

## Changes Made

### IdlixProvider.kt
- Added `extractSubtitlesFromHtml()` function to extract subtitles from Majorplay Next.js JSON format
- Added `MajorplaySubtitle` data class
- Modified `loadLinks()` to detect Majorplay/Jeniusplay embeds and extract subtitles before emitting video

### Extractor.kt
- Added `MajorplaySubtitle` data class to Majorplay extractor
- Added `extractSubtitlesFromHtml()` function to Majorplay extractor
- Modified `getUrl()` to call subtitle extraction from HTML embed page
- Supports extraction from:
  - JSON `subtitles` array in script tags
  - JSON `initialToken` format
  - HTML `<track>` elements
  - Direct .vtt/.srt/.ass URLs

