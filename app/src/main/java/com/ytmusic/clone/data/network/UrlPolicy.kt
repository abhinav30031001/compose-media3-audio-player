package com.ytmusic.clone.data.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

enum class PolicyScope {
    STREAM,  // googlevideo.com (suffix), youtube.com (exact + suffix)
    IMAGE,   // ytimg.com, googleusercontent.com, ggpht.com (suffixes)
    LYRICS,  // lrclib.net (exact)
    CONFIG,  // raw.githubusercontent.com (exact)
    API,     // www.youtube.com, music.youtube.com, suggestqueries.google.com (exact)
    ALL      // Union of all verified allowed domains
}

/**
 * Phase A2: Narrow, hardened host allowlist enforcing exact hosts and dot-boundary suffixes.
 * Strict HTTPS only; rejects IP literals, localhost, and subdomain spoofing.
 * Zero test-only backdoors or localhost bypasses in production code.
 */
object UrlPolicy {

    private val API_EXACT = setOf("www.youtube.com", "music.youtube.com", "suggestqueries.google.com")
    private val API_SUFFIX = emptySet<String>()

    private val CONFIG_EXACT = setOf("raw.githubusercontent.com")
    private val CONFIG_SUFFIX = emptySet<String>()

    private val STREAM_EXACT = setOf("www.youtube.com", "youtube.com")
    private val STREAM_SUFFIX = setOf("googlevideo.com", "youtube.com")

    private val IMAGE_EXACT = emptySet<String>()
    private val IMAGE_SUFFIX = setOf("ytimg.com", "googleusercontent.com", "ggpht.com")

    private val LYRICS_EXACT = setOf("lrclib.net")
    private val LYRICS_SUFFIX = emptySet<String>()

    private val ALL_EXACT = API_EXACT + CONFIG_EXACT + STREAM_EXACT + IMAGE_EXACT + LYRICS_EXACT
    private val ALL_SUFFIX = API_SUFFIX + CONFIG_SUFFIX + STREAM_SUFFIX + IMAGE_SUFFIX + LYRICS_SUFFIX

    private val IP_REGEX = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")

    fun isAllowed(urlStr: String?, scope: PolicyScope = PolicyScope.ALL): Boolean {
        if (urlStr.isNullOrBlank()) return false
        val parsed = urlStr.toHttpUrlOrNull() ?: return false
        return isAllowed(parsed, scope)
    }

    fun isAllowed(url: HttpUrl, scope: PolicyScope = PolicyScope.ALL): Boolean {
        // 1. Strict HTTPS scheme only (http, file, content, data, ftp strictly rejected)
        if (url.scheme != "https") {
            return false
        }

        val host = url.host.lowercase()

        // 2. Reject localhost, IPv4 literals, and IPv6 literals (e.g., [::1])
        if (host == "localhost" || IP_REGEX.matches(host) || host.contains(":") || host.startsWith("[") || host.endsWith("]")) {
            return false
        }

        val (exactHosts, suffixDomains) = when (scope) {
            PolicyScope.STREAM -> Pair(STREAM_EXACT, STREAM_SUFFIX)
            PolicyScope.IMAGE -> Pair(IMAGE_EXACT, IMAGE_SUFFIX)
            PolicyScope.LYRICS -> Pair(LYRICS_EXACT, LYRICS_SUFFIX)
            PolicyScope.CONFIG -> Pair(CONFIG_EXACT, CONFIG_SUFFIX)
            PolicyScope.API -> Pair(API_EXACT, API_SUFFIX)
            PolicyScope.ALL -> Pair(ALL_EXACT, ALL_SUFFIX)
        }

        // 3. Exact host match OR dot-boundary suffix match
        if (exactHosts.contains(host)) {
            return true
        }

        return suffixDomains.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    fun verifyOrThrow(urlStr: String?, scope: PolicyScope = PolicyScope.ALL): String {
        if (!isAllowed(urlStr, scope)) {
            DiagnosticLogger.log("network", "BLOCKED", "Blocked URL violating $scope policy")
            val sanitized = urlStr?.substringBefore('?') ?: "unknown"
            throw IOException("Blocked URL violating security policy ($scope): $sanitized")
        }
        return urlStr!!
    }
}
