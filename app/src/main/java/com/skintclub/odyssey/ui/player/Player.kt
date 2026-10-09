package com.skintclub.odyssey.ui.player

import android.content.BroadcastReceiver
import android.net.Uri
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.BitmapDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Player.STATE_READY
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.skintclub.odyssey.LocalDatabase
import com.skintclub.odyssey.LocalDownloadUtil
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.constants.*
import com.skintclub.odyssey.db.entities.PlaylistSongMap
import com.skintclub.odyssey.extensions.togglePlayPause
import com.skintclub.odyssey.models.MediaMetadata
import com.skintclub.odyssey.playback.ExoDownloadService
import com.skintclub.odyssey.playback.queues.ODYSSEY_GROUPING_ARTIST_ID_PREFIX
import com.skintclub.odyssey.club.SECRET_EPISODES_ROUTE
import com.skintclub.odyssey.club.SECRET_EPISODES_TITLE
import com.skintclub.odyssey.ui.component.BottomSheet
import com.skintclub.odyssey.ui.component.BottomSheetState
import com.skintclub.odyssey.ui.component.COLLAPSED_ANCHOR
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.ListDialog
import com.skintclub.odyssey.ui.component.ListItem
import com.skintclub.odyssey.ui.component.LocalMenuState
import com.skintclub.odyssey.ui.component.ResizableIconButton
import com.skintclub.odyssey.ui.component.rememberBottomSheetState
import com.skintclub.odyssey.ui.menu.AddToPlaylistDialog
import com.skintclub.odyssey.ui.menu.PlayerMenu
import com.skintclub.odyssey.ui.screens.Screens
import com.skintclub.odyssey.ui.screens.settings.DarkMode
import com.skintclub.odyssey.ui.screens.settings.PlayerTextAlignment
import com.skintclub.odyssey.ui.theme.extractGradientColors
import com.skintclub.odyssey.viewmodels.OdysseyViewModel
import com.skintclub.odyssey.utils.getConnectedBluetoothDeviceName
import com.skintclub.odyssey.utils.joinByBullet
import com.skintclub.odyssey.utils.makeTimeString
import com.skintclub.odyssey.utils.rememberEnumPreference
import com.skintclub.odyssey.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.saket.squiggles.SquigglySlider
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * A fully custom, hand-rolled pill slider (not androidx Slider) used by the Apple
 * player styles. Material3's Slider constrains the overall touch/layout height to its
 * own internal sizing regardless of a custom track's requested height, which made both
 * the timeline and volume sliders read as too thin and unreliable to drag. This owns its
 * own gesture handling and layout end to end so both height and touch behavior are fully
 * controllable.
 */
@Composable
private fun ApplePillSlider(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    onProgressChangeFinished: (() -> Unit)? = null,
) {
    var isActive by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val trackHeight by animateDpAsState(
        targetValue = if (isActive) 16.dp else 10.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "applePillSliderHeight",
    )

    Box(
        modifier =
            modifier
                .height(32.dp)
                .onGloballyPositioned { widthPx = it.size.width.toFloat() }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isActive = true
                            if (widthPx > 0f) onProgressChange((offset.x / widthPx).coerceIn(0f, 1f))
                        },
                        onDragEnd = {
                            isActive = false
                            onProgressChangeFinished?.invoke()
                        },
                        onDragCancel = {
                            isActive = false
                            onProgressChangeFinished?.invoke()
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            if (widthPx > 0f) onProgressChange((change.position.x / widthPx).coerceIn(0f, 1f))
                        },
                    )
                },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(50))
                    .background(color.copy(alpha = 0.2f)),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(trackHeight)
                    .clip(RoundedCornerShape(50))
                    .background(color.copy(alpha = 0.9f)),
        )
    }
}

/**
 * Isolated into its own composable so the continuous per-frame `state.value`/
 * `state.progress` reads driving this brush only recompose this tiny function instead of
 * the entire (very large) [BottomSheetPlayer]. Reading them directly in that function's
 * body was forcing a full recomposition of the whole player on every single frame of the
 * expand/collapse animation, which is what made it feel laggy.
 */
