package com.skintclub.odyssey

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import kotlin.math.abs
import androidx.navigation.NavBackStackEntry
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.rememberNavController
import coil.imageLoader
import coil.request.ImageRequest
import com.skintclub.odyssey.club.ClubLink
import com.skintclub.odyssey.club.parseClubLink
import com.skintclub.odyssey.constants.*
import com.skintclub.odyssey.db.MusicDatabase
import com.skintclub.odyssey.extensions.*
import com.skintclub.odyssey.models.MediaMetadata
import com.skintclub.odyssey.playback.DownloadUtil
import com.skintclub.odyssey.playback.MusicService
import com.skintclub.odyssey.playback.MusicService.MusicBinder
import com.skintclub.odyssey.playback.PlayerConnection
import com.skintclub.odyssey.playback.queues.OdysseyQueue
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaItem
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaMetadata
import com.skintclub.odyssey.ui.component.*
import com.skintclub.odyssey.ui.component.shimmer.ShimmerTheme
import com.skintclub.odyssey.ui.menu.OdysseyEpisodeMenu
import com.skintclub.odyssey.ui.player.BottomSheetPlayer
import com.skintclub.odyssey.ui.video.VideoPlayerActivity
import com.skintclub.odyssey.ui.screens.*
import com.skintclub.odyssey.ui.screens.search.OdysseySearchResultsList
import com.skintclub.odyssey.ui.screens.settings.DarkMode
import com.skintclub.odyssey.ui.screens.settings.ThemeColorSource
import com.skintclub.odyssey.ui.screens.settings.rememberThemeColorSource
import com.skintclub.odyssey.ui.theme.ColorSaver
import com.skintclub.odyssey.ui.theme.DefaultThemeColor
import com.skintclub.odyssey.ui.theme.InnerTuneTheme
import com.skintclub.odyssey.ui.theme.extractThemeColor
import com.skintclub.odyssey.ui.utils.appBarScrollBehavior
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.ui.utils.resetHeightOffset
import com.skintclub.odyssey.utils.dataStore
import com.skintclub.odyssey.utils.get
import com.skintclub.odyssey.utils.rememberEnumPreference
import com.skintclub.odyssey.utils.rememberPreference
import com.skintclub.odyssey.viewmodels.ClubLinkTarget
import com.skintclub.odyssey.viewmodels.OdysseyViewModel
import com.valentinilk.shimmer.LocalShimmerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NAV_ANIM_MS = 300

// Going back always cross-fades, whether between tabs or out of a sub-screen.
private fun navPopEnter(): EnterTransition = fadeIn(tween(NAV_ANIM_MS))

