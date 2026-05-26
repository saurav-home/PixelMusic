package com.unshoo.pixelmusic.presentation.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.presentation.components.MiniPlayerHeight
import com.unshoo.pixelmusic.presentation.components.subcomps.EnhancedSongListItem
import com.unshoo.pixelmusic.presentation.viewmodel.PlayerViewModel
import com.unshoo.pixelmusic.presentation.viewmodel.QuickPicksViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val QUICK_PICKS_CATEGORIES = listOf(
    "All",
    "Romance",
    "Love",
    "Pump",
    "Punjabi",
    "Bollywood",
    "Chill",
    "Party",
    "Sad",
    "Dance"
)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun QuickPicksAllScreen(
    playerViewModel: PlayerViewModel,
    navController: NavController,
    quickPicksViewModel: QuickPicksViewModel = hiltViewModel()
) {
    val allSongs by quickPicksViewModel.quickPicks.collectAsStateWithLifecycle()
    val currentSongId by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val isPlaying by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.isPlaying }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    var selectedCategory by remember { mutableStateOf("All") }

    val filteredSongs = remember(allSongs, selectedCategory) {
        if (selectedCategory == "All") allSongs
        else allSongs.filter { song ->
            val haystack = "${song.title} ${song.artist} ${song.album} ${song.genre.orEmpty()}"
                .lowercase()
            selectedCategory.lowercase().split(" ").any { keyword ->
                haystack.contains(keyword)
            }
        }.ifEmpty { allSongs }
    }

    val bgColors = listOf(
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        MaterialTheme.colorScheme.surface.copy(alpha = 0.60f),
        MaterialTheme.colorScheme.surface
    )
    val backgroundBrush = remember { Brush.verticalGradient(colors = bgColors, endY = 900f) }
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                bottom = MiniPlayerHeight + navBarPadding + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Header
            item(key = "qp_header") {
                QuickPicksAllHeader(songCount = filteredSongs.size)
            }

            // Action buttons
            if (filteredSongs.isNotEmpty()) {
                item(key = "qp_actions") {
                    QuickPicksActions(
                        onPlay = {
                            val first = filteredSongs.firstOrNull() ?: return@QuickPicksActions
                            playerViewModel.playSongs(filteredSongs, first, "Quick Picks")
                        },
                        onShuffle = {
                            playerViewModel.playSongsShuffled(filteredSongs, "Quick Picks", startAtZero = true)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // Category chips
            item(key = "qp_chips") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    items(QUICK_PICKS_CATEGORIES, key = { it }) { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = { Text(text = category, style = MaterialTheme.typography.labelLarge) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }

            // Songs
            items(filteredSongs, key = { it.id }) { song ->
                EnhancedSongListItem(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    song = song,
                    isCurrentSong = currentSongId == song.id,
                    isPlaying = currentSongId == song.id && isPlaying,
                    onClick = {
                        playerViewModel.showAndPlaySong(song, filteredSongs, "Quick Picks")
                    },
                    onMoreOptionsClick = { clickedSong ->
                        playerViewModel.selectSongForInfo(clickedSong)
                    }
                )
            }
        }

        // Back button
        FilledIconButton(
            onClick = { navController.popBackStack() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 10.dp, top = 8.dp)
                .clip(CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back"
            )
        }
    }
}

@Composable
private fun QuickPicksAllHeader(songCount: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Spacer(modifier = Modifier.height(36.dp))
            Text(
                text = "Quick Picks",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (songCount > 0) {
                Text(
                    text = "$songCount songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QuickPicksActions(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onPlay,
            modifier = Modifier.weight(1f).fillMaxSize(),
            shape = RoundedCornerShape(
                topStart = 52.dp, topEnd = 14.dp,
                bottomStart = 52.dp, bottomEnd = 14.dp
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Play")
        }

        FilledTonalButton(
            onClick = onShuffle,
            modifier = Modifier.weight(1f).fillMaxSize(),
            shape = RoundedCornerShape(
                topStart = 14.dp, topEnd = 52.dp,
                bottomStart = 14.dp, bottomEnd = 52.dp
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Shuffle")
        }
    }
}
