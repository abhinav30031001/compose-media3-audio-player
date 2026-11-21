# Changelog

## v7.57 (Step 9 — Final Release)
- Final regression pass across `OfficialVideoMatcher`, `StreamResolver`, `YouTubeExtractor`, `MusicPlayerManager`, `FullPlayerSheet`, and `ExtractorPrefs`.
- Added `rejectLog` unit test coverage in `OfficialVideoMatcherTest.kt`.
- Updated `README.md` and `CHANGELOG.md`.

## v7.56 (Step 8 — Switching Conflicts & Lifecycle)
- Added monotonic `loadGeneration` counter in `MusicPlayerManager` to prevent rapid `Song <-> Video` or track-switch races.
- Suppressed fallback toast spam on playlist auto-advance while keeping explicit tap feedback.
- Added `seekTo()` duration clamping and next-track video pre-warming when `videoPreferred` is active.

## v7.55 (Step 7 — Quality, Codecs & HLS Fallback)
- Added H.264 fallback in `resolveAdaptiveOrProgressiveVideo` when `MediaCodecList` is overly strict, plus `maxHeight`-aware progressive fallback.
- Ensured `VISIONOS_HLS` fallback preserves `maxAudioBitrate` and `ROLE_FLAG_MAIN` for non-dubbed audio while capping video resolution.

## v7.54 (Step 6 — Robust Video Lookup)
- Added per-candidate rejection reason logging in `OfficialVideoMatcher` and `YouTubeExtractor`.
- Added `musicTwoRowItemRenderer`, `gridVideoRenderer`, and `compactVideoRenderer` response parsing.
- Added `withTimeoutOrNull` + `ensureActive()` cancellation and persistent 200-entry official video ID cache in `ExtractorPrefs`.

## v7.42 – v7.53
- Fixed lockscreen foreground service drops and restored monochrome Heart/Repeat/Play Emblem notification icons.
- Added playlist sorting (**Recently added**, **Default**, **Title A–Z**, **Artist A–Z**) and in-playlist search.
- Added `Song | Video` mode, edge-to-edge landscape fullscreen with Fit/Fill and pinch-to-zoom, and `OfficialVideoMatcher`.
