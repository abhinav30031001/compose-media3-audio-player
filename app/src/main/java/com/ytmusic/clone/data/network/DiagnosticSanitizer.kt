package com.ytmusic.clone.data.network

/**
 * Shared diagnostic text sanitizer (Task C1).
 * Guarantees zero sensitive URLs, tokens, visitorData, header secrets,
 * or authorization parameters are leaked in logs, toasts, clipboard exports, or UI screens.
 */
object DiagnosticSanitizer {
    private val URL_REGEX = Regex("""https?://[^\s"',]+""")
    private val VISITOR_JSON_REGEX = Regex("""(?i)["'\\]*(?:visitorData|visitor_data|visitorId|X-Goog-Visitor-Id)["'\\]*\s*[:=]\s*["']?[^"'\s,;}&]+["']?""")
    private val AUTH_HEADER_REGEX = Regex("""(?i)(?:Authorization|Cookie)\s*:\s*[^\r\n]+""")
    private val BEARER_REGEX = Regex("""(?i)bearer\s+[A-Za-z0-9._~+/-]+""")
    private val QUERY_SECRETS_REGEX = Regex("""(?i)(?:[?&]|\b)(?:sig|lsig|pot|key|token|n)=[^&\s"',;]+""")
    private val COOKIE_KEY_REGEX = Regex("""(?i)\b(?:__Secure-[13]PAPISID|SAPISID|APISID|HSID|SSID|SID|LSID|__Secure-[13]PSID)=[^;\s"']+""")
    private val BARE_BASE64_REGEX = Regex("""(?<![A-Za-z0-9+/=])[A-Za-z0-9+/]{40,}={0,2}(?![A-Za-z0-9+/=])""")

    fun sanitize(input: String?): String {
        if (input.isNullOrBlank()) return ""
        return input
            .replace(URL_REGEX, "[URL_REDACTED]")
            .replace(AUTH_HEADER_REGEX, "Authorization/Cookie=[REDACTED]")
            .replace(VISITOR_JSON_REGEX, "visitorData=[REDACTED]")
            .replace(BEARER_REGEX, "bearer [REDACTED]")
            .replace(QUERY_SECRETS_REGEX, "[SECRET_PARAM_REDACTED]")
            .replace(COOKIE_KEY_REGEX, "[COOKIE_REDACTED]")
            .replace(BARE_BASE64_REGEX, "[TOKEN_REDACTED]")
    }
}
