package com.ytmusic.clone

import com.google.gson.JsonObject
import com.ytmusic.clone.data.network.AndroidVrProgressiveResolver
import com.ytmusic.clone.data.network.CircuitState
import com.ytmusic.clone.data.network.DiagnosticLogger
import com.ytmusic.clone.data.network.PlayerQueryResult
import com.ytmusic.clone.data.network.ResolveResult
import com.ytmusic.clone.data.network.Resolved
import com.ytmusic.clone.data.network.ResolverChain
import com.ytmusic.clone.data.network.StreamResolver
import com.ytmusic.clone.data.network.TierCircuitBreaker
import com.ytmusic.clone.data.network.UrlPolicy
import com.ytmusic.clone.data.network.VisionOsProgressiveResolver
import com.ytmusic.clone.data.network.YouTubeExtractor.ResolvedStream
import com.ytmusic.clone.player.FailoverDecision
import com.ytmusic.clone.player.FailoverEngine
import com.ytmusic.clone.player.PrefetchManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ResolverChainTest {

    private class FakeResolver(
        override val id: String,
        override val requiresSharedPlayer: Boolean = true,
        var resultToReturn: ResolveResult = ResolveResult.Success(
            Resolved.Progressive(
                url = "https://mock.googlevideo.com/videoplayback?itag=251",
                mime = "audio/webm",
                format = "Opus (160kbps)",
                tierId = id
            )
        ),
        var delayMs: Long = 0L,
        var throwCancellation: Boolean = false
    ) : StreamResolver {
        val callCount = AtomicInteger(0)
        var lastReceivedSharedPlayer: JsonObject? = null
        var lastReceivedCpn: String? = null

        override suspend fun resolve(
            videoId: String,
            sharedPlayerJson: JsonObject?,
            cpn: String?
        ): ResolveResult {
            callCount.incrementAndGet()
            lastReceivedSharedPlayer = sharedPlayerJson
            lastReceivedCpn = cpn
            if (throwCancellation) {
                throw CancellationException("Simulated job cancellation")
            }
            if (delayMs > 0) {
                delay(delayMs)
            }
            return resultToReturn
        }
    }

    private val mockPlayerProvider: suspend (String, Boolean) -> PlayerQueryResult = { _, _ ->
        val dummyJson = JsonObject().apply {
            add("playabilityStatus", JsonObject().apply { addProperty("status", "OK") })
        }
        PlayerQueryResult.Success(dummyJson, "mock_cpn_1234")
    }

    @Before
    fun setUp() {
        TierCircuitBreaker.resetAll()
        DiagnosticLogger.clear()
    }

    @After
    fun tearDown() {
        TierCircuitBreaker.resetAll()
        DiagnosticLogger.clear()
        com.ytmusic.clone.data.network.VisionOsPlayerClient.httpClient = com.ytmusic.clone.data.network.SecureHttp.newBuilder(com.ytmusic.clone.data.network.PolicyScope.STREAM)
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        com.ytmusic.clone.data.network.AndroidVrPlayerClient.httpClient = com.ytmusic.clone.data.network.SecureHttp.newBuilder(com.ytmusic.clone.data.network.PolicyScope.STREAM)
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Test
    fun chainTriesInOrderAndReturnsFirstWorking() = runBlocking {
        val tier1 = FakeResolver("tier1")
        val tier2 = FakeResolver("tier2")
        val chain = ResolverChain(listOf(tier1, tier2), playerProvider = mockPlayerProvider)

        val result = chain.resolve("song1")

        assertNotNull(result)
        assertEquals("tier1", result?.tierId)
        assertEquals(1, tier1.callCount.get())
        assertEquals(0, tier2.callCount.get()) // Short-circuits: tier2 is not called
    }

    @Test
    fun chainFallsBackToTier2WhenTier1Fails() = runBlocking {
        val tier1 = FakeResolver("tier1", resultToReturn = ResolveResult.TierWide("403 Forbidden"))
        val tier2 = FakeResolver(
            "tier2",
            resultToReturn = ResolveResult.Success(
                Resolved.Hls(
                    url = "https://mock.googlevideo.com/manifest.m3u8",
                    format = "HLS (AAC)",
                    tierId = "tier2"
                )
            )
        )
        val chain = ResolverChain(listOf(tier1, tier2), playerProvider = mockPlayerProvider)

        val result = chain.resolve("song1")

        assertNotNull(result)
        assertEquals("tier2", result?.tierId)
        assertTrue(result is Resolved.Hls)
        assertEquals(1, tier1.callCount.get())
        assertEquals(1, tier2.callCount.get())
    }

    @Test
    fun perVideoSkipMemorySkipsFailedTiers() = runBlocking {
        val tier1 = FakeResolver("tier1", resultToReturn = ResolveResult.TierWide("Probe rejected"))
        val tier2 = FakeResolver("tier2")
        val chain = ResolverChain(listOf(tier1, tier2), playerProvider = mockPlayerProvider)

        // Initial resolution for songA: tier1 fails, tier2 succeeds
        val res1 = chain.resolve("songA")
        assertEquals("tier2", res1?.tierId)
        assertEquals(1, tier1.callCount.get())

        // Now imagine tier1 is repaired, but we pass skipFailed = true for songA
        tier1.resultToReturn = ResolveResult.Success(
            Resolved.Progressive(
                url = "https://mock.googlevideo.com/videoplayback",
                mime = "audio/webm",
                format = "Opus (160kbps)",
                tierId = "tier1"
            )
        )
        val res2 = chain.resolve("songA", skipFailed = true)
        assertEquals("tier2", res2?.tierId)
        assertEquals(1, tier1.callCount.get()) // tier1 was skipped without being called!

        // But for a new track songB, tier1 is attempted
        val resB = chain.resolve("songB", skipFailed = true)
        assertEquals("tier1", resB?.tierId)
        assertEquals(2, tier1.callCount.get()) // songB tried tier1
    }

    @Test
    fun circuitBreakerTripsAfterThreeFailures() {
        val tier = "test_tier"
        val t0 = 1000L

        // Initial state
        assertEquals(CircuitState.CLOSED, TierCircuitBreaker.getState(tier, t0))
        assertTrue(TierCircuitBreaker.canExecute(tier, t0))

        // 1st failure
        TierCircuitBreaker.recordFailure(tier, t0)
        assertEquals(CircuitState.CLOSED, TierCircuitBreaker.getState(tier, t0))
        assertTrue(TierCircuitBreaker.canExecute(tier, t0))

        // 2nd failure
        TierCircuitBreaker.recordFailure(tier, t0 + 100L)
        assertEquals(CircuitState.CLOSED, TierCircuitBreaker.getState(tier, t0 + 100L))
        assertTrue(TierCircuitBreaker.canExecute(tier, t0 + 100L))

        // 3rd failure -> Trips to OPEN
        TierCircuitBreaker.recordFailure(tier, t0 + 200L)
        assertEquals(CircuitState.OPEN, TierCircuitBreaker.getState(tier, t0 + 200L))
        assertFalse(TierCircuitBreaker.canExecute(tier, t0 + 200L))
    }

    @Test
    fun circuitBreakerTransitionsToHalfOpenAndRecovers() {
        val tier = "test_tier_recovery"
        val t0 = 10_000L

        // Trip into OPEN
        repeat(3) { TierCircuitBreaker.recordFailure(tier, t0) }
        assertEquals(CircuitState.OPEN, TierCircuitBreaker.getState(tier, t0))

        // 5 minutes later: still OPEN
        val t5m = t0 + (5 * 60 * 1000L)
        assertEquals(CircuitState.OPEN, TierCircuitBreaker.getState(tier, t5m))
        assertFalse(TierCircuitBreaker.canExecute(tier, t5m))

        // 10 minutes + 1ms later: HALF_OPEN
        val t10m1ms = t0 + (10 * 60 * 1000L) + 1L
        assertEquals(CircuitState.HALF_OPEN, TierCircuitBreaker.getState(tier, t10m1ms))

        // Trial request is allowed exactly once
        assertTrue(TierCircuitBreaker.canExecute(tier, t10m1ms))
        // Simultaneous request while trial in progress is rejected
        assertFalse(TierCircuitBreaker.canExecute(tier, t10m1ms))

        // Trial succeeds -> transitions to CLOSED
        TierCircuitBreaker.recordSuccess(tier)
        assertEquals(CircuitState.CLOSED, TierCircuitBreaker.getState(tier, t10m1ms + 500L))
        assertTrue(TierCircuitBreaker.canExecute(tier, t10m1ms + 500L))
        assertEquals(0, TierCircuitBreaker.getConsecutiveFailures(tier))
    }

    @Test
    fun circuitBreakerHalfOpenTrialFailsReturnsToOpen() {
        val tier = "test_tier_trial_fail"
        val t0 = 10_000L

        // Trip into OPEN
        repeat(3) { TierCircuitBreaker.recordFailure(tier, t0) }

        // Cooldown elapsed -> enters HALF_OPEN
        val t10m = t0 + (10 * 60 * 1000L) + 10L
        assertEquals(CircuitState.HALF_OPEN, TierCircuitBreaker.getState(tier, t10m))
        assertTrue(TierCircuitBreaker.canExecute(tier, t10m))

        // Trial fails
        TierCircuitBreaker.recordFailure(tier, t10m + 100L)

        // Returns to OPEN for another 10m cooldown
        assertEquals(CircuitState.OPEN, TierCircuitBreaker.getState(tier, t10m + 100L))
        assertFalse(TierCircuitBreaker.canExecute(tier, t10m + 100L))
        assertTrue(TierCircuitBreaker.getRemainingCooldownMs(tier, t10m + 100L) > 0L)
    }

    @Test
    fun timeoutHandlingAbortsSlowTier() = runBlocking {
        val slowTier = FakeResolver("slow_tier", delayMs = 300L)
        val fastTier = FakeResolver("fast_tier", delayMs = 0L)
        val chain = ResolverChain(listOf(slowTier, fastTier), perTierTimeoutMs = 50L, playerProvider = mockPlayerProvider)

        val result = chain.resolve("timeout_song")

        assertNotNull(result)
        assertEquals("fast_tier", result?.tierId)
    }

    @Test
    fun cancellationExceptionIsRethrownAndDoesNotTripBreaker() = runBlocking {
        val cancelingTier = FakeResolver("canceling_tier", throwCancellation = true)
        val chain = ResolverChain(listOf(cancelingTier), playerProvider = mockPlayerProvider)

        try {
            chain.resolve("cancel_test")
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            // Expected
        }

        // Must not record failure in circuit breaker
        assertEquals(0, TierCircuitBreaker.getConsecutiveFailures("canceling_tier"))
        assertEquals(CircuitState.CLOSED, TierCircuitBreaker.getState("canceling_tier"))
    }

    @Test
    fun videoSpecificErrorsAbortChainAndDoNotTripBreaker() = runBlocking {
        val tier1 = FakeResolver("tier1", resultToReturn = ResolveResult.VideoSpecific("LOGIN_REQUIRED: age-restricted"))
        val tier2 = FakeResolver("tier2")
        val chain = ResolverChain(listOf(tier1, tier2), playerProvider = mockPlayerProvider)

        val result = chain.resolve("age_restricted_song")

        // Aborts chain immediately with null (song is unplayable)
        assertNull(result)
        assertEquals(1, tier1.callCount.get())
        assertEquals(0, tier2.callCount.get())

        // Must NOT hit circuit breaker
        assertEquals(0, TierCircuitBreaker.getConsecutiveFailures("tier1"))
        assertEquals(0, TierCircuitBreaker.getConsecutiveFailures("tier2"))
    }

    @Test
    fun transientErrorsDoNotTripBreaker() = runBlocking {
        val tier1 = FakeResolver("tier1", resultToReturn = ResolveResult.Transient("Read timeout"))
        val chain = ResolverChain(listOf(tier1), playerProvider = mockPlayerProvider)

        val result = chain.resolve("transient_song")

        assertNull(result)
        assertEquals(0, TierCircuitBreaker.getConsecutiveFailures("tier1"))
        assertEquals(CircuitState.CLOSED, TierCircuitBreaker.getState("tier1"))
    }

    @Test
    fun downloadProgressiveOnlyEnforcesProgressiveTiers() = runBlocking {
        val hlsOnlyTier = FakeResolver(
            "visionos_hls",
            resultToReturn = ResolveResult.Success(
                Resolved.Hls("https://mock/manifest.m3u8", "HLS (AAC)", tierId = "visionos_hls")
            )
        )
        val chain = ResolverChain(listOf(hlsOnlyTier), playerProvider = mockPlayerProvider)

        // When progressiveOnly = true, visionos_hls is filtered out
        val result = chain.resolve("dl_song", progressiveOnly = true, recordBreaker = false)

        assertNull(result)
        assertEquals(0, hlsOnlyTier.callCount.get())
    }

    @Test
    fun leastRecentlyFailedFallbackAttemptsOldestFailedTier() = runBlocking {
        val t0 = System.currentTimeMillis()
        val tier1 = FakeResolver("tier1")
        val tier2 = FakeResolver("tier2")

        // Trip both tiers into OPEN, with tier1 failing earlier (t0 - 500L) and tier2 failing later (t0)
        repeat(3) { TierCircuitBreaker.recordFailure("tier1", t0 - 500L) }
        repeat(3) { TierCircuitBreaker.recordFailure("tier2", t0) }

        assertEquals(CircuitState.OPEN, TierCircuitBreaker.getState("tier1"))
        assertEquals(CircuitState.OPEN, TierCircuitBreaker.getState("tier2"))

        val chain = ResolverChain(listOf(tier1, tier2), playerProvider = mockPlayerProvider)

        // Normal loop skips both tiers because both are OPEN.
        // Fallback selects least-recently-failed (tier1, oldest failure) and attempts it once.
        val result = chain.resolve("fallback_song")

        assertNotNull(result)
        assertEquals("tier1", result?.tierId)
        assertEquals(1, tier1.callCount.get())
        // And success restores tier1 to CLOSED
        assertEquals(CircuitState.CLOSED, TierCircuitBreaker.getState("tier1"))
    }

    @Test
    fun diagnosticsSanitizesUrlsAndVisitorData() {
        DiagnosticLogger.log(
            tierId = "visionos_progressive",
            status = "SUCCESS",
            probeResult = "Resolved at https://rr1---sn-4g5ednss.googlevideo.com/videoplayback?expire=123&visitorData=ABC123XYZ in 410ms"
        )

        val entries = DiagnosticLogger.getRecentEntries()
        assertEquals(1, entries.size)
        val probe = entries.first().probeResult

        assertFalse("Diagnostic must not contain raw URL", probe.contains("https://"))
        assertFalse("Diagnostic must not contain raw visitorData token", probe.contains("ABC123XYZ"))
        assertTrue("Diagnostic must redact URLs", probe.contains("[URL_REDACTED]"))
    }

    // =========================================================================
    // v7.18 Requirement 7 Unit Tests:
    // 1. Age-restricted classification vs Bot-check detection
    // 2. Pure FailoverEngine & EOF failover to HLS
    // 3. Trial release in finally on cancellation
    // 4. Player-call 403 => TierWide trips breaker
    // 5. Progressive probe with MockWebServer (cliff, short body, 200 full-file, 206 valid)
    // 6. Least-recently-failed fallback respects skipFailed
    // =========================================================================

    @Test
    fun ageRestrictedClassifiedAsVideoSpecificWithoutBreakerPenalty() = runBlocking {
        val tier1 = FakeResolver("tier1")
        val agePlayerProvider: suspend (String, Boolean) -> PlayerQueryResult = { _, _ ->
            PlayerQueryResult.VideoSpecific("LOGIN_REQUIRED: Sign in to confirm your age")
        }
        val chain = ResolverChain(listOf(tier1), playerProvider = agePlayerProvider)

        val result = chain.resolve("age_restricted_song")

        // Age-restricted returns null immediately
        assertNull(result)
        // Resolver is not even attempted
        assertEquals(0, tier1.callCount.get())
        // Must NOT trip circuit breaker
        assertEquals(0, TierCircuitBreaker.getConsecutiveFailures("tier1"))
        assertEquals(CircuitState.CLOSED, TierCircuitBreaker.getState("tier1"))
    }

    @Test
    fun botChallengeClassifiedAsTierWideTripsCircuitBreaker() = runBlocking {
        val tier1 = FakeResolver("tier1")
        val botPlayerProvider: suspend (String, Boolean) -> PlayerQueryResult = { _, _ ->
            PlayerQueryResult.TierWide("LOGIN_REQUIRED: Sign in to confirm you're not a bot")
        }
        val chain = ResolverChain(listOf(tier1), playerProvider = botPlayerProvider)

        val result = chain.resolve("bot_song")

        assertNull(result)
        assertEquals(0, tier1.callCount.get())
        // Breaker failure must be recorded for candidates
        assertEquals(1, TierCircuitBreaker.getConsecutiveFailures("tier1"))
    }

    @Test
    fun pureFailoverEngineEofFailoverToHlsAndSkipTier() = runBlocking {
        // 1. Pure FailoverEngine evaluation
        val eofDecision = FailoverEngine.evaluatePlaybackError(
            errorCode = 0,
            httpStatusCode = null,
            isEofOrTimeout = true,
            hasRetriedSameClient = false,
            hasFailedOverCurrentTrack = false,
            playedLong = false
        )
        assertEquals(FailoverDecision.Action.FAILOVER_NEXT_TIER, eofDecision.action)
        assertTrue("EOF failover must mark video tier failed", eofDecision.markVideoTierFailed)
        assertFalse("EOF failover must NOT blame circuit breaker", eofDecision.blameCircuitBreaker)

        // 2. ResolverChain integration: tier1 marked failed for video, resolves tier2 (HLS)
        val tier1 = FakeResolver("tier1")
        val tier2 = FakeResolver(
            "tier2",
            resultToReturn = ResolveResult.Success(
                Resolved.Hls(
                    url = "https://mock.googlevideo.com/manifest.m3u8",
                    format = "HLS (AAC)",
                    tierId = "tier2"
                )
            )
        )
        val chain = ResolverChain(listOf(tier1, tier2), playerProvider = mockPlayerProvider)
        chain.markTierFailedForVideo("vidEOF", "tier1")

        val result = chain.resolve("vidEOF", skipFailed = true)
        assertNotNull(result)
        assertEquals("tier2", result?.tierId)
        assertTrue(result is Resolved.Hls)
        assertEquals(0, tier1.callCount.get()) // tier1 was skipped!
        assertEquals(1, tier2.callCount.get())
    }

    @Test
    fun trialReleaseOnCancellationLeavesHalfOpenUsable() = runBlocking {
        val tierId = "canceling_tier"
        val t0 = 10_000L

        // Trip into OPEN
        repeat(3) { TierCircuitBreaker.recordFailure(tierId, t0) }
        assertEquals(CircuitState.OPEN, TierCircuitBreaker.getState(tierId, t0))

        // Cooldown passes -> enters HALF_OPEN
        val tCooldown = t0 + TierCircuitBreaker.COOLDOWN_DURATION_MS + 10L
        assertEquals(CircuitState.HALF_OPEN, TierCircuitBreaker.getState(tierId, tCooldown))

        // Resolver that throws CancellationException during trial
        val cancelingResolver = FakeResolver(tierId, throwCancellation = true)
        val chain = ResolverChain(listOf(cancelingResolver), playerProvider = mockPlayerProvider)

        try {
            chain.resolve("cancel_test")
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            // Expected
        }

        // Verification: trialInProgress MUST be released in finally block!
        // Another trial request must now be permitted rather than blocked
        assertTrue("Trial lock must be released on cancellation", TierCircuitBreaker.canExecute(tierId, tCooldown))
    }

    @Test
    fun playerQuery403ReturnsTierWideAndRecordsBreakerFailure() = runBlocking {
        val tier1 = FakeResolver("tier1")
        val tier2 = FakeResolver("tier2")
        val http403Provider: suspend (String, Boolean) -> PlayerQueryResult = { _, _ ->
            PlayerQueryResult.TierWide("Player HTTP 403")
        }
        val chain = ResolverChain(listOf(tier1, tier2), playerProvider = http403Provider)

        val result = chain.resolve("track403")

        assertNull(result)
        assertEquals(0, tier1.callCount.get())
        assertEquals(0, tier2.callCount.get())
        // Both candidates recorded failure in circuit breaker
        assertEquals(1, TierCircuitBreaker.getConsecutiveFailures("tier1"))
        assertEquals(1, TierCircuitBreaker.getConsecutiveFailures("tier2"))
    }

    @Test
    fun leastRecentlyFailedFallbackRespectsSkipFailedForCurrentVideo() = runBlocking {
        val tier1 = FakeResolver("tier1")
        val tier2 = FakeResolver(
            "tier2",
            resultToReturn = ResolveResult.Success(
                Resolved.Hls(
                    url = "https://mock.googlevideo.com/manifest.m3u8",
                    format = "HLS (AAC)",
                    tierId = "tier2"
                )
            )
        )

        val t0 = System.currentTimeMillis()
        // Trip both tiers into OPEN (tier1 oldest failure, tier2 newest)
        repeat(3) { TierCircuitBreaker.recordFailure("tier1", t0 - 500L) }
        repeat(3) { TierCircuitBreaker.recordFailure("tier2", t0) }

        val chain = ResolverChain(listOf(tier1, tier2), playerProvider = mockPlayerProvider)

        // Mark tier1 as failed for current video "songMidFail"
        chain.markTierFailedForVideo("songMidFail", "tier1")

        // When resolving with skipFailed = true, fallback MUST NOT choose tier1
        // even though tier1 failed longer ago, because tier1 is in failedForVideo!
        val result = chain.resolve("songMidFail", skipFailed = true)

        assertNotNull(result)
        assertEquals("tier2", result?.tierId) // Chose tier2!
        assertEquals(0, tier1.callCount.get())
        assertEquals(1, tier2.callCount.get())
    }

    @Test
    fun progressiveProbeMockWebServerTests() {
        val server = MockWebServer()
        server.start()
        com.ytmusic.clone.data.network.VisionOsPlayerClient.httpClient = okhttp3.OkHttpClient.Builder().build()

        try {
            val mockUrl = server.url("/videoplayback?clen=5000000").toString()

            // 1. Cliff: server returns HTTP 403 on range probe
            server.enqueue(MockResponse().setResponseCode(403))
            val cliffResult = VisionOsProgressiveResolver.probeProgressiveStream(mockUrl, 5_000_000L)
            assertTrue("Expected TierWide on 403 cliff", cliffResult is ResolveResult.TierWide)
            assertTrue((cliffResult as ResolveResult.TierWide).reason.contains("HTTP 403"))

            // 2. Short body: server returns 206 Partial Content but only 500 bytes instead of 1024
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setBody(Buffer().write(ByteArray(500)))
            )
            val shortResult = VisionOsProgressiveResolver.probeProgressiveStream(mockUrl, 5_000_000L)
            assertTrue("Expected TierWide on short body", shortResult is ResolveResult.TierWide)
            assertTrue((shortResult as ResolveResult.TierWide).reason.contains("Truncated range probe"))

            // 3. 200 full-file rejection: server returns 200 OK and dumps full file (100,000 bytes)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(Buffer().write(ByteArray(100_000)))
            )
            val fullFileResult = VisionOsProgressiveResolver.probeProgressiveStream(mockUrl, 5_000_000L)
            assertTrue("Expected TierWide when server returns full file on Range", fullFileResult is ResolveResult.TierWide)
            assertTrue((fullFileResult as ResolveResult.TierWide).reason.contains("returned full file"))

            // 4. Valid 206 partial content with exact 1024 bytes -> Success
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setBody(Buffer().write(ByteArray(1024)))
            )
            val validResult = VisionOsProgressiveResolver.probeProgressiveStream(mockUrl, 5_000_000L)
            assertTrue("Expected Success on valid range probe", validResult is ResolveResult.Success)
            assertEquals("visionos_progressive", (validResult as ResolveResult.Success).resolved.tierId)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun independentClientSucceedsWhenVisionOsPlayerFailsWithTierWide() = runBlocking {
        val visionTier = FakeResolver("visionos_progressive", requiresSharedPlayer = true)
        val vrTier = FakeResolver("android_vr_progressive", requiresSharedPlayer = false, resultToReturn = ResolveResult.Success(
            Resolved.Progressive(
                url = "https://mock.googlevideo.com/vr_playback",
                mime = "audio/webm",
                format = "VR Opus (160kbps)",
                tierId = "android_vr_progressive"
            )
        ))

        // VisionOS player endpoint returns 403 TierWide failure
        val failingVisionPlayerProvider: suspend (String, Boolean) -> PlayerQueryResult = { _, _ ->
            PlayerQueryResult.TierWide("Player HTTP 403")
        }

        val chain = ResolverChain(listOf(visionTier, vrTier), playerProvider = failingVisionPlayerProvider)

        val result = chain.resolve("track_visionos_blocked")

        // Result should NOT be null; it should succeed via the independent VR tier!
        assertNotNull(result)
        assertEquals("android_vr_progressive", result?.tierId)
        assertEquals(0, visionTier.callCount.get()) // visionTier skipped because shared player failed
        assertEquals(1, vrTier.callCount.get()) // vrTier called and succeeded!
        assertEquals(1, TierCircuitBreaker.getConsecutiveFailures("visionos_progressive"))
        assertEquals(0, TierCircuitBreaker.getConsecutiveFailures("android_vr_progressive"))
    }

    @Test
    fun prefetchManagerCacheHitAndExpiry() = runBlocking {
        PrefetchManager.clear()

        val mockStream = ResolvedStream(
            url = "https://mock.googlevideo.com/videoplayback",
            format = "Opus (160kbps)",
            userAgent = "MockUA",
            client = "visionos_progressive"
        )

        // 1. Initially empty
        assertNull(PrefetchManager.getPrefetched("song123"))

        // 2. Put fresh entry and retrieve
        PrefetchManager.putPrefetched("song123", mockStream)
        val hit = PrefetchManager.getPrefetched("song123")
        assertNotNull(hit)
        assertEquals("visionos_progressive", hit?.client)

        // 3. Consumed on hit: subsequent get returns null
        assertNull(PrefetchManager.getPrefetched("song123"))

        // 4. Expiry: put expired entry (> 10 mins old)
        val expiredTime = System.currentTimeMillis() - (11 * 60 * 1000L)
        PrefetchManager.putPrefetched("songExpired", mockStream, timestampMs = expiredTime)
        assertNull("Expired prefetch entry must be evicted", PrefetchManager.getPrefetched("songExpired"))
    }

    @Test
    fun androidVrProgressiveProbeMockWebServerTests() {
        val server = MockWebServer()
        server.start()
        com.ytmusic.clone.data.network.AndroidVrPlayerClient.httpClient = okhttp3.OkHttpClient.Builder().build()

        try {
            val mockUrl = server.url("/videoplayback?clen=5000000").toString()

            // 1. Cliff: server returns HTTP 403 on range probe
            server.enqueue(MockResponse().setResponseCode(403))
            val cliffResult = AndroidVrProgressiveResolver.probeProgressiveStream(mockUrl, 5_000_000L)
            assertTrue("Expected TierWide on 403 cliff", cliffResult is ResolveResult.TierWide)
            assertTrue((cliffResult as ResolveResult.TierWide).reason.contains("HTTP 403"))

            // 2. Valid 206 partial content with exact 1024 bytes -> Success
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setBody(Buffer().write(ByteArray(1024)))
            )
            val validResult = AndroidVrProgressiveResolver.probeProgressiveStream(mockUrl, 5_000_000L)
            assertTrue("Expected Success on valid Android VR range probe", validResult is ResolveResult.Success)
            assertEquals("android_vr_progressive", (validResult as ResolveResult.Success).resolved.tierId)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun independentResolverReceivesNullSharedPlayerEvenWhenVisionOsSucceeds() = runBlocking {
        val tier1 = FakeResolver(
            id = "visionos_progressive",
            requiresSharedPlayer = true,
            resultToReturn = ResolveResult.TierWide("Range probe cliff failed")
        )
        val tier3 = FakeResolver(
            id = "android_vr_progressive",
            requiresSharedPlayer = false,
            resultToReturn = ResolveResult.Success(
                Resolved.Progressive(
                    url = "https://mock.googlevideo.com/vr_stream",
                    mime = "audio/webm",
                    format = "VR Opus",
                    tierId = "android_vr_progressive"
                )
            )
        )

        val chain = ResolverChain(listOf(tier1, tier3), playerProvider = mockPlayerProvider)
        val res = chain.resolve("song1")

        assertNotNull(res)
        assertEquals("android_vr_progressive", res?.tierId)
        // Verify Tier 1 received shared JSON from visionOS mockPlayerProvider
        assertNotNull(tier1.lastReceivedSharedPlayer)
        // Verify Tier 3 received NULL shared JSON to ensure 100% independent client isolation
        assertNull("Independent VR resolver must NOT receive visionOS shared player JSON", tier3.lastReceivedSharedPlayer)
        assertNull("Independent VR resolver must NOT receive visionOS shared CPN", tier3.lastReceivedCpn)
    }

    @Test
    fun vrFailureDoesNotTouchVisionOsCircuitCounters() = runBlocking {
        val tier1 = FakeResolver("visionos_progressive", requiresSharedPlayer = true)
        val tier3 = FakeResolver("android_vr_progressive", requiresSharedPlayer = false, resultToReturn = ResolveResult.TierWide("VR network failure"))

        TierCircuitBreaker.resetAll()
        assertEquals(0, TierCircuitBreaker.getConsecutiveFailures("visionos_progressive"))
        assertEquals(0, TierCircuitBreaker.getConsecutiveFailures("android_vr_progressive"))

        // Simulate 2 VR failures
        TierCircuitBreaker.recordFailure("android_vr_progressive")
        TierCircuitBreaker.recordFailure("android_vr_progressive")

        assertEquals(2, TierCircuitBreaker.getConsecutiveFailures("android_vr_progressive"))
        // VisionOS counter must remain pristine at 0!
        assertEquals(0, TierCircuitBreaker.getConsecutiveFailures("visionos_progressive"))
    }

    @Test
    fun failoverEngineAllowsCascadingThroughTier3() {
        val badHttpStatusError = androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS

        // 1. First 403 error on Tier 1 when hasRetriedSameClient is false -> retry same tier fresh
        val initial403 = FailoverEngine.evaluatePlaybackError(
            errorCode = badHttpStatusError,
            httpStatusCode = 403,
            isEofOrTimeout = false,
            hasRetriedSameClient = false,
            failoverCount = 0,
            maxFailovers = 2,
            playedLong = false
        )
        assertEquals(FailoverDecision.Action.RETRY_SAME_TIER_FRESH, initial403.action)

        // 2. HTTP 403 on Tier 1 after retry -> Failover to next tier (failoverCount = 0 -> 1)
        val decision1 = FailoverEngine.evaluatePlaybackError(
            errorCode = badHttpStatusError,
            httpStatusCode = 403,
            isEofOrTimeout = false,
            hasRetriedSameClient = true,
            failoverCount = 0,
            maxFailovers = 2,
            playedLong = false
        )
        assertEquals(FailoverDecision.Action.FAILOVER_NEXT_TIER, decision1.action)
        assertTrue(decision1.blameCircuitBreaker)

        // 3. HTTP 403 on Tier 2 -> Failover to Tier 3 (failoverCount = 1 -> 2)
        val decision2 = FailoverEngine.evaluatePlaybackError(
            errorCode = badHttpStatusError,
            httpStatusCode = 403,
            isEofOrTimeout = false,
            hasRetriedSameClient = true,
            failoverCount = 1,
            maxFailovers = 2,
            playedLong = false
        )
        assertEquals(FailoverDecision.Action.FAILOVER_NEXT_TIER, decision2.action)

        // 4. Once failoverCount reaches maxFailovers (2), do not loop infinitely
        val decision3 = FailoverEngine.evaluatePlaybackError(
            errorCode = badHttpStatusError,
            httpStatusCode = 403,
            isEofOrTimeout = false,
            hasRetriedSameClient = true,
            failoverCount = 2,
            maxFailovers = 2,
            playedLong = false
        )
        assertEquals(FailoverDecision.Action.NONE, decision3.action)
    }

    @Test
    fun remoteConfigVersionRegexValidation() {
        assertTrue(com.ytmusic.clone.data.network.RemoteConfigFetcher.isValidVersion("1.71.26"))
        assertTrue(com.ytmusic.clone.data.network.RemoteConfigFetcher.isValidVersion("1.0"))
        assertTrue(com.ytmusic.clone.data.network.RemoteConfigFetcher.isValidVersion("7.24.1"))
        assertTrue(com.ytmusic.clone.data.network.RemoteConfigFetcher.isValidVersion("2024.11.10"))

        assertFalse(com.ytmusic.clone.data.network.RemoteConfigFetcher.isValidVersion(null))
        assertFalse(com.ytmusic.clone.data.network.RemoteConfigFetcher.isValidVersion(""))
        assertFalse(com.ytmusic.clone.data.network.RemoteConfigFetcher.isValidVersion("    "))
        assertFalse(com.ytmusic.clone.data.network.RemoteConfigFetcher.isValidVersion("v1.2.3"))
        assertFalse(com.ytmusic.clone.data.network.RemoteConfigFetcher.isValidVersion("1.2.3.4.5.6"))
        assertFalse(com.ytmusic.clone.data.network.RemoteConfigFetcher.isValidVersion("invalid_version"))
        assertFalse(com.ytmusic.clone.data.network.RemoteConfigFetcher.isValidVersion("1.0; rm -rf /"))
    }
}
