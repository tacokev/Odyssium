package com.skintclub.odyssey.ui.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.club.isSecretEpisodeId
import com.skintclub.odyssey.transcripts.Transcript
import com.skintclub.odyssey.transcripts.Transcripts
import com.skintclub.odyssey.ui.utils.fadingEdge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** How long after the user scrolls the transcript before it goes back to following playback. */
private const val FOLLOW_RESUME_DELAY_MS = 3000L

/** A transcript line as shown: the speaker resolved to a name and (for characters featured on
 * the episode's club page) a portrait. */
private data class TranscriptLine(
    val startMs: Long,
    val text: String,
    val speakerKey: String?,
    val speakerName: String?,
    val portraitUrl: String?,
)

/** Character portraits from the episode's "Featured Characters" (its club content details),
 * keyed by character id (first 15 characters, so 15- and 18-character Salesforce ids match)
 * and by lowercased name. Empty when they can't be fetched (logged out, offline, secret). */
private data class Portraits(
    val byId: Map<String, String> = emptyMap(),
    val byName: Map<String, String> = emptyMap(),
)

private suspend fun loadPortraits(context: Context, episodeId: String): Portraits {
    if (isSecretEpisodeId(episodeId)) return Portraits()
    val characters = try {
        ClubClient(context).getContentDetails(episodeId)?.characters.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
        return Portraits()
    }
    val withImage = characters.mapNotNull { c ->
        (c.thumbnailSmall ?: c.thumbnailMedium)?.takeIf { it.isNotBlank() }?.let { c to it }
    }
    return Portraits(
        byId = withImage.mapNotNull { (c, url) -> c.id?.takeIf { it.isNotBlank() }?.let { it.take(15) to url } }.toMap(),
        byName = withImage.mapNotNull { (c, url) -> c.name?.takeIf { it.isNotBlank() }?.let { it.trim().lowercase() to url } }.toMap(),
    )
}

private fun linesOf(transcript: Transcript, portraits: Portraits): List<TranscriptLine> {
    val speakers = transcript.speakers.orEmpty()
    return transcript.utterances.orEmpty()
        .filter { !it.text.isNullOrBlank() }
        .sortedBy { it.start }
        .map { utterance ->
            val speaker = utterance.speaker?.let { speakers[it] }
            val name = speaker?.name?.takeIf { it.isNotBlank() }
            TranscriptLine(
                startMs = (utterance.start * 1000).toLong(),
                text = utterance.text!!.trim(),
                speakerKey = utterance.speaker,
                speakerName = name,
                portraitUrl = name?.let {
                    speaker.clubCharacterId?.take(15)?.let(portraits.byId::get)
                        ?: portraits.byName[it.trim().lowercase()]
                },
            )
        }
}

/** The index of the last line that has started by [positionMs], or -1 before the first. */
private fun currentLineIndex(lines: List<TranscriptLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.lastIndex
    var found = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (lines[mid].startMs <= positionMs) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

/**
 * Episode [episodeId]'s imported transcript, shown in the player in place of the cover art:
 * every line with who's speaking (and their portrait, for characters featured on the episode's
 * club page). Follows playback, highlighting the current line; tapping a line seeks to it.
 */
@Composable
fun TranscriptView(
    episodeId: String,
    sliderPositionProvider: () -> Long?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val density = LocalDensity.current

    val transcript by produceState<Transcript?>(null, episodeId) {
        value = Transcripts.load(context, episodeId)
    }
    val portraits by produceState(Portraits(), episodeId) {
        value = loadPortraits(context, episodeId)
    }
    val lines = remember(transcript, portraits) { transcript?.let { linesOf(it, portraits) }.orEmpty() }

    var currentIndex by remember(lines) { mutableIntStateOf(-1) }
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    val listState = rememberLazyListState()

    // Programmatic scrolls don't go through nested scrolling, so anything seen here is the user.
    val userScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                lastUserScroll = System.currentTimeMillis()
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(lines) {
        if (lines.isEmpty()) return@LaunchedEffect
        while (isActive) {
            val position = sliderPositionProvider() ?: playerConnection.player.currentPosition
            currentIndex = currentLineIndex(lines, position)
            delay(100)
        }
    }

    LaunchedEffect(currentIndex, lastUserScroll) {
        if (currentIndex < 0) return@LaunchedEffect
        val sinceUserScroll = System.currentTimeMillis() - lastUserScroll
        if (sinceUserScroll < FOLLOW_RESUME_DELAY_MS) delay(FOLLOW_RESUME_DELAY_MS - sinceUserScroll)
        val offset = -with(density) { 96.dp.roundToPx() }
        listState.animateScrollToItem(currentIndex, offset)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier,
    ) {
        when {
            transcript == null -> Text(
                text = "Loading transcript…",
                style = MaterialTheme.typography.bodyMedium,
                color = color.copy(alpha = 0.6f),
            )

            lines.isEmpty() -> Text(
                text = "This transcript is empty",
                style = MaterialTheme.typography.bodyMedium,
                color = color.copy(alpha = 0.6f),
            )

            else -> LazyColumn(
                state = listState,
                // Room past the first and last lines so they can scroll clear of the fades.
                contentPadding = PaddingValues(vertical = FadeHeight),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdge(vertical = FadeHeight)
                    .nestedScroll(userScrollConnection),
            ) {
                itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                    val previous = lines.getOrNull(index - 1)
                    TranscriptLineRow(
                        line = line,
                        showSpeaker = previous == null || previous.speakerKey != line.speakerKey,
                        isCurrent = index == currentIndex,
                        color = color,
                        onClick = {
                            lastUserScroll = 0L
                            playerConnection.player.seekTo(line.startMs)
                        },
                    )
                }
            }
        }
    }
}

private val AvatarSize = 32.dp
private val FadeHeight = 48.dp

@Composable
private fun TranscriptLineRow(
    line: TranscriptLine,
    showSpeaker: Boolean,
    isCurrent: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .padding(top = if (showSpeaker) 6.dp else 0.dp),
    ) {
        Box(Modifier.size(AvatarSize), contentAlignment = Alignment.Center) {
            if (showSpeaker && line.speakerName != null) {
                if (line.portraitUrl != null) {
                    AsyncImage(
                        model = line.portraitUrl,
                        contentDescription = line.speakerName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(AvatarSize)
                            .clip(CircleShape),
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(AvatarSize)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.15f)),
                    ) {
                        Text(
                            text = line.speakerName.take(1).uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = color,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(4.dp))

        Column(Modifier.weight(1f)) {
            if (showSpeaker) {
                Text(
                    text = line.speakerName ?: "Unknown speaker",
                    style = MaterialTheme.typography.labelMedium,
                    color = color.copy(alpha = if (isCurrent) 0.9f else 0.55f),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            // Only the line being spoken gets a background; every line keeps the same padding
            // so the highlight doesn't shift the text as it moves along.
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = color.copy(alpha = if (isCurrent) 1f else 0.55f),
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isCurrent) color.copy(alpha = 0.14f) else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
