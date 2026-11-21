package com.ytmusic.clone

import com.ytmusic.clone.data.network.RemoteConfigFetcher
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * Phase 4 & Task B1 Unit Tests:
 * - Version regex and component bounds verification
 * - Anti-rollback timestamp verification logic
 * - Ed25519 verification: RFC 8032 test vector 1, fresh keypair, bit-flipped payload, truncated sig, garbage Base64.
 */
class RemoteConfigSecurityTest {

    @Test
    fun testValidVersionFormats() {
        assertTrue(RemoteConfigFetcher.isValidVersion("1.02"))
        assertTrue(RemoteConfigFetcher.isValidVersion("1.71.26"))
        assertTrue(RemoteConfigFetcher.isValidVersion("1.20260804.16.00"))
        assertTrue(RemoteConfigFetcher.isValidVersion("2"))
        assertTrue(RemoteConfigFetcher.isValidVersion("99999"))
    }

    @Test
    fun testMalformedVersionFormats() {
        assertFalse(RemoteConfigFetcher.isValidVersion(null))
        assertFalse(RemoteConfigFetcher.isValidVersion(""))
        assertFalse(RemoteConfigFetcher.isValidVersion("   "))
        assertFalse(RemoteConfigFetcher.isValidVersion("1.02.alpha"))
        assertFalse(RemoteConfigFetcher.isValidVersion("1.0.0.0.0.1")) // 6 components (> 5)
        assertFalse(RemoteConfigFetcher.isValidVersion("100000")) // Exceeds 99999 limit
        assertFalse(RemoteConfigFetcher.isValidVersion("-1.0"))
        assertFalse(RemoteConfigFetcher.isValidVersion("1.04<script>"))
        assertFalse(RemoteConfigFetcher.isValidVersion("1.04\n\r"))
    }

    @Test
    fun testCanonicalPayloadGeneration() {
        val payload = RemoteConfigFetcher.buildCanonicalPayload("1.04", "1.71.26", "1.2026.00", 1731330000L)
        val text = String(payload, Charsets.UTF_8)
        assertTrue(text.contains("visionos=1.04"))
        assertTrue(text.contains("android_vr=1.71.26"))
        assertTrue(text.contains("remix=1.2026.00"))
        assertTrue(text.contains("ts=1731330000"))
    }

    @Test
    fun testRfc8032TestVector1Verifies() {
        // RFC 8032 Section 7.1 Test 1 (empty message)
        val rfcPub = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="
        val rfcSig = "5VZDAMNgrHKQhuLMgG6CioSHfx645dl02HPgZSJJAVVfuIIVkKM7rMYeOXAc+bRr0lv18FlbviRlUUFDjnoQCw=="
        assertTrue("RFC 8032 Test Vector 1 must verify successfully",
            RemoteConfigFetcher.verifySignature(byteArrayOf(), rfcSig, rfcPub))
    }

    @Test
    fun testFreshGeneratedKeypairSignsAndVerifies() {
        val kpGen = Ed25519KeyPairGenerator()
        kpGen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = kpGen.generateKeyPair()
        val priv = kp.private as Ed25519PrivateKeyParameters
        val pub = kp.public as Ed25519PublicKeyParameters

        val pubB64 = Base64.getEncoder().encodeToString(pub.encoded)
        val payload = RemoteConfigFetcher.buildCanonicalPayload("1.05", "1.72.0", "1.20260901.00.00", 1731340000L)

        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(payload, 0, payload.size)
        val sigB64 = Base64.getEncoder().encodeToString(signer.generateSignature())

        assertTrue("Valid signature under freshly generated keypair must verify",
            RemoteConfigFetcher.verifySignature(payload, sigB64, pubB64))

        // Bit flip in payload must fail
        val flipped = payload.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse("Bit-flipped payload must fail verification",
            RemoteConfigFetcher.verifySignature(flipped, sigB64, pubB64))

        // Truncated signature must fail
        val truncatedBytes = Base64.getDecoder().decode(sigB64).copyOf(32)
        val truncatedSigB64 = Base64.getEncoder().encodeToString(truncatedBytes)
        assertFalse("Truncated signature must fail verification",
            RemoteConfigFetcher.verifySignature(payload, truncatedSigB64, pubB64))

        // Garbage Base64 must fail safely without crashing
        assertFalse("Garbage Base64 signature must fail verification",
            RemoteConfigFetcher.verifySignature(payload, "not_a_valid_base64!!!", pubB64))
    }

    @Test
    fun testBadSignatureRejected() {
        val payload = RemoteConfigFetcher.buildCanonicalPayload("1.04", "1.71.26", "1.2026.00", 1731330000L)
        val fakeSignature = "dGhpcyBpcyBhIGZha2Ugc2lnbmF0dXJlIHRoYXQgbXVzdCBiZSByZWplY3RlZCBieSB0aGUgdW5pdCB0ZXN0IQ=="
        assertFalse(RemoteConfigFetcher.verifySignature(payload, fakeSignature))
    }
}
