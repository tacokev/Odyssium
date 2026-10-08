package com.skintclub.odyssey.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.extensions.togglePlayPause
import com.skintclub.odyssey.ui.component.EmptyPlaceholder
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.LocalMenuState
import com.skintclub.odyssey.ui.component.NavigationTitle
import com.skintclub.odyssey.ui.component.SongListItem
import com.skintclub.odyssey.ui.menu.SongMenu
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.viewmodels.ListeningStats
import com.skintclub.odyssey.viewmodels.StatsViewModel
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(
    navController: NavController,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val stats by viewModel.stats.collectAsState()

    LazyColumn(
        contentPadding =
            LocalPlayerAwareWindowInsets.current
                .only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ).asPaddingValues(),
        modifier = Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)),
    ) {
        val s = stats ?: return@LazyColumn
        if (s.totalPlays == 0) {
            item {
                EmptyPlaceholder(
                    icon = R.drawable.trending_up,
                    text = stringResource(R.string.stats_empty),
                )
            }
            return@LazyColumn
        }

        item(key = "total") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.stats_total_listening),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.stats_hours, "%.1f".format(s.totalTimeMs / 3_600_000.0)),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    s.firstListen?.let {
                        Text(
                            text = stringResource(R.string.stats_since, it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        item(key = "tiles") {
            StatTiles(s)
        }

        item(key = "topTitle") {
            NavigationTitle(title = stringResource(R.string.stats_top_episodes))
        }

        itemsIndexed(
            items = s.topEpisodes,
            key = { _, episode -> episode.song.id },
        ) { _, episode ->
            SongListItem(
                song = episode.song,
                isActive = episode.song.id == mediaMetadata?.id,
                isPlaying = isPlaying,
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = formatListenTime(episode.timeMs),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = pluralStringResource(R.plurals.n_time, episode.plays, episode.plays),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (episode.song.id == mediaMetadata?.id) {
                                    playerConnection.player.togglePlayPause()
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuState.show {
                                    SongMenu(
                                        originalSong = episode.song,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        ).animateItem(),
            )
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.stats)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

@Composable
private fun StatTiles(s: ListeningStats) {
    val tiles =
        listOf(
            stringResource(R.string.stats_this_week) to formatListenTime(s.thisWeekMs),
            stringResource(R.string.stats_this_month) to formatListenTime(s.thisMonthMs),
            stringResource(R.string.stats_episodes_played) to s.episodesPlayed.toString(),
            stringResource(R.string.stats_total_plays) to s.totalPlays.toString(),
            stringResource(R.string.stats_days_listened) to s.daysListened.toString(),
            stringResource(R.string.stats_daily_average) to formatListenTime(s.averagePerDayMs),
            stringResource(R.string.stats_current_streak) to pluralStringResource(R.plurals.n_day, s.currentStreak, s.currentStreak),
            stringResource(R.string.stats_longest_streak) to pluralStringResource(R.plurals.n_day, s.longestStreak, s.longestStreak),
            stringResource(R.string.stats_longest_session) to formatListenTime(s.longestSessionMs),
            stringResource(R.string.stats_favorite_day) to
                (s.favoriteWeekday?.getDisplayName(TextStyle.FULL, Locale.getDefault()) ?: "-"),
            stringResource(R.string.stats_favorite_time) to
                (s.favoriteHour?.let { LocalTime.of(it, 0).format(DateTimeFormatter.ofPattern("h a")) } ?: "-"),
        )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEach { (label, value) ->
                    StatTile(label = label, value = value, modifier = Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun formatListenTime(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        totalMinutes > 0 -> "${minutes}m"
        ms > 0 -> "<1m"
        else -> "0m"
    }
}

enum class OptionStats {
    WEEKS,
    MONTHS,
    YEARS,
    CONTINUOUS,
}
