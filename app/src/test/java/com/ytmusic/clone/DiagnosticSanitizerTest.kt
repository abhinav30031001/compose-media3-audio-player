package com.ytmusic.clone

import com.ytmusic.clone.data.network.DiagnosticSanitizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task C1 Unit tests: Verifies DiagnosticSanitizer redacts JSON visitorData, snake_case tokens,
 * Authorization/Cookie headers, query parameter secrets, and bare Base64 tokens.
 */
class DiagnosticSanitizerTest {

    @Test
    fun testUrlRedaction() {
        val raw = "Stream probe failed for https://rr1---sn-4g5ednks.googlevideo.com/videoplayback?expire=12345 with 403"
        val clean = DiagnosticSanitizer.sanitize(raw)
        assertFalse(clean.contains("googlevideo.com"))
        assertFalse(clean.contains("https://"))
        assertTrue(clean.contains("[URL_REDACTED]"))
        assertTrue(clean.contains("403"))
    }

    @Test
    fun testJsonAndSnakeCaseVisitorTokensRedacted() {
        val jsonForm = """{"visitorData":"CgtCQXFwdkJHbk93OCj59sC1BjIKCgJVUxIEZW4tVQ"}"""
        val cleanJson = DiagnosticSanitizer.sanitize(jsonForm)
        assertFalse("JSON visitorData value must be redacted", cleanJson.contains("CgtCQXFwdkJHbk93OC"))
        assertTrue(cleanJson.contains("visitorData=[REDACTED]"))

        val snakeCase = "visitor_data=ABC123XYZ and visitorId: ID_9999"
        val cleanSnake = DiagnosticSanitizer.sanitize(snakeCase)
        assertFalse("Snake case visitor_data value must be redacted", cleanSnake.contains("ABC123XYZ"))
        assertFalse("visitorId value must be redacted", cleanSnake.contains("ID_9999"))
    }

    @Test
    fun testHeadersAndBearerRedacted() {
        val raw = "Authorization: Bearer ya29.a0AfH6SMD_secret123\nCookie: SID=abc123456; HSID=def789"
        val clean = DiagnosticSanitizer.sanitize(raw)
        assertFalse(clean.contains("ya29.a0AfH6SMD_secret123"))
        assertFalse(clean.contains("abc123456"))
        assertTrue(clean.contains("Authorization/Cookie=[REDACTED]"))
    }

    @Test
    fun testQuerySecretsAndBareBase64Redacted() {
        val raw = "Probe sig=secretSigVal&pot=potSecret&key=AIzaSecret&token=secTok&n=nParam and longToken: VGhpcyBpcyBhIHZlcnkgbG9uZyBiYXNlNjQgdG9rZW4gdGhhdCBleGNlZWRzIDQwIGNoYXJhY3RlcnM="
        val clean = DiagnosticSanitizer.sanitize(raw)
        assertFalse(clean.contains("secretSigVal"))
        assertFalse(clean.contains("potSecret"))
        assertFalse(clean.contains("AIzaSecret"))
        assertFalse(clean.contains("VGhpcyBpcyBh"))
        assertTrue(clean.contains("[SECRET_PARAM_REDACTED]"))
        assertTrue(clean.contains("[TOKEN_REDACTED]"))
    }

    @Test
    fun testDiagnosticLabelsPreserved() {
        val raw = "[2026-11-11 12:00:00] [visionos_progressive] [HTTP 200] [PASS]"
        val clean = DiagnosticSanitizer.sanitize(raw)
        assertTrue(clean.contains("visionos_progressive"))
        assertTrue(clean.contains("HTTP 200"))
        assertTrue(clean.contains("PASS"))
    }

    @Test
    fun testBlankOrNullInput() {
        assertTrue(DiagnosticSanitizer.sanitize(null).isEmpty())
        assertTrue(DiagnosticSanitizer.sanitize("   ").isEmpty())
    }
}
