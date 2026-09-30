package com.skintclub.odyssey.ui.video

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.ClubContent
import com.skintclub.odyssey.club.formatVideoLength
import com.skintclub.odyssey.ui.theme.InnerTuneTheme

/**
 * Full-screen video playback, kept apart from the audio-only music player and its queue:
 * a 16:9 player over the video's details and the rest of its shelf in portrait, the video
 * alone (system bars hidden) in landscape, and picture-in-picture when leaving the app
 * mid-video.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlayerActivity : ComponentActivity() {
    private val viewModel: VideoPlayerViewModel by viewModels()

    private var isInPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val videos = runCatching {
            gson.fromJson<List<VideoItem>>(intent.getStringExtra(EXTRA_VIDEOS), videoListType)
        }.getOrNull().orEmpty()
        if (videos.isEmpty()) {
            finish()
            return
        }
        viewModel.start(
            shelfTitle = intent.getStringExtra(EXTRA_SHELF_TITLE).orEmpty(),
            videos = videos,
            startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0),
        )

        viewModel.player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                updatePipParams()
            }
        })

        setContent {
            InnerTuneTheme(darkTheme = true, pureBlack = true) {
                VideoPlayerScreen(
                    viewModel = viewModel,
                    isInPip = isInPip,
                    onFullscreenChange = ::setFullscreen,
                    onClose = ::finish,
                )
            }
        }
    }

    /** The fullscreen button locks to landscape; leaving it locks back to portrait, since the
     * phone is presumably still held sideways and would otherwise rotate straight back. */
    private fun setFullscreen(fullscreen: Boolean) {
        requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    override fun onResume() {
        super.onResume()
        applySystemBars(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun applySystemBars(fullscreen: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .apply {
                // Android 12+ can enter PiP straight from the home gesture, animated.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(viewModel.player.isPlaying)
            }
            .build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasPipFeature()) {
            pipParams()?.let { setPictureInPictureParams(it) }
        }
    }

    private fun hasPipFeature() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Before Android 12, PiP has to be entered by hand on the way out.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S &&
            hasPipFeature() && viewModel.player.isPlaying
        ) {
            pipParams()?.let { enterPictureInPictureMode(it) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        // Keeps playing in PiP; anything else leaving the screen pauses it.
        if (!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode)) {
            viewModel.player.pause()
        }
    }

    companion object {
        private const val EXTRA_VIDEOS = "videos"
        private const val EXTRA_SHELF_TITLE = "shelf_title"
        private const val EXTRA_START_INDEX = "start_index"

        private val gson = Gson()
        private val videoListType = object : TypeToken<List<VideoItem>>() {}.type

        /** Plays [videos] from [startIndex], stepping on through the rest. */
        fun intent(context: Context, shelfTitle: String, videos: List<ClubContent>, startIndex: Int): Intent =
            Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_VIDEOS, gson.toJson(videos.map(VideoItem::from)))
                .putExtra(EXTRA_SHELF_TITLE, shelfTitle)
                .putExtra(EXTRA_START_INDEX, startIndex)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPlayerScreen(
    viewModel: VideoPlayerViewModel,
    isInPip: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullscreen = isLandscape || isInPip
    val videos by viewModel.videos.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val loadState by viewModel.loadState.collectAsState()
    val shelfTitle by viewModel.shelfTitle.collectAsState()
    val current = videos.getOrNull(currentIndex)

    // Back out of fullscreen before out of the player.
    BackHandler(enabled = isLandscape && !isInPip) { onFullscreenChange(false) }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        if (fullscreen) {
            VideoSurface(
                viewModel = viewModel,
                loadState = loadState,
                showControls = !isInPip,
                isFullscreen = true,
                onFullscreenChange = onFullscreenChange,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    VideoSurface(
                        viewModel = viewModel,
                        loadState = loadState,
                        showControls = true,
                        isFullscreen = false,
                        onFullscreenChange = onFullscreenChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                    )
                    IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                            tint = Color.White,
                        )
                    }
                }
                VideoDetails(
                    shelfTitle = shelfTitle,
                    current = current,
                    videos = videos,
                    currentIndex = currentIndex,
                    onSelect = { viewModel.play(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surface),
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(
    viewModel: VideoPlayerViewModel,
    loadState: VideoLoadState,
    showControls: Boolean,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = viewModel.player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                    setShowSubtitleButton(true)
                    controllerAutoShow = true
                }
            },
            update = { view ->
                view.useController = showControls
                // The controller keeps its own fullscreen flag, which a rotation by hand never
                // updates - so the click's own idea of the new state is ignored for this
                // screen's, and the icon is put back to match it.
                view.setFullscreenButtonClickListener {
                    onFullscreenChange(!isFullscreen)
                    view.post { view.showFullscreenState(isFullscreen) }
                }
                view.showFullscreenState(isFullscreen)
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        when (loadState) {
            VideoLoadState.Loading -> CircularProgressIndicator(color = Color.White)
            is VideoLoadState.Failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(loadState.message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = viewModel::retry) { Text("Try again") }
            }
            VideoLoadState.Ready -> {}
        }
    }
}

