package com.ytmusic.clone

import com.ytmusic.clone.data.network.PolicyScope
import com.ytmusic.clone.data.network.SafeDns
import com.ytmusic.clone.data.network.SecureHttp
import com.ytmusic.clone.data.network.UrlPolicy
import com.ytmusic.clone.data.network.readCapped
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Phase A2 Unit Tests: Hardened UrlPolicy allowlist, SafeDns, network interceptor redirect validation.
 */
class UrlPolicyTest {

    @Test
    fun testReliabilityGoldenListAllowed() {
        assertTrue("YouTube player API must pass API scope",
            UrlPolicy.isAllowed("https://www.youtube.com/youtubei/v1/player", PolicyScope.API))
        assertTrue("YouTube player API must pass STREAM scope",
            UrlPolicy.isAllowed("https://www.youtube.com/youtubei/v1/player", PolicyScope.STREAM))

        assertTrue("YouTube Music search must pass API scope",
            UrlPolicy.isAllowed("https://music.youtube.com/youtubei/v1/search", PolicyScope.API))

        assertTrue("googlevideo stream URL must pass STREAM scope",
            UrlPolicy.isAllowed("https://rr3---sn-abc123.googlevideo.com/videoplayback?x=y", PolicyScope.STREAM))

        assertTrue("googlevideo redirector must pass STREAM scope",
            UrlPolicy.isAllowed("https://redirector.googlevideo.com/videoplayback", PolicyScope.STREAM))

        assertTrue("i.ytimg.com image must pass IMAGE scope",
            UrlPolicy.isAllowed("https://i.ytimg.com/vi/x/hq.jpg", PolicyScope.IMAGE))

        assertTrue("lh3.googleusercontent.com image must pass IMAGE scope",
            UrlPolicy.isAllowed("https://lh3.googleusercontent.com/x", PolicyScope.IMAGE))

        assertTrue("yt3.ggpht.com image must pass IMAGE scope",
            UrlPolicy.isAllowed("https://yt3.ggpht.com/x", PolicyScope.IMAGE))

        assertTrue("Google suggest queries must pass API scope",
            UrlPolicy.isAllowed("https://suggestqueries.google.com/complete/search?q=a", PolicyScope.API))

        assertTrue("LRCLIB endpoint must pass LYRICS scope",
            UrlPolicy.isAllowed("https://lrclib.net/api/get", PolicyScope.LYRICS))

        assertTrue("Raw GitHub config endpoint must pass CONFIG scope",
            UrlPolicy.isAllowed("https://raw.githubusercontent.com/ytmusic-android/config/main/client_versions.json", PolicyScope.CONFIG))
    }

    @Test
    fun testBlockedListRejected() {
        // HTTP schemes must be rejected
        assertFalse("Plain HTTP player API must be blocked",
            UrlPolicy.isAllowed("http://www.youtube.com/youtubei/v1/player", PolicyScope.API))
        assertFalse("Plain HTTP googlevideo stream must be blocked",
            UrlPolicy.isAllowed("http://rr1.googlevideo.com/videoplayback", PolicyScope.STREAM))

        // Subdomain spoofing
        assertFalse("evilgooglevideo.com must be blocked",
            UrlPolicy.isAllowed("https://evilgooglevideo.com", PolicyScope.STREAM))
        assertFalse("googlevideo.com.evil.com must be blocked",
            UrlPolicy.isAllowed("https://googlevideo.com.evil.com", PolicyScope.STREAM))

        // Open redirect / broad google.com domain spoofing
        assertFalse("Generic google.com open redirect URL must be blocked in API scope",
            UrlPolicy.isAllowed("https://google.com/url?q=https://evil.com", PolicyScope.API))

        // Unapproved GitHub subdomains
        assertFalse("gist.githubusercontent.com must be blocked in CONFIG scope",
            UrlPolicy.isAllowed("https://gist.githubusercontent.com/x", PolicyScope.CONFIG))

        // IP literals and loopbacks
        assertFalse("Private IPv4 literal must be blocked",
            UrlPolicy.isAllowed("https://192.168.1.5/x", PolicyScope.ALL))
        assertFalse("IPv6 loopback [::1] must be blocked",
            UrlPolicy.isAllowed("https://[::1]/x", PolicyScope.ALL))
        assertFalse("Localhost must be blocked",
            UrlPolicy.isAllowed("https://localhost/api", PolicyScope.ALL))
    }

