# 🎵 YouTube Music Android Client (v7.57 Hardened)

An ultra-lightweight (~2.2 MB), privacy-hardened Android music client built with **Kotlin**, **Jetpack Compose**, and **AndroidX Media3 (ExoPlayer)**. Featuring a 100% on-device multi-client extraction engine, hardware-backed Keystore AEAD encryption, strict SSRF defenses, zero telemetry, and deep audio DSP control.

---

## 🛡️ Security & Privacy Architecture

The app is engineered around defensive threat modeling to ensure zero identity leakage and strict isolation from third-party tracking:

1. **Hardware-Backed Keystore AEAD Encryption**:
   - Local playlists, liked tracks, listening history, and player state are encrypted at rest using AES-256-GCM via the Android Keystore.
   - Each data store binds unique Additional Authenticated Data (`AAD`) to prevent cross-database splicing attacks.
   - Atomic file writes via `SafeAtomicFile` protect against corruption during unexpected app termination.

2. **Network SSRF & DNS Rebinding Defenses**:
   - All network traffic routes through centralized `SecureHttp` clients configured with strict `UrlPolicy` scope allowlists.
   - Custom `SafeDns` resolves IP addresses prior to connection and strictly drops:
     - Loopback (`127.0.0.0/8`, `::1`)
     - Private networks (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`)
     - Carrier-Grade NAT (`100.64.0.0/10`)
     - Link-Local and multicast (`169.254.0.0/16`, `224.0.0.0/4`, `fe80::/10`)
     - Benchmark and documentation ranges (`198.18.0.0/15`, `192.0.2.0/24`)
     - IPv4-mapped IPv6 representations (`::ffff:0:0/96`).

3. **IPC & Controller Authorization**:
   - `MusicPlayerService` enforces caller package verification via `PackageValidator`.
   - Distinguishes trusted system controllers (System UI lockscreen media controls, Android Auto) from third-party applications, rejecting unauthorized background control commands.

4. **Cryptographically Signed Remote Configuration**:
   - Dynamic client version updates are authenticated with Ed25519 digital signatures.
   - Replay and rollback protection rejects configurations with timestamps older than active parameters or exceeding a 30-day forward drift.
   - Payloads are strictly capped at 16 KB to protect against heap exhaustion.

5. **Diagnostic & Logging Sanitization**:
   - `DiagnosticSanitizer` scrubs logcat outputs, crash traces, and UI diagnostics of session tokens, `visitorData`, authorization headers, and query parameters (`sig`, `lsig`, `pot`).
   - Release builds are non-debuggable with ProGuard logging rule stripping and R8 full-mode optimization.

6. **Backup & Data Extraction Lockdown**:
   - `data_extraction_rules.xml` excludes app storage, databases, and preferences from ADB backups and cloud migration agents, keeping listening data strictly on-device.

7. **Zero-Permission Secure Audio Export (SAF)**:
   - Export downloaded tracks directly to device shared storage (`Music/`, `Downloads/`) via Android's Storage Access Framework with zero broad storage permissions.
   - Enforces a 6-layer defense pipeline: deep 64-byte container magic number verification (Opus `OggS`+`OpusHead`, AAC `ftyp` brands), instant blocking of ELF, APK/ZIP, PE executables, shell scripts, and HTML injection, filename sanitization (Unicode Bidi RTLO stripping, DOS device name protection, extension lock), scheme and DocumentsProvider authority verification, canonical sandbox containment checks, 150 MB safety cap, 32 KB chunked streaming with POSIX `fsync()`, byte-for-byte count verification, and atomic rollback on error.

---

## ✨ Features (v7.57)

### Playback & DSP Audio Engine
- **Decoupled Scrubbing & Haptics**: Seekbar decouples continuous drag from audio stream requests to eliminate network jitter; displays live scrub time and delta pills (`+0:15s`), accompanied by gentle haptic ticks on minute crossings.
- **Synchronized Karaoke Lyrics**: Millisecond-precision LRC parsing via LRCLIB with search fallback; active lines feature dynamic font scaling (`1.04f`) and opacity emphasis, auto-scroll centering, and interactive tap-to-seek.
- **Optimized 5-Band Equalizer**: Interactive frequency sliders (60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz), customizable presets, rotary Bass Boost (0–100%), and 3D Virtualizer (0–100%). Drag gestures update hardware DSP in real-time with zero audio pops, debouncing disk writes until drag completion (`onValueChangeFinished`), with tactile snaps at unity gain (`0 dB`) and a dedicated header "Reset Flat" button.
- **Pitch & Speed Studio**: Variable playback speed (0.5x to 2.0x) and independent semitone pitch shifting (-6 to +6 semitones) powered by Media3's `SonicAudioProcessor`, featuring tactile snap at 1.0x/0 st and persistent audio parameters across track advancements.
- **Volume Normalization (Sound Check)**: Integrated `LoudnessEnhancer` audio effect normalizes perceived loudness differences across tracks.
- **Fade Transitions**: Configurable fade between tracks (Off, 3s, 5s, 8s, 12s) — the outgoing track fades down, the next fades in at its own normalized loudness (not an overlapping crossfade). The next track's first ~25 s are pre-buffered into the audio cache so track changes start almost instantly. 250ms micro-fades on manual skip eliminate digital clicks.
- **Sleep Timer with Fade-Out**: Duration presets (5m to 120m), "End of Track" pausing, and gentle 3-second volume fade-out.
- **Output Switcher (Cast / MediaRouter2)**: Seamless routing to Bluetooth, Google Cast, and system audio endpoints using `MediaRouter2.showSystemOutputSwitcher()` on Android 14+/15/17, System UI panel on Android 11–13, and in-app fallback.

### Official Music Video Mode (v7.57)
- **Strict Official Video Matching (`OfficialVideoMatcher`)**: Uses YouTube Music metadata (`MUSIC_VIDEO_TYPE_OMV` / `counterpart`), verified artist/VEVO channels, whole-word variant/lyric/cover filtering, asymmetric official-video duration windows, per-candidate rejection logging, and a persistent 200-entry `ExtractorPrefs` lookup cache.
- **Audio-Parity Video Streaming (`MergingMediaSource` + `VideoCodecPolicy`)**: Pairs `1080p`/`720p`/`480p`/`360p` hardware-decodable video (`H.264/AVC1` > `VP9` > `AV1`) with **Opus 160 kbps** non-DRC audio and identical `-14 LUFS` loudness normalization, with `VISIONOS_HLS` and progressive MP4 fallbacks.
- **Edge-to-Edge Fullscreen & Gestures**: Root-window landscape fullscreen with display-cutout support, **Fit / Fill** + pinch-to-zoom, symmetric **10s** seek controls, double-tap `±10s`, live quality switching, and race-safe rapid **Song ↔ Video** toggling.

### Queue & Library Management
- **Fluid Floating Drag-and-Drop Queue**: Grab the handle and the row lifts under your finger (haptic pick-up, slight scale and shadow) and tracks it exactly; rows slide out of the way smoothly, the list auto-scrolls near the edges, and the row settles softly into its slot on release. The handle takes priority over list scrolling and row taps.
- **Playlist-Bounded Shuffle & Repeat**: True 0ms-gap `Player.REPEAT_MODE_ONE`, and clean shuffle that restores original playlist order when toggled off with zero external queue pollution.
- **Infinite Autoplay & Quick Actions**: Extends queue when approaching completion; swipe right to "Play next", swipe left to "Add to queue".
- **Offline Library Search & Active Downloads**: Instant in-memory search filter with zero network calls, plus live active download cards with spinning progress indicators.
- **Hardened Backup & Restore v2**: Authenticated v2 envelope with SHA-256 cryptographic content digest, 15 MB streaming guardrail, pre-parse JSON magic header verification, deep field sanitization (Unicode Bidi RTLO stripping, string length clamping, thumbnail domain filtering, absolute path stripping), and interactive user choice (Merge vs. Clean Replace) via Storage Access Framework.
- **Secure Audio Export (SAF)**: Export downloaded tracks to device storage with zero broad permissions via system file picker (`CreateDocument`), accessible via the three-dot menu on any downloaded song.
- **Lockscreen & Notification Controls**: Custom MediaSession command buttons for Favorite/Like and Repeat mode toggling from the system lockscreen and notification card.

### UI & Motion Architecture
- **Single-Sheet Morphing Architecture**: The player sheet follows vertical drags 1:1, smoothly morphing the MiniPlayer into the FullPlayer with dynamic corner radius interpolation.
- **Animated Liquid Mesh Backdrop**: Apple Music-inspired fluid canvas extracting dominant and complementary color accents from album artwork.
- **Modular Component Architecture**: Decoupled player components (`LyricsPanel.kt`, `QueuePanel.kt`, `AnimatedLiquidArtworkBackground.kt`, `PitchSpeedStudioDialog.kt`, `EqualizerSheet.kt`, `SleepTimerSheet.kt`).
- **Android Hierarchical Back Navigation**: "Press back again to exit" with 2-second confirmation window to prevent accidental exits.

---

## 🛠️ Tech Stack & Dependencies

- **Language**: Kotlin 2.0.0
- **UI Framework**: Jetpack Compose (Material 3)
- **Audio Engine**: AndroidX Media3 1.3.1 (ExoPlayer, HLS, OkHttp DataSource, Session)
- **Image Loading**: Coil 2.6.0
- **Cryptography**: BouncyCastle (`bcprov-jdk18on:1.78.1`), Android Keystore (AES-256-GCM)
- **Networking**: OkHttp 4.12.0
- **JSON Serialization**: Gson 2.10.1

---

## 🔨 Building the Project

### Building in Google Colab
1. Upload `ytmusic-android-v7-57-final.zip` to your Colab session.
2. Run the provided builder script to compile with R8 minification and sign with your release key.

### Building Locally via Gradle
Ensure you have Android SDK 34+ installed. Run:
```bash
# Run the 100 unit tests
./gradlew testReleaseUnitTest

# Assemble signed release APK
export YT_KEYSTORE_PATH="/path/to/keystore.jks"
export YT_KEYSTORE_PASSWORD="your_password"
export YT_KEY_ALIAS="your_alias"
export YT_KEY_PASSWORD="your_key_password"

./gradlew assembleRelease
```
The output APK is generated at `app/build/outputs/apk/release/app-release.apk` (~2.2 MB).

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 🔊 Audio Fidelity Notes (v7.41)

- Format picker skips DRC (dynamic-range-compressed) variants and non-default/dubbed audio tracks, and ranks by codec then average bitrate.
- Audio cache keys include the format, so Opus/AAC/HLS bytes of the same song never mix.
- HLS fallback forces the highest audio rendition.
- If the device cannot decode Opus, the track is retried as AAC automatically.
- Search keeps YouTube Music's own result order (user uploads are only moved to the bottom).
- Thumbnails fall back from `maxresdefault` to `sddefault`/`hqdefault` when the large image does not exist.

## v7.53 – Official video work (in progress)
- **Step 5:** Official-video matching moved to `OfficialVideoMatcher` (pure Kotlin, unit-tested in
  `OfficialVideoMatcherTest`). Fixes: titles like `Song - From "Movie"` / `- Remastered 2011` no longer
  block official videos; remix/live/part variants rejected on both sides; label channels (T-Series, Sony)
  accepted when duration matches; untyped YT Music results accepted under strict checks; searches use the
  cleaned title.