private fun navPopExit(): ExitTransition = fadeOut(tween(NAV_ANIM_MS))

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var downloadUtil: DownloadUtil

    private var playerConnection by mutableStateOf<PlayerConnection?>(null)

    /** A club link the app was opened with (tapped anywhere on the phone, or shared to it),
     * waiting for the UI to open it. */
    private val pendingClubLink = MutableStateFlow<ClubLink?>(null)
    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                if (service is MusicBinder) {
                    playerConnection = PlayerConnection(this@MainActivity, service, database, lifecycleScope)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                playerConnection?.dispose()
                playerConnection = null
            }
        }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeClubLink(intent)
    }

    /** Queues [intent]'s club link, if it has one: a tapped link's URL, or a shared one's text. */
    private fun takeClubLink(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        // A shared link can arrive with a title or message around it.
        val link = parseClubLink(text) ?: text.split(Regex("""\s+""")).firstNotNullOfOrNull(::parseClubLink)
        if (link != null) pendingClubLink.value = link
    }

    override fun onStart() {
        super.onStart()
        startService(Intent(this, MusicService::class.java))
        bindService(Intent(this, MusicService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        unbindService(serviceConnection)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (dataStore.get(StopMusicOnTaskClearKey, false) && playerConnection?.isPlaying?.value == true && isFinishing) {
            stopService(Intent(this, MusicService::class.java))
            unbindService(serviceConnection)
            playerConnection = null
        }
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Only a fresh launch: after a rotation, savedInstanceState is set and the link that
        // opened the app has already been handled.
        if (savedInstanceState == null) takeClubLink(intent)
        // Android 13+ starts apps with notifications off. Without them there's no playback
        // notification, so no lock screen controls and the system freezes the app soon after
        // it leaves the screen, stopping the episode.
        if (savedInstanceState == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        lifecycleScope.launch {
            dataStore.data
                .map { it[DisableScreenshotKey] ?: false }
                .distinctUntilChanged()
                .collectLatest {
                    if (it) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE,
                        )
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
        }

        setContent {
            val themeColorSource by rememberThemeColorSource()
            val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
            val pureBlack by rememberPreference(PureBlackKey, defaultValue = false)
            val isSystemInDarkTheme = isSystemInDarkTheme()
            val useDarkTheme =
                remember(darkTheme, isSystemInDarkTheme) {
                    if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
                }
            LaunchedEffect(useDarkTheme) {
                setSystemBarAppearance(useDarkTheme)
            }
            var themeColor by rememberSaveable(stateSaver = ColorSaver) {
                mutableStateOf(DefaultThemeColor)
            }

            LaunchedEffect(playerConnection, themeColorSource, isSystemInDarkTheme) {
                val playerConnection = playerConnection
                if (themeColorSource != ThemeColorSource.ARTWORK || playerConnection == null) {
                    themeColor = themeColorSource.seed ?: DefaultThemeColor
                    return@LaunchedEffect
                }
                playerConnection.service.currentMediaMetadata.collectLatest { song ->
                    themeColor =
                        if (song != null) {
                            withContext(Dispatchers.IO) {
                                val result =
                                    imageLoader.execute(
                                        ImageRequest
                                            .Builder(this@MainActivity)
                                            .data(song.thumbnailUrl)
                                            .allowHardware(false) // pixel access is not supported on Config#HARDWARE bitmaps
                                            .build(),
                                    )
                                (result.drawable as? BitmapDrawable)?.bitmap?.extractThemeColor() ?: DefaultThemeColor
                            }
                        } else {
                            DefaultThemeColor
                        }
                }
            }

            InnerTuneTheme(
                darkTheme = useDarkTheme,
                pureBlack = pureBlack,
                themeColor = themeColor,
                // Only a wallpaper-based source falls back to the wallpaper's palette; a fixed
                // color (Default included) always builds from its own seed.
                useWallpaperColors = themeColorSource.seed == null,
            ) {
                BoxWithConstraints(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                ) {
                    val focusManager = LocalFocusManager.current
                    val density = LocalDensity.current
                    val windowsInsets = WindowInsets.systemBars
                    val bottomInset = with(density) { windowsInsets.getBottom(density).toDp() }
                    val topInset = with(density) { windowsInsets.getTop(density).toDp() }

                    // Pure black only blacks out `surface`/`background` (see ColorScheme.pureBlack),
                    // not surfaceContainer, so the top/bottom bars need their own override here.
                    val useBlackBars = useDarkTheme && pureBlack
                    val navigationBarColor = if (useBlackBars) Color.Black else NavigationBarDefaults.containerColor
                    val navigationBarTonalElevation = if (useBlackBars) 0.dp else NavigationBarDefaults.Elevation

                    val navController = rememberNavController()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()

                    val navigationItems = remember { Screens.MainScreens }
                    val compactNavigationBar by rememberPreference(CompactNavigationBarKey, defaultValue = false)

                    val topLevelScreens =
                        listOf(
                            Screens.Home.route,
                            Screens.Playlists.route,
                            "settings",
                        )

                    val (query, onQueryChange) =
                        rememberSaveable(stateSaver = TextFieldValue.Saver) {
                            mutableStateOf(TextFieldValue())
                        }
                    var active by rememberSaveable {
                        mutableStateOf(false)
                    }
                    val onActiveChange: (Boolean) -> Unit = { newActive ->
                        active = newActive
                        if (!newActive) {
                            focusManager.clearFocus()
                            if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                                onQueryChange(TextFieldValue())
                            }
                        }
                    }
                    val searchBarFocusRequester = remember { FocusRequester() }

                    val shouldShowNavigationBar =
                        remember(navBackStackEntry, active) {
                            navBackStackEntry?.destination?.route == null ||
                                (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route } && !active)
                        }
                    val fullNavigationBarHeight = if (compactNavigationBar) CompactNavigationBarHeight else NavigationBarHeight
                    val navigationBarHeight by animateDpAsState(
                        targetValue = if (shouldShowNavigationBar) fullNavigationBarHeight else 0.dp,
                        animationSpec = NavigationBarAnimationSpec,
                        label = "navigationBarHeight",
                    )

                    val navigationBarGap = if (shouldShowNavigationBar) MiniPlayerNavigationBarSpacing else 0.dp

                    val playerBottomSheetState =
                        rememberBottomSheetState(
                            dismissedBound = 0.dp,
                            collapsedBound =
                                bottomInset +
                                    (if (shouldShowNavigationBar) fullNavigationBarHeight else 0.dp) +
                                    navigationBarGap +
                                    MiniPlayerHeight,
                            expandedBound = maxHeight,
                        )

                    val playerAwareWindowInsets =
                        remember(bottomInset, shouldShowNavigationBar, fullNavigationBarHeight, navigationBarGap, playerBottomSheetState.isDismissed) {
                            var bottom = bottomInset
                            if (shouldShowNavigationBar) bottom += fullNavigationBarHeight
                            if (!playerBottomSheetState.isDismissed) bottom += MiniPlayerHeight + navigationBarGap
                            windowsInsets
                                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                                .add(WindowInsets(top = AppBarHeight, bottom = bottom))
                        }

                    val searchBarScrollBehavior =
                        appBarScrollBehavior(
                            canScroll = {
                                (playerBottomSheetState.isCollapsed || playerBottomSheetState.isDismissed)
                            },
                        )
                    val topAppBarScrollBehavior =
                        appBarScrollBehavior(
                            canScroll = {
                                (playerBottomSheetState.isCollapsed || playerBottomSheetState.isDismissed)
                            },
                        )

                    // Set here (rather than left for SearchBar to set) so the limit is
                    // already in place the first time the user scrolls, since SearchBar
                    // itself isn't composed until search is activated at least once.
                    val topActionsHeightOffsetLimit =
                        with(density) { -(AppBarHeight.toPx() + windowsInsets.getTop(density)) }
                    SideEffect {
                        if (searchBarScrollBehavior.state.heightOffsetLimit != topActionsHeightOffsetLimit) {
                            searchBarScrollBehavior.state.heightOffsetLimit = topActionsHeightOffsetLimit
                        }
                    }

                    LaunchedEffect(navBackStackEntry) {
                        searchBarScrollBehavior.state.resetHeightOffset()
                        topAppBarScrollBehavior.state.resetHeightOffset()
                    }
                    LaunchedEffect(active) {
                        if (active) {
                            searchBarScrollBehavior.state.resetHeightOffset()
                            topAppBarScrollBehavior.state.resetHeightOffset()
                            searchBarFocusRequester.requestFocus()
                        }
                    }

                    LaunchedEffect(playerConnection) {
                        val player = playerConnection?.player ?: return@LaunchedEffect
                        if (player.currentMediaItem == null) {
                            if (!playerBottomSheetState.isDismissed) {
                                playerBottomSheetState.dismiss()
                            }
                        } else {
                            if (playerBottomSheetState.isDismissed) {
                                playerBottomSheetState.collapseSoft()
                            }
                        }
                    }

                    DisposableEffect(playerConnection, playerBottomSheetState) {
                        val player = playerConnection?.player ?: return@DisposableEffect onDispose { }
                        val listener =
                            object : Player.Listener {
                                override fun onMediaItemTransition(
                                    mediaItem: MediaItem?,
                                    reason: Int,
                                ) {
                                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                                        mediaItem != null &&
                                        playerBottomSheetState.isDismissed
                                    ) {
                                        playerBottomSheetState.collapseSoft()
                                    }
                                }
                            }
                        player.addListener(listener)
                        onDispose {
                            player.removeListener(listener)
                        }
                    }

                    val coroutineScope = rememberCoroutineScope()

                    val odysseyViewModel: OdysseyViewModel = hiltViewModel()
                    val odysseyAlbums by odysseyViewModel.albums.collectAsState()
                    val odysseyNeedsRelogin by odysseyViewModel.needsRelogin.collectAsState()
                    if (odysseyNeedsRelogin) {
                        AlertDialog(
                            onDismissRequest = odysseyViewModel::onReloginHandled,
                            title = { Text("Login required") },
                            text = { Text("Your Club session has expired. Log in again to keep browsing episodes.") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        odysseyViewModel.onReloginHandled()
                                        navController.navigate("odyssey_login") {
                                            popUpTo(0)
                                        }
                                    },
                                ) {
                                    Text("Login")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = odysseyViewModel::onReloginHandled) {
                                    Text("Later")
                                }
                            },
                        )
                    }
                    val searchIndex by odysseyViewModel.searchIndex.collectAsState()
                    val searchResults = remember(searchIndex, query.text) { searchIndex.search(query.text) }

                    // The secret feature: pasting (or submitting) a CDN episode path/URL plays it
                    // right away and files it under Secret Episodes. False if [input] isn't one.
                    val playSecretEpisode: (String) -> Boolean = { input ->
                        val episode = odysseyViewModel.addSecretEpisode(input)
                        if (episode != null) {
                            playerConnection?.playQueue(OdysseyQueue(this@MainActivity, listOf(episode), 0))
                            Toast.makeText(this@MainActivity, R.string.added_to_secret_episodes, Toast.LENGTH_SHORT).show()
                            onQueryChange(TextFieldValue())
                            onActiveChange(false)
                        }
                        episode != null
                    }
                    val onSearchQueryChange: (TextFieldValue) -> Unit = { value ->
                        if (!playSecretEpisode(value.text)) onQueryChange(value)
                    }
                    // Opens where a club link leads. An episode is played when [playEpisode] (a
                    // link entered in search); otherwise (a link tapped elsewhere) an album
                    // episode opens its page, and only an episode without one is played.
                    suspend fun openClubLinkTarget(target: ClubLinkTarget, playEpisode: Boolean) {
                        // A link from outside the app starts afresh from Home, so Back from
                        // it goes Home (then out) rather than through whatever was open before.
                        fun open(route: String) {
                            navController.navigate(route) {
                                if (!playEpisode) {
                                    popUpTo(navController.graph.findStartDestination().id)
                                    launchSingleTop = true
                                }
                            }
                        }
                        suspend fun play(episodes: List<Map<String, Any>>, index: Int) {
                            // Opened cold by a link, the player service may still be binding.
                            val connection = snapshotFlow { playerConnection }.filterNotNull().first()
                            connection.playQueue(OdysseyQueue(this@MainActivity, episodes, index))
                        }
                        when (target) {
                            is ClubLinkTarget.AlbumEpisode ->
                                if (playEpisode) {
                                    play(target.episodes, target.index)
                                } else {
                                    open("odyssey_episode/${Uri.encode(target.albumName)}/${target.index}")
                                }
                            is ClubLinkTarget.Episode -> play(target.episodes, target.index)
                            is ClubLinkTarget.Video -> startActivity(
                                VideoPlayerActivity.intent(this@MainActivity, target.shelfTitle, target.videos, target.index),
                            )
                            is ClubLinkTarget.Comic ->
                                open("odyssey_comic/${Uri.encode(target.seriesId)}/${Uri.encode(target.contentId)}")
                            is ClubLinkTarget.Album -> open("odyssey_album/${Uri.encode(target.name)}")
                            is ClubLinkTarget.ComicSeries -> open("odyssey_comic_series/${Uri.encode(target.seriesId)}")
                            is ClubLinkTarget.Playlist -> open("odyssey_playlist/${Uri.encode(target.playlistId)}")
                            is ClubLinkTarget.Grouping -> open("odyssey_grouping/${Uri.encode(target.groupingId)}")
                            ClubLinkTarget.NotFound ->
                                Toast.makeText(this@MainActivity, "Couldn't open that link in the app", Toast.LENGTH_SHORT).show()
                        }
                    }

                    LaunchedEffect(Unit) {
                        pendingClubLink.filterNotNull().collect { link ->
                            pendingClubLink.value = null
                            openClubLinkTarget(odysseyViewModel.resolveClubLink(link), playEpisode = false)
                        }
                    }

                    // Enter plays the top result: the first episode, or failing that the whole
                    // first grouping. A club link plays the episode it points to.
                    val onSearchSubmit: (String) -> Unit = { text ->
                        val clubLink = parseClubLink(text)
                        if (clubLink != null) {
                            onQueryChange(TextFieldValue())
                            onActiveChange(false)
                            coroutineScope.launch {
                                openClubLinkTarget(odysseyViewModel.resolveClubLink(clubLink), playEpisode = true)
                            }
                        } else if (!playSecretEpisode(text)) {
                            val firstEpisode = searchResults.episodes.firstOrNull()
                            val queue = if (firstEpisode != null) {
                                OdysseyQueue(this@MainActivity, firstEpisode.queue, firstEpisode.queueIndex)
                            } else {
                                searchResults.groupings.firstOrNull()
                                    ?.let(odysseyViewModel::episodesOf)
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { OdysseyQueue(this@MainActivity, it, 0) }
                            }
                            if (queue != null) {
                                playerConnection?.playQueue(queue)
                                onActiveChange(false)
                            }
                        }
                    }

                    CompositionLocalProvider(
                        LocalDatabase provides database,
                        LocalContentColor provides contentColorFor(MaterialTheme.colorScheme.surface),
                        LocalPlayerConnection provides playerConnection,
                        LocalPlayerAwareWindowInsets provides playerAwareWindowInsets,
                        LocalDownloadUtil provides downloadUtil,
                        LocalShimmerTheme provides ShimmerTheme,
                        LocalTopBarColor provides navigationBarColor,
                    ) {
                        val randomizerHistory by odysseyViewModel.randomizerHistory.collectAsState()
                        // Swiping only steps through the history on the random episode page it
                        // belongs to, not on an episode page reached some other way (e.g. a
                        // recommendation).
                        val onRandomizerPage = navBackStackEntry?.destination?.route == ODYSSEY_EPISODE_ROUTE &&
                            randomizerHistory.current == navBackStackEntry?.arguments?.let {
                                it.getString("albumName") to it.getInt("episodeIndex")
                            }
                        // Whether the latest randomizer page change went back, so it slides in
                        // from the left like the swipe that triggered it.
                        var randomizerSteppedBack by remember { mutableStateOf(false) }
                        val openRandomizerPick: (Pair<String, Int>, Boolean) -> Unit = { (albumName, episodeIndex), back ->
                            randomizerSteppedBack = back
                            navController.navigate("odyssey_episode/${Uri.encode(albumName)}/$episodeIndex") {
                                popUpTo(ODYSSEY_EPISODE_ROUTE) { inclusive = true }
                            }
                        }
                        val gestureInsets = WindowInsets.systemGestures
                        val layoutDirection = LocalLayoutDirection.current

                        NavHost(
                            navController = navController,
                            startDestination = Screens.Home.route,
                            // Top-level tabs cross-fade; anything else slides in fully over the
                            // previous screen, which drifts back a little underneath it. Going back
                            // cross-fades.
                            enterTransition = {
                                if (initialState.destination.route in topLevelScreens && targetState.destination.route in topLevelScreens) {
                                    fadeIn(tween(NAV_ANIM_MS))
                                } else if (randomizerSteppedBack && isRandomizerStep()) {
                                    slideInHorizontally(tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) { -it }
                                } else {
                                    slideInHorizontally(tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) { it }
                                }
                            },
                            exitTransition = {
                                if (initialState.destination.route in topLevelScreens && targetState.destination.route in topLevelScreens) {
                                    fadeOut(tween(NAV_ANIM_MS))
                                } else if (randomizerSteppedBack && isRandomizerStep()) {
                                    slideOutHorizontally(tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) { it / 4 }
                                } else {
                                    slideOutHorizontally(tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) { -it / 4 }
                                }
                            },
                            popEnterTransition = { navPopEnter() },
                            popExitTransition = { navPopExit() },
                            // The back gesture would otherwise use Navigation's default preview (the
                            // screen shrinking), then jump to the pop transition on release.
                            predictivePopEnterTransition = { navPopEnter() },
                            predictivePopExitTransition = { navPopExit() },
                            modifier =
                                Modifier
                                    .nestedScroll(
                                        if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                                            searchBarScrollBehavior.nestedScrollConnection
                                        } else {
                                            topAppBarScrollBehavior.nestedScrollConnection
                                        },
                                    )
                                    .then(
                                        if (onRandomizerPage) {
                                            Modifier.randomizerHistorySwipe(gestureInsets, layoutDirection) { steps ->
                                                odysseyViewModel.stepRandomizerHistory(steps)
                                                    ?.let { openRandomizerPick(it, steps < 0) }
                                            }
                                        } else {
                                            Modifier
                                        },
                                    ),
                        ) {
                            navigationBuilder(navController, topAppBarScrollBehavior, odysseyViewModel)
                        }

                        // The random-episode button lives here, above NavHost, as a sibling
                        // of the animated route content rather than inside each screen's own
                        // composable - otherwise it got swept up in NavHost's horizontal slide
                        // transition along with the episode content every time a new random
                        // episode replaced the current one.
                        val excludeBonusEpisodes by rememberPreference(ExcludeBonusEpisodesKey, defaultValue = true)
                        val excludeSubsequentParts by rememberPreference(ExcludeSubsequentPartsKey, defaultValue = true)
                        val randomizerListMode by rememberEnumPreference(RandomizerListModeKey, defaultValue = RandomizerListMode.OFF)
                        val randomizerPlaylistId by rememberPreference(RandomizerPlaylistIdKey, defaultValue = "")
                        val randomizerPlayOnPress by rememberPreference(RandomizerPlayOnPressKey, defaultValue = false)
                        val randomizerNonRepeating by rememberPreference(RandomizerNonRepeatingKey, defaultValue = true)
                        var randomizerDiceFace by remember { mutableStateOf((1..6).random()) }

                        AnimatedVisibility(
                            visible = navBackStackEntry?.destination?.route == Screens.Home.route ||
                                navBackStackEntry?.destination?.route == ODYSSEY_ALBUMS_ROUTE ||
                                navBackStackEntry?.destination?.route == ODYSSEY_EPISODE_ROUTE,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.BottomEnd),
                        ) {
                            FloatingActionButton(
                                onClick = {
                                    val (albumName, episodeIndex) =
                                        odysseyViewModel.pickRandomEpisode(
                                            excludeBonusEpisodes,
                                            excludeSubsequentParts,
                                            randomizerListMode,
                                            randomizerPlaylistId,
                                            randomizerNonRepeating,
                                        ) ?: return@FloatingActionButton
                                    randomizerDiceFace = (1..6).random()
                                    if (randomizerPlayOnPress) {
                                        val album = odysseyAlbums.find { it.name == albumName }
                                        if (album != null && album.episodes.getOrNull(episodeIndex) != null) {
                                            playerConnection?.playQueue(
                                                OdysseyQueue(
                                                    context = this@MainActivity,
                                                    episodes = album.episodes,
                                                    startIndex = episodeIndex,
                                                ),
                                            )
                                        }
                                    } else {
                                        odysseyViewModel.recordRandomPick(albumName, episodeIndex)
                                        openRandomizerPick(albumName to episodeIndex, false)
                                    }
                                },
                                modifier = Modifier
                                    .windowInsetsPadding(playerAwareWindowInsets.only(WindowInsetsSides.Bottom))
                                    .padding(16.dp),
                            ) {
                                Icon(
                                    painter = painterResource(diceFaceDrawable(randomizerDiceFace)),
                                    contentDescription = null,
                                )
                            }
                        }

                        // Scrim behind the status bar so its icons stay legible over whatever
                        // scrolls up underneath, instead of the transparent edge-to-edge status
                        // bar showing busy content straight through. Skipped on the episode
                        // details page so its cover art reads as a clean, unbroken poster.
                        AnimatedVisibility(
                            visible = navBackStackEntry?.destination?.route != ODYSSEY_EPISODE_ROUTE,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.TopCenter),
                        ) {
                            Surface(
                                color = navigationBarColor,
                                tonalElevation = navigationBarTonalElevation,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(topInset),
                            ) {}
                        }

                        AnimatedVisibility(
                            visible = active,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            SearchBar(
                                query = query,
                                onQueryChange = onSearchQueryChange,
                                onSearch = onSearchSubmit,
                                active = active,
                                onActiveChange = onActiveChange,
                                scrollBehavior = searchBarScrollBehavior,
                                placeholder = {
                                    Text(text = stringResource(R.string.search))
                                },
                                leadingIcon = {
                                    IconButton(
                                        onClick = { onActiveChange(false) },
                                        onLongClick = {},
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.arrow_back),
                                            contentDescription = null,
                                        )
                                    }
                                },
                                focusRequester = searchBarFocusRequester,
                                modifier = Modifier.align(Alignment.TopCenter),
                            ) {
                                OdysseySearchResultsList(
                                    results = searchResults,
                                    navController = navController,
                                    viewModel = odysseyViewModel,
                                    onDismiss = { onActiveChange(false) },
                                )
                            }
                        }

                        BottomSheetPlayer(
                            state = playerBottomSheetState,
                            navController = navController,
                            odysseyViewModel = odysseyViewModel,
                        )

                        Surface(
                            color = navigationBarColor,
                            tonalElevation = navigationBarTonalElevation,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .offset {
                                        if (navigationBarHeight == 0.dp) {
                                            IntOffset(
                                                x = 0,
                                                y = (bottomInset + fullNavigationBarHeight).roundToPx(),
                                            )
                                        } else {
                                            val slideOffset =
                                                (bottomInset + fullNavigationBarHeight) *
                                                    playerBottomSheetState.progress.coerceIn(
                                                        0f,
                                                        1f,
                                                    )
                                            val hideOffset =
                                                (bottomInset + fullNavigationBarHeight) *
                                                    (1 - navigationBarHeight / fullNavigationBarHeight)
                                            IntOffset(
                                                x = 0,
                                                y = (slideOffset + hideOffset).roundToPx(),
                                            )
                                        }
                                    },
                        ) {
                            val isHomeSelected =
                                navBackStackEntry?.destination?.hierarchy?.any { it.route == Screens.Home.route } == true
                            val isPlaylistsSelected =
                                navBackStackEntry?.destination?.hierarchy?.any { it.route == Screens.Playlists.route } == true
                            val navigateHome = {
                                if (!isHomeSelected) {
                                    navController.navigate(Screens.Home.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                            val navigatePlaylists = {
                                if (!isPlaylistsSelected) {
                                    navController.navigate(Screens.Playlists.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                            val activateSearch = { onActiveChange(true) }

                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .windowInsetsPadding(windowsInsets.only(WindowInsetsSides.Bottom))
                                        .height(fullNavigationBarHeight),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (compactNavigationBar) {
                                    CompactNavigationBarButton(
                                        selected = isHomeSelected,
                                        iconRes = R.drawable.home,
                                        onClick = navigateHome,
                                    )
                                    CompactNavigationBarButton(
                                        selected = active,
                                        iconRes = R.drawable.search,
                                        onClick = activateSearch,
                                    )
                                    CompactNavigationBarButton(
                                        selected = isPlaylistsSelected,
                                        iconRes = R.drawable.queue_music,
                                        onClick = navigatePlaylists,
                                    )
                                } else {
                                    NavigationBarItem(
                                        selected = isHomeSelected,
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.home),
                                                contentDescription = null,
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = stringResource(R.string.home),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        onClick = navigateHome,
                                    )

                                    NavigationBarItem(
                                        selected = active,
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.search),
                                                contentDescription = null,
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = stringResource(R.string.search),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        onClick = activateSearch,
                                    )

                                    NavigationBarItem(
                                        selected = isPlaylistsSelected,
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.queue_music),
                                                contentDescription = null,
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = stringResource(R.string.filter_library),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        onClick = navigatePlaylists,
                                    )
                                }
                            }
                        }

                        BottomSheetMenu(
                            state = LocalMenuState.current,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun setSystemBarAppearance(isDark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView.rootView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            window.statusBarColor = (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            window.navigationBarColor = (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
    }
}

/** Icon-only bottom nav button for [CompactNavigationBarKey]. Unlike [NavigationBarItem] its
 * whole footprint is a centered 48dp circle, so it stays vertically centered no matter how
 * short the bar is — [NavigationBarItem] instead assumes room for a label under the icon and
 * top-aligns within whatever height it's given, which looks off once that height is compact. */
@Composable
private fun CompactNavigationBarButton(
    selected: Boolean,
    iconRes: Int,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable(onClick = onClick),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The random-episode button's face, re-rolled on every press to sell the "randomizer" bit
 * instead of always showing the same static die face. */
private fun diceFaceDrawable(face: Int): Int =
    when (face) {
        1 -> R.drawable.dice_1
        2 -> R.drawable.dice_2
        3 -> R.drawable.dice_3
        4 -> R.drawable.dice_4
        5 -> R.drawable.dice_5
        else -> R.drawable.dice_6
    }

val LocalDatabase = staticCompositionLocalOf<MusicDatabase> { error("No database provided") }
val LocalPlayerConnection = staticCompositionLocalOf<PlayerConnection?> { error("No PlayerConnection provided") }
val LocalPlayerAwareWindowInsets = compositionLocalOf<WindowInsets> { error("No WindowInsets provided") }
val LocalDownloadUtil = staticCompositionLocalOf<DownloadUtil> { error("No DownloadUtil provided") }

/** The status-bar strip's color (black in pure-black mode, otherwise the navigation bar's
 * container color), so screen top bars that slide up behind it can match it exactly. */
val LocalTopBarColor = compositionLocalOf { Color.Unspecified }

/** Whether this transition is the randomizer moving from one episode page to another. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isRandomizerStep() =
    initialState.destination.route == ODYSSEY_EPISODE_ROUTE && targetState.destination.route == ODYSSEY_EPISODE_ROUTE

/** How far a swipe has to travel to step through the randomizer history. */
private val RandomizerSwipeDistance = 96.dp

/** Minimum distance from either screen edge a swipe has to start at, on top of the system's own
 * edge-gesture zone, so it never competes with Android's back gesture. */
private val RandomizerSwipeEdgeMargin = 24.dp

/**
 * Swiping across a random episode page steps through the randomizer history: left to right goes
 * back ([onSwipe] with -1), right to left goes forward (1). Swipes that start in the system's
 * edge-gesture zones are ignored, so Android's back gesture still works there. Horizontally
 * scrolling rows on the page (characters, recommendations) handle their own swipes first, and a
 * mostly vertical drag scrolls the page as usual.
 */
private fun Modifier.randomizerHistorySwipe(
    gestureInsets: WindowInsets,
    layoutDirection: LayoutDirection,
    onSwipe: (steps: Int) -> Unit,
): Modifier = pointerInput(Unit) {
    var tracking = false
    var distance = 0f
    detectHorizontalDragGestures(
        onDragStart = { start ->
            val margin = RandomizerSwipeEdgeMargin.toPx()
            val left = gestureInsets.getLeft(this, layoutDirection) + margin
            val right = size.width - gestureInsets.getRight(this, layoutDirection) - margin
            tracking = start.x in left..right
            distance = 0f
        },
        onDragEnd = {
            if (tracking && abs(distance) >= RandomizerSwipeDistance.toPx()) onSwipe(if (distance > 0) -1 else 1)
            tracking = false
        },
        onDragCancel = { tracking = false },
    ) { change, dragAmount ->
        if (tracking) {
            distance += dragAmount
            change.consume()
        }
    }
}
