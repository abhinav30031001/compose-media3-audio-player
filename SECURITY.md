# Security Architecture & Verification: YT Music Android

This document details the verified security posture, threat model, cryptographic architecture, key rotation workflows, and evidence matrix for the hardened **YT Music Android** application (v7.38).

---

## 1. Threat Model

1. **Supply Chain & Build Integrity:** Release APKs mistakenly signed with compromised debug keys, tampered Gradle wrapper binaries, or unvetted upstream dependencies.
2. **Network Interception & SSRF:** Malicious DNS rebinding, internal network probing (loopback/private IP traversal), insecure HTTP scheme fallback, unconstrained redirect loops, or memory exhaustion via unbounded payload dumps.
3. **IPC & Rogue MediaController Exploitation:** Malicious third-party apps attempting to hijack playback, extract listening history, browse private playlists, or inject arbitrary file/content URIs via `MediaSession` and `MediaLibraryService`.
4. **Remote Configuration Tampering:** Unauthorized overrides of client version strings, replay attacks with outdated configuration payloads, or downgrade attacks bypassing YouTube's bot-detection mitigations.
5. **Local Data Theft & Information Disclosure:** Extraction of user playlists, liked songs, or download indices from plaintext storage; path-traversal attacks via manipulated download paths; and leakage of sensitive tokens/URLs into system logs or clipboard history.

---

## 2. Cryptographic Architecture & Defenses

### Remote Configuration Cryptographic Integrity (`RemoteConfigFetcher.kt`)
* **Dual-Provider Ed25519 Verification:** Verifies Ed25519 signatures over canonical version manifests (`visionos=<v>&android_vr=<v>&remix=<v>&ts=<ts>`). On Android API 33+, uses platform `Signature.getInstance("Ed25519")`; on API 26–32, falls back to BouncyCastle `Ed25519Signer`.
* **Key Rotation:** `RemoteConfigFetcher.EMBEDDED_PUBLIC_KEYS_BASE64` contains an array of trusted public keys, accepting any manifest signed by any active key.
* **Timestamp & Rollback Protection:** Enforces non-negative timestamps bounded by `last_config_ts < ts <= now + 30 days`.
* **Offline Signing Tool:** `tools/sign_config.py` enforces passphrase-protected PEM files (`BestAvailableEncryption`), sets restrictive `0600` permissions, and refuses to write private keys inside git repositories.

### Local Storage Encryption & AEAD Binding (`LocalCrypto.kt`)
* **Hardware-Backed AES-256-GCM:** Encrypts `library.json`, `player_state.json`, and `downloaded_songs.json` using non-exportable Android Keystore keys (alias `ytmusic_local_v1`).
* **AEAD Domain Binding (`ENC2`):** Applies Associated Authenticated Data (AAD) for each file type (`"library"`, `"player_state"`, `"downloads_index"`). Files cannot be swapped across stores.
* **Loss-Proof Atomic Persistence (`SafeAtomicFile.kt`):** Uses Android's native `android.util.AtomicFile` (startWrite/finishWrite/failWrite) on device with atomic temp-file fallback in tests.
* **Transparent Migration & Failure Safety:** Reads legacy `ENC1` and plaintext files seamlessly. On decryption failure, the unreadable file is preserved as `<name>.corrupt`, `loadFailed` is set, and the app refuses to overwrite corrupt data.

### Network Layer Protection (`SecureHttp.kt`, `UrlPolicy.kt`)
* **Network Interceptor Redirect Gate:** Uses `addNetworkInterceptor` and `guardedDns` to validate every network hop before DNS resolution or connection initiation occurs.
* **Granular Allowlisting:** Strict dot-boundary and exact host matching across 5 traffic scopes (`STREAM`, `IMAGE`, `LYRICS`, `CONFIG`, `API`). Scheme must be `https`.
* **Comprehensive SSRF Filtering (`SafeDns`):** Rejects IPv4 `0.0.0.0/8`, CGNAT `100.64.0.0/10`, benchmarking `198.18.0.0/15`, multicast, IPv6 unique-local `fc00::/7`, link-local `fe80::/10`, loopback, and IPv4-mapped IPv6 addresses.
* **Streaming Memory Caps:** Bounded chunked streaming strictly caps InnerTube API JSON (8 MB), HLS playlists (1 MB), lyrics (512 KB), artwork (2 MB), and config (16 KB).

