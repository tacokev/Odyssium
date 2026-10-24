package com.skintclub.odyssey.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalDownloadUtil
import com.skintclub.odyssey.LocalFreeRadio
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.FreeRadio
import com.skintclub.odyssey.club.RadioEpisode
import com.skintclub.odyssey.club.toEpisodeMap
import com.skintclub.odyssey.constants.ClubMembershipCardDismissedKey
import com.skintclub.odyssey.constants.ShowEpisodeNumberKey
import com.skintclub.odyssey.playback.queues.OdysseyQueue
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaMetadata
import com.skintclub.odyssey.ui.component.MediaMetadataListItem
import com.skintclub.odyssey.utils.joinByBullet
import com.skintclub.odyssey.utils.rememberPreference
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/**
 * Home while logged out: the episodes aired on the radio in the last few days, which anyone
 * can listen to (see [FreeRadio]), each of which can be downloaded for a week, and a way to log
 * in for everything else.
 */
@Composable
fun FreeRadioHome(
    navController: NavController,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val freeRadio = LocalFreeRadio.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val radioEpisodes by freeRadio.episodes.collectAsState()
    val refreshError by freeRadio.refreshError.collectAsState()
    val isRefreshing by freeRadio.isRefreshing.collectAsState()
    val downloads by LocalDownloadUtil.current.downloads.collectAsState()
    val downloadedAt by freeRadio.downloadedAt.collectAsState(initial = emptyMap())
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val showEpisodeNumber by rememberPreference(ShowEpisodeNumberKey, defaultValue = false)
    var membershipCardDismissed by rememberPreference(ClubMembershipCardDismissedKey, defaultValue = false)
    val episodes = remember(radioEpisodes) { radioEpisodes.map { it.toEpisodeMap() } }

    LaunchedEffect(Unit) {
        freeRadio.refresh()
        freeRadio.deleteExpiredDownloads()
    }

    LazyColumn(
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Free this week", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "The latest episodes aired on the radio are free to listen to. Downloads last a week.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (radioEpisodes.isEmpty()) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    when {
                        isRefreshing -> CircularProgressIndicator()
                        refreshError != null -> {
                            Text("Couldn't load this week's episodes. Check your connection.")
                            TextButton(onClick = freeRadio::refresh) { Text("Try again") }
                        }
                        else -> Text("No free episodes right now.")
                    }
                }
            }
        }

        itemsIndexed(
            items = radioEpisodes,
            key = { _, episode -> episode.id },
        ) { index, episode ->
            val metadata = remember(episode, showEpisodeNumber) {
                odysseyEpisodeToMediaMetadata(context, episodes[index], showEpisodeNumber)
            }
            val download = downloads[episode.id]
            val daysLeft = downloadedAt[episode.id]
                ?.takeIf { download?.state == Download.STATE_COMPLETED }
                ?.let { at ->
                    val left = at + FreeRadio.DOWNLOAD_LIFETIME_MS - System.currentTimeMillis()
                    ceil(left / 86_400_000.0).toInt().coerceAtLeast(1)
                }
            MediaMetadataListItem(
                mediaMetadata = metadata,
                isActive = mediaMetadata?.id == episode.id,
                isPlaying = isPlaying,
                subtitle = joinByBullet(
                    airedOn(episode),
                    episode.mediaLength?.let { "${it / 60_000} min" },
                    daysLeft?.let { if (it == 1) "1 day left" else "$it days left" },
                ),
                trailingContent = {
                    FreeDownloadButton(
                        download = download,
                        onDownload = { freeRadio.download(episode) },
                        onRemove = { freeRadio.removeDownload(episode.id) },
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        playerConnection.playQueue(OdysseyQueue(context = context, episodes = episodes, startIndex = index))
                    },
            )
        }

        if (!membershipCardDismissed) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Box {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Have a Club membership?",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(end = 32.dp),
                            )
                            Text(
                                text = "Log in for every episode, album, video and more.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                            )
                            Button(onClick = { navController.navigate("odyssey_login") }) { Text("Log in") }
                        }
                        // Closed for good; logging in is still in Settings > Account.
                        IconButton(
                            onClick = { membershipCardDismissed = true },
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Icon(painterResource(R.drawable.close), contentDescription = "Dismiss")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FreeDownloadButton(
    download: Download?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    when (download?.state) {
        Download.STATE_COMPLETED ->
            IconButton(onClick = onRemove) {
                Icon(painterResource(R.drawable.offline), contentDescription = "Remove download")
            }
        Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING ->
            IconButton(onClick = onRemove) {
                val percent = download.percentDownloaded
                if (percent > 0f) {
                    CircularProgressIndicator(progress = { percent / 100f }, modifier = Modifier.size(24.dp))
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        else ->
            IconButton(onClick = onDownload) {
                Icon(painterResource(R.drawable.download), contentDescription = "Download for a week")
            }
    }
}

private val airedFormat = DateTimeFormatter.ofPattern("EEE, MMM d")

/** "Aired Sat, Oct 24", from the episode's last air date. */
private fun airedOn(episode: RadioEpisode): String? =
    episode.recentAirDate
        ?.take(10)
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.let { "Aired ${it.format(airedFormat)}" }
