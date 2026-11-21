package com.ytmusic.clone.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ytmusic.clone.data.local.LibraryStore
import com.ytmusic.clone.data.model.Song
import com.ytmusic.clone.data.network.YouTubeExtractor
import com.ytmusic.clone.player.MusicPlayerManager
import com.ytmusic.clone.ui.components.SongListItem
import com.ytmusic.clone.ui.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class HomeFeedSection(
    val title: String,
    val query: String,
    val songs: List<Song>,
    val categoryTag: String? = null
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onNavigateToSearch: () -> Unit,
    onMoodSearch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val history by LibraryStore.history.collectAsState()
    val liked by LibraryStore.liked.collectAsState()
    val listState = rememberLazyListState()
    val quickPicksState = rememberLazyListState()
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = quickPicksState)

    // YouTube Music Mood Categories
    val moods = remember {
        listOf(
            "Energize" to "energetic upbeat songs",
            "Workout" to "workout gym music",
            "Relax" to "relaxing chill music",
            "Focus" to "focus study music",
            "Commute" to "driving commute songs"
        )
    }

    var selectedMood by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Curated fallback tracks for instant offline/cold start play
    val initialFallbackSongs = remember {
        listOf(
            Song("jfKfPfyJRdk", "lofi hip hop radio - beats to relax/study to", "Lofi Girl", 0, "https://i.ytimg.com/vi/jfKfPfyJRdk/sddefault.jpg"),
            Song("fJ9rUzIMcZQ", "Bohemian Rhapsody", "Queen", 359, "https://i.ytimg.com/vi/fJ9rUzIMcZQ/sddefault.jpg"),
            Song("kJQP7kiw5Fk", "Despacito", "Luis Fonsi ft. Daddy Yankee", 281, "https://i.ytimg.com/vi/kJQP7kiw5Fk/sddefault.jpg"),
            Song("JGwWNGJdvx8", "Shape of You", "Ed Sheeran", 233, "https://i.ytimg.com/vi/JGwWNGJdvx8/sddefault.jpg"),
            Song("hT_nvWreIhg", "Counting Stars", "OneRepublic", 257, "https://i.ytimg.com/vi/hT_nvWreIhg/sddefault.jpg"),
            Song("3JZ_D3ELwOQ", "Radioactive", "Imagine Dragons", 187, "https://i.ytimg.com/vi/3JZ_D3ELwOQ/sddefault.jpg"),
            Song("kXYiU_JCYtU", "Numb", "Linkin Park", 186, "https://i.ytimg.com/vi/kXYiU_JCYtU/sddefault.jpg"),
            Song("09R8_2nJtjg", "Sugar", "Maroon 5", 235, "https://i.ytimg.com/vi/09R8_2nJtjg/sddefault.jpg"),
            Song("OPf0YbXqDm0", "Uptown Funk", "Mark Ronson ft. Bruno Mars", 270, "https://i.ytimg.com/vi/OPf0YbXqDm0/sddefault.jpg"),
            Song("CevxZvSJLk8", "Roar", "Katy Perry", 223, "https://i.ytimg.com/vi/CevxZvSJLk8/sddefault.jpg"),
            Song("YQHsXMglC9A", "Hello", "Adele", 295, "https://i.ytimg.com/vi/YQHsXMglC9A/sddefault.jpg"),
            Song("450p7goxZqg", "All of Me", "John Legend", 269, "https://i.ytimg.com/vi/450p7goxZqg/sddefault.jpg")
        )
    }

    var quickPicks by remember { mutableStateOf(initialFallbackSongs) }

    // Catalog of general sections
    val generalSectionCatalog = remember {
        listOf(
            Triple("Trending Now", "trending music songs", "GLOBAL CHARTS"),
            Triple("Chill & Relax", "chill acoustic relax music", "CHILL VIBES"),
            Triple("Energy Boost", "high energy workout gym music", "POWER UP"),
            Triple("Focus & Study", "focus study beats lofi", "FLOW STATE"),
            Triple("Feel-Good Pop", "feel good upbeat pop hits", "POP SPOTLIGHT"),
            Triple("Global Top Hits", "global top hits 2026", "TOP 50"),
            Triple("Hip-Hop & R&B", "top hip hop and r&b hits", "STREET BEATS"),
            Triple("Rock Classics", "rock alternative hits", "TIMELESS ROCK"),
            Triple("Late Night Drive", "night synthwave drive songs", "AFTER DARK"),
            Triple("Acoustic Coffeehouse", "acoustic guitar coffee songs", "COFFEEHOUSE")
        )
    }

    // Mood-specific curated section maps
    val moodSectionCatalog = remember(selectedMood) {
        when (selectedMood) {
            "Energize" -> listOf(
                Triple("High Energy Hits", "high energy pop dance hits", "PARTY PULSE"),
                Triple("Club & Electronic", "electronic dance music party", "CLUB NIGHT"),
                Triple("Upbeat Anthems", "upbeat feel good songs", "FEEL GOOD"),
                Triple("Bass & Beats", "bass heavy dance songs", "HEAVY BASS")
            )
            "Workout" -> listOf(
                Triple("Gym Motivation", "gym motivation workout songs", "PUSH HARDER"),
                Triple("Cardio & Running", "running bpm cardio music", "CARDIO RHYTHM"),
                Triple("Power Hip-Hop", "workout trap hip hop", "HEAVY WEIGHTS"),
                Triple("Hard Rock Training", "heavy rock workout songs", "ROCK ENERGY")
            )
            "Relax" -> listOf(
                Triple("Peaceful Acoustic", "peaceful acoustic guitar songs", "TRANQUILITY"),
                Triple("Lofi & Chillout", "lofi chill beats relax", "LOFI LOUNGE"),
                Triple("Ambient Evening", "ambient relaxing music sleep", "SLOW DOWN"),
                Triple("Soft Piano Melodies", "soft calming piano music", "MEDITATION")
            )
            "Focus" -> listOf(
                Triple("Deep Focus & Study", "deep focus study beats", "CONCENTRATION"),
                Triple("Coding & Flow State", "synthwave coding focus beats", "SYNTH FLOW"),
                Triple("Instrumental Focus", "instrumental study background music", "NO WORDS"),
                Triple("Brainwave Beats", "binaural focus ambient music", "DEEP WORK")
            )
            "Commute" -> listOf(
                Triple("Late Night Drive", "night drive synthwave songs", "HIGHWAY CRUISING"),
                Triple("Road Trip Classics", "road trip rock pop classics", "SING ALONG"),
                Triple("Morning Commute Hits", "morning radio commute songs", "ON THE ROAD"),
                Triple("City Lights Beats", "chill hip hop commute vibes", "CITY NIGHTS")
            )
            else -> generalSectionCatalog
        }
    }

    var loadedSections by remember { mutableStateOf<List<HomeFeedSection>>(emptyList()) }
    var isLoadingMore by remember { mutableStateOf(false) }

    // Live Feed Fetcher: triggers on launch, on manual refresh, or on mood filter change
    LaunchedEffect(selectedMood, refreshTrigger) {
        isRefreshing = true
        val quickPicksQuery = if (selectedMood != null) {
            "${selectedMood!!.lowercase()} top music hits"
        } else {
            "top hits 2026"
        }

        try {
            coroutineScope {
                val qpDeferred = async { YouTubeExtractor.searchSongs(quickPicksQuery) }
                val initialCatalog = moodSectionCatalog.take(2)
                val sectionsDeferred = initialCatalog.map { item ->
                    async {
                        val songs = YouTubeExtractor.searchSongs(item.second)
                        if (songs.isNotEmpty()) HomeFeedSection(item.first, item.second, songs, item.third) else null
                    }
                }

                val qpResult = qpDeferred.await()
                if (qpResult.isNotEmpty()) {
                    quickPicks = qpResult
                }

                val secResults = sectionsDeferred.mapNotNull { it.await() }
                loadedSections = secResults
            }
        } catch (_: Exception) {
        } finally {
            isRefreshing = false
        }
    }

    // Continuous scroll detection: triggers loading more sections when scrolling near bottom
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 2 && loadedSections.size < moodSectionCatalog.size && !isLoadingMore && !isRefreshing
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && loadedSections.size < moodSectionCatalog.size && !isLoadingMore && !isRefreshing) {
            isLoadingMore = true
            val nextIndex = loadedSections.size
            val nextCatalogItem = moodSectionCatalog[nextIndex]
            try {
                val songs = YouTubeExtractor.searchSongs(nextCatalogItem.second)
                if (songs.isNotEmpty()) {
                    loadedSections = loadedSections + HomeFeedSection(
                        nextCatalogItem.first,
                        nextCatalogItem.second,
                        songs,
                        nextCatalogItem.third
                    )
                }
            } catch (_: Exception) {
            } finally {
                isLoadingMore = false
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(YtBackground),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // App Bar
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(YtPrimaryRed),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Logo",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(11.dp)
                                    .offset(x = 0.8.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Music",
                        color = YtTextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { refreshTrigger++ }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Feed",
                            tint = if (isRefreshing) YtPrimaryRed else YtTextPrimary
                        )
                    }
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = YtTextPrimary
                        )
                    }
                }
            }
        }

        // Mood Chips Row (In-Place Dynamic Filtering)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // "All" filter chip
                val isAllSelected = selectedMood == null
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isAllSelected) YtPrimaryRed else YtChipBackground)
                        .clickable { selectedMood = null }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "All",
                        color = YtTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }

                moods.forEach { (label, _) ->
                    val isSelected = selectedMood == label
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) YtPrimaryRed else YtChipBackground)
                            .clickable {
                                selectedMood = if (isSelected) null else label
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = label,
                            color = YtTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Subtle loading indicator when refreshing feed
        if (isRefreshing) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = YtPrimaryRed,
                    trackColor = Color.Transparent
                )
            }
        }

        // Quick Picks Header (YT Music Style)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = if (selectedMood != null) "START RADIO • ${selectedMood!!.uppercase()}" else "START RADIO FROM A SONG",
                        fontSize = 11.sp,
                        color = YtTextSecondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = if (selectedMood != null) "Quick picks • $selectedMood" else "Quick picks",
                        fontSize = 24.sp,
                        color = YtTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        if (quickPicks.isNotEmpty()) {
                            MusicPlayerManager.playSong(context, quickPicks.first(), quickPicks)
                            MusicPlayerManager.setFullPlayerExpanded(true)
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = YtChipBackground)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = YtTextPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Play all", fontSize = 12.sp, color = YtTextPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // YouTube Music Quick Picks: Horizontal swipeable carousel with 4 stacked songs per column & page snapping
        item {
            val columns = remember(quickPicks) { quickPicks.chunked(4) }
            val currentColumnIndex by remember {
                derivedStateOf {
                    val firstVisible = quickPicksState.firstVisibleItemIndex
                    val offset = quickPicksState.firstVisibleItemScrollOffset
                    if (offset > 240) minOf(columns.size - 1, firstVisible + 1) else firstVisible
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                LazyRow(
                    state = quickPicksState,
                    flingBehavior = snapFlingBehavior,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(columns) { columnSongs ->
                        Column(
                            modifier = Modifier.fillParentMaxWidth(0.88f)
                        ) {
                            columnSongs.forEach { song ->
                                SongListItem(
                                    song = song,
                                    onClick = {
                                        MusicPlayerManager.playSong(context, song, quickPicks)
                                        MusicPlayerManager.setFullPlayerExpanded(true)
                                    },
                                    horizontalPadding = 0.dp,
                                    swipeToQueue = false // carousel owns horizontal gestures
                                )
                            }
                        }
                    }
                }

                // Official YouTube Music pagination dots indicating current carousel page
                if (columns.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(columns.size) { index ->
                            val isSelected = index == currentColumnIndex
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(width = if (isSelected) 16.dp else 6.dp, height = 5.dp)
                                    .clip(RoundedCornerShape(2.5.dp))
                                    .background(if (isSelected) YtPrimaryRed else YtTextSecondary.copy(alpha = 0.35f))
                            )
                        }
                    }
                }
            }
        }

        // Listen again (recently played)
        if (history.isNotEmpty() && selectedMood == null) {
            item {
                SectionHeader(
                    title = "Listen again",
                    categoryTag = "RECENT ACTIVITY",
                    onPlayAll = {
                        MusicPlayerManager.playSong(context, history.first(), history)
                        MusicPlayerManager.setFullPlayerExpanded(true)
                    }
                )
            }
            item {
                SongRow(history) { song ->
                    MusicPlayerManager.playSong(context, song, history)
                    MusicPlayerManager.setFullPlayerExpanded(true)
                }
            }
        }

        // Liked songs
        if (liked.isNotEmpty() && selectedMood == null) {
            item {
                SectionHeader(
                    title = "Your liked songs",
                    categoryTag = "YOUR FAVORITES",
                    onPlayAll = {
                        MusicPlayerManager.playSong(context, liked.first(), liked)
                        MusicPlayerManager.setFullPlayerExpanded(true)
                    }
                )
            }
            item {
                SongRow(liked) { song ->
                    MusicPlayerManager.playSong(context, song, liked)
                    MusicPlayerManager.setFullPlayerExpanded(true)
                }
            }
        }

        // Dynamically loaded infinite-scroll sections
        loadedSections.forEach { section ->
            item(key = section.title) {
                SectionHeader(
                    title = section.title,
                    categoryTag = section.categoryTag,
                    onPlayAll = {
                        if (section.songs.isNotEmpty()) {
                            MusicPlayerManager.playSong(context, section.songs.first(), section.songs)
                            MusicPlayerManager.setFullPlayerExpanded(true)
                        }
                    }
                )
            }
            item(key = "${section.title}_row") {
                SongRow(section.songs) { song ->
                    MusicPlayerManager.playSong(context, song, section.songs)
                    MusicPlayerManager.setFullPlayerExpanded(true)
                }
            }
        }

        // Loading spinner when fetching more sections
        if (isLoadingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = YtPrimaryRed,
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.5.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    categoryTag: String? = null,
    onPlayAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (categoryTag != null) {
                Text(
                    text = categoryTag.uppercase(),
                    fontSize = 11.sp,
                    color = YtTextSecondary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
            Text(
                text = title,
                fontSize = 22.sp,
                color = YtTextPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        if (onPlayAll != null) {
            Button(
                onClick = onPlayAll,
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = YtChipBackground)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = YtTextPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Play all", fontSize = 12.sp, color = YtTextPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SongRow(songs: List<Song>, onPlay: (Song) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(songs) { song ->
            Column(
                modifier = Modifier
                    .width(140.dp)
                    .clickable { onPlay(song) }
            ) {
                AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(140.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = song.title,
                    color = YtTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    color = YtTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
