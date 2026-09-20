package com.skintclub.odyssey

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.navigation.compose.rememberNavController
import coil.imageLoader
import coil.request.ImageRequest
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
import com.skintclub.odyssey.ui.screens.*
import com.skintclub.odyssey.ui.screens.settings.DarkMode
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
import com.skintclub.odyssey.viewmodels.OdysseyViewModel
import com.valentinilk.shimmer.LocalShimmerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var downloadUtil: DownloadUtil

    private var playerConnection by mutableStateOf<PlayerConnection?>(null)
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
            val enableDynamicTheme by rememberPreference(DynamicThemeKey, defaultValue = true)
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

            LaunchedEffect(playerConnection, enableDynamicTheme, isSystemInDarkTheme) {
                val playerConnection = playerConnection
                if (!enableDynamicTheme || playerConnection == null) {
                    themeColor = DefaultThemeColor
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

                    val shouldShowTopActions =
                        remember(active, navBackStackEntry) {
                            !active && navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }
                        }
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

                    // Drives the sort/settings icons sliding out on scroll below. Set here
                    // (rather than left for SearchBar to set) so the limit is already in
                    // place the first time the user scrolls, since SearchBar itself isn't
                    // composed until search is activated at least once.
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
                    val odysseySignedCookie by odysseyViewModel.signedCookie.collectAsState()
                    val odysseyNeedsRelogin by odysseyViewModel.needsRelogin.collectAsState()
                    LaunchedEffect(odysseyNeedsRelogin) {
                        if (odysseyNeedsRelogin) {
                            navController.navigate("odyssey_login") {
                                popUpTo(0)
                            }
                            odysseyViewModel.onReloginHandled()
                        }
                    }
                    val odysseySortDescending by odysseyViewModel.sortDescending.collectAsState()
                    val searchResults =
                        remember(odysseyAlbums, query.text) {
                            val q = query.text.trim()
                            if (q.isEmpty()) {
                                emptyList()
                            } else {
                                odysseyAlbums.flatMap { album ->
                                    album.episodes.mapIndexedNotNull { index, episode ->
                                        val title = episode["name"] as? String ?: return@mapIndexedNotNull null
                                        if (title.contains(q, ignoreCase = true) ||
                                            album.name.contains(q, ignoreCase = true)
                                        ) {
                                            Triple(album, index, episode)
                                        } else {
                                            null
                                        }
                                    }
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
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = Screens.Home.route,
                            enterTransition = {
                                if (initialState.destination.route in topLevelScreens && targetState.destination.route in topLevelScreens) {
                                    fadeIn(tween(250))
                                } else {
                                    fadeIn(tween(250)) + slideInHorizontally { it / 2 }
                                }
                            },
                            exitTransition = {
                                if (initialState.destination.route in topLevelScreens && targetState.destination.route in topLevelScreens) {
                                    fadeOut(tween(200))
                                } else {
                                    fadeOut(tween(200)) + slideOutHorizontally { -it / 2 }
                                }
                            },
                            popEnterTransition = {
                                if (initialState.destination.route in topLevelScreens &&
                                    targetState.destination.route in topLevelScreens
                                ) {
                                    fadeIn(tween(250))
                                } else {
                                    fadeIn(tween(250)) + slideInHorizontally { -it / 2 }
                                }
                            },
                            popExitTransition = {
                                if (initialState.destination.route in topLevelScreens &&
                                    targetState.destination.route in topLevelScreens
                                ) {
                                    fadeOut(tween(200))
                                } else {
                                    fadeOut(tween(200)) + slideOutHorizontally { it / 2 }
                                }
                            },
                            modifier =
                                Modifier.nestedScroll(
                                    if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                                        searchBarScrollBehavior.nestedScrollConnection
                                    } else {
                                        topAppBarScrollBehavior.nestedScrollConnection
                                    },
                                ),
                        ) {
                            navigationBuilder(navController, topAppBarScrollBehavior, odysseyViewModel)
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
                            visible = shouldShowTopActions,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset {
                                        IntOffset(x = 0, y = searchBarScrollBehavior.state.heightOffset.roundToInt())
                                    },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier
                                        .windowInsetsPadding(windowsInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                                        .height(AppBarHeight)
                                        .padding(horizontal = 4.dp),
                            ) {
                                if (navBackStackEntry?.destination?.route == Screens.Home.route) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier =
                                            Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    odysseyViewModel.toggleSortOrder()
                                                },
                                    ) {
                                        Icon(
                                            painter =
                                                painterResource(
                                                    if (odysseySortDescending) {
                                                        R.drawable.arrow_upward
                                                    } else {
                                                        R.drawable.arrow_downward
                                                    },
                                                ),
                                            contentDescription = null,
                                        )
                                    }
                                }

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier =
                                        Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                navController.navigate("settings")
                                            },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.settings),
                                        contentDescription = null,
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = active,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            SearchBar(
                                query = query,
                                onQueryChange = onQueryChange,
                                onSearch = { },
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
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(
                                        items = searchResults,
                                        key = { (album, index, _) -> "${album.name}_$index" },
                                    ) { (album, index, episode) ->
                                        val metadata = odysseyEpisodeToMediaMetadata(episode)
                                        val mediaItem = odysseyEpisodeToMediaItem(episode, odysseySignedCookie)
                                        val episodeMenuState = LocalMenuState.current

                                        MediaMetadataListItem(
                                            mediaMetadata = metadata,
                                            trailingContent = {
                                                IconButton(
                                                    onClick = {
                                                        episodeMenuState.show {
                                                            OdysseyEpisodeMenu(
                                                                navController = navController,
                                                                mediaMetadata = metadata,
                                                                mediaItem = mediaItem,
                                                                albumName = album.name,
                                                                episodeIndex = index,
                                                                onDismiss = episodeMenuState::dismiss,
                                                            )
                                                        }
                                                    },
                                                    onLongClick = {},
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.more_vert),
                                                        contentDescription = null,
                                                    )
                                                }
                                            },
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        playerConnection?.playQueue(
                                                            OdysseyQueue(
                                                                episodes = album.episodes,
                                                                startIndex = index,
                                                                signedCookie = odysseySignedCookie,
                                                            ),
                                                        )
                                                        onActiveChange(false)
                                                    },
                                        )
                                    }
                                }
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
                                                text = stringResource(R.string.albums),
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
                                                text = stringResource(R.string.playlists),
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

val LocalDatabase = staticCompositionLocalOf<MusicDatabase> { error("No database provided") }
val LocalPlayerConnection = staticCompositionLocalOf<PlayerConnection?> { error("No PlayerConnection provided") }
val LocalPlayerAwareWindowInsets = compositionLocalOf<WindowInsets> { error("No WindowInsets provided") }
val LocalDownloadUtil = staticCompositionLocalOf<DownloadUtil> { error("No DownloadUtil provided") }
