# Compose Media3 Audio & DSP Player (v7.67)

An ultra-lightweight (~2.2 MB), privacy-hardened Android audio streaming and DSP player built with **Kotlin 2.0.0**, **Jetpack Compose (Material 3)**, and **AndroidX Media3 (ExoPlayer)**. Engineered with hardware-backed Keystore AEAD encryption, strict SSRF/DNS-rebinding defenses, zero telemetry, synchronized lyrics, official music-video parity streaming, and real-time audio DSP controls.

## Security & Privacy Architecture

1. **Hardware-Backed Keystore AEAD Encryption (`LocalCrypto`, `SafeAtomicFile`)**
   - Local playlists, liked tracks, listening history, and player state are encrypted at rest using AES-256-GCM via the Android Keystore with unique Additional Authenticated Data (`AAD`) per store.
   - Atomic file writes via `SafeAtomicFile` protect against corruption during unexpected app termination.

2. **Network SSRF & DNS Rebinding Defenses (`SecureHttp`, `UrlPolicy`, `SafeDns`)**
   - All network traffic routes through centralized `SecureHttp` clients with strict `UrlPolicy` scope allowlists.
   - Custom `SafeDns` resolves IP addresses prior to connection and drops loopback (`127.0.0.0/8`, `::1`), private LAN (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), Carrier-Grade NAT (`100.64.0.0/10`), link-local/multicast (`169.254.0.0/16`, `224.0.0.0/4`, `fe80::/10`), benchmark/documentation ranges, and IPv4-mapped IPv6 addresses (`::ffff:0:0/96`).

3. **IPC & Controller Authorization (`PackageValidator`)**
   - `MusicPlayerService` verifies calling packages before accepting MediaSession commands, distinguishing trusted system controllers (System UI lockscreen, Android Auto) from unauthorized third-party apps.

4. **Cryptographically Signed Remote Configuration**
   - Dynamic client configuration updates are verified with Ed25519 digital signatures, replay/rollback timestamp checks (30-day forward drift cap), and a 16 KB payload limit.

5. **Diagnostic Sanitization & Backup Lockdown**
   - `DiagnosticSanitizer` scrubs tokens, `visitorData`, authorization headers, and sensitive query parameters (`sig`, `lsig`, `pot`) from logs and diagnostics.
   - `data_extraction_rules.xml` excludes app storage, databases, and preferences from ADB backups and cloud migration; release builds use R8 full-mode optimization with logging stripped.

6. **Zero-Permission Secure Audio Export (SAF)**
   - Exports downloaded tracks to shared device storage (`Music/`, `Downloads/`) via Android's Storage Access Framework (`CreateDocument`) with zero broad storage permissions.
   - Enforces a 6-layer validation pipeline: 64-byte container magic-byte verification (Opus `OggS`+`OpusHead`, AAC `ftyp`), executable/HTML rejection, Unicode Bidi RTLO filename sanitization, DocumentsProvider authority checks, canonical sandbox containment, 150 MB cap, 32 KB chunked streaming with POSIX `fsync()`, byte-count verification, and atomic rollback on failure.

## Key Features

### Playback & Audio DSP Engine
- **Format & Audio Fidelity:** Prioritizes non-DRC studio audio ranked by codec and bitrate, isolates cache keys by format so Opus/AAC/HLS bytes never mix, forces highest HLS audio rendition on fallback, and automatically retries in AAC if Opus decoding fails.
- **Decoupled Scrubbing & Haptics:** Seekbar decouples continuous drag from stream requests to prevent network jitter, displaying live scrub time, delta pills (`+0:15s`), and haptic ticks at minute crossings.
- **Synchronized Karaoke Lyrics:** Millisecond-precision LRC parsing via LRCLIB with search fallback, active-line scaling (`1.04f`), auto-scroll centering, and tap-to-seek.
- **5-Band Equalizer & Effects:** Real-time sliders (60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz), customizable presets, rotary Bass Boost (0–100%), 3D Virtualizer (0–100%), unity-gain (`0 dB`) tactile snaps, header "Reset Flat", and debounced disk persistence.
- **Pitch & Speed Studio:** Independent playback speed (`0.5x`–`2.0x`) and semitone pitch shifting (`-6` to `+6` st) powered by Media3's `SonicAudioProcessor`.
- **Loudness Normalization, Fade Transitions & Sleep Timer:** `LoudnessEnhancer` volume normalization, configurable track fade transitions (Off, 3s, 5s, 8s, 12s) with 25s pre-buffering and 250ms skip micro-fades, plus sleep timer presets with 3-second fade-out.
- **Output Switcher:** Seamless routing to Bluetooth, Cast, and system audio endpoints via `MediaRouter2.showSystemOutputSwitcher()` (Android 14+) and system/in-app fallbacks.

### Official Music Video Mode
- **Strict Video Matching (`OfficialVideoMatcher`):** Matches official music videos using verified artist/label checks, whole-word remix/live/cover filtering, asymmetric duration windows, and a 200-entry persistent lookup cache.
- **Audio-Parity Streaming (`MergingMediaSource` + `VideoCodecPolicy`):** Pairs `1080p`/`720p`/`480p`/`360p` hardware-decodable video (`H.264/AVC1` > `VP9` > `AV1`) with Opus 160 kbps non-DRC audio and `-14 LUFS` loudness parity, with HLS and progressive fallbacks.
- **Fullscreen & Gestures:** Edge-to-edge landscape fullscreen with cutout support, Fit/Fill + pinch-to-zoom, double-tap `±10s` seek, live quality switching, and race-safe Song ↔ Video toggling.

### Queue, Library & Motion UI
- **Floating Drag-and-Drop Queue:** Handle-initiated row lifting with haptic feedback, edge auto-scroll, swipe-to-queue actions, playlist-bounded shuffle/repeat (`REPEAT_MODE_ONE`), and infinite autoplay.
- **Offline Library & Hardened Backup v2:** Instant zero-network library search, live active download progress cards, and SHA-256 authenticated JSON backup/restore via SAF with Merge vs. Clean Replace modes.
- **Single-Sheet Morphing & Liquid Backdrop:** 1:1 draggable MiniPlayer-to-FullPlayer sheet transition with dynamic corner-radius interpolation, album-art liquid mesh background, and Lockscreen/Notification MediaSession controls.

## Tech Stack

- **Language:** Kotlin `2.0.0`
- **UI:** Jetpack Compose (Material 3, BOM `2024.06.00`)
- **Media Engine:** AndroidX Media3 `1.3.1` (ExoPlayer, HLS, OkHttp DataSource, Session)
- **Networking & Serialization:** OkHttp `4.12.0`, Gson `2.11.0`, Coil `2.7.0`
- **Cryptography:** BouncyCastle `1.78.1`, Android Keystore (`AES-256-GCM`)
- **Build & Testing:** AGP `8.4.2`, Gradle, JUnit `4.13.2` (149 unit tests)

## Building Locally

Requires Android SDK 34+ and JDK 17.

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug

# Assemble signed release APK
export YT_KEYSTORE_PATH="/path/to/keystore.jks"
export YT_KEYSTORE_PASSWORD="your_password"
export YT_KEY_ALIAS="your_alias"
export YT_KEY_PASSWORD="your_key_password"

./gradlew assembleRelease
```

The release APK is generated at `app/build/outputs/apk/release/app-release.apk`.

## License

This project is licensed under the MIT License — see [LICENSE](https://www.google.com/search?q=LICENSE) for details.
