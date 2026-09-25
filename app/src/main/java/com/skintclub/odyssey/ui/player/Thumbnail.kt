package com.skintclub.odyssey.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.constants.PlayerHorizontalPadding
import com.skintclub.odyssey.constants.SwipeThumbnailKey
import com.skintclub.odyssey.constants.ThumbnailCornerRadius
import com.skintclub.odyssey.utils.rememberPreference
import kotlin.math.roundToInt

@Composable
fun Thumbnail(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    changeColor: Boolean = false,
    color: Color,
    // When false, the artwork image itself is hidden (but gestures, lyrics, and error
    // states still work as normal). Used by the Apple Music player background style,
    // which already shows the cover as part of the background, so this avoids drawing
    // the same cover art twice.
    showArtwork: Boolean = true,
) {
    val playerConnection = LocalPlayerConnection.current ?: return

    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val error by playerConnection.error.collectAsState()

    val swipeThumbnail by rememberPreference(SwipeThumbnailKey, true)

    var offsetX by remember { mutableFloatStateOf(0f) }

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = PlayerHorizontalPadding)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragCancel = {
                                    offsetX = 0f
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    if (swipeThumbnail) {
                                        offsetX += dragAmount
                                    }
                                },
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
                AsyncImage(
                    model = mediaMetadata?.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .offset { IntOffset(offsetX.roundToInt(), 0) }
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(ThumbnailCornerRadius * 2))
                            .alpha(if (showArtwork) 1f else 0f)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = { offset ->
                                        if (offset.x < size.width / 2) {
                                            playerConnection.player.seekBack()
                                        } else {
                                            playerConnection.player.seekForward()
                                        }
                                    },
                                )
                            },
                )
            }
        }

        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier =
                Modifier
                    .padding(32.dp)
                    .align(Alignment.Center),
        ) {
            error?.let { error ->
                PlaybackError(
                    error = error,
                    retry = playerConnection.player::prepare,
                )
            }
        }
    }
}
