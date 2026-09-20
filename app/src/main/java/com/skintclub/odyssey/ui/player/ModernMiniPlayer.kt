package com.skintclub.odyssey.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.skintclub.odyssey.LocalDatabase
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
 * Mini player styled after vivi-music's "New" design: a full-width pill with a circular
 * progress ring around the thumbnail/play button and a favorite (like) toggle.
 */
@Composable
fun ModernMiniPlayer(
    positionProvider: () -> Long,
    durationProvider: () -> Long,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current

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
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val librarySong by database.song(mediaMetadata?.id).collectAsState(initial = null)
    val isLiked = librarySong?.song?.liked == true

    val gradientColors = rememberMiniPlayerGradientColors(mediaMetadata, miniPlayerBackground)

    var offsetX by remember { mutableFloatStateOf(0f) }

    // Matches the BottomSheetPlayer's collapsed backdrop color exactly (it always
    // resolves to a flat surfaceContainer while collapsed), so the floating pill's
    // margin blends into the backdrop instead of showing a mismatched panel.
    val backgroundColor =
        if (pureBlack && useDarkTheme) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    val isDynamicBackground = miniPlayerBackground != PlayerBackgroundStyle.DEFAULT
    val primaryColor = if (isDynamicBackground) Color.White else MaterialTheme.colorScheme.primary
    val outlineColor = if (isDynamicBackground) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline
    val onSurfaceColor = if (isDynamicBackground) Color.White else MaterialTheme.colorScheme.onSurface
    val errorColor = MaterialTheme.colorScheme.error

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
                    .clip(RoundedCornerShape(32.dp))
                    .background(backgroundColor)
                    .border(1.dp, outlineColor.copy(alpha = 0.3f), RoundedCornerShape(32.dp))
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                val trackColor = outlineColor.copy(alpha = 0.2f)
                val strokeWidth = 3.dp

                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(48.dp)
                            .drawWithContent {
                                drawContent()
                                val duration = durationProvider()
                                val position = positionProvider()
                                val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                                val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                                val diameter = size.minDimension
                                val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)

                                drawArc(
                                    color = trackColor,
                                    startAngle = 0f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = Size(diameter, diameter),
                                    style = stroke,
                                )
                                drawArc(
                                    color = primaryColor,
                                    startAngle = -90f,
                                    sweepAngle = 360f * progress,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = Size(diameter, diameter),
                                    style = stroke,
                                )
                            },
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .border(1.dp, outlineColor.copy(alpha = 0.3f), CircleShape)
                                .clickable {
                                    if (playbackState == Player.STATE_ENDED) {
                                        playerConnection.player.seekTo(0, 0)
                                        playerConnection.player.playWhenReady = true
                                    } else {
                                        playerConnection.player.togglePlayPause()
                                    }
                                },
                    ) {
                        mediaMetadata?.let { metadata ->
                            AsyncImage(
                                model = metadata.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape),
                            )
                        }

                        if (!isPlaying || playbackState == Player.STATE_ENDED) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                            )
                            Icon(
                                painter =
                                    painterResource(
                                        if (playbackState == Player.STATE_ENDED) R.drawable.replay else R.drawable.play,
                                    ),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
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
                                    tint = onSurfaceColor.copy(alpha = 0.7f),
                                    modifier =
                                        Modifier
                                            .size(14.dp)
                                            .padding(end = 4.dp),
                                )
                            }
                            Text(
                                text = metadata.artists.joinToString { it.name },
                                color = onSurfaceColor.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.basicMarquee(),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .border(
                                width = 1.dp,
                                color = if (isLiked) errorColor.copy(alpha = 0.5f) else outlineColor.copy(alpha = 0.3f),
                                shape = CircleShape,
                            ).background(
                                color = if (isLiked) errorColor.copy(alpha = 0.1f) else Color.Transparent,
                                shape = CircleShape,
                            ).clickable { playerConnection.service.toggleLike() },
                ) {
                    Icon(
                        painter = painterResource(if (isLiked) R.drawable.favorite else R.drawable.favorite_border),
                        contentDescription = null,
                        tint = if (isLiked) errorColor else onSurfaceColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
