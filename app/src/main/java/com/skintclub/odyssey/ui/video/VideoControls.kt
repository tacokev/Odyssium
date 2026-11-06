package com.skintclub.odyssey.ui.video

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.skintclub.odyssey.R
import com.skintclub.odyssey.utils.makeTimeString
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val CONTROLS_HIDE_DELAY_MS = 3000L
private const val SEEK_STEP_MS = 10_000L
private val PlaybackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** What the controls show of [player], kept up to date by its listener (and, for the position,
 * a poll - the player doesn't report that as it moves). */
private class PlayerUiState {
    var isPlaying by mutableStateOf(false)
    var playWhenReady by mutableStateOf(false)
    var playbackState by mutableIntStateOf(Player.STATE_IDLE)
    var positionMs by mutableLongStateOf(0L)
    var bufferedMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var hasNext by mutableStateOf(false)
    var hasCaptions by mutableStateOf(false)
    var captionsOn by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)

    fun update(player: Player) {
        isPlaying = player.isPlaying
        playWhenReady = player.playWhenReady
        playbackState = player.playbackState
        positionMs = player.currentPosition.coerceAtLeast(0)
        bufferedMs = player.bufferedPosition.coerceAtLeast(0)
        durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        hasNext = player.hasNextMediaItem()
        hasCaptions = player.currentTracks.containsType(C.TRACK_TYPE_TEXT)
        captionsOn = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        speed = player.playbackParameters.speed
    }
}

