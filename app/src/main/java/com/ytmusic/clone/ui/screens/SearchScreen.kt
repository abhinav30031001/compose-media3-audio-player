package com.ytmusic.clone.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ytmusic.clone.data.model.Song
import com.ytmusic.clone.data.network.YouTubeExtractor
import com.ytmusic.clone.player.MusicPlayerManager
import com.ytmusic.clone.ui.components.SongListItem
import com.ytmusic.clone.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object SearchHistoryStore {
    private const val PREFS = "search_history_prefs"
    private const val KEY = "history_list"

    fun get(context: Context): List<String> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|||").filter { it.isNotBlank() }
    }

    fun add(context: Context, q: String) {
        val trimmed = q.trim()
        if (trimmed.isBlank()) return
        val list = get(context).filter { !it.equals(trimmed, ignoreCase = true) }.toMutableList()
        list.add(0, trimmed)
        val capped = list.take(15)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        p.putString(KEY, capped.joinToString("|||"))
        p.apply()
    }

    fun remove(context: Context, q: String) {
        val list = get(context).filter { !it.equals(q.trim(), ignoreCase = true) }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        p.putString(KEY, list.joinToString("|||"))
        p.apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

@Composable
fun SearchScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String? = null,
    onInitialQueryConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Song>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var recentSearches by remember { mutableStateOf(SearchHistoryStore.get(context)) }
    var isLoading by remember { mutableStateOf(false) }
    var isSubmitted by remember { mutableStateOf(false) }

    fun performSearch(targetQuery: String = query) {
        val q = targetQuery.trim()
        if (q.isEmpty()) return
        query = q
        keyboardController?.hide()
        isLoading = true
        isSubmitted = true
        suggestions = emptyList()

        SearchHistoryStore.add(context, q)
        recentSearches = SearchHistoryStore.get(context)

        coroutineScope.launch {
            val videoId = extractVideoId(q)
            if (videoId != null) {
                val directSong = Song(
                    id = videoId,
                    title = "Playing from link",
                    artist = "YouTube Music",
                    thumbnailUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
                )
                searchResults = listOf(directSong)
                MusicPlayerManager.playSong(context, directSong, listOf(directSong))
                MusicPlayerManager.setFullPlayerExpanded(true)
            } else {
                val results = YouTubeExtractor.searchSongs(q)
                searchResults = results
            }
            isLoading = false
        }
    }

    // Debounced Search Suggestions as user types (250ms debounce)
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            suggestions = emptyList()
            if (!isSubmitted) searchResults = emptyList()
            return@LaunchedEffect
        }
        if (isSubmitted) return@LaunchedEffect

        delay(250) // 250ms debounce
        try {
            suggestions = YouTubeExtractor.getSearchSuggestions(q)
        } catch (_: Exception) {
            suggestions = emptyList()
        }
    }

    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            query = initialQuery
            performSearch(initialQuery)
            onInitialQueryConsumed()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(YtBackground)
    ) {
        // Search Input Bar (YouTube Music Pill Style)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = YtTextPrimary
                )
            }

            TextField(
                value = query,
                onValueChange = {
                    query = it
                    isSubmitted = false
                },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .clip(RoundedCornerShape(25.dp)),
                placeholder = {
                    Text(
                        text = "Search songs, albums, artists",
                        color = YtTextSecondary,
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = YtTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            suggestions = emptyList()
                            isSubmitted = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = YtTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { performSearch() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = YtSurfaceVariant,
                    unfocusedContainerColor = YtSurfaceVariant,
                    focusedTextColor = YtTextPrimary,
                    unfocusedTextColor = YtTextPrimary,
                    cursorColor = YtPrimaryRed,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )

            Spacer(modifier = Modifier.width(6.dp))
        }

        // CONTENT SECTION
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = YtPrimaryRed)
                }
            }

            // 1. Live Search Suggestions (when typing and not submitted)
            !isSubmitted && query.trim().isNotEmpty() && suggestions.isNotEmpty() -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)
                ) {
                    items(suggestions) { suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    performSearch(suggestion)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = YtTextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = suggestion,
                                    color = YtTextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = {
                                    query = suggestion
                                    isSubmitted = false
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NorthWest,
                                    contentDescription = "Insert into search",
                                    tint = YtTextSecondary.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. Recent Searches (when search bar is empty)
            !isSubmitted && query.trim().isEmpty() -> {
                if (recentSearches.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = YtTextSecondary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Search for songs, artists, or paste links",
                                color = YtTextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp, bottom = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "RECENT SEARCHES",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = YtTextSecondary,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = "Clear all",
                                    fontSize = 12.sp,
                                    color = YtPrimaryRed,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable {
                                        SearchHistoryStore.clear(context)
                                        recentSearches = emptyList()
                                    }
                                )
                            }
                        }

                        items(recentSearches) { recent ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        performSearch(recent)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = YtTextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        text = recent,
                                        color = YtTextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        SearchHistoryStore.remove(context, recent)
                                        recentSearches = SearchHistoryStore.get(context)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = YtTextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Search Results (when submitted)
            isSubmitted -> {
                if (searchResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No results found for \"$query\". Check spelling or try another search.",
                            color = YtTextSecondary,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(searchResults) { song ->
                            SongListItem(
                                song = song,
                                onClick = {
                                    MusicPlayerManager.playSongFromSearch(context, song)
                                    MusicPlayerManager.setFullPlayerExpanded(true)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun extractVideoId(text: String): String? {
    val clean = text.trim()
    if (clean.length == 11 && clean.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
        return clean
    }
    val patterns = listOf(
        """v=([a-zA-Z0-9_-]{11})""",
        """youtu\.be/([a-zA-Z0-9_-]{11})""",
        """music\.youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})""",
        """youtube\.com/shorts/([a-zA-Z0-9_-]{11})""",
        """youtube\.com/embed/([a-zA-Z0-9_-]{11})"""
    )
    for (pat in patterns) {
        val regex = Regex(pat)
        val match = regex.find(clean)
        if (match != null && match.groupValues.size > 1) {
            return match.groupValues[1]
        }
    }
    return null
}
