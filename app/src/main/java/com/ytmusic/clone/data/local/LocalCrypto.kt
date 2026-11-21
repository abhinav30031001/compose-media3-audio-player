package com.ytmusic.clone.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Thrown when the hardware-backed keystore is unavailable.
 */
class CryptoUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Key provider interface for Android Keystore with zero hardcoded fallbacks.
 */
interface KeyProvider {
    @Throws(CryptoUnavailableException::class)
    fun key(): SecretKey
}

/**
 * Production key provider backed strictly by Android Keystore.
 * Throws CryptoUnavailableException if the hardware Keystore is inaccessible.
 */
object AndroidKeystoreKeyProvider : KeyProvider {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ytmusic_local_v1"

    override fun key(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (t: Throwable) {
            throw CryptoUnavailableException("Android Keystore is unavailable", t)
        }
    }
}

/**
 * Phase A3: Local storage encryption with AEAD binding (ENC2) and zero hardcoded fallbacks.
 * Protects private JSON files (library.json, player_state.json, downloaded_songs.json).
 */
object LocalCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val HEADER_ENC1 = "ENC1"
    private const val HEADER_ENC2 = "ENC2"

    @VisibleForTesting
    internal var keyProvider: KeyProvider = AndroidKeystoreKeyProvider

    fun encrypt(plainText: String, aadName: String): ByteArray {
        val key = keyProvider.key()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aadName.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val buffer = ByteBuffer.allocate(4 + 1 + iv.size + ciphertext.size)
        buffer.put(HEADER_ENC2.toByteArray(Charsets.US_ASCII))
        buffer.put(iv.size.toByte())
        buffer.put(iv)
        buffer.put(ciphertext)
        return buffer.array()
    }

    fun decryptOrMigrate(bytes: ByteArray, aadName: String): String? {
        if (bytes.isEmpty()) return null

        val isEnc2 = bytes.size >= 5 &&
                bytes[0] == 'E'.code.toByte() &&
                bytes[1] == 'N'.code.toByte() &&
                bytes[2] == 'C'.code.toByte() &&
                bytes[3] == '2'.code.toByte()

        val isEnc1 = bytes.size >= 5 &&
                bytes[0] == 'E'.code.toByte() &&
                bytes[1] == 'N'.code.toByte() &&
                bytes[2] == 'C'.code.toByte() &&
                bytes[3] == '1'.code.toByte()

        if (!isEnc1 && !isEnc2) {
            // Transparent migration: file is still plaintext JSON
            return try {
                String(bytes, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }

        return try {
            val buffer = ByteBuffer.wrap(bytes)
            val magic = ByteArray(4)
            buffer.get(magic)
            val ivLen = buffer.get().toInt() and 0xFF
            val iv = ByteArray(ivLen)
            buffer.get(iv)
            val cipherText = ByteArray(buffer.remaining())
            buffer.get(cipherText)

            val key = keyProvider.key()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            if (isEnc2) {
                cipher.updateAAD(aadName.toByteArray(Charsets.UTF_8))
            }
            val decrypted = cipher.doFinal(cipherText)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.w("LocalCrypto", e.javaClass.simpleName)
            null
        }
    }
}