/**
 * YouTube-style controls over the video: tap to show or hide them, double-tap either side to
 * skip 10 seconds. A dimmed overlay holds previous / play-pause / next in the middle, time and
 * fullscreen along the bottom, captions and speed at the top right; a thin progress bar sits on
 * the bottom edge (always there in portrait, like YouTube's).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoControls(
    player: Player,
    title: String?,
    isLoading: Boolean,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember { PlayerUiState() }
    DisposableEffect(player) {
        state.update(player)
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = state.update(player)
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (isActive) {
            state.positionMs = player.currentPosition.coerceAtLeast(0)
            state.bufferedMs = player.bufferedPosition.coerceAtLeast(0)
            delay(250)
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    // Bumped by every interaction so the hide timer starts over.
    var interaction by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val ended = state.playbackState == Player.STATE_ENDED
    LaunchedEffect(controlsVisible, interaction, state.isPlaying, scrubbing, menuOpen) {
        if (controlsVisible && state.isPlaying && !scrubbing && !menuOpen) {
            delay(CONTROLS_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }
    fun poke() {
        interaction++
    }

    // Which side was double-tapped, for the "10 seconds" hint there.
    var seekHint by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(700)
            seekHint = null
        }
    }

    val buffering = isLoading || (state.playbackState == Player.STATE_BUFFERING && state.playWhenReady)

    BoxWithConstraints(modifier = modifier) {
        val width = constraints.maxWidth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            poke()
                        },
                        onDoubleTap = { offset ->
                            val forward = offset.x > width / 2f
                            val target = if (forward) {
                                player.currentPosition + SEEK_STEP_MS
                            } else {
                                player.currentPosition - SEEK_STEP_MS
                            }
                            player.seekTo(target.coerceIn(0, state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
                            val previous = seekHint
                            val count = if (previous != null && previous.first == forward) previous.second + 1 else 1
                            seekHint = forward to count
                            poke()
                        },
                    )
                },
        )

        seekHint?.let { (forward, count) ->
            SeekHint(
                forward = forward,
                seconds = count * (SEEK_STEP_MS / 1000).toInt(),
                modifier = Modifier
                    .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(0.35f),
            )
        }

        AnimatedVisibility(
            visible = controlsVisible || ended,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                // Top: back/collapse and the title (fullscreen only, like YouTube), then
                // captions and the settings menu.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    ControlIcon(
                        icon = R.drawable.expand_more,
                        onClick = { if (isFullscreen) onFullscreenChange(false) else onClose() },
                    )
                    if (isFullscreen && title != null) {
                        Text(
                            text = title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (state.hasCaptions) {
                        ControlIcon(
                            icon = if (state.captionsOn) {
                                androidx.media3.ui.R.drawable.exo_ic_subtitle_on
                            } else {
                                androidx.media3.ui.R.drawable.exo_ic_subtitle_off
                            },
                            onClick = {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, state.captionsOn)
                                    .apply { if (!state.captionsOn) setPreferredTextLanguage(null).setSelectUndeterminedTextLanguage(true) }
                                    .build()
                                poke()
                            },
                        )
                    }
                    Box {
                        ControlIcon(icon = R.drawable.settings, onClick = { menuOpen = true })
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            Text(
                                "Playback speed",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            PlaybackSpeeds.forEach { speed ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (speed == 1f) "Normal" else "${speed}x".replace(".0x", "x"),
                                            fontWeight = if (speed == state.speed) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        player.playbackParameters = PlaybackParameters(speed)
                                        menuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }

                // Middle: previous, play/pause, next.
                if (!buffering) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(36.dp),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        ControlIcon(
                            icon = R.drawable.skip_previous,
                            size = 32.dp,
                            onClick = {
                                player.seekToPrevious()
                                poke()
                            },
                        )
                        ControlIcon(
                            icon = when {
                                ended -> R.drawable.replay
                                state.playWhenReady -> R.drawable.pause
                                else -> R.drawable.play
                            },
                            size = 44.dp,
                            background = true,
                            onClick = {
                                when {
                                    ended -> {
                                        player.seekTo(0)
                                        player.play()
                                    }
                                    state.playWhenReady -> player.pause()
                                    else -> player.play()
                                }
                                poke()
                            },
                        )
                        ControlIcon(
                            icon = R.drawable.skip_next,
                            size = 32.dp,
                            enabled = state.hasNext,
                            onClick = {
                                player.seekToNext()
                                poke()
                            },
                        )
                    }
                }

                // Bottom: time and fullscreen, above the seek bar.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, bottom = if (isFullscreen) 28.dp else 6.dp),
                ) {
                    Text(
                        text = buildString {
                            append(makeTimeString(state.positionMs))
                            if (state.durationMs > 0) append(" / ").append(makeTimeString(state.durationMs))
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.weight(1f))
                    ControlIcon(
                        icon = if (isFullscreen) {
                            androidx.media3.ui.R.drawable.exo_ic_fullscreen_exit
                        } else {
                            androidx.media3.ui.R.drawable.exo_ic_fullscreen_enter
                        },
                        onClick = { onFullscreenChange(!isFullscreen) },
                    )
                }
            }
        }

        if (buffering) {
            androidx.compose.material3.CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp),
            )
        }

        // The seek bar: on the video's bottom edge in portrait (always shown, the thumb only
        // with the controls), and lifted above the bottom with the controls in fullscreen.
        val showBar = !isFullscreen || controlsVisible || ended
        if (showBar) {
            SeekBar(
                positionMs = state.positionMs,
                bufferedMs = state.bufferedMs,
                durationMs = state.durationMs,
                showThumb = controlsVisible || ended,
                onScrubbingChange = { scrubbing = it; poke() },
                onSeek = { player.seekTo(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = if (isFullscreen) 16.dp else 0.dp)
                    .padding(bottom = if (isFullscreen) 12.dp else 0.dp),
            )
        }
    }
}

@Composable
private fun ControlIcon(
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    size: Dp = 24.dp,
    enabled: Boolean = true,
    background: Boolean = false,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size + 24.dp)
            .clip(CircleShape)
            .then(if (background) Modifier.background(Color.Black.copy(alpha = 0.35f)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun SeekHint(forward: Boolean, seconds: Int, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.background(
            Brush.horizontalGradient(
                if (forward) {
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.18f))
                } else {
                    listOf(Color.White.copy(alpha = 0.18f), Color.Transparent)
                },
            ),
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(R.drawable.fast_forward),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.graphicsLayer { scaleX = if (forward) 1f else -1f },
            )
            Text("$seconds seconds", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    showThumb: Boolean,
    onScrubbingChange: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = scrubFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val thumbSize by animateDpAsState(
        when {
            scrubFraction != null -> 16.dp
            showThumb -> 12.dp
            else -> 0.dp
        },
        label = "thumb",
    )
    val trackHeight by animateDpAsState(if (scrubFraction != null) 5.dp else 3.dp, label = "track")

    Box(modifier = modifier) {
        scrubFraction?.let { f ->
            Text(
                text = makeTimeString((f * durationMs).toLong()),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                // A taller touch target than the bar itself, drawn along its bottom.
                .height(20.dp)
                .pointerInput(durationMs) {
                    if (durationMs <= 0) return@pointerInput
                    detectTapGestures { offset ->
                        onSeek(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                        onScrubbingChange(false)
                    }
                }
                .pointerInput(durationMs) {
                    if (durationMs <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            onScrubbingChange(true)
                        },
                        onDragEnd = {
                            scrubFraction?.let { onSeek((it * durationMs).toLong()) }
                            scrubFraction = null
                            onScrubbingChange(false)
                        },
                        onDragCancel = {
                            scrubFraction = null
                            onScrubbingChange(false)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
        ) {
            val h = trackHeight.toPx()
            val y = size.height - h
            val radius = CornerRadius(h / 2, h / 2)
            drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, y), Size(size.width, h), radius)
            drawRoundRect(Color.White.copy(alpha = 0.5f), Offset(0f, y), Size(size.width * bufferedFraction, h), radius)
            drawRoundRect(accent, Offset(0f, y), Size(size.width * fraction, h), radius)
            val thumb = thumbSize.toPx()
            if (thumb > 0f) {
                drawCircle(
                    accent,
                    radius = thumb / 2,
                    center = Offset((size.width * fraction).coerceIn(thumb / 2, size.width - thumb / 2), y + h / 2),
                )
            }
        }
    }
}
