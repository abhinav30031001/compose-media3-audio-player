package com.ytmusic.clone.player

import androidx.media3.common.PlaybackException

data class FailoverDecision(
    val action: Action,
    val blameCircuitBreaker: Boolean,
    val markVideoTierFailed: Boolean
) {
    enum class Action {
        NONE,
        RETRY_SAME_TIER_FRESH,
        FAILOVER_NEXT_TIER
    }
}

/**
 * Pure failover decision engine.
 *
 * Rules:
 *  - markTierFailedForVideo(song.id, client) called for ANY failover (EOF/stall/timeout/403/410).
 *  - TierCircuitBreaker.recordFailure ONLY called for 403/410 when not playedLong.
 *  - HTTP 403 triggers one forceFresh same-tier retry first before failover.
 *  - Bounded at maxFailovers (number of tiers - 1 = 2) per track per play to prevent infinite bounce loops
 *    while allowing failover to cascade through Tier 1 -> Tier 2 -> Tier 3.
 */
object FailoverEngine {

    fun evaluatePlaybackError(
        errorCode: Int,
        httpStatusCode: Int?,
        isEofOrTimeout: Boolean,
        hasRetriedSameClient: Boolean,
        failoverCount: Int = 0,
        maxFailovers: Int = 2,
        playedLong: Boolean
    ): FailoverDecision {
        val isHttp403 = httpStatusCode == 403
        val isHttp410 = httpStatusCode == 410
        val isBadHttpStatus = (errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) && (isHttp403 || isHttp410)

        // Only handle 403, 410, bad http status with 403/410, or EOF/timeout/stall
        if (!isHttp403 && !isHttp410 && !isBadHttpStatus && !isEofOrTimeout) {
            return FailoverDecision(
                action = FailoverDecision.Action.NONE,
                blameCircuitBreaker = false,
                markVideoTierFailed = false
            )
        }

        // 1. One forceFresh same-tier retry on 403 before switching tiers
        if (isHttp403 && !hasRetriedSameClient) {
            return FailoverDecision(
                action = FailoverDecision.Action.RETRY_SAME_TIER_FRESH,
                blameCircuitBreaker = false,
                markVideoTierFailed = false
            )
        }

        // 2. Cap at maxFailovers per track per play
        if (failoverCount >= maxFailovers) {
            return FailoverDecision(
                action = FailoverDecision.Action.NONE,
                blameCircuitBreaker = false,
                markVideoTierFailed = false
            )
        }

        // 3. For ANY failover (EOF/stall/timeout/403/410):
        // Always mark video tier as failed, but ONLY blame circuit breaker for 403/410 when not playedLong!
        val shouldBlameCircuitBreaker = !playedLong && (isHttp403 || isHttp410)

        return FailoverDecision(
            action = FailoverDecision.Action.FAILOVER_NEXT_TIER,
            blameCircuitBreaker = shouldBlameCircuitBreaker,
            markVideoTierFailed = true
        )
    }

    // Overload for backward compatibility with callers passing hasFailedOverCurrentTrack
    fun evaluatePlaybackError(
        errorCode: Int,
        httpStatusCode: Int?,
        isEofOrTimeout: Boolean,
        hasRetriedSameClient: Boolean,
        hasFailedOverCurrentTrack: Boolean,
        playedLong: Boolean
    ): FailoverDecision = evaluatePlaybackError(
        errorCode = errorCode,
        httpStatusCode = httpStatusCode,
        isEofOrTimeout = isEofOrTimeout,
        hasRetriedSameClient = hasRetriedSameClient,
        failoverCount = if (hasFailedOverCurrentTrack) 2 else 0,
        maxFailovers = 2,
        playedLong = playedLong
    )
}
