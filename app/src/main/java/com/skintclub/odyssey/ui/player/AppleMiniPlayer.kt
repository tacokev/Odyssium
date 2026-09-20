package com.skintclub.odyssey.ui.player

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.constants.DarkModeKey
import com.skintclub.odyssey.constants.MiniPlayerBackgroundStyleKey
import com.skintclub.odyssey.constants.MiniPlayerHeight
import com.skintclub.odyssey.constants.PlayerBackgroundStyle
import com.skintclub.odyssey.constants.PureBlackMiniPlayerKey
import com.skintclub.odyssey.constants.SwipeThumbnailKey
import com.skintclub.odyssey.extensions.togglePlayPause
import com.skintclub.odyssey.ui.screens.settings.DarkMode
import com.skintclub.odyssey.utils.rememberEnumPreference
import com.skintclub.odyssey.utils.rememberPreference
import kotlin.math.roundToInt

/**
 * Mini player styled after the Apple Music floating capsule: a rounded, inset card with
 * a bottom progress track, swipe-to-skip on the thumbnail, and larger transport controls.
 */
@Composable
fun AppleMiniPlayer(
    positionProvider: () -> Long,
    durationProvider: () -> Long,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return

    val swipeThumbnail by rememberPreference(SwipeThumbnailKey, defaultValue = true)

    val isSystemInDarkTheme = isSystemInDarkTheme()
    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val useDarkTheme =
        remember(darkTheme, isSystemInDarkTheme) {
            if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
        }
    val pureBlack by rememberPreference(PureBlackMiniPlayerKey, defaultValue = false)
    val miniPlayerBackground by rememberEnumPreference(MiniPlayerBackgroundStyleKey, defaultValue = PlayerBackgroundStyle.DEFAULT)

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val playbackState by playerConnection.playbackState.collectAsState()
    val error by playerConnection.error.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()

    val gradientColors = rememberMiniPlayerGradientColors(mediaMetadata, miniPlayerBackground)

    var offsetX by remember { mutableFloatStateOf(0f) }

    // Matches the BottomSheetPlayer's collapsed backdrop color exactly (it always
    // resolves to a flat surfaceContainer while collapsed), so the floating pill's
    // margin blends into the backdrop instead of showing a mismatched panel.
    val backgroundColor =
        if (pureBlack && useDarkTheme) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    val isDynamicBackground = miniPlayerBackground != PlayerBackgroundStyle.DEFAULT
    val onSurfaceColor = if (isDynamicBackground) Color.White else MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = if (isDynamicBackground) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    val trackColor = if (isDynamicBackground) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val progressColor = if (isDynamicBackground) Color.White else MaterialTheme.colorScheme.primary

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(MiniPlayerHeight)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .clip(RoundedCornerShape(16.dp))
                    .background(backgroundColor)
                    .pointerInput(swipeThumbnail) {
                        if (!swipeThumbnail) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragCancel = { offsetX = 0f },
                            onHorizontalDrag = { _, dragAmount -> offsetX += dragAmount },
                            onDragEnd = {
                                if (offsetX > 300) {
                                    if (playerConnection.player.previousMediaItemIndex != -1) {
                                        playerConnection.player.seekToPreviousMediaItem()
                                    }
                                } else if (offsetX < -300) {
                                    if (playerConnection.player.nextMediaItemIndex != -1) {
                                        playerConnection.player.seekToNext()
                                    }
                                }
                                offsetX = 0f
                            },
                        )
                    },
        ) {
            MiniPlayerBackgroundLayer(
                style = miniPlayerBackground,
                mediaMetadata = mediaMetadata,
                gradientColors = gradientColors,
            )

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.BottomCenter)
                        .drawWithContent {
                            val duration = durationProvider()
                            val position = positionProvider()
                            val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                            drawRect(trackColor)
                            drawRect(progressColor, size = Size(size.width * progress, size.height))
                        },
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
            ) {
                Box(modifier = Modifier.padding(6.dp)) {
                    mediaMetadata?.let {
                        AsyncImage(
                            model = it.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = error != null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.info),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                }

                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                ) {
                    mediaMetadata?.let { metadata ->
                        Text(
                            text = metadata.title,
                            color = onSurfaceColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (metadata.explicit) {
                                Icon(
                                    painter = painterResource(R.drawable.explicit),
                                    contentDescription = null,
                                    tint = onSurfaceVariantColor,
                                    modifier =
                                        Modifier
                                            .size(14.dp)
                                            .padding(end = 4.dp),
                                )
                            }
                            Text(
                                text = metadata.artists.joinToString { it.name },
                                color = onSurfaceVariantColor,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.basicMarquee(),
                            )
                        }
                    }
                }

                IconButton(
                    onClick = {
                        if (playbackState == Player.STATE_ENDED) {
                            playerConnection.player.seekTo(0, 0)
                            playerConnection.player.playWhenReady = true
                        } else {
                            playerConnection.player.togglePlayPause()
                        }
                    },
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (playbackState == Player.STATE_ENDED) {
                                    R.drawable.replay
                                } else if (isPlaying) {
                                    R.drawable.pause
                                } else {
                                    R.drawable.play
                                },
                            ),
                        contentDescription = null,
                        tint = onSurfaceColor,
                        modifier = Modifier.size(28.dp),
                    )
                }

                IconButton(
                    enabled = canSkipNext,
                    onClick = playerConnection::seekToNext,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_next),
                        contentDescription = null,
                        tint = if (canSkipNext) onSurfaceColor else onSurfaceColor.copy(alpha = 0.3f),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}