    @Test
    fun testRedirectToDisallowedHostBlockedBeforeDns() {
        val server = MockWebServer()
        server.start()
        val requestedDnsHosts = mutableListOf<String>()

        val recordingDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                requestedDnsHosts.add(hostname)
                return Dns.SYSTEM.lookup(hostname)
            }
        }

        try {
            // First response: 302 redirect to disallowed host https://evil.example/x
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://evil.example/x")
            )

            // Injectable checker allows only the mock server's host and port
            val mockHost = server.hostName
            val testSpecs = listOf(okhttp3.ConnectionSpec.MODERN_TLS, okhttp3.ConnectionSpec.CLEARTEXT)
            val client = SecureHttp.newBuilder(
                scope = PolicyScope.STREAM,
                checker = { it.host == mockHost },
                dns = recordingDns,
                connectionSpecs = testSpecs,
                followSslRedirects = true
            ).build()

            val request = Request.Builder().url(server.url("/start")).build()
            try {
                client.newCall(request).execute()
                fail("Expected IOException on redirect to disallowed host evil.example")
            } catch (e: IOException) {
                assertTrue("Must be blocked by network interceptor", e.message?.contains("Blocked URL") == true)
            }

            // Assert that recording Dns was NEVER asked to resolve evil.example
            assertFalse("Recording DNS must never resolve disallowed redirect host",
                requestedDnsHosts.contains("evil.example"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testSameScopeRedirectToAllowedUrlFollowed() {
        val server = MockWebServer()
        server.start()

        try {
            // 302 redirect to allowed target path on same server
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", server.url("/destination").toString())
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("Redirect success")
            )

            val mockHost = server.hostName
            val testSpecs = listOf(okhttp3.ConnectionSpec.MODERN_TLS, okhttp3.ConnectionSpec.CLEARTEXT)
            val client = SecureHttp.newBuilder(
                scope = PolicyScope.STREAM,
                checker = { it.host == mockHost },
                dns = Dns.SYSTEM,
                connectionSpecs = testSpecs
            ).build()

            val request = Request.Builder().url(server.url("/start")).build()
            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("Redirect success", response.body?.string())
            }
        } finally {
            server.shutdown()
        }
    }

    @Test(expected = IOException::class)
    fun testVerifyOrThrowThrowsOnDisallowedUrl() {
        UrlPolicy.verifyOrThrow("https://evil.example/video", PolicyScope.STREAM)
    }

    @Test
    fun testReadCappedEnforcesLimit() {
        val body = "A".repeat(20000).toResponseBody("text/plain".toMediaType())
        val response = Response.Builder()
            .request(Request.Builder().url("https://www.youtube.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()

        try {
            response.readCapped(16 * 1024)
            fail("Expected IOException when body exceeds 16KB")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("exceeded maximum cap") == true)
        }
    }

    @Test
    fun testReadCappedPassesUnderLimit() {
        val content = "{\"status\":\"ok\"}"
        val body = content.toResponseBody("application/json".toMediaType())
        val response = Response.Builder()
            .request(Request.Builder().url("https://www.youtube.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()

        val result = response.readCapped(16 * 1024)
        assertEquals(content, result)
    }

    @Test(expected = UnknownHostException::class)
    fun testSafeDnsRejectsLoopback() {
        SafeDns.lookup("127.0.0.1")
    }

    // ---------- Task C7: Comprehensive SafeDns IP Range Tests ----------

    @Test
    fun testSafeDnsBlocksExtendedRanges() {
        // 0.0.0.0/8
        assertTrue(SafeDns.isBlocked(InetAddress.getByName("0.1.2.3")))
        // Loopback
        assertTrue(SafeDns.isBlocked(InetAddress.getByName("127.0.0.1")))
        // CGNAT 100.64.0.0/10
        assertTrue(SafeDns.isBlocked(InetAddress.getByName("100.64.0.1")))
        assertTrue(SafeDns.isBlocked(InetAddress.getByName("100.127.255.255")))
        // Benchmarking 198.18.0.0/15
        assertTrue(SafeDns.isBlocked(InetAddress.getByName("198.18.0.1")))
        assertTrue(SafeDns.isBlocked(InetAddress.getByName("198.19.255.255")))
        // Private ranges
        assertTrue(SafeDns.isBlocked(InetAddress.getByName("10.0.0.1")))
        assertTrue(SafeDns.isBlocked(InetAddress.getByName("172.16.0.1")))
        assertTrue(SafeDns.isBlocked(InetAddress.getByName("192.168.1.1")))
        // Multicast
        assertTrue(SafeDns.isBlocked(InetAddress.getByName("224.0.0.1")))
        // IPv6 Loopback
        assertTrue(SafeDns.isBlocked(InetAddress.getByName("::1")))
        // IPv6 Unique Local fc00::/7
        assertTrue(SafeDns.isBlocked(InetAddress.getByName("fc00::1")))
        assertTrue(SafeDns.isBlocked(InetAddress.getByName("fd12:3456::1")))
        // IPv6 Link-Local fe80::/10
        assertTrue(SafeDns.isBlocked(InetAddress.getByName("fe80::1")))
    }

    @Test
    fun testSafeDnsAllowsPublicAddresses() {
        // Public Google IP
        assertFalse(SafeDns.isBlocked(InetAddress.getByName("142.250.190.46")))
        // Cloudflare public DNS
        assertFalse(SafeDns.isBlocked(InetAddress.getByName("1.1.1.1")))
    }
}