---

## 3. Key Rotation & Certificate Extraction Workflows

### Rotating the Remote Config Signing Key
1. Generate an encrypted private key and public key offline:
   ```bash
   python3 tools/sign_config.py --generate-key --out-key ~/ed25519_private.pem
   ```
2. Enter a strong passphrase when prompted. Store `ed25519_private.pem` offline in a secure vault.
3. Copy the printed Base64 public key and append it to `EMBEDDED_PUBLIC_KEYS_BASE64` in `data/network/RemoteConfigFetcher.kt`:
   ```kotlin
   // TODO(owner): replace with YOUR key
   val EMBEDDED_PUBLIC_KEYS_BASE64: List<String> = listOf(
       "<YOUR_NEW_BASE64_KEY>",
       "0zOI00iV02uqlZVEdKR+b6zqzdKqyVRqfDzr/1m6bGE="
   )
   ```
4. Sign configuration files before publishing to GitHub:
   ```bash
   python3 tools/sign_config.py --config client_versions.json --key ~/ed25519_private.pem
   ```

### Extracting Certificate Fingerprints for `PackageValidator`
1. Extract the SHA-256 fingerprint from the companion/controller APK:
   ```bash
   apksigner verify --print-certs controller_app.apk
   ```
2. Normalize the SHA-256 hex digest to uppercase without colons and add it to `ALLOWED_CERT_HASHES` in `player/PackageValidator.kt`.

---

## 4. Manual Device Smoke Test Checklist

Run through this checklist on the compiled release APK:
* [ ] **Search:** Search for a song, verify suggestions and search results appear.
* [ ] **Playback:** Tap play, verify audio streams and playback indicator moves.
* [ ] **Track Skip:** Next/previous buttons transition smoothly.
* [ ] **Background & Lockscreen:** Turn off screen; verify playback continues and lockscreen controls function.
* [ ] **Queue & Autoplay:** Queue tracks, verify continuous playback across songs.
* [ ] **Library Persistence:** Like a song, restart the app from recent apps, verify the liked song persists in Library.
* [ ] **Playlists:** Create a custom playlist, add a track, restart app, verify playlist persists.
* [ ] **Downloads & Offline Play:** Download an M4A track and an Opus track, enable Airplane mode, verify both play offline.
* [ ] **Lyrics:** Toggle lyrics in player; test LRCLIB privacy switch in Settings.
* [ ] **Settings:** Open Client Health & Settings; verify version fields and diagnostics.
* [ ] **Remote Config Fetch:** Tap Fetch in Settings; verify valid signed versions apply.
* [ ] **Bluetooth / Android Auto:** Verify media controls and category browsing on external controllers.

---

## 5. Security Compliance Matrix (Round 2 Tasks A1 – D)

