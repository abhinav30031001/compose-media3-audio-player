package com.ytmusic.clone.data.local

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * First-Install Password Gate.
 *
 * - Set [REQUIRE_INSTALL_PASSWORD] to `false` (or remove the gate check in MainActivity)
 *   in any future update to completely disable the password requirement for everyone.
 * - Uses PBKDF2WithHmacSHA256 (120,000 iterations) so no plain-text password exists in the APK.
 * - Automatically unlocks existing users upgrading from an earlier version without a password prompt.
 * - Persists lock state & brute-force cooldown in hardware-backed Keystore AEAD storage.
 */
object InstallAccessGate {

    /**
     * Master toggle: set to `false` in a future update to remove the install password completely.
     */
    const val REQUIRE_INSTALL_PASSWORD = true

    private const val FILE_NAME = "install_gate.enc"
    private const val AAD_NAME = "install_gate"

    // PBKDF2-HMAC-SHA256 (120,000 iterations) salt & hash — zero plain-text password stored.
    private const val SALT_HEX = "e5958541be8e402df5f0994457d97638"
    private const val EXPECTED_HASH_HEX = "78fabb125b5c69c506c844f0d1aa49fc5317884037edc43dc50bd58c59bb722b"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val MAX_FREE_ATTEMPTS = 5

    private val gson = Gson()
    private val lock = Any()

    data class GateState(
        val unlocked: Boolean = false,
        val failedAttempts: Int = 0,
        val lockedUntilMs: Long = 0L
    )

    sealed class UnlockAttemptResult {
        object Success : UnlockAttemptResult()
        data class LockedOut(val remainingSeconds: Long) : UnlockAttemptResult()
        data class WrongPassword(val attemptsRemainingBeforeLockout: Int) : UnlockAttemptResult()
    }

    fun isUnlocked(context: Context): Boolean {
        if (!REQUIRE_INSTALL_PASSWORD) return true

        synchronized(lock) {
            val state = loadState(context)
            if (state.unlocked) return true

            // Auto-unlock if this is an update over an already-installed previous version
            if (isUpgradeFromExistingInstall(context)) {
                saveState(context, GateState(unlocked = true, failedAttempts = 0, lockedUntilMs = 0L))
                return true
            }

            return false
        }
    }

    fun getRemainingLockoutSeconds(context: Context): Long {
        synchronized(lock) {
            val state = loadState(context)
            val now = System.currentTimeMillis()
            return if (state.lockedUntilMs > now) {
                ((state.lockedUntilMs - now) + 999L) / 1000L
            } else {
                0L
            }
        }
    }

    fun attemptUnlock(context: Context, passwordInput: String): UnlockAttemptResult {
        if (!REQUIRE_INSTALL_PASSWORD) return UnlockAttemptResult.Success

        synchronized(lock) {
            val now = System.currentTimeMillis()
            val state = loadState(context)

            if (state.unlocked) return UnlockAttemptResult.Success

            if (state.lockedUntilMs > now) {
                val remSec = ((state.lockedUntilMs - now) + 999L) / 1000L
                return UnlockAttemptResult.LockedOut(remSec)
            }

            if (verifyPasswordHash(passwordInput)) {
                saveState(context, GateState(unlocked = true, failedAttempts = 0, lockedUntilMs = 0L))
                return UnlockAttemptResult.Success
            }

            val newFails = state.failedAttempts + 1
            val cooldownMs = when {
                newFails >= 11 -> 10 * 60_000L // 10 minutes
                newFails >= 8 -> 2 * 60_000L   // 2 minutes
                newFails >= MAX_FREE_ATTEMPTS -> 30_000L // 30 seconds
                else -> 0L
            }

            val lockedUntil = if (cooldownMs > 0L) now + cooldownMs else 0L
            saveState(context, GateState(unlocked = false, failedAttempts = newFails, lockedUntilMs = lockedUntil))

            return if (cooldownMs > 0L) {
                UnlockAttemptResult.LockedOut(cooldownMs / 1000L)
            } else {
                UnlockAttemptResult.WrongPassword((MAX_FREE_ATTEMPTS - newFails).coerceAtLeast(0))
            }
        }
    }

    internal fun verifyPasswordHash(input: String): Boolean {
        if (input.isEmpty()) return false
        return try {
            val salt = hexToBytes(SALT_HEX)
            val expected = hexToBytes(EXPECTED_HASH_HEX)
            val spec = PBEKeySpec(input.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val actual = factory.generateSecret(spec).encoded
            spec.clearPassword()
            MessageDigest.isEqual(actual, expected)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Detects whether the app was updated over an existing installation (including older versions
     * that predated InstallAccessGate) versus a brand-new fresh install.
     */
    private fun isUpgradeFromExistingInstall(context: Context): Boolean {
        // 1. Check PackageManager install vs. update timestamps (>10s difference indicates an update)
        try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (pkgInfo.lastUpdateTime - pkgInfo.firstInstallTime > 10_000L) {
                return true
            }
        } catch (_: Throwable) {}

        // 2. Check if pre-existing user data files from an earlier version already exist on disk
        val filesDir = context.filesDir
        val hasLibrary = File(filesDir, "library.json").exists()
        val hasPlayerState = File(filesDir, "player_state.json").exists()
        val hasDownloads = File(filesDir, "downloads/downloaded_songs.json").exists()
        val hasPrefs = try {
            context.getSharedPreferences("extractor_prefs", Context.MODE_PRIVATE).all.isNotEmpty()
        } catch (_: Throwable) {
            false
        }

        return hasLibrary || hasPlayerState || hasDownloads || hasPrefs
    }

    private fun loadState(context: Context): GateState {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return GateState()
            val raw = SafeAtomicFile.readBytes(file)
            val json = LocalCrypto.decryptOrMigrate(raw, AAD_NAME) ?: return GateState()
            gson.fromJson(json, GateState::class.java) ?: GateState()
        } catch (_: Throwable) {
            GateState()
        }
    }

    private fun saveState(context: Context, state: GateState) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            val json = gson.toJson(state)
            val encrypted = LocalCrypto.encrypt(json, AAD_NAME)
            SafeAtomicFile.writeBytes(file, encrypted)
        } catch (_: Throwable) {
            // Best-effort persistence
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val out = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }
}