@Composable
private fun rememberPlayerSheetBrush(
    state: BottomSheetState,
    isFloatingMiniPlayer: Boolean,
    hasGradientColours: Boolean,
    changeBound: Dp,
    gradientColors: List<Color>,
): Brush {
    val floatingBackdropAlpha =
        if (isFloatingMiniPlayer) ((state.progress - 0.25f) * 4).coerceIn(0f, 1f) else 1f

    return if (hasGradientColours && state.value > changeBound) {
        Brush.verticalGradient(gradientColors.map { it.copy(alpha = it.alpha * floatingBackdropAlpha) })
    } else {
        val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
        Brush.verticalGradient(
            listOf(
                surfaceContainer.copy(alpha = floatingBackdropAlpha),
                surfaceContainer.copy(alpha = floatingBackdropAlpha),
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetPlayer(
    state: BottomSheetState,
    navController: NavController,
    odysseyViewModel: OdysseyViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current

    val clipboardManager = LocalClipboardManager.current
    val density = LocalDensity.current
    val bottomInset = with(density) { WindowInsets.systemBars.getBottom(density).toDp() }

    val playerConnection = LocalPlayerConnection.current ?: return

    val isSystemInDarkTheme = isSystemInDarkTheme()
    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val pureBlack by rememberPreference(PureBlackKey, defaultValue = false)
    val playerBackground by rememberEnumPreference(PlayerBackgroundStyleKey, defaultValue = PlayerBackgroundStyle.DEFAULT)
    val playerStyle by rememberEnumPreference(PlayerStyleKey, defaultValue = PlayerStyle.DEFAULT)
    val useBlackBackground =
        remember(isSystemInDarkTheme, darkTheme, pureBlack, playerBackground) {
            val useDarkTheme = if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
            val usePureBlackPlayerBackground = (playerBackground == PlayerBackgroundStyle.DEFAULT)
            useDarkTheme && pureBlack && usePureBlackPlayerBackground
        }

    val playerTextAlignment by rememberEnumPreference(PlayerTextAlignmentKey, PlayerTextAlignment.SIDED)

    val playbackState by playerConnection.playbackState.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    // The "artist" shown for an Odyssey episode is actually its containing album/grouping
    // (see odysseyEpisodeToMediaMetadata) - its Artist.id carries a marker prefix so it can
    // be told apart from an actual artist and made to open that grouping instead.
    val odysseyGroupingName =
        mediaMetadata?.artists?.firstOrNull()?.id
            ?.takeIf { it.startsWith(ODYSSEY_GROUPING_ARTIST_ID_PREFIX) }
            ?.removePrefix(ODYSSEY_GROUPING_ARTIST_ID_PREFIX)
    val onArtistClick: (() -> Unit)? =
        odysseyGroupingName?.let { groupingName ->
            {
                state.collapseSoft()
                // Pop up to Home first so repeated taps (or a tap when an album page is
                // already stacked underneath) don't keep pushing duplicate copies of the
                // same album screen onto the back stack.
                val route = if (groupingName == SECRET_EPISODES_TITLE) {
                    SECRET_EPISODES_ROUTE
                } else {
                    "odyssey_album/${Uri.encode(groupingName)}"
                }
                navController.navigate(route) {
                    popUpTo(Screens.Home.route)
                }
            }
        }

    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()

    val sliderStyle by rememberEnumPreference(SliderStyleKey, SliderStyle.DEFAULT)

    val seekIncrement by rememberEnumPreference(SeekIncrementKey, defaultValue = SeekIncrement.TEN)
    val seekBackIcon = if (seekIncrement.backMs == 30_000L) R.drawable.replay_30 else R.drawable.replay_10
    val seekForwardIcon = if (seekIncrement.forwardMs == 30_000L) R.drawable.forward_30 else R.drawable.forward_10
    // ExoPlayer's own seekBack()/seekForward() use the increment fixed at player creation
    // time (MusicService), which can't be changed at runtime — so the amount is applied
    // here instead, replicating the same offset-and-clamp behavior Player.seekBack() does.
    val onSeekBack: () -> Unit = {
        val player = playerConnection.player
        player.seekTo((player.currentPosition - seekIncrement.backMs).coerceAtLeast(0))
    }
    val onSeekForward: () -> Unit = {
        val player = playerConnection.player
        val duration = player.duration
        var target = player.currentPosition + seekIncrement.forwardMs
        if (duration != C.TIME_UNSET) target = target.coerceAtMost(duration)
        player.seekTo(target)
    }

    var position by rememberSaveable(playbackState) {
        mutableLongStateOf(playerConnection.player.currentPosition)
    }
    var duration by rememberSaveable(playbackState) {
        mutableLongStateOf(playerConnection.player.duration)
    }
    var sliderPosition by remember {
        mutableStateOf<Long?>(null)
    }

    var gradientColors by remember {
        mutableStateOf<List<Color>>(emptyList())
    }

    var changeColor by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(mediaMetadata, playerBackground, darkTheme, useBlackBackground) {
        if (useBlackBackground) {
            gradientColors = listOf(Color.Black, Color.Black)
        } else if (playerBackground == PlayerBackgroundStyle.GRADIENT) {
            withContext(Dispatchers.IO) {
                val result =
                    (
                        context.imageLoader
                            .execute(
                                ImageRequest
                                    .Builder(context)
                                    .data(mediaMetadata?.thumbnailUrl)
                                    .allowHardware(false)
                                    .build(),
                            ).drawable as? BitmapDrawable
                    )?.bitmap?.extractGradientColors(
                        darkTheme =
                            darkTheme == DarkMode.ON || (darkTheme == DarkMode.AUTO && isSystemInDarkTheme),
                    )

                result?.let {
                    gradientColors = it
                }
            }
        } else {
            gradientColors = emptyList()
        }
    }

    val changeBound = state.expandedBound / 3

    val hasGradientColours : Boolean = gradientColors.size >= 2
    val whiteContrastThreshold = 2.0
    val blackContrastThreshold = 2.0

    val whiteGradientContrast : Double = when {
        hasGradientColours -> {
            ColorUtils.calculateContrast(
                gradientColors.first().toArgb(),
                Color.White.toArgb(),
            )
        }
        else -> whiteContrastThreshold
    }

    val blackGradientContrast : Double = when {
        hasGradientColours -> {
            ColorUtils.calculateContrast(
                gradientColors.last().toArgb(),
                Color.Black.toArgb(),
            )
        }
        else -> blackContrastThreshold
    }

    val onBackgroundColor =
        when (playerBackground) {
            PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.onBackground
            PlayerBackgroundStyle.BLUR -> Color.White
            PlayerBackgroundStyle.APPLE_MUSIC -> Color.White
            else -> {
                when {
                    hasGradientColours &&
                            whiteGradientContrast < whiteContrastThreshold &&
                            blackGradientContrast > blackContrastThreshold -> {
                        changeColor = true
                        Color.Black
                    }
                    hasGradientColours &&
                            whiteGradientContrast > whiteContrastThreshold &&
                            blackGradientContrast < blackContrastThreshold -> {
                        changeColor = true
                        Color.White
                    }
                    else -> {
                        changeColor = false
                        MaterialTheme.colorScheme.onSurface
                    }
                }
            }
        }

    val sleepTimerEnabled =
        remember(playerConnection.service.sleepTimer.triggerTime, playerConnection.service.sleepTimer.pauseWhenSongEnd) {
            playerConnection.service.sleepTimer.isActive
        }

    var sleepTimerTimeLeft by remember {
        mutableLongStateOf(0L)
    }

    LaunchedEffect(sleepTimerEnabled) {
        if (sleepTimerEnabled) {
            while (isActive) {
                sleepTimerTimeLeft =
                    if (playerConnection.service.sleepTimer.pauseWhenSongEnd) {
                        playerConnection.player.duration - playerConnection.player.currentPosition
                    } else {
                        playerConnection.service.sleepTimer.triggerTime - System.currentTimeMillis()
                    }
                delay(1000L)
            }
        }
    }

    var showSleepTimerDialog by remember {
        mutableStateOf(false)
    }

    var sleepTimerValue by remember {
        mutableFloatStateOf(30f)
    }
    if (showSleepTimerDialog) {
        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { showSleepTimerDialog = false },
            icon = { Icon(painter = painterResource(R.drawable.bedtime), contentDescription = null) },
            title = { Text(stringResource(R.string.sleep_timer)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSleepTimerDialog = false
                        playerConnection.service.sleepTimer.start(sleepTimerValue.roundToInt())
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSleepTimerDialog = false },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = pluralStringResource(R.plurals.minute, sleepTimerValue.roundToInt(), sleepTimerValue.roundToInt()),
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    Slider(
                        value = sleepTimerValue,
                        onValueChange = { sleepTimerValue = it },
                        valueRange = 5f..120f,
                        steps = (120 - 5) / 5 - 1,
                    )

                    OutlinedButton(
                        onClick = {
                            showSleepTimerDialog = false
                            playerConnection.service.sleepTimer.start(-1)
                        },
                    ) {
                        Text(stringResource(R.string.end_of_episode))
                    }
                }
            },
        )
    }

    LaunchedEffect(playbackState) {
        if (playbackState == STATE_READY) {
            while (isActive) {
                delay(100)
                position = playerConnection.player.currentPosition
                duration = playerConnection.player.duration
            }
        }
    }

    var showDetailsDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // If the currently playing track is an Odyssey episode, "Details" should land on the
    // same full-screen episode page ODYSSEY_EPISODE_ROUTE reaches from the episode list's
    // own "Details" menu item, rather than the generic technical info dialog below.
    val odysseyAlbums by odysseyViewModel.albums.collectAsState()
    val currentOdysseyEpisode =
        remember(mediaMetadata, odysseyAlbums) {
            val albumName = mediaMetadata?.album?.title ?: return@remember null
            val album = odysseyAlbums.find { it.name == albumName } ?: return@remember null
            val episodeIndex =
                album.episodes.indexOfFirst { episode ->
                    val id = episode["id"] as? String ?: episode["download_url"] as? String
                    id != null && id == mediaMetadata?.id
                }
            if (episodeIndex == -1) null else albumName to episodeIndex
        }
    val onShowDetails: () -> Unit = {
        val episode = currentOdysseyEpisode
        if (episode != null) {
            state.collapseSoft()
            // The player can be opened from anywhere, so the back stack underneath it may
            // already hold an unrelated episode page (or several). Pop up to the albums
            // screen first so back from here always lands there instead of on a stale one.
            navController.navigate("odyssey_episode/${Uri.encode(episode.first)}/${episode.second}") {
                popUpTo(Screens.Home.route)
            }
        } else {
            showDetailsDialog = true
        }
    }
    val onTitleClick: (() -> Unit)? = if (currentOdysseyEpisode != null) onShowDetails else null
    val titleClickModifier = if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier

    if (showDetailsDialog) {
        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { showDetailsDialog = false },
            containerColor = if (useBlackBackground) Color.Black else AlertDialogDefaults.containerColor,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.info),
                    contentDescription = null,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showDetailsDialog = false },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            text = {
                Column(
                    modifier =
                        Modifier
                            .sizeIn(minWidth = 280.dp, maxWidth = 560.dp)
                            .verticalScroll(rememberScrollState()),
                ) {
                    listOf(
                        stringResource(R.string.song_title) to mediaMetadata?.title,
                        stringResource(R.string.song_artists) to mediaMetadata?.artists?.joinToString { it.name },
                        stringResource(R.string.media_id) to mediaMetadata?.id,
                        stringResource(R.string.volume) to "${(playerConnection.player.volume * 100).toInt()}%",
                    ).forEach { (label, text) ->
                        val displayText = text ?: stringResource(R.string.unknown)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.titleMedium,
                            modifier =
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(displayText))
                                        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                                    },
                                ),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
        )
    }

    val queueSheetState =
        rememberBottomSheetState(
            dismissedBound = 0.dp,
            collapsedBound = bottomInset + MiniPlayerHeight,
            expandedBound = state.expandedBound,
            // Unlike the player's own sheet, the queue has no "nothing to show yet" state to
            // dismiss into — it should always rest collapsed (pull-tab visible) whenever the
            // player is open. Defaulting to DISMISSED left it animating to 0dp (fully
            // off-screen) on first composition, so the tab stayed invisible until the user
            // expanded and collapsed it once, which flips the remembered anchor.
            initialAnchor = COLLAPSED_ANCHOR,
        )

    val miniPlayerStyle by rememberEnumPreference(MiniPlayerStyleKey, defaultValue = MiniPlayerStyle.DEFAULT)
    val isFloatingMiniPlayer = miniPlayerStyle != MiniPlayerStyle.DEFAULT

    BottomSheet(
        state = state,
        modifier = modifier,
        // Floating mini player styles sit on top of the screen behind them, so the
        // collapsed backdrop fades in from fully transparent as the sheet expands, using
        // the same progress curve BottomSheet already uses to fade the expanded content in
        // (so the two stay in sync and there's no abrupt pop when collapsing/expanding).
        brushBackgroundColor = rememberPlayerSheetBrush(
            state = state,
            isFloatingMiniPlayer = isFloatingMiniPlayer,
            hasGradientColours = hasGradientColours,
            changeBound = changeBound,
            gradientColors = gradientColors,
        ),
        onDismiss = {
            playerConnection.player.stop()
            playerConnection.player.clearMediaItems()
        },
        collapsedContent = {
            // Providers instead of raw values: position ticks every 100ms while playing, and
            // reading it here (or as a plain parameter deeper down) would force MiniPlayer's
            // whole subtree — including the notably heavier Modern/Apple styles' gradients,
            // borders, and library lookups — to recompose every tick. Deferring the read into
            // these lambdas means only the leaf progress-drawing code re-evaluates on each
            // tick, which is what made expand/collapse feel laggy specifically on those styles.
            MiniPlayer(
                positionProvider = { position },
                durationProvider = { duration },
                viewModel = odysseyViewModel,
            )
        },
    ) {
        if (playerBackground == PlayerBackgroundStyle.BLUR && !useBlackBackground) {
            AsyncImage(
                model = mediaMetadata?.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .blur(80.dp),
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
            )
        } else if (playerBackground == PlayerBackgroundStyle.APPLE_MUSIC) {
            // Layer 1: full-bleed, heavily blurred artwork behind everything, for ambient
            // color that fills the whole screen.
            AsyncImage(
                model = mediaMetadata?.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .blur(120.dp),
            )

            // Layer 2: crisp, uncropped artwork that acts as the visible cover for this
            // style, covering the blurred layer beneath. It's feathered into the blur at
            // the bottom edge so there's no hard seam. Because this is already the cover,
            // the player's own Thumbnail is hidden while this background is active (see
            // `showArtwork = false` at the Thumbnail call sites) so it isn't drawn twice.
            AsyncImage(
                model = mediaMetadata?.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.65f)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush =
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Black, Color.Black, Color.Transparent),
                                    ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
            )

            // Layer 3: subtle dark gradient for depth and text contrast.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.05f),
                                    Color.Black.copy(alpha = 0.4f),
                                ),
                            ),
                        ),
            )
        }

        val controlsContent: @Composable ColumnScope.(MediaMetadata) -> Unit = { mediaMetadata ->
            val isLiked = rememberEpisodeLiked(odysseyViewModel, mediaMetadata.id)

            val playPauseRoundness by animateDpAsState(
                targetValue = if (isPlaying) 24.dp else 36.dp,
                animationSpec = tween(durationMillis = 90, easing = LinearEasing),
                label = "playPauseRoundness",
            )

            Row(
                horizontalArrangement =
                    when (playerTextAlignment) {
                        PlayerTextAlignment.SIDED -> Arrangement.Start
                        PlayerTextAlignment.CENTER -> Arrangement.Center
                    },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding),
            ) {
                AnimatedContent(
                    targetState = mediaMetadata.title,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "",
                ) { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = onBackgroundColor,
                        modifier =
                            titleClickModifier
                                .basicMarquee()
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                horizontalArrangement =
                    when (playerTextAlignment) {
                        PlayerTextAlignment.SIDED -> Arrangement.Start
                        PlayerTextAlignment.CENTER -> Arrangement.Center
                    },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding)
                        .then(if (onArtistClick != null) Modifier.clickable(onClick = onArtistClick) else Modifier),
            ) {
                mediaMetadata.artists.fastForEachIndexed { index, artist ->
                    AnimatedContent(
                        targetState = artist.name,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "",
                    ) { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            color = onBackgroundColor,
                            maxLines = 1,
                        )
                    }

                    if (index != mediaMetadata.artists.lastIndex) {
                        AnimatedContent(
                            targetState = ", ",
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "",
                        ) { comma ->
                            Text(
                                text = comma,
                                style = MaterialTheme.typography.titleMedium,
                                color = onBackgroundColor,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding),
            ) {
                Spacer(modifier = Modifier.weight(1f))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable {
                                odysseyViewModel.setEpisodeLiked(mediaMetadata.id, !isLiked)
                            },
                ) {
                    Image(
                        painter = painterResource(if (isLiked) R.drawable.favorite else R.drawable.favorite_border),
                        contentDescription = null,
                        colorFilter =
                            ColorFilter.tint(
                                if (isLiked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                            ),
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .size(24.dp),
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    AnimatedContent(
                        label = "sleepTimer",
                        targetState = sleepTimerEnabled,
                    ) { sleepTimerEnabled ->
                        if (sleepTimerEnabled) {
                            Text(
                                text = makeTimeString(sleepTimerTimeLeft),
                                style = MaterialTheme.typography.labelLarge,
                                color = onBackgroundColor,
                                maxLines = 1,
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable(onClick = playerConnection.service.sleepTimer::clear)
                                        .basicMarquee(),
                            )
                        } else {
                            Box(
                                modifier =
                                    Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .clickable {
                                            showSleepTimerDialog = true
                                        },
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.bedtime),
                                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSecondaryContainer),
                                    contentDescription = null,
                                    modifier =
                                        Modifier
                                            .align(Alignment.Center)
                                            .size(24.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable {
                                menuState.show {
                                    PlayerMenu(
                                        mediaMetadata = mediaMetadata,
                                        navController = navController,
                                        playerBottomSheetState = state,
                                        viewModel = odysseyViewModel,
                                        onShowDetailsDialog = onShowDetails,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                ) {
                    Image(
                        painter = painterResource(R.drawable.more_horiz),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSecondaryContainer),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            when (sliderStyle) {
                SliderStyle.SQUIGGLY -> {
                    SquigglySlider(
                        value = (sliderPosition ?: position).toFloat(),
                        valueRange = 0f..(if (duration == C.TIME_UNSET) 0f else duration.toFloat()),
                        onValueChange = {
                            sliderPosition = it.toLong()
                        },
                        onValueChangeFinished = {
                            sliderPosition?.let {
                                playerConnection.player.seekTo(it)
                                position = it
                            }
                            sliderPosition = null
                        },
                        modifier = Modifier.padding(horizontal = PlayerHorizontalPadding),
                        squigglesSpec =
                            SquigglySlider.SquigglesSpec(
                                amplitude = if (isPlaying) (2.dp).coerceAtLeast(2.dp) else 0.dp,
                                strokeWidth = 3.dp,
                            ),
                    )
                }

                SliderStyle.DEFAULT -> {
                    Slider(
                        value = (sliderPosition ?: position).toFloat(),
                        valueRange = 0f..(if (duration == C.TIME_UNSET) 0f else duration.toFloat()),
                        onValueChange = {
                            sliderPosition = it.toLong()
                        },
                        onValueChangeFinished = {
                            sliderPosition?.let {
                                playerConnection.player.seekTo(it)
                                position = it
                            }
                            sliderPosition = null
                        },
                        modifier = Modifier.padding(horizontal = PlayerHorizontalPadding),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding + 4.dp),
            ) {
                Text(
                    text = makeTimeString(sliderPosition ?: position),
                    style = MaterialTheme.typography.labelMedium,
                    color = onBackgroundColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = if (duration != C.TIME_UNSET) makeTimeString(duration) else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = onBackgroundColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ResizableIconButton(
                        icon = seekBackIcon,
                        color = onBackgroundColor,
                        modifier =
                            Modifier
                                .size(32.dp)
                                .padding(4.dp)
                                .align(Alignment.Center),
                        onClick = onSeekBack,
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    ResizableIconButton(
                        icon = R.drawable.skip_previous,
                        enabled = canSkipPrevious,
                        color = onBackgroundColor,
                        modifier =
                            Modifier
                                .size(32.dp)
                                .align(Alignment.Center),
                        onClick = playerConnection::seekToPrevious,
                    )
                }

                Spacer(Modifier.width(8.dp))

                Box(
                    modifier =
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(playPauseRoundness))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable {
                                if (playbackState == STATE_ENDED) {
                                    playerConnection.player.seekTo(0, 0)
                                    playerConnection.player.playWhenReady = true
                                } else {
                                    playerConnection.player.togglePlayPause()
                                }
                            },
                ) {
                    Image(
                        painter =
                            painterResource(
                                if (playbackState ==
                                    STATE_ENDED
                                ) {
                                    R.drawable.replay
                                } else if (isPlaying) {
                                    R.drawable.pause
                                } else {
                                    R.drawable.play
                                },
                            ),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSecondaryContainer),
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .size(36.dp),
                    )
                }

                Spacer(Modifier.width(8.dp))

                Box(modifier = Modifier.weight(1f)) {
                    ResizableIconButton(
                        icon = R.drawable.skip_next,
                        enabled = canSkipNext,
                        color = onBackgroundColor,
                        modifier =
                            Modifier
                                .size(32.dp)
                                .align(Alignment.Center),
                        onClick = playerConnection::seekToNext,
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    ResizableIconButton(
                        icon = seekForwardIcon,
                        color = onBackgroundColor,
                        modifier =
                            Modifier
                                .size(32.dp)
                                .padding(4.dp)
                                .align(Alignment.Center),
                        onClick = onSeekForward,
                    )
                }
            }
        }

        val playerAppleContent: @Composable ColumnScope.(MediaMetadata) -> Unit = { metadata ->
            val isLiked = rememberEpisodeLiked(odysseyViewModel, metadata.id)

            val audioContext = LocalContext.current
            val audioManager =
                remember { audioContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
            val maxSystemVolume =
                remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            var systemVolume by remember {
                mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxSystemVolume)
            }
            var bluetoothDeviceName by remember { mutableStateOf(getConnectedBluetoothDeviceName(audioContext)) }

            DisposableEffect(Unit) {
                fun refreshAudioState() {
                    systemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxSystemVolume
                    bluetoothDeviceName = getConnectedBluetoothDeviceName(audioContext)
                }
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            receiverContext: Context,
                            intent: Intent,
                        ) = refreshAudioState()
                    }
                val filter =
                    IntentFilter().apply {
                        addAction(AudioManager.ACTION_HEADSET_PLUG)
                        addAction("android.bluetooth.adapter.action.STATE_CHANGED")
                        addAction("android.bluetooth.device.action.ACL_CONNECTED")
                        addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
                        addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                        // Fired whenever any stream's volume changes, by any means (hardware
                        // keys, another app, a connected device's own controls) — keeps the
                        // slider in sync both ways instead of only app-to-device.
                        addAction("android.media.VOLUME_CHANGED_ACTION")
                    }
                ContextCompat.registerReceiver(audioContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                val audioDeviceCallback =
                    object : AudioDeviceCallback() {
                        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshAudioState()

                        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshAudioState()
                    }
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
                onDispose {
                    audioContext.unregisterReceiver(receiver)
                    audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
                }
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding),
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = metadata.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = onBackgroundColor,
                            modifier = titleClickModifier.basicMarquee(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = metadata.artists.joinToString { it.name },
                            style = MaterialTheme.typography.titleMedium,
                            color = onBackgroundColor.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (onArtistClick != null) Modifier.clickable(onClick = onArtistClick) else Modifier,
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(onBackgroundColor.copy(alpha = 0.1f))
                                .clickable {
                                    if (sleepTimerEnabled) {
                                        playerConnection.service.sleepTimer.clear()
                                    } else {
                                        showSleepTimerDialog = true
                                    }
                                },
                    ) {
                        if (sleepTimerEnabled) {
                            Text(
                                text = makeTimeString(sleepTimerTimeLeft),
                                style = MaterialTheme.typography.labelSmall,
                                color = onBackgroundColor,
                                maxLines = 1,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.bedtime),
                                contentDescription = null,
                                tint = onBackgroundColor,
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(onBackgroundColor.copy(alpha = 0.1f))
                                .clickable {
                                    menuState.show {
                                        PlayerMenu(
                                            mediaMetadata = metadata,
                                            navController = navController,
                                            playerBottomSheetState = state,
                                            viewModel = odysseyViewModel,
                                            onShowDetailsDialog = onShowDetails,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_horiz),
                            contentDescription = null,
                            tint = onBackgroundColor,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                val v2CurrentPos = sliderPosition ?: position
                val timelineDurationMs = if (duration == C.TIME_UNSET) 0L else duration
                ApplePillSlider(
                    progress = if (timelineDurationMs > 0) v2CurrentPos.toFloat() / timelineDurationMs else 0f,
                    onProgressChange = { fraction -> sliderPosition = (fraction * timelineDurationMs).toLong() },
                    onProgressChangeFinished = {
                        sliderPosition?.let {
                            playerConnection.player.seekTo(it)
                            position = it
                        }
                        sliderPosition = null
                    },
                    color = onBackgroundColor,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = makeTimeString(v2CurrentPos),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = onBackgroundColor.copy(alpha = 0.7f),
                    )
                    Text(
                        text = if (duration != C.TIME_UNSET) "-" + makeTimeString((duration - v2CurrentPos).coerceAtLeast(0L)) else "",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = onBackgroundColor.copy(alpha = 0.7f),
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(48.dp),
                    ) {
                        ResizableIconButton(
                            icon = seekBackIcon,
                            color = onBackgroundColor,
                            modifier = Modifier.size(28.dp),
                            onClick = onSeekBack,
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(56.dp),
                    ) {
                        ResizableIconButton(
                            icon = R.drawable.skip_previous,
                            enabled = canSkipPrevious,
                            color = onBackgroundColor,
                            modifier = Modifier.size(36.dp),
                            onClick = playerConnection::seekToPrevious,
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(80.dp)
                                .clickable {
                                    if (playbackState == STATE_ENDED) {
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
                                    if (playbackState == STATE_ENDED) {
                                        R.drawable.replay
                                    } else if (isPlaying) {
                                        R.drawable.pause
                                    } else {
                                        R.drawable.play
                                    },
                                ),
                            contentDescription = null,
                            tint = onBackgroundColor,
                            modifier = Modifier.size(64.dp),
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(56.dp),
                    ) {
                        ResizableIconButton(
                            icon = R.drawable.skip_next,
                            enabled = canSkipNext,
                            color = onBackgroundColor,
                            modifier = Modifier.size(36.dp),
                            onClick = playerConnection::seekToNext,
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(48.dp),
                    ) {
                        ResizableIconButton(
                            icon = seekForwardIcon,
                            color = onBackgroundColor,
                            modifier = Modifier.size(28.dp),
                            onClick = onSeekForward,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.volume_down),
                        contentDescription = null,
                        tint = onBackgroundColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    ApplePillSlider(
                        progress = systemVolume,
                        onProgressChange = { fraction ->
                            systemVolume = fraction
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * maxSystemVolume).toInt(), 0)
                        },
                        color = onBackgroundColor,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        painter = painterResource(R.drawable.volume_up),
                        contentDescription = null,
                        tint = onBackgroundColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(
                        onClick = { odysseyViewModel.setEpisodeLiked(metadata.id, !isLiked) },
                        onLongClick = {},
                    ) {
                        Icon(
                            painter = painterResource(if (isLiked) R.drawable.favorite else R.drawable.favorite_border),
                            contentDescription = null,
                            tint = if (isLiked) MaterialTheme.colorScheme.primary else onBackgroundColor.copy(alpha = 0.6f),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(if (bluetoothDeviceName != null) R.drawable.headphones else R.drawable.speaker),
                            contentDescription = null,
                            tint = onBackgroundColor.copy(alpha = 0.6f),
                        )
                        bluetoothDeviceName?.let { name ->
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = onBackgroundColor.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 84.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = { queueSheetState.expandSoft() },
                        onLongClick = {},
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.queue_music),
                            contentDescription = null,
                            tint = onBackgroundColor.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }

        when (LocalConfiguration.current.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                Row(
                    modifier =
                        Modifier
                            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                            .padding(bottom = queueSheetState.collapsedBound),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f),
                    ) {
                        Thumbnail(
                            sliderPositionProvider = { sliderPosition },
                            modifier = Modifier.nestedScroll(state.preUpPostDownNestedScrollConnection),
                            changeColor = changeColor,
                            color = onBackgroundColor,
                            showArtwork = playerBackground != PlayerBackgroundStyle.APPLE_MUSIC,
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                            Modifier
                                .weight(1f)
                                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
                    ) {
                        Spacer(Modifier.weight(1f))

                        mediaMetadata?.let {
                            controlsContent(it)
                        }

                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier
                            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                            .padding(bottom = queueSheetState.collapsedBound),
                ) {
                    if (playerStyle == PlayerStyle.DEFAULT) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.weight(1f),
                        ) {
                            Thumbnail(
                                sliderPositionProvider = { sliderPosition },
                                modifier = Modifier.nestedScroll(state.preUpPostDownNestedScrollConnection),
                                changeColor = changeColor,
                                color = onBackgroundColor,
                                showArtwork = playerBackground != PlayerBackgroundStyle.APPLE_MUSIC,
                            )
                        }

                        mediaMetadata?.let { controlsContent(it) }

                        Spacer(Modifier.height(24.dp))
                    } else {
                        // Weighted spacers on both sides of the thumbnail (instead of a
                        // weighted thumbnail box) keep the controls pinned to the bottom
                        // with no dead gap below them, matching vivi's cover layout.
                        Spacer(Modifier.weight(1f))

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f),
                        ) {
                            Thumbnail(
                                sliderPositionProvider = { sliderPosition },
                                modifier = Modifier.nestedScroll(state.preUpPostDownNestedScrollConnection),
                                changeColor = changeColor,
                                color = onBackgroundColor,
                                showArtwork = playerBackground != PlayerBackgroundStyle.APPLE_MUSIC,
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        mediaMetadata?.let {
                            when (playerStyle) {
                                PlayerStyle.APPLE -> playerAppleContent(it)
                                PlayerStyle.DEFAULT -> {}
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }

        Queue(
            state = queueSheetState,
            playerBottomSheetState = state,
            navController = navController,
            backgroundColor =
                if (useBlackBackground) {
                    Color.Black
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            onBackgroundColor = onBackgroundColor,
        )
    }
}
