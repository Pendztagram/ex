# IdlixProvider Fix Plan

## Issues Found & Fixed
1. ✅ `Majorplay` extractor NOT registered in `IdlixProviderPlugin.kt` — FIXED
2. ✅ `loadLinks` regex/parsers outdated — FIXED with expanded fallback selectors & regex
3. ✅ `extractUrlFromSolveResponse` limited JSON field handling — FIXED with recursive JSON search
4. ✅ `emitDirectMediaLinks` only handled `majorplay.net` — FIXED with broader embed domain matching
5. ✅ `Majorplay` extractor fallback too narrow — FIXED with multiple script patterns

## Files Changed (IdlixProvider)
- `IdlixProviderPlugin.kt` — Register `Majorplay()`
- `IdlixProvider.kt` — Logging, expanded fallback parsing, recursive `extractUrlFromSolveResponse`, broader `emitDirectMediaLinks`
- `Extractor.kt` — `Majorplay` with more script regex patterns and `.txt` support

## Files Changed (SoraStream)
- `SoraStreamPlugin.kt` — Register `Majorplay2()` extractor
- `Extractors.kt` — Added `Majorplay2` extractor class with WebViewResolver + script/HLS fallback
- `SoraExtractor.kt` — Rewritten `invokeIdlix` (API search → challenge/solve → loadExtractor), added `invokeYflix` (new source), updated `invokeKisskh` with inline `.txt` decryption, added helper functions (`solvePow`, `sha256`, `extractUrlFromSolveResponse`, `kisskhDecryptTxt`, `kisskhDecryptLine`, `IntArray.toKisskhByteArray`)

## Notes
- Domain `z1.idlixku.com` is behind Cloudflare challenge; the `CloudflareKiller` interceptor handles this.
- Added extensive `Log.d()` at each stage (`aclr`, `challengeText`, `solveRes`, `embedUrl`, `resolvedUrl`, `fallback`) for debugging.
- Fallback now checks: `iframe[src]`, `iframe[data-src]`, `video source[src]`, `video[src]`, script URLs, direct `.m3u8`/`.mp4` in scripts, and AES-GCM decryption.
- Build verified: `gradlew :SoraStream:compileDebugKotlin` — BUILD SUCCESSFUL

