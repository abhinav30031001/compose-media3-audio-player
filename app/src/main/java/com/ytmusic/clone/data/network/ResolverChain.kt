package com.ytmusic.clone.data.network

import android.util.Log
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Executes a prioritized chain of StreamResolvers with resilience features:
 *  - Sequential execution with per-tier timeout (~8s).
 *  - Per-tier circuit breaker filtering (skips tiers in cooldown).
 *  - Single shared player response fetch per resolve across tiers that share it.
 *  - Client isolation: independent tiers (e.g. Android VR) never reuse visionOS player JSON,
 *    and visionOS trips do not touch Android VR visitor tokens or breaker counters.
 *  - Typed result handling: VideoSpecific aborts without breaker penalty,
 *    TierWide trips breaker, Transient retries/skips safely.
 *  - Per-video skip-failed memory: if a tier failed for a video, mid-track failovers skip it.
 *  - Least-recently-failed fallback: if all tiers are blocked, attempts one trial request.
 *  - Downloads support: progressiveOnly=true, recordBreaker=false, skipVideoMemory=true.
 *  - Local in-memory diagnostics logging (sanitized, zero secrets).
 */
class ResolverChain(
    val resolvers: List<StreamResolver>,
    val circuitBreaker: TierCircuitBreaker = TierCircuitBreaker,
    val perTierTimeoutMs: Long = 8_000L,
    val playerProvider: suspend (videoId: String, forceFresh: Boolean) -> PlayerQueryResult =
        { id, fresh -> VisionOsPlayerClient.getPlayerResponse(id, fresh) }
) {
    companion object {
        private const val TAG = "ResolverChain"
    }

    @Volatile var lastFailureReason: String? = null

    // Bounded in-memory per-video failed tier tracking (max 100 recent videos)
    private val videoFailedTiers = object : LinkedHashMap<String, MutableSet<String>>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<String>>?): Boolean {
            return size > 100
        }
    }

    private fun recordTierSuccess(tierId: String) {
        if (tierId == AndroidVrProgressiveResolver.TIER_ID) {
            AndroidVrPlayerClient.recordTierSuccess()
        } else {
            VisionOsPlayerClient.recordTierSuccess()
        }
    }

    private fun recordTierWideFailure(tierId: String) {
        if (tierId == AndroidVrProgressiveResolver.TIER_ID) {
            AndroidVrPlayerClient.recordTierWideFailure()
        } else {
            VisionOsPlayerClient.recordTierWideFailure()
        }
    }

    suspend fun resolve(
        videoId: String,
        skipFailed: Boolean = false,
        progressiveOnly: Boolean = false,
        recordBreaker: Boolean = true,
        skipVideoMemory: Boolean = false,
        forceFreshPlayer: Boolean = false,
        excludedTierIds: Set<String> = emptySet()
    ): Resolved? {
        lastFailureReason = null
        val baseResolvers = if (progressiveOnly) {
            resolvers.filter { it.isProgressive }
        } else {
            resolvers
        }
        val candidateResolvers = if (excludedTierIds.isEmpty()) baseResolvers else baseResolvers.filter { it.id !in excludedTierIds }

        if (candidateResolvers.isEmpty()) {
            Log.w(TAG, "No candidate resolvers available for progressiveOnly=$progressiveOnly")
            return null
        }

        val failedForVideo = synchronized(videoFailedTiers) {
            videoFailedTiers.getOrPut(videoId) { mutableSetOf() }
        }

        // Shared player fetch once per resolve across shared tiers
        // Bug 7: Only fetch shared player if at least ONE resolver that requires it can actually execute
        var sharedPlayerSuccess: Pair<JsonObject, String>? = null
        val eligibleSharedResolvers = candidateResolvers.filter {
            it.requiresSharedPlayer &&
            (!recordBreaker || circuitBreaker.canExecute(it.id)) &&
            (!skipFailed || skipVideoMemory || !synchronized(videoFailedTiers) { failedForVideo.contains(it.id) })
        }

        if (eligibleSharedResolvers.isNotEmpty()) {
            val playerResult = try {
                playerProvider(videoId, forceFreshPlayer)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Player response fetch failed for $videoId: ${e.javaClass.simpleName}")
                PlayerQueryResult.Transient(e.javaClass.simpleName)
            }

            when (playerResult) {
                is PlayerQueryResult.Success -> {
                    sharedPlayerSuccess = Pair(playerResult.json, playerResult.cpn)
                }

                is PlayerQueryResult.VideoSpecific -> {
                    Log.w(TAG, "Shared player video-specific unplayable for $videoId: ${playerResult.reason}")
                    lastFailureReason = playerResult.reason
                    DiagnosticLogger.log("shared_player", "VIDEO_SPECIFIC", "Shared player unplayable: ${playerResult.reason}")
                    if (candidateResolvers.none { !it.requiresSharedPlayer }) {
                        return null
                    }
                }

                is PlayerQueryResult.TierWide -> {
                    Log.w(TAG, "Tier-wide failure during shared player fetch for $videoId: ${playerResult.reason}")
                    if (recordBreaker) {
                        candidateResolvers.filter { it.requiresSharedPlayer }.forEach { circuitBreaker.recordFailure(it.id) }
                        VisionOsPlayerClient.recordTierWideFailure()
                    }
                    DiagnosticLogger.log("shared_player", "TIER_WIDE_FAIL", "Player API TierWide failure: ${playerResult.reason}")
                    // If no independent resolvers exist, abort now
                    if (candidateResolvers.none { !it.requiresSharedPlayer }) {
                        return null
                    }
                }

                is PlayerQueryResult.Transient -> {
                    Log.w(TAG, "Transient error during shared player fetch for $videoId: ${playerResult.reason}")
                    DiagnosticLogger.log("shared_player", "TRANSIENT_FAIL", "Player API Transient error: ${playerResult.reason}")
                    if (candidateResolvers.none { !it.requiresSharedPlayer }) {
                        return null
                    }
                }
            }
        }

        val attemptedTiers = mutableSetOf<String>()
        var encounteredVideoSpecific = false

        val isLoginRequired = (lastFailureReason ?: "").contains("LOGIN_REQUIRED", ignoreCase = true)

        for (resolver in candidateResolvers) {
            val tierId = resolver.id

            // Strict privacy gate: authenticated Tier 3 ONLY runs when shared player failed with LOGIN_REQUIRED
            if (tierId == WebSafariAuthHlsResolver.TIER_ID && !isLoginRequired) {
                continue
            }

            // If resolver requires shared player but shared player fetch failed/skipped, skip it
            if (resolver.requiresSharedPlayer && sharedPlayerSuccess == null) {
                continue
            }

            // 1. Skip-failed per-video check
            if (skipFailed && !skipVideoMemory && synchronized(videoFailedTiers) { failedForVideo.contains(tierId) }) {
                Log.d(TAG, "Skipping tier $tierId for video $videoId (failed earlier for this track)")
                DiagnosticLogger.log(
                    tierId = tierId,
                    status = "SKIPPED",
                    probeResult = "Skipped (failed earlier for video $videoId)"
                )
                continue
            }

            // 2. Circuit breaker check
            if (recordBreaker && !circuitBreaker.canExecute(tierId)) {
                val state = circuitBreaker.getState(tierId)
                val remainingSec = circuitBreaker.getRemainingCooldownMs(tierId) / 1000L
                Log.w(TAG, "Tier $tierId is in $state (${remainingSec}s remaining) - skipping")
                DiagnosticLogger.log(
                    tierId = tierId,
                    status = "BLOCKED",
                    probeResult = "Circuit breaker $state (${remainingSec}s remaining)"
                )
                continue
            }

            // 3. Timed resolution attempt with shared player JSON if available
            attemptedTiers.add(tierId)
            val startTime = System.currentTimeMillis()
            var resolveResult: ResolveResult? = null

            try {
                try {
                    // Bug 1: Only pass shared player JSON if this resolver requires shared player!
                    val jsonToPass = if (resolver.requiresSharedPlayer) sharedPlayerSuccess?.first else null
                    val cpnToPass = if (resolver.requiresSharedPlayer) sharedPlayerSuccess?.second else null
                    resolveResult = withTimeoutOrNull(perTierTimeoutMs) {
                        resolver.resolve(
                            videoId,
                            sharedPlayerJson = jsonToPass,
                            cpn = cpnToPass
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Tier $tierId threw exception for $videoId: ${e.javaClass.simpleName}")
                    resolveResult = ResolveResult.Transient(e.javaClass.simpleName)
                }

                val latencyMs = System.currentTimeMillis() - startTime

                // 4. Handle typed result (Bug 2: per-client success & failure hooks)
                when (resolveResult) {
                    is ResolveResult.Success -> {
                        if (recordBreaker) {
                            circuitBreaker.recordSuccess(tierId)
                            recordTierSuccess(tierId)
                        }
                        DiagnosticLogger.log(
                            tierId = tierId,
                            status = "SUCCESS",
                            probeResult = "Resolved ${resolveResult.resolved.format} in ${latencyMs}ms"
                        )
                        Log.i(TAG, "Tier $tierId resolved successfully for $videoId in ${latencyMs}ms")
                        return resolveResult.resolved
                    }

                    is ResolveResult.VideoSpecific -> {
                        encounteredVideoSpecific = true
                        // Video-specific issues (age restricted, private, removed): DO NOT hit breaker
                        DiagnosticLogger.log(
                            tierId = tierId,
                            status = "VIDEO_SPECIFIC",
                            probeResult = "Video unavailable (${resolveResult.reason}) in ${latencyMs}ms"
                        )
                        Log.w(TAG, "Tier $tierId video-specific error for $videoId: ${resolveResult.reason}")
                        // Stop further tiers immediately since the video is unplayable
                        return null
                    }

                    is ResolveResult.TierWide -> {
                        if (recordBreaker) {
                            circuitBreaker.recordFailure(tierId)
                            recordTierWideFailure(tierId)
                        }
                        if (!skipVideoMemory) {
                            synchronized(videoFailedTiers) { failedForVideo.add(tierId) }
                        }
                        DiagnosticLogger.log(
                            tierId = tierId,
                            status = "TIER_WIDE_FAIL",
                            probeResult = "Failed in ${latencyMs}ms: ${resolveResult.reason}"
                        )
                        Log.w(TAG, "Tier $tierId tier-wide failure for $videoId: ${resolveResult.reason}")
                    }

                    is ResolveResult.Transient, null -> {
                        val reason = (resolveResult as? ResolveResult.Transient)?.reason ?: "Timeout after ${latencyMs}ms"
                        // Transient network issues: DO NOT hit breaker
                        if (!skipVideoMemory) {
                            synchronized(videoFailedTiers) { failedForVideo.add(tierId) }
                        }
                        DiagnosticLogger.log(
                            tierId = tierId,
                            status = "TRANSIENT_FAIL",
                            probeResult = "Transient error in ${latencyMs}ms: $reason"
                        )
                        Log.w(TAG, "Tier $tierId transient failure for $videoId: $reason")
                    }
                }
            } finally {
                // Ensure trialInProgress is ALWAYS released in finally on success, failure, cancellation, VideoSpecific, or Transient
                if (recordBreaker) {
                    circuitBreaker.releaseTrial(tierId)
                }
            }
        }

        // 5. Amendment 7: Least-recently-failed fallback
        // If all tiers were skipped or blocked (and not VideoSpecific), allow max one fallback attempt.
        if (!encounteredVideoSpecific && recordBreaker) {
            val eligibleTiers = candidateResolvers
                .map { it.id }
                .filter { tierId ->
                    tierId != WebSafariAuthHlsResolver.TIER_ID &&
                    !attemptedTiers.contains(tierId) &&
                    (!skipFailed || skipVideoMemory || !synchronized(videoFailedTiers) { failedForVideo.contains(tierId) })
                }
            val fallbackTierId = circuitBreaker.getLeastRecentlyFailedTier(eligibleTiers)
            if (fallbackTierId != null) {
                val fallbackResolver = candidateResolvers.firstOrNull { it.id == fallbackTierId }
                if (fallbackResolver != null) {
                    // If fallback tier requires shared player response but it wasn't fetched yet (because all shared tiers were blocked), fetch it now for this trial
                    if (fallbackResolver.requiresSharedPlayer && sharedPlayerSuccess == null) {
                        val playerResult = try {
                            playerProvider(videoId, forceFreshPlayer)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.w(TAG, "Fallback player fetch failed for $videoId: ${e.javaClass.simpleName}")
                            PlayerQueryResult.Transient(e.javaClass.simpleName)
                        }
                        if (playerResult is PlayerQueryResult.Success) {
                            sharedPlayerSuccess = Pair(playerResult.json, playerResult.cpn)
                        }
                    }

                    if (!fallbackResolver.requiresSharedPlayer || sharedPlayerSuccess != null) {
                        Log.i(TAG, "Attempting least-recently-failed fallback tier: $fallbackTierId")
                        val startTime = System.currentTimeMillis()
                        try {
                            val fbJson = if (fallbackResolver.requiresSharedPlayer) sharedPlayerSuccess?.first else null
                            val fbCpn = if (fallbackResolver.requiresSharedPlayer) sharedPlayerSuccess?.second else null
                            val fallbackRes = try {
                                withTimeoutOrNull(perTierTimeoutMs) {
                                    fallbackResolver.resolve(
                                        videoId,
                                        sharedPlayerJson = fbJson,
                                        cpn = fbCpn
                                    )
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.w(TAG, "Fallback attempt on $fallbackTierId threw: ${e.javaClass.simpleName}")
                                ResolveResult.Transient(e.javaClass.simpleName)
                            }
                            val latency = System.currentTimeMillis() - startTime
                            if (fallbackRes is ResolveResult.Success) {
                                circuitBreaker.recordSuccess(fallbackTierId)
                                recordTierSuccess(fallbackTierId)
                                DiagnosticLogger.log(
                                    tierId = fallbackTierId,
                                    status = "FALLBACK_SUCCESS",
                                    probeResult = "Fallback resolved ${fallbackRes.resolved.format} in ${latency}ms"
                                )
                                return fallbackRes.resolved
                            } else if (fallbackRes is ResolveResult.TierWide) {
                                circuitBreaker.recordFailure(fallbackTierId)
                                recordTierWideFailure(fallbackTierId)
                            }
                        } finally {
                            circuitBreaker.releaseTrial(fallbackTierId)
                        }
                    }
                }
            }
        }

        Log.e(TAG, "All candidate tiers exhausted for video $videoId (progressiveOnly=$progressiveOnly)")
        return null
    }

    fun markTierFailedForVideo(videoId: String, tierId: String) {
        synchronized(videoFailedTiers) {
            val set = videoFailedTiers.getOrPut(videoId) { mutableSetOf() }
            set.add(tierId)
        }
    }

    fun clearVideoMemory(videoId: String) {
        synchronized(videoFailedTiers) {
            videoFailedTiers.remove(videoId)
        }
    }

    fun clearAllVideoMemory() {
        synchronized(videoFailedTiers) {
            videoFailedTiers.clear()
        }
    }
}

/**
 * Default production instance configured with 3 resilient, independent tiers:
 * Tier 1: visionOS Progressive (Opus 251 > AAC 140, 3MB range probe)
 * Tier 2: visionOS HLS (.m3u8, AAC Backup Tier)
 * Tier 3: Android VR Progressive (Oculus/Meta Quest InnerTube client, unthrottled, independent client/tokens)
 */
object DefaultResolverChain {
    val instance = ResolverChain(
        resolvers = listOf(
            VisionOsProgressiveResolver,
            VisionOsHlsResolver,
            WebSafariAuthHlsResolver
        )
    )
}
