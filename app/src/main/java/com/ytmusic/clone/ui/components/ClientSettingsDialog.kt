package com.ytmusic.clone.ui.components

import com.ytmusic.clone.player.AudioCache

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import com.ytmusic.clone.data.local.DownloadManager
import com.ytmusic.clone.data.local.ExtractorPrefs
import com.ytmusic.clone.data.network.AndroidVrPlayerClient
import com.ytmusic.clone.data.network.AndroidVrProgressiveResolver
import com.ytmusic.clone.data.network.CircuitState
import com.ytmusic.clone.data.network.DiagnosticLogger
import com.ytmusic.clone.data.network.RemoteConfigFetcher
import com.ytmusic.clone.data.network.ResolveResult
import com.ytmusic.clone.data.network.Resolved
import com.ytmusic.clone.data.network.TierCircuitBreaker
import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import com.ytmusic.clone.data.network.WebSafariAuthHlsResolver
import com.ytmusic.clone.data.network.VisionOsHlsResolver
import com.ytmusic.clone.data.network.VisionOsPlayerClient
import com.ytmusic.clone.data.network.VisionOsProfile
import com.ytmusic.clone.data.network.VisionOsProgressiveResolver
import com.ytmusic.clone.data.network.YouTubeExtractor
import com.ytmusic.clone.player.MusicPlayerManager
import com.ytmusic.clone.player.PrefetchManager
import com.ytmusic.clone.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class TierSelfTestItem(
    val tierId: String,
    val name: String,
    val status: String,       // "PASS" or "FAIL"
    val latencyMs: Long,
    val detail: String
)

data class SoakTestResult(
    val tierId: String,
    val name: String,
    val passed: Boolean,
    val bytesTransferred: Long,
    val durationSeconds: Int,
    val detail: String,
    // v7.19: true when the test could not run for a reason that is NOT the tier's fault
    // (e.g. every test track is restricted). Shown as SKIP, not FAIL.
    val inconclusive: Boolean = false
)

// v7.19: fallback list so one removed/restricted track can't make a healthy tier look broken.
private val SOAK_TEST_VIDEO_IDS = listOf("kJQP7kiw5Fk", "dQw4w9WgXcQ")

