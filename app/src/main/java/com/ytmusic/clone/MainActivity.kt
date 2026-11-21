package com.ytmusic.clone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ytmusic.clone.ui.components.PanelGesture
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import com.ytmusic.clone.data.local.InstallAccessGate
import com.ytmusic.clone.ui.components.InstallLockScreen
import com.ytmusic.clone.player.MusicPlayerManager
import com.ytmusic.clone.player.MusicPlayerService
import com.ytmusic.clone.ui.components.FullPlayerSheet
import com.ytmusic.clone.ui.components.MiniPlayer
import com.ytmusic.clone.ui.screens.HomeScreen
import com.ytmusic.clone.ui.screens.LibraryScreen
import com.ytmusic.clone.ui.screens.SearchScreen
import com.ytmusic.clone.ui.theme.*

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force explicit title and TaskDescription so Android Recent Apps switcher displays "YT Music" instead of package name
        title = getString(R.string.app_name)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setTaskDescription(
                    android.app.ActivityManager.TaskDescription.Builder()
                        .setLabel(getString(R.string.app_name))
                        .build()
                )
            } else {
                @Suppress("DEPRECATION")
                setTaskDescription(
                    android.app.ActivityManager.TaskDescription(
                        getString(R.string.app_name),
                        null,
                        0xFF030303.toInt()
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore if OEM modifies TaskDescription
        }

        val initiallyUnlocked = InstallAccessGate.isUnlocked(this)
        if (initiallyUnlocked) {
            initializeUnlockedSession(intent)
        }

        setContent {
            YTMusicTheme {
                var isUnlocked by remember { mutableStateOf(initiallyUnlocked) }
                if (isUnlocked) {
                    MainApp()
                } else {
                    InstallLockScreen(
                        onUnlocked = {
                            initializeUnlockedSession(intent)
                            isUnlocked = true
                        }
                    )
                }
            }
        }
    }

    private fun initializeUnlockedSession(launchIntent: Intent?) {
        // Initialize audio engine immediately
        MusicPlayerManager.getOrCreatePlayer(this)

        // Show the last song (paused) if nothing is playing yet
        MusicPlayerManager.restoreLastSession(this)

        // Start MusicPlayerService while in foreground so lockscreen & media session are always ready
        try {
            val serviceIntent = Intent(this, MusicPlayerService::class.java)
            startService(serviceIntent)
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to start MusicPlayerService on create", e)
        }

        // Request notification permission for playback controls on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (launchIntent?.getBooleanExtra("open_full_player", false) == true) {
            MusicPlayerManager.setFullPlayerExpanded(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_full_player", false)) {
            MusicPlayerManager.setFullPlayerExpanded(true)
        }
    }

    // App goes to the background (home button, recent apps, screen off): save our place.
    override fun onStart() {
        super.onStart()
        MusicPlayerManager.setVideoSurfaceActive(true)
    }

    override fun onStop() {
        super.onStop()
        MusicPlayerManager.setVideoSurfaceActive(false)
        MusicPlayerManager.saveState()
    }
}

enum class NavigationItem(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    SEARCH("Search", Icons.Default.Search),
    LIBRARY("Library", Icons.Default.LibraryMusic)
}

