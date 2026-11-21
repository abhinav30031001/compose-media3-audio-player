package com.ytmusic.clone.data.network

/**
 * Pure classifier for a non-OK playabilityStatus (v7.19).
 *
 * Words are matched as WHOLE words only. The old code used contains("age") / contains("bot"),
 * which also matched "message", "manage", "both", "robot" and could turn a real bot-check into
 * a "video specific" result (no breaker, no HLS fallback).
 *
 * Order of rules:
 *  1. Reason mentions the word "age"                       -> VIDEO_SPECIFIC (age-restricted)
 *  2. status == LOGIN_REQUIRED and reason has word "bot"   -> BOT_CHECK (tier-wide)
 *  3. UNPLAYABLE / LOGIN_REQUIRED / CONTENT_CHECK_REQUIRED -> VIDEO_SPECIFIC
 *  4. anything else                                        -> TIER_WIDE
 */
object PlayabilityClassifier {
    enum class Kind { BOT_CHECK, VIDEO_SPECIFIC, TIER_WIDE }

    private val AGE_WORD = Regex("\\bage\\b", RegexOption.IGNORE_CASE)
    private val BOT_WORD = Regex("\\bbot\\b", RegexOption.IGNORE_CASE)

    fun classify(status: String?, reason: String): Kind {
        if (AGE_WORD.containsMatchIn(reason)) return Kind.VIDEO_SPECIFIC
        if (status == "LOGIN_REQUIRED" && BOT_WORD.containsMatchIn(reason)) return Kind.BOT_CHECK
        if (status == "UNPLAYABLE" || status == "LOGIN_REQUIRED" || status == "CONTENT_CHECK_REQUIRED") {
            return Kind.VIDEO_SPECIFIC
        }
        return Kind.TIER_WIDE
    }
}