@Composable
fun ClientSettingsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    // Editable version states (cleaned up per v7.18 requirement 5)
    var visionOsVersion by remember { mutableStateOf(ExtractorPrefs.getVisionOsVersion()) }
    var androidVrVersion by remember { mutableStateOf(ExtractorPrefs.getAndroidVrVersion()) }
    var remixVersion by remember { mutableStateOf(ExtractorPrefs.getRemixVersion()) }
    var isFetchingRemoteConfig by remember { mutableStateOf(false) }

    // Test results state
    var isTesting by remember { mutableStateOf(false) }
    var selfTestResults by remember { mutableStateOf<List<TierSelfTestItem>>(emptyList()) }
    var healthRefreshTrigger by remember { mutableStateOf(0) }

    // Playback soak test state (v7.18 requirement 8)
    var isSoakTesting by remember { mutableStateOf(false) }
    var soakStatusText by remember { mutableStateOf("") }
    var soakResults by remember { mutableStateOf<List<SoakTestResult>>(emptyList()) }
    var isSignedIn by remember { mutableStateOf(ExtractorPrefs.hasYoutubeCookie()) }
    var showSignInWebView by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = YtSurface,
            contentColor = YtTextPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Client Health & Settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = YtTextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // --- SECTION 0: Optional YouTube Sign-In (for LOGIN_REQUIRED tracks) ---
                    Text(
                        text = "YouTube Account (Restricted Tracks)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = YtPrimaryRed
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when {
                            isSignedIn && ExtractorPrefs.isYoutubeCookieExpired() -> "Session expired — tap Re-Sign In"
                            isSignedIn -> "Signed in (session encrypted on device)"
                            else -> "Not signed in — sign in only if a song says LOGIN_REQUIRED."
                        },
                        fontSize = 12.sp,
                        color = if (isSignedIn) Color(0xFF4CAF50) else YtTextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "How it works: Normal songs and background prefetch stay 100% logged-out. Your session is only used when Tier 1 & 2 (visionOS) fail with LOGIN_REQUIRED. Use a secondary Google account to avoid any risk to your main account.",
                        fontSize = 11.sp,
                        color = YtTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showSignInWebView = true },
                            colors = ButtonDefaults.buttonColors(containerColor = YtPrimaryRed),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (isSignedIn) "Re-Sign In" else "Sign In to YouTube", color = Color.White, fontSize = 12.sp)
                        }
                        if (isSignedIn) {
                            OutlinedButton(
                                onClick = {
                                    ExtractorPrefs.clearYoutubeCookie()
                                    try { CookieManager.getInstance().removeAllCookies(null) } catch (_: Throwable) {}
                                    isSignedIn = false
                                    Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Sign Out", color = YtTextPrimary, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    // --- SECTION 1: Tier Circuit Breakers ---
                    Text(
                        text = "Tier Circuit Breakers",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = YtPrimaryRed
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "3 consecutive failures trip a tier into 10m cooldown (OPEN). After 10m, 1 trial is permitted (HALF-OPEN).",
                        fontSize = 12.sp,
                        color = YtTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    key(healthRefreshTrigger) {
                        val tiers = listOf(
                            VisionOsProgressiveResolver.TIER_ID to "Tier 1: visionOS Progressive (Opus 160k)",
                            VisionOsHlsResolver.TIER_ID to "Tier 2: visionOS HLS (.m3u8 AAC Backup)",
                            WebSafariAuthHlsResolver.TIER_ID to "Tier 3: Signed-In Web HLS (Restricted Tracks)"
                        )

                        tiers.forEach { (tierId, displayName) ->
                            val state = TierCircuitBreaker.getState(tierId)
                            val remainingSec = TierCircuitBreaker.getRemainingCooldownMs(tierId) / 1000L
                            val fails = TierCircuitBreaker.getConsecutiveFailures(tierId)

                            val stateLabel = when (state) {
                                CircuitState.CLOSED -> "CLOSED (Healthy)"
                                CircuitState.HALF_OPEN -> "HALF-OPEN (Trial)"
                                CircuitState.OPEN -> "OPEN (${remainingSec / 60}m ${remainingSec % 60}s left, $fails fails)"
                            }

                            val stateColor = when (state) {
                                CircuitState.CLOSED -> Color(0xFF4CAF50)
                                CircuitState.HALF_OPEN -> Color(0xFFFFB300)
                                CircuitState.OPEN -> Color(0xFFFF5252)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(YtSurfaceVariant, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = displayName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = YtTextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stateLabel,
                                    fontSize = 11.sp,
                                    color = stateColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            TierCircuitBreaker.resetAll()
                            healthRefreshTrigger++
                            Toast.makeText(context, "Circuit breakers & cooldowns reset to CLOSED", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = YtTextPrimary)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reset Circuit Breakers to CLOSED", fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = YtDivider)
                    Spacer(modifier = Modifier.height(16.dp))

                    // --- SECTION 2: Self-Test Tiers Now ---
                    Text(
                        text = "Self-Test Tiers Now",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = YtPrimaryRed
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Deep-probes progressive Opus past 3 MB and verifies HLS playlist & first segment on known public tracks (kJQP7kiw5Fk / dQw4w9WgXcQ).",
                        fontSize = 12.sp,
                        color = YtTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            if (!isTesting) {
                                isTesting = true
                                selfTestResults = emptyList()
                                coroutineScope.launch {
                                    val testVideos = listOf("kJQP7kiw5Fk", "dQw4w9WgXcQ")
                                    val items = mutableListOf<TierSelfTestItem>()

                                    val resolvers = listOf(
                                        VisionOsProgressiveResolver to "visionOS Progressive",
                                        VisionOsHlsResolver to "visionOS HLS"
                                    )

                                    for ((resolver, name) in resolvers) {
                                        val tierId = resolver.id
                                        var lastResult: ResolveResult? = null
                                        var latency = 0L

                                        for (videoId in testVideos) {
                                            val tStart = System.currentTimeMillis()
                                            try {
                                                val res = resolver.resolve(videoId)
                                                latency = System.currentTimeMillis() - tStart
                                                lastResult = res
                                                if (res is ResolveResult.Success) {
                                                    break
                                                }
                                                if (res !is ResolveResult.VideoSpecific) {
                                                    break
                                                }
                                            } catch (e: Exception) {
                                                latency = System.currentTimeMillis() - tStart
                                                lastResult = ResolveResult.Transient(e.javaClass.simpleName)
                                                break
                                            }
                                        }

                                        when (lastResult) {
                                            is ResolveResult.Success -> {
                                                TierCircuitBreaker.transitionToHalfOpen(tierId)
                                                items.add(TierSelfTestItem(tierId, name, "PASS", latency, lastResult.resolved.format))
                                            }
                                            is ResolveResult.VideoSpecific -> {
                                                items.add(TierSelfTestItem(tierId, name, "FAIL", latency, "Video restricted: ${lastResult.reason}"))
                                            }
                                            is ResolveResult.TierWide -> {
                                                TierCircuitBreaker.recordFailure(tierId)
                                                items.add(TierSelfTestItem(tierId, name, "FAIL", latency, lastResult.reason))
                                            }
                                            is ResolveResult.Transient, null -> {
                                                val reason = (lastResult as? ResolveResult.Transient)?.reason ?: "Timeout"
                                                items.add(TierSelfTestItem(tierId, name, "FAIL", latency, reason))
                                            }
                                        }
                                    }

                                    selfTestResults = items
                                    isTesting = false
                                    healthRefreshTrigger++
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isTesting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = YtPrimaryRed,
                            contentColor = Color.White
                        )
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing Both Tiers (3MB + HLS Probe)...", fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Self-Test Tiers Now", fontSize = 13.sp)
                        }
                    }

                    if (selfTestResults.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        // Result table header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(YtSurfaceVariant, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Tier", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = YtTextSecondary, modifier = Modifier.weight(1.3f))
                            Text("Status", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = YtTextSecondary, modifier = Modifier.weight(0.8f))
                            Text("Latency", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = YtTextSecondary, modifier = Modifier.weight(0.9f))
                            Text("Result", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = YtTextSecondary, modifier = Modifier.weight(1.5f))
                        }

                        selfTestResults.forEach { item ->
                            val isPass = item.status == "PASS"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.dp)
                                    .background(YtSurfaceVariant.copy(alpha = 0.5f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(item.name, fontSize = 11.sp, color = YtTextPrimary, modifier = Modifier.weight(1.3f), fontWeight = FontWeight.Medium)
                                Text(
                                    item.status,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isPass) Color(0xFF4CAF50) else Color(0xFFFF5252),
                                    modifier = Modifier.weight(0.8f)
                                )
                                Text("${item.latencyMs}ms", fontSize = 11.sp, color = YtTextSecondary, modifier = Modifier.weight(0.9f))
                                Text(item.detail, fontSize = 11.sp, color = YtTextPrimary, modifier = Modifier.weight(1.5f))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = YtDivider)
                    Spacer(modifier = Modifier.height(16.dp))

                    // --- SECTION 3: Playback Soak Test (90s via ExoPlayer) ---
                    Text(
                        text = "Playback Soak Test (90s)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = YtPrimaryRed
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Plays a known track for ~90s via ExoPlayer on each tier to verify real audio delivery crossing 1 MiB without error.",
                        fontSize = 12.sp,
                        color = YtTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (isSoakTesting) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(YtSurfaceVariant, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = YtPrimaryRed,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Running Soak Test...",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = YtTextPrimary
                                )
                                Text(
                                    text = soakStatusText,
                                    fontSize = 11.sp,
                                    color = YtTextSecondary
                                )
                            }
                            TextButton(onClick = { isSoakTesting = false }) {
                                Text("Stop", color = Color(0xFFFF5252), fontSize = 12.sp)
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                if (!isSoakTesting) {
                                    isSoakTesting = true
                                    soakResults = emptyList()
                                    soakStatusText = "Preparing ExoPlayer soak test..."
                                    coroutineScope.launch {
                                        val tiers = listOf(
                                            VisionOsProgressiveResolver to "visionOS Progressive",
                                            VisionOsHlsResolver to "visionOS HLS"
                                        )
                                        val results = mutableListOf<SoakTestResult>()

                                        for ((resolver, tierName) in tiers) {
                                            if (!isSoakTesting) break
                                            // v7.19: try each test track; a restricted track is skipped, not a tier failure
                                            var testVideoId = SOAK_TEST_VIDEO_IDS.first()
                                            var resolveResult: ResolveResult = ResolveResult.Transient("not run")
                                            for (candidateId in SOAK_TEST_VIDEO_IDS) {
                                                if (!isSoakTesting) break
                                                testVideoId = candidateId
                                                soakStatusText = "Resolving $tierName for $candidateId..."
                                                resolveResult = try {
                                                    withContext(Dispatchers.IO) {
                                                        resolver.resolve(candidateId)
                                                    }
                                                } catch (e: Exception) {
                                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                                    ResolveResult.Transient(e.javaClass.simpleName)
                                                }
                                                if (resolveResult !is ResolveResult.VideoSpecific) break
                                            }

                                            if (resolveResult !is ResolveResult.Success) {
                                                val isTrackIssue = resolveResult is ResolveResult.VideoSpecific
                                                val reason = when (resolveResult) {
                                                    is ResolveResult.VideoSpecific -> "Test tracks unavailable (not a tier failure): ${resolveResult.reason}"
                                                    is ResolveResult.TierWide -> "TierWide fail: ${resolveResult.reason}"
                                                    is ResolveResult.Transient -> "Transient: ${resolveResult.reason}"
                                                    else -> "Resolution failed"
                                                }
                                                results.add(
                                                    SoakTestResult(
                                                        tierId = resolver.id,
                                                        name = tierName,
                                                        passed = false,
                                                        bytesTransferred = 0L,
                                                        durationSeconds = 0,
                                                        detail = if (isTrackIssue) reason else "Failed resolve: $reason",
                                                        inconclusive = isTrackIssue
                                                    )
                                                )
                                                soakResults = results.toList()
                                                continue
                                            }

                                            val resolved = (resolveResult as ResolveResult.Success).resolved
                                            val playedVideoId = testVideoId
                                            soakStatusText = "Playing $tierName (~90s soak via ExoPlayer)..."

                                            var errorMsg: String? = null
                                            val bytesCounter = AtomicLong(0L)
                                            var playedSec = 0

                                            val testPlayer = withContext(Dispatchers.Main) {
                                                val transferListener = object : TransferListener {
                                                    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                                                    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                                                    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferredChunk: Int) {
                                                        bytesCounter.addAndGet(bytesTransferredChunk.toLong())
                                                    }
                                                    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                                                }
                                                // v7.19: same MediaSource/data source as real playback for this tier
                                                val isHls = resolved is Resolved.Hls || resolver.id == VisionOsHlsResolver.TIER_ID
                                                val mediaItem = MediaItem.Builder()
                                                    .setUri(Uri.parse(resolved.url))
                                                    .setMediaId(playedVideoId)
                                                    .build()
                                                val mediaSource = MusicPlayerManager.createMediaSource(
                                                    context = context.applicationContext,
                                                    mediaItem = mediaItem,
                                                    isHls = isHls,
                                                    transferListener = transferListener,
                                                    userAgent = resolved.userAgent
                                                )
                                                val player = ExoPlayer.Builder(context).build()
                                                // same as real playback: never fetch/decode video
                                                player.trackSelectionParameters = player.trackSelectionParameters
                                                    .buildUpon()
                                                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                                    .build()
                                                player.volume = 0f
                                                player.addListener(object : Player.Listener {
                                                    override fun onPlayerError(error: PlaybackException) {
                                                        errorMsg = "${error.errorCodeName} (${error.errorCode}): ${error.message}"
                                                    }
                                                })
                                                player.setMediaSource(mediaSource)
                                                player.prepare()
                                                player.play()
                                                player
                                            }

                                            try {
                                                for (s in 1..90) {
                                                    if (!isSoakTesting || errorMsg != null) break
                                                    delay(1000L)
                                                    playedSec = s
                                                    val transferredKb = bytesCounter.get() / 1024L
                                                    soakStatusText = "$tierName: ${s}s / 90s ($transferredKb KiB transferred)"
                                                    val state = withContext(Dispatchers.Main) { testPlayer.playbackState }
                                                    if (state == Player.STATE_ENDED) break
                                                }
                                            } finally {
                                                // NonCancellable so the test player is released even if the dialog is closed mid-test
                                                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                                                    testPlayer.stop()
                                                    testPlayer.release()
                                                }
                                            }

                                            val totalBytes = bytesCounter.get()
                                            val crossed1MiB = totalBytes >= (1024L * 1024L)
                                            val passed = (errorMsg == null) && crossed1MiB
                                            val detail = when {
                                                errorMsg != null -> "Error: $errorMsg"
                                                passed -> "PASSED: ${totalBytes / 1024L} KiB in ${playedSec}s (>= 1 MiB)"
                                                else -> "FAILED: Only ${totalBytes / 1024L} KiB in ${playedSec}s (< 1 MiB)"
                                            }

                                            results.add(
                                                SoakTestResult(
                                                    tierId = resolver.id,
                                                    name = tierName,
                                                    passed = passed,
                                                    bytesTransferred = totalBytes,
                                                    durationSeconds = playedSec,
                                                    detail = detail
                                                )
                                            )
                                            soakResults = results.toList()
                                        }

                                        isSoakTesting = false
                                        soakStatusText = ""
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isTesting && !isSoakTesting,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Run 90s Playback Soak Test", fontSize = 13.sp, color = Color.White)
                        }
                    }

                    if (soakResults.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(YtSurfaceVariant, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Tier", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = YtTextSecondary, modifier = Modifier.weight(1.3f))
                            Text("Status", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = YtTextSecondary, modifier = Modifier.weight(0.8f))
                            Text("Data", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = YtTextSecondary, modifier = Modifier.weight(0.9f))
                            Text("Detail", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = YtTextSecondary, modifier = Modifier.weight(1.5f))
                        }

                        soakResults.forEach { res ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.dp)
                                    .background(YtSurfaceVariant.copy(alpha = 0.5f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(res.name, fontSize = 11.sp, color = YtTextPrimary, modifier = Modifier.weight(1.3f), fontWeight = FontWeight.Medium)
                                Text(
                                    if (res.passed) "PASS" else if (res.inconclusive) "SKIP" else "FAIL",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (res.passed) Color(0xFF4CAF50) else if (res.inconclusive) Color(0xFFFFB300) else Color(0xFFFF5252),
                                    modifier = Modifier.weight(0.8f)
                                )
                                Text("${res.bytesTransferred / 1024L} KiB", fontSize = 11.sp, color = YtTextSecondary, modifier = Modifier.weight(0.9f))
                                Text(res.detail, fontSize = 11.sp, color = YtTextPrimary, modifier = Modifier.weight(1.5f))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = YtDivider)
                    Spacer(modifier = Modifier.height(16.dp))

                    // --- SECTION 4: Editable Client Versions ---
                    Text(
                        text = "Edit Client Versions",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = YtPrimaryRed
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "If YouTube breaks a client version, change it here in 10s without Colab.",
                        fontSize = 12.sp,
                        color = YtTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    VersionField(label = "visionOS Version", value = visionOsVersion) { visionOsVersion = it }
                    Spacer(modifier = Modifier.height(6.dp))
                    VersionField(label = "Android VR Version", value = androidVrVersion) { androidVrVersion = it }
                    Spacer(modifier = Modifier.height(6.dp))
                    VersionField(label = "YouTube Music Web (Search)", value = remixVersion) { remixVersion = it }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            if (!isFetchingRemoteConfig) {
                                isFetchingRemoteConfig = true
                                coroutineScope.launch {
                                    val res = RemoteConfigFetcher.fetchAndApply()
                                    isFetchingRemoteConfig = false
                                    if (res.success) {
                                        visionOsVersion = ExtractorPrefs.getVisionOsVersion()
                                        androidVrVersion = ExtractorPrefs.getAndroidVrVersion()
                                        remixVersion = ExtractorPrefs.getRemixVersion()
                                        Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Remote check: ${res.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isFetchingRemoteConfig,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = YtTextPrimary)
                    ) {
                        Text(
                            if (isFetchingRemoteConfig) "Checking Remote Config..." else "Fetch Latest Versions from GitHub",
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!RemoteConfigFetcher.isValidVersion(visionOsVersion)) {
                                    Toast.makeText(context, "Invalid visionOS version format", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (!RemoteConfigFetcher.isValidVersion(androidVrVersion)) {
                                    Toast.makeText(context, "Invalid Android VR version format", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (!RemoteConfigFetcher.isValidVersion(remixVersion)) {
                                    Toast.makeText(context, "Invalid remix version format", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                ExtractorPrefs.setVersions(
                                    visionOs = visionOsVersion,
                                    androidVr = androidVrVersion,
                                    remix = remixVersion
                                )
                                VisionOsPlayerClient.invalidateCache()
                                AndroidVrPlayerClient.invalidateCache()
                                PrefetchManager.clearCache()
                                Toast.makeText(context, "Client versions saved & caches invalidated!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = YtPrimaryRed)
                        ) {
                            Text("Save Versions", fontSize = 12.sp, color = Color.White)
                        }

                        OutlinedButton(
                            onClick = {
                                ExtractorPrefs.resetToDefaults()
                                visionOsVersion = ExtractorPrefs.DEFAULT_VISIONOS_VERSION
                                androidVrVersion = ExtractorPrefs.DEFAULT_ANDROID_VR_VERSION
                                remixVersion = ExtractorPrefs.DEFAULT_REMIX_VERSION
                                VisionOsPlayerClient.invalidateCache()
                                AndroidVrPlayerClient.invalidateCache()
                                PrefetchManager.clearCache()
                                Toast.makeText(context, "Reset to default versions (visitorData preserved)", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = YtTextPrimary)
                        ) {
                            Text("Defaults", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = YtDivider)
                    Spacer(modifier = Modifier.height(16.dp))

                    // --- SECTION 5: Instant Prefetch & Player Cache ---
                    Text(
                        text = "Prefetch & Cache Management",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = YtPrimaryRed
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Proactive background prefetch for upcoming tracks (~0ms gapless start).",
                        fontSize = 12.sp,
                        color = YtTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    var prefetchSize by remember { mutableStateOf(0) }
                    LaunchedEffect(Unit) {
                        prefetchSize = PrefetchManager.size()
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(YtSurfaceVariant, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Prefetched Streams", fontSize = 13.sp, color = YtTextPrimary, fontWeight = FontWeight.Medium)
                            Text("$prefetchSize stream(s) ready in memory", fontSize = 11.sp, color = YtTextSecondary)
                        }
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    PrefetchManager.clear()
                                    prefetchSize = 0
                                    VisionOsPlayerClient.invalidateCache()
                                    AndroidVrPlayerClient.invalidateCache()
                                    Toast.makeText(context, "Prefetch and player caches cleared", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = YtPrimaryRed)
                        ) {
                            Text("Clear", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // --- SECTION 7: Local Diagnostics (Zero Secrets) ---
                    Text(
                        text = "Local Diagnostics Trace",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = YtPrimaryRed
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "In-memory circular log (max 50 entries). Zero URLs, tokens, visitorData, or cookies.",
                        fontSize = 12.sp,
                        color = YtTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val entries = DiagnosticLogger.getRecentEntries()
                    val diagText = if (entries.isNotEmpty()) {
                        entries.joinToString("\n") { "[${it.formattedTime}] [${it.tierId}] [${it.status}] ${it.probeResult}" }
                    } else {
                        YouTubeExtractor.lastDiagnostics.ifEmpty { "No local diagnostics recorded yet." }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .background(Color.Black, RoundedCornerShape(8.dp))
                            .border(1.dp, YtDivider, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = diagText,
                            color = Color(0xFFEEEEEE),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val cleanText = com.ytmusic.clone.data.network.DiagnosticSanitizer.sanitize(diagText)
                            val clipData = android.content.ClipData.newPlainText("YTMusic Diagnostics", cleanText)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                clipData.description.extras = android.os.PersistableBundle().apply {
                                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                                }
                            }
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(clipData)
                            android.widget.Toast.makeText(context, "Sanitized diagnostics copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = YtTextPrimary)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Diagnostics to Clipboard", fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = YtSurfaceVariant)
                ) {
                    Text("Close", color = YtTextPrimary)
                }
            }
        }
    }
    if (showSignInWebView) {
        YouTubeSignInDialog(
            onSignedIn = { cookie ->
                ExtractorPrefs.setYoutubeCookie(cookie)
                isSignedIn = true
                showSignInWebView = false
                Toast.makeText(context, "YouTube session saved!", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showSignInWebView = false }
        )
    }
}

@Composable
private fun VersionField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column {
        Text(text = label, fontSize = 11.sp, color = YtTextSecondary)
        Spacer(modifier = Modifier.height(2.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = YtTextPrimary,
                unfocusedTextColor = YtTextPrimary,
                cursorColor = YtPrimaryRed,
                focusedBorderColor = YtPrimaryRed,
                unfocusedBorderColor = YtProgressTrack
            )
        )
    }

}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeSignInDialog(
    onSignedIn: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var captured by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val allowedHosts = remember {
        setOf("accounts.google.com", "myaccount.google.com", "youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com")
    }
    val ytHosts = remember {
        setOf("youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com")
    }

    fun isAllowedUrl(url: String?, hosts: Set<String>): Boolean {
        val u = url ?: return false
        val uri = try { Uri.parse(u) } catch (_: Throwable) { return false }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host in hosts || hosts.any { host.endsWith(".$it") }
    }

    fun wipeWebViewSession() {
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            WebStorage.getInstance().deleteAllData()
        } catch (_: Throwable) {}
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.stopLoading()
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = YtSurface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Sign in to YouTube", color = YtTextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            if (captured) return@TextButton
                            val cookie = try {
                                CookieManager.getInstance().getCookie("https://www.youtube.com") ?: ""
                            } catch (_: Throwable) { "" }
                            if (cookie.contains("SAPISID=") || cookie.contains("__Secure-3PAPISID=")) {
                                captured = true
                                wipeWebViewSession()
                                onSignedIn(cookie)
                            } else {
                                Toast.makeText(context, "Finish signing in first", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("Done", color = YtPrimaryRed, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = {
                            wipeWebViewSession()
                            onDismiss()
                        }) {
                            Text("Cancel", color = YtTextSecondary)
                        }
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        wipeWebViewSession()
                        WebView(ctx).apply {
                            webViewRef = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    return !isAllowedUrl(request?.url?.toString(), allowedHosts)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (captured || !isAllowedUrl(url, ytHosts)) return
                                    val cookie = try {
                                        CookieManager.getInstance().getCookie("https://www.youtube.com") ?: ""
                                    } catch (_: Throwable) { "" }
                                    if (cookie.contains("SAPISID=") || cookie.contains("__Secure-3PAPISID=")) {
                                        captured = true
                                        wipeWebViewSession()
                                        onSignedIn(cookie)
                                    }
                                }
                            }
                            loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https://music.youtube.com/")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