| Task | Status | Verified Evidence (Test / Command / Class) |
| :--- | :---: | :--- |
| **A1 M4A Magic Bytes** | **DONE** | `LocalStorageSecurityTest.testAudioMagicBytesRealisticMp4Accepted`, `testAudioMagicBytesFtgpRejected` |
| **A2 Redirect Interceptor** | **DONE** | `UrlPolicyTest.testRedirectToDisallowedHostBlockedBeforeDns`, `testSameScopeRedirectToAllowedUrlFollowed` |
| **A2 Host Allowlist** | **DONE** | `UrlPolicyTest.testReliabilityGoldenListAllowed`, `testBlockedListRejected` |
| **A3 Zero Key Fallbacks** | **DONE** | `LocalStorageSecurityTest.testCryptoUnavailableDoesNotWritePlaintext`, `AndroidKeystoreKeyProvider` |
| **A3 AEAD Domain Binding** | **DONE** | `LocalStorageSecurityTest.testLocalCryptoRoundTripWithAead`, `testLocalCryptoWrongAadFails`, `testLocalCryptoTamperedCiphertextFails` |
| **A4 R8 Minification** | **DONE** | `app/build.gradle.kts` (`isMinifyEnabled = true`, `isShrinkResources = true`), generated `mapping.txt` |
| **A4 Release Lint Active** | **DONE** | `app/build.gradle.kts` (`checkReleaseBuilds = true`, `abortOnError = true`), passed `:app:lintVitalRelease` |
| **B1 Ed25519 Verification** | **DONE** | `RemoteConfigSecurityTest.testRfc8032TestVector1Verifies`, `testFreshGeneratedKeypairSignsAndVerifies` |
| **B1 Body Caps & Timestamps** | **DONE** | `RemoteConfigFetcher.readCapped(16KB)`, `nowSec + 30.days` upper bound check |
| **B1 Passphrase Tool** | **DONE** | `tools/sign_config.py` (`getpass`, `BestAvailableEncryption`, `0600` permissions) |
| **B2 Dependencies Update** | **DONE** | `gradle/libs.versions.toml`, `libs.bouncycastle.bcprov` integration |
| **B3 Truthful SECURITY.md** | **DONE** | Verified Evidence matrix, accurate constant names (`EMBEDDED_PUBLIC_KEYS_BASE64`) |
| **C1 Diagnostic Sanitizer** | **DONE** | `DiagnosticSanitizerTest.testJsonAndSnakeCaseVisitorTokensRedacted`, `testHeadersAndBearerRedacted`, `testQuerySecretsAndBareBase64Redacted` |
| **C1 Log Scrubbing** | **DONE** | Replaced `${e.message}` across 7 files with exception class name; user-friendly download Toasts |
| **C2 Package Trust List** | **DONE** | `PackageValidatorTest.testAllowlistIntegrity`, `testSpoofedPackagesRejected`, `SYSTEM_UID`, `FLAG_UPDATED_SYSTEM_APP` |
| **C3 AtomicFile Persistence**| **DONE** | `SafeAtomicFile.kt`, `LocalStorageSecurityTest.testSafeAtomicFileRoundTrip`, `rescanOrphanedAudioFiles` |
| **C4 Path Containment** | **DONE** | `LocalStorageSecurityTest.testPathContainmentRejectsSiblingAndTraversal` (`File.separator` boundary) |
| **C5 Artwork Limits** | **DONE** | `MusicPlayerManager.kt` (`readBytesCapped(2MB)`, `inJustDecodeBounds` rejecting > 4096×4096) |
| **C6 Encrypt visitorData** | **DONE** | `LocalStorageSecurityTest.testVisitorDataEncryptionRoundTrip`, `ExtractorPrefs.kt` (AES-256-GCM, AAD `visitor_data`) |
| **C7 SafeDns Completeness** | **DONE** | `UrlPolicyTest.testSafeDnsBlocksExtendedRanges` (0.0.0.0/8, CGNAT, 198.18/15, fc00::/7, fe80::/10, IPv4-mapped) |
| **D .gitignore & Retention** | **DONE** | `.gitignore` (keys, pem, keystore, gradle), `LocalStorageSecurityTest.testPartFileRetentionUnder24Hours` |

---

## 6. User-Visible Behavior Changes

1. **Download Storage Sandboxing:** Downloads are stored in private internal app storage (`context.noBackupFilesDir`). Files are protected from other apps and third-party media scanners.
2. **Offline Re-scanning:** If the database index file is ever lost or cleared, the app automatically scans and recovers valid audio tracks (`<id>.m4a|opus`) on startup.
3. **Download Resume Across Restarts:** Unfinished `.part` files under 24 hours old with valid metadata are kept across app restarts and safely resumed via `If-Range` and `Content-Range`.
4. **Lockscreen Privacy:** Media notifications are configured with `Notification.VISIBILITY_PRIVATE`.
5. **In-App Privacy Controls:** Settings features a "Privacy & Data Controls" section for LRCLIB lyrics lookups, anonymous `visitorData` reset, history clearing, and full data wipe.
6. **Network & SSL Strictness:** Cleartext HTTP and non-allowlisted redirects are strictly blocked.

---

## 7. Artifact Locations
* **Release APK:** `app/build/outputs/apk/release/app-release.apk`
* **R8 ProGuard Mapping:** `app/build/outputs/mapping/release/mapping.txt`