@Composable
fun MainApp() {
    var currentTab by remember { mutableStateOf(NavigationItem.HOME) }
    var pendingSearch by remember { mutableStateOf<String?>(null) }
    val isFullPlayerExpanded by MusicPlayerManager.isFullPlayerExpanded.collectAsState()
    val currentSong by MusicPlayerManager.currentSong.collectAsState()

    val hasSong = currentSong != null
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var expansion by remember { mutableFloatStateOf(if (isFullPlayerExpanded && hasSong) 1f else 0f) }
    var settleTarget by remember { mutableFloatStateOf(expansion) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    var navBarHeightPx by remember { mutableFloatStateOf(with(density) { 52.dp.toPx() }) }
    var miniPlayerHeightPx by remember { mutableFloatStateOf(with(density) { 56.dp.toPx() }) }
    var screenHeightPx by remember { mutableFloatStateOf(0f) }

    // Docked position is exactly above the bottom navigation bar
    fun travelPx() = (screenHeightPx - navBarHeightPx - miniPlayerHeightPx).coerceAtLeast(1f)

    fun settle(open: Boolean, velocityPxPerSec: Float = 0f) {
        settleJob?.cancel()
        val target = if (open) 1f else 0f
        settleTarget = target
        settleJob = scope.launch {
            MusicPlayerManager.setFullPlayerExpanded(open)
            animate(
                initialValue = expansion,
                targetValue = target,
                initialVelocity = -velocityPxPerSec / travelPx(),
                animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f)
            ) { v, _ -> expansion = v.coerceIn(0f, 1f) }
        }
    }

    LaunchedEffect(isFullPlayerExpanded, hasSong) {
        val open = isFullPlayerExpanded && hasSong
        val target = if (open) 1f else 0f
        if (settleTarget != target) settle(open)
    }

    val onPanelDragStart: () -> Unit = { settleJob?.cancel() }
    val onPanelDrag: (Float) -> Unit = { dy ->
        expansion = (expansion - dy / travelPx()).coerceIn(0f, 1f)
    }
    val onPanelDragEnd: (Float) -> Unit = { vy ->
        settle(PanelGesture.settleExpanded(expansion, vy, flingVelocity = with(density) { 600.dp.toPx() }), vy)
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    var lastBackPressTime by remember { mutableLongStateOf(0L) }

    // Official hierarchical back navigation:
    // 1. If player is open -> collapse player to MiniPlayer
    // 2. If on Search or Library tab -> return to Home tab
    // 3. If on Home tab -> show "Press back again to exit"
    BackHandler(enabled = true) {
        if (isFullPlayerExpanded || expansion > 0f) {
            settle(false)
        } else if (currentTab != NavigationItem.HOME) {
            currentTab = NavigationItem.HOME
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackPressTime < 2000L) {
                (context as? android.app.Activity)?.finish()
            } else {
                lastBackPressTime = now
                android.widget.Toast.makeText(context, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { screenHeightPx = it.height.toFloat() }
            .background(YtBackground)
    ) {
        // 1. Primary Content Area (Home, Search, Library)
        val bottomBarPadding = if (hasSong) {
            with(density) { (navBarHeightPx + miniPlayerHeightPx).toDp() }
        } else {
            with(density) { navBarHeightPx.toDp() }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomBarPadding)
        ) {
            when (currentTab) {
                NavigationItem.HOME -> HomeScreen(
                    onNavigateToSearch = { currentTab = NavigationItem.SEARCH },
                    onMoodSearch = { q ->
                        pendingSearch = q
                        currentTab = NavigationItem.SEARCH
                    }
                )
                NavigationItem.SEARCH -> SearchScreen(
                    onNavigateBack = { currentTab = NavigationItem.HOME },
                    initialQuery = pendingSearch,
                    onInitialQueryConsumed = { pendingSearch = null }
                )
                NavigationItem.LIBRARY -> LibraryScreen()
            }
        }

        // 2. The Unified Player Sheet
        if (hasSong) {
            val travelDistance = travelPx()
            val cornerRadius = ((1f - expansion) * 16).dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = (1f - expansion) * travelDistance
                    }
                    .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
                    .background(YtBackground) // 100% OPAQUE - NO BLACK DIM SCRIM, NO TRANSLUCENT GHOSTING
            ) {
                // FULL PLAYER CONTENT (fades in as expansion increases from 0.12 to 1)
                if (expansion > 0.05f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = ((expansion - 0.12f) / 0.88f).coerceIn(0f, 1f)
                            }
                    ) {
                        FullPlayerSheet(
                            onDismiss = { settle(false) },
                            onPanelDragStart = onPanelDragStart,
                            onPanelDrag = onPanelDrag,
                            onPanelDragEnd = onPanelDragEnd
                        )
                    }
                }

                // MINI PLAYER CONTENT (fades out as expansion increases from 0 to 0.3)
                if (expansion < 0.35f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { miniPlayerHeightPx = it.height.toFloat() }
                            .graphicsLayer {
                                alpha = (1f - expansion / 0.25f).coerceIn(0f, 1f)
                            }
                    ) {
                        MiniPlayer(
                            onClick = { settle(true) },
                            onPanelDragStart = onPanelDragStart,
                            onPanelDrag = onPanelDrag,
                            onPanelDragEnd = onPanelDragEnd
                        )
                    }
                }
            }
        }

        // 3. Compact YouTube Music Bottom Navigation Bar (52dp height, matching official app)
        Surface(
            color = YtSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { navBarHeightPx = it.height.toFloat() }
                .graphicsLayer {
                    translationY = (expansion * 1.25f).coerceIn(0f, 1f) * navBarHeightPx
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                // 0.5dp subtle top divider line, matching official app
                Divider(
                    color = Color(0xFF282828),
                    thickness = 0.5.dp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NavigationItem.values().forEach { item ->
                        val selected = currentTab == item
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { currentTab = item }
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = if (selected) Color.White else YtTextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = item.label,
                                color = if (selected) Color.White else YtTextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }


    }
}
