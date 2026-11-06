package com.skintclub.odyssey.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.constants.ListThumbnailSize
import com.skintclub.odyssey.constants.ThumbnailCornerRadius
import com.skintclub.odyssey.playback.queues.OdysseyQueue
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaMetadata
import com.skintclub.odyssey.transcripts.TranscriptMatch
import com.skintclub.odyssey.transcripts.TranscriptSearch
import com.skintclub.odyssey.transcripts.TranscriptSearchState
import com.skintclub.odyssey.transcripts.Transcripts
import com.skintclub.odyssey.utils.joinByBullet
import com.skintclub.odyssey.utils.makeTimeString
import com.skintclub.odyssey.viewmodels.OdysseySearchIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Search results when the search bar is searching transcripts: every line of an episode's
 * transcript that has the query, tapping one plays its episode from that line. */
@Composable
fun TranscriptSearchResultsList(
    query: String,
    searchIndex: OdysseySearchIndex,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val entries by remember { Transcripts.episodeIds(context) }.collectAsState()
    val stateFlow = remember(entries) { TranscriptSearch.state(context) }
    val state by stateFlow.collectAsState()

    val episodeById = remember(searchIndex) { searchIndex.episodes.associateBy { it.episode["id"] as? String } }
    val episodeOrder = remember(searchIndex) { searchIndex.episodes.mapNotNull { it.episode["id"] as? String } }

    var matches by remember { mutableStateOf<List<TranscriptMatch>?>(emptyList()) }
    val ready = state as? TranscriptSearchState.Ready
    LaunchedEffect(ready, episodeOrder, query) {
        if (ready == null) return@LaunchedEffect
        if (query.trim().length < 2) {
            matches = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        matches = null
        matches = withContext(Dispatchers.Default) {
            TranscriptSearch.search(ready.episodes, episodeOrder, query)
        }
    }

    when (val s = state) {
        null -> Unit
        is TranscriptSearchState.Indexing -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
        ) {
            Text("Getting transcripts ready to search…", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is TranscriptSearchState.Failed -> Message(s.message)
        is TranscriptSearchState.Ready -> {
            val list = matches
            when {
                s.episodes.isEmpty() -> Message("No transcripts imported. Add them in Settings > Content.")
                query.trim().length < 2 -> Message("Search what's said in ${s.episodes.size} episodes")
                list == null -> Unit
                list.isEmpty() -> Message("Nobody says that")
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(list, key = { i, m -> "${m.episodeId}_${m.startMs}_$i" }) { _, match ->
                        val result = episodeById[match.episodeId] ?: return@itemsIndexed
                        TranscriptMatchRow(
                            match = match,
                            title = (result.episode["name"] as? String).orEmpty(),
                            thumbnailUrl = remember(result) {
                                odysseyEpisodeToMediaMetadata(context, result.episode).thumbnailUrl
                            },
                            onClick = {
                                playerConnection?.playQueue(
                                    OdysseyQueue(
                                        context = context,
                                        episodes = result.queue,
                                        startIndex = result.queueIndex,
                                        startPositionMs = match.startMs,
                                    ),
                                )
                                onDismiss()
                            },
                        )
                    }
                    if (list.size >= 300) {
                        item(key = "more") { Message("Showing the first 300 lines. Search for more words to narrow it down.") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun TranscriptMatchRow(
    match: TranscriptMatch,
    title: String,
    thumbnailUrl: String?,
    onClick: () -> Unit,
) {
    val highlight = MaterialTheme.colorScheme.primary
    val snippet = remember(match, highlight) {
        buildAnnotatedString {
            append(match.snippet.substring(0, match.matchStart))
            withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold)) {
                append(match.snippet.substring(match.matchStart, match.matchEnd))
            }
            append(match.snippet.substring(match.matchEnd))
        }
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        AsyncImage(
            model = thumbnailUrl,
            contentDescription = null,
            modifier = Modifier
                .size(ListThumbnailSize)
                .clip(RoundedCornerShape(ThumbnailCornerRadius)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = joinByBullet(makeTimeString(match.startMs), match.speaker),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