@Composable
private fun VideoDetails(
    shelfTitle: String,
    current: VideoItem?,
    videos: List<VideoItem>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Keeps the playing video in view as the list auto-advances.
    LaunchedEffect(currentIndex) {
        if (videos.size > 1) listState.animateScrollToItem(UP_NEXT_HEADER_INDEX + 1 + currentIndex)
    }

    LazyColumn(state = listState, modifier = modifier) {
        item(key = "details") {
            var expanded by rememberSaveable(current?.id) { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = current?.description != null) { expanded = !expanded }
                    .animateContentSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = current?.title.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                val meta = listOfNotNull(
                    shelfTitle.takeIf { it.isNotBlank() },
                    current?.lengthMs?.let(::formatVideoLength),
                    "${currentIndex + 1} of ${videos.size}".takeIf { videos.size > 1 },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                current?.description?.let { description ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!expanded) {
                        Text(
                            text = "More",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        if (videos.size > 1) {
            item(key = "up_next_header") {
                HorizontalDivider()
                Text(
                    text = "Up next",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            itemsIndexed(videos, key = { _, video -> video.id }) { index, video ->
                UpNextRow(video = video, isCurrent = index == currentIndex, onClick = { onSelect(index) })
            }
        }
        item(key = "bottom_inset") {
            Spacer(Modifier.padding(bottom = 16.dp).windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/** The details item comes first, then the "Up next" header. */
private const val UP_NEXT_HEADER_INDEX = 1

@Composable
private fun UpNextRow(video: VideoItem, isCurrent: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrent) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(140.dp)
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isCurrent) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Icon(painterResource(R.drawable.play), contentDescription = null, tint = Color.White)
                }
            } else {
                formatVideoLength(video.lengthMs)?.let { length ->
                    VideoLengthBadge(length, Modifier.align(Alignment.BottomEnd).padding(4.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (isCurrent) {
                Text("Now playing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerView.showFullscreenState(fullscreen: Boolean) {
    val icon = if (fullscreen) {
        androidx.media3.ui.R.drawable.exo_styled_controls_fullscreen_exit
    } else {
        androidx.media3.ui.R.drawable.exo_styled_controls_fullscreen_enter
    }
    listOf(androidx.media3.ui.R.id.exo_fullscreen, androidx.media3.ui.R.id.exo_minimal_fullscreen).forEach { id ->
        findViewById<ImageView>(id)?.setImageResource(icon)
    }
}

/** A video's length over the bottom corner of its thumbnail. */
@Composable
fun VideoLengthBadge(length: String, modifier: Modifier = Modifier) {
    Text(
        text = length,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
