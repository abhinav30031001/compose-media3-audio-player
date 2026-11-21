package com.ytmusic.clone.data.network

import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Phase 2.2 & Tasks C7: Comprehensive SSRF & DNS rebinding protection.
 * Rejects private, loopback, link-local, CGNAT, benchmarking, multicast, and IPv6 unique-local IPs.
 */
object SafeDns : Dns {

    fun isBlocked(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isAnyLocalAddress || addr.isMulticastAddress) {
            return true
        }

        val raw = addr.address

        // Check for IPv4-mapped IPv6 address (::ffff:w.x.y.z)
        val ipv4Bytes: ByteArray? = if (raw.size == 16) {
            val isMapped = raw[0] == 0.toByte() && raw[1] == 0.toByte() && raw[2] == 0.toByte() &&
                    raw[3] == 0.toByte() && raw[4] == 0.toByte() && raw[5] == 0.toByte() &&
                    raw[6] == 0.toByte() && raw[7] == 0.toByte() && raw[8] == 0.toByte() &&
                    raw[9] == 0.toByte() && raw[10] == 0xFF.toByte() && raw[11] == 0xFF.toByte()
            if (isMapped) raw.copyOfRange(12, 16) else null
        } else if (raw.size == 4) {
            raw
        } else {
            null
        }

        if (ipv4Bytes != null) {
            val b0 = ipv4Bytes[0].toInt() and 0xFF
            val b1 = ipv4Bytes[1].toInt() and 0xFF

            // 0.0.0.0/8
            if (b0 == 0) return true
            // Loopback 127.0.0.0/8
            if (b0 == 127) return true
            // Private 10.0.0.0/8
            if (b0 == 10) return true
            // Private 172.16.0.0/12
            if (b0 == 172 && (b1 in 16..31)) return true
            // Private 192.168.0.0/16
            if (b0 == 192 && b1 == 168) return true
            // Link-local 169.254.0.0/16
            if (b0 == 169 && b1 == 254) return true
            // CGNAT 100.64.0.0/10 (100.64.0.0 - 100.127.255.255)
            if (b0 == 100 && (b1 in 64..127)) return true
            // Benchmarking 198.18.0.0/15 (198.18.0.0 - 198.19.255.255)
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true
            // Multicast 224.0.0.0/4
            if (b0 in 224..239) return true
            // Broadcast / Reserved 240.0.0.0/4
            if (b0 >= 240) return true
        } else if (raw.size == 16) {
            val b0 = raw[0].toInt() and 0xFF
            val b1 = raw[1].toInt() and 0xFF
            // IPv6 Unique Local fc00::/7 (fc00:: - fdff::)
            if ((b0 and 0xFE) == 0xFC) return true
            // IPv6 Link-Local fe80::/10 (fe80:: - febf::)
            if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true
        }

        return false
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        val filtered = addresses.filterNot { isBlocked(it) }
        if (filtered.isEmpty()) {
            DiagnosticLogger.log("network", "BLOCKED", "Blocked DNS resolution to non-public IP for $hostname")
            throw UnknownHostException("Blocked DNS resolution to private/local address for host")
        }
        return filtered
    }
}

/**
 * Phase A2 & Tasks C5/C7: Centralized secure OkHttpClient factory.
 *
 * Guarantees across all network clients:
 *  1. DNS pre-resolution gate and SafeDns preventing SSRF and private IP resolution.
 *  2. MODERN_TLS (TLS 1.2+ only, modern cipher suites).
 *  3. Network interceptor (addNetworkInterceptor) enforcing UrlPolicy on every hop and redirect target.
 *  4. followSslRedirects(false) and followRedirects(true) guarded by the network interceptor.
 *  5. Injectable checker, dns, connectionSpecs, and followSslRedirects for testing without global flags.
 */
object SecureHttp {
    fun newBuilder(
        scope: PolicyScope = PolicyScope.ALL,
        checker: (HttpUrl) -> Boolean = { UrlPolicy.isAllowed(it, scope) },
        dns: Dns = SafeDns,
        connectionSpecs: List<ConnectionSpec> = listOf(ConnectionSpec.MODERN_TLS),
        followSslRedirects: Boolean = false
    ): OkHttpClient.Builder {
        val guardedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val dummyUrl = "https://$hostname/".toHttpUrlOrNull()
                if (dummyUrl != null && !checker(dummyUrl)) {
                    DiagnosticLogger.log("network", "BLOCKED", "Blocked URL: $hostname ($scope)")
                    throw IOException("Blocked URL violating $scope policy: $hostname")
                }
                return dns.lookup(hostname)
            }
        }

        return OkHttpClient.Builder()
            .dns(guardedDns)
            .connectionSpecs(connectionSpecs)
            .followSslRedirects(followSslRedirects)
            .followRedirects(true)
            .addNetworkInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url

                // Validate request URL on every hop (including redirects)
                if (!checker(url)) {
                    DiagnosticLogger.log("network", "BLOCKED", "Blocked URL: ${url.host} ($scope)")
                    throw IOException("Blocked URL violating $scope policy: ${url.host}")
                }

                chain.proceed(request)
            })
    }
}

/**
 * Phase 2.3: Safe response body reader with strict size caps.
 * Reads in buffered chunks and enforces maxBytes limit.
 * Prevents memory exhaustion / zip-bomb denial of service attacks.
 */
fun Response.readCapped(maxBytes: Long): String {
    val body = this.body ?: throw IOException("Empty response body")
    val contentLength = body.contentLength()
    if (contentLength > maxBytes) {
        throw IOException("Response Content-Length ($contentLength bytes) exceeded maximum cap of $maxBytes bytes")
    }

    val source = body.source()
    val buffer = okio.Buffer()
    var totalRead = 0L

    while (totalRead <= maxBytes) {
        val toRead = minOf(8192L, maxBytes + 1L - totalRead)
        val read = source.read(buffer, toRead)
        if (read == -1L) {
            break
        }
        totalRead += read
        if (totalRead > maxBytes) {
            throw IOException("Response size exceeded maximum cap of $maxBytes bytes")
        }
    }
    return buffer.readUtf8()
}

/**
 * Task C5: Bounded raw bytes reader for image artwork and binary responses.
 */
fun Response.readBytesCapped(maxBytes: Long): ByteArray {
    val body = this.body ?: throw IOException("Empty response body")
    val contentLength = body.contentLength()
    if (contentLength > maxBytes) {
        throw IOException("Response Content-Length ($contentLength bytes) exceeded cap of $maxBytes bytes")
    }

    val source = body.source()
    val buffer = okio.Buffer()
    var totalRead = 0L

    while (totalRead <= maxBytes) {
        val toRead = minOf(8192L, maxBytes + 1L - totalRead)
        val read = source.read(buffer, toRead)
        if (read == -1L) {
            break
        }
        totalRead += read
        if (totalRead > maxBytes) {
            throw IOException("Response size exceeded maximum cap of $maxBytes bytes")
        }
    }
    return buffer.readByteArray()
}
