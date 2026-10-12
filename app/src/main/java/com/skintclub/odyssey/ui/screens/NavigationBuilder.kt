package com.skintclub.odyssey.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.composable
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.skintclub.odyssey.club.ODYSSEY_COMICS_ROUTE
import com.skintclub.odyssey.club.ODYSSEY_COMIC_ROUTE
import com.skintclub.odyssey.club.ODYSSEY_COMIC_SERIES_ROUTE
import com.skintclub.odyssey.club.ODYSSEY_VIDEOS_ROUTE
import com.skintclub.odyssey.club.ODYSSEY_VIDEO_SHELF_ROUTE
import com.skintclub.odyssey.club.SECRET_EPISODES_ROUTE
import com.skintclub.odyssey.ui.screens.settings.AboutScreen
import com.skintclub.odyssey.ui.screens.settings.AccountSettings
import com.skintclub.odyssey.ui.screens.settings.AppearanceSettings
import com.skintclub.odyssey.ui.screens.settings.ClubContentSettings
import com.skintclub.odyssey.ui.screens.settings.PlayerSettings
import com.skintclub.odyssey.ui.screens.settings.RandomizerSettings
import com.skintclub.odyssey.ui.screens.settings.SettingsScreen
import com.skintclub.odyssey.ui.screens.settings.StorageSettings
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

/** Route pattern for [OdysseyEpisodeDetailsScreen], shared with its `popUpTo` callers so
 * repeated shuffles replace the current episode screen instead of stacking a new one each
 * time — back should return to wherever the shuffle chain started, not step through it. */
const val ODYSSEY_EPISODE_ROUTE = "odyssey_episode/{albumName}/{episodeIndex}"

@OptIn(ExperimentalMaterial3Api::class)
fun NavGraphBuilder.navigationBuilder(
    navController: NavHostController,
    scrollBehavior: TopAppBarScrollBehavior,
    odysseyViewModel: OdysseyViewModel,
) {
    screen(Screens.Home.route) {
        HomeScreen(navController, odysseyViewModel)
    }
    screen(ODYSSEY_ALBUMS_ROUTE) {
        OdysseyAlbumsScreen(navController, odysseyViewModel)
    }
    screen("odyssey_episodes") {
        OdysseyAllEpisodesScreen(navController, odysseyViewModel)
    }
    screen("odyssey_series") {
        OdysseySeriesScreen(navController, odysseyViewModel)
    }
    screen("odyssey_collections") {
        OdysseyCollectionsScreen(navController, odysseyViewModel)
    }
    screen("odyssey_podcasts") {
        OdysseyPodcastsScreen(navController, odysseyViewModel)
    }
    screen(ODYSSEY_COMICS_ROUTE) {
        OdysseyComicsScreen(navController, odysseyViewModel)
    }
    screen(
        ODYSSEY_COMIC_SERIES_ROUTE,
        arguments = listOf(navArgument("groupingId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val groupingId = backStackEntry.arguments?.getString("groupingId").orEmpty()
        OdysseyComicSeriesScreen(
            navController = navController,
            viewModel = odysseyViewModel,
            groupingId = groupingId,
        )
    }
    screen(
        ODYSSEY_COMIC_ROUTE,
        arguments = listOf(
            navArgument("groupingId") { type = NavType.StringType },
            navArgument("contentId") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        OdysseyComicReaderScreen(
            navController = navController,
            viewModel = odysseyViewModel,
            groupingId = backStackEntry.arguments?.getString("groupingId").orEmpty(),
            contentId = backStackEntry.arguments?.getString("contentId").orEmpty(),
        )
    }
    screen(ODYSSEY_VIDEOS_ROUTE) {
        OdysseyVideosScreen(navController, odysseyViewModel)
    }
    screen(
        ODYSSEY_VIDEO_SHELF_ROUTE,
        arguments = listOf(navArgument("shelfId") { type = NavType.StringType }),
    ) { backStackEntry ->
        OdysseyVideoShelfScreen(
            navController = navController,
            viewModel = odysseyViewModel,
            shelfId = backStackEntry.arguments?.getString("shelfId").orEmpty(),
        )
    }
    screen(
        ODYSSEY_GROUPING_ROUTE,
        arguments = listOf(navArgument("groupingId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val groupingId = backStackEntry.arguments?.getString("groupingId").orEmpty()
        OdysseyGroupingScreen(
            navController = navController,
            viewModel = odysseyViewModel,
            groupingId = groupingId,
        )
    }
    screen(Screens.Playlists.route) {
        OdysseyPlaylistsScreen(navController, odysseyViewModel)
    }
    screen("odyssey_liked") {
        OdysseyLikedScreen(navController, odysseyViewModel)
    }
    screen("odyssey_downloaded") {
        OdysseyDownloadedScreen(navController, odysseyViewModel)
    }
    screen(SECRET_EPISODES_ROUTE) {
        OdysseySecretEpisodesScreen(navController, odysseyViewModel)
    }
    screen(
        "odyssey_playlist/{playlistId}",
        arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val playlistId = backStackEntry.arguments?.getString("playlistId").orEmpty()
        OdysseyPlaylistScreen(
            navController = navController,
            viewModel = odysseyViewModel,
            playlistId = playlistId,
        )
    }
    screen(
        "odyssey_playlist_edit/{playlistId}",
        arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val playlistId = backStackEntry.arguments?.getString("playlistId").orEmpty()
        OdysseyPlaylistEditScreen(
            navController = navController,
            viewModel = odysseyViewModel,
            playlistId = playlistId,
        )
    }
    screen(
        "odyssey_album/{albumName}",
        arguments = listOf(navArgument("albumName") { type = NavType.StringType }),
    ) { backStackEntry ->
        val albumName = backStackEntry.arguments?.getString("albumName").orEmpty()
        OdysseyAlbumScreen(
            navController = navController,
            albumName = albumName,
            viewModel = odysseyViewModel,
        )
    }
    screen(
        ODYSSEY_EPISODE_ROUTE,
        arguments = listOf(
            navArgument("albumName") { type = NavType.StringType },
            navArgument("episodeIndex") { type = NavType.IntType },
        ),
    ) { backStackEntry ->
        val albumName = backStackEntry.arguments?.getString("albumName").orEmpty()
        val episodeIndex = backStackEntry.arguments?.getInt("episodeIndex") ?: 0
        OdysseyEpisodeDetailsScreen(
            navController = navController,
            viewModel = odysseyViewModel,
            albumName = albumName,
            episodeIndex = episodeIndex,
        )
    }
    screen("history") {
        HistoryScreen(navController, odysseyViewModel)
    }
    screen("stats") {
        StatsScreen(navController)
    }
    screen("settings") {
        SettingsScreen(navController, scrollBehavior)
    }
    screen("settings/appearance") {
        AppearanceSettings(navController, scrollBehavior)
    }
    screen("settings/player") {
        PlayerSettings(navController, scrollBehavior)
    }
    screen("settings/randomizer") {
        RandomizerSettings(navController, scrollBehavior)
    }
    screen("settings/content") {
        ClubContentSettings(navController, scrollBehavior)
    }
    screen("settings/account") {
        AccountSettings(navController, scrollBehavior)
    }
    screen("settings/storage") {
        StorageSettings(navController, scrollBehavior, odysseyViewModel)
    }
    screen("settings/about") {
        AboutScreen(navController, scrollBehavior)
    }
    screen("odyssey_login") {
        OdysseyLoginScreen(navController)
    }
    screen("odyssey_logout") {
        OdysseyLogoutScreen(navController)
    }
    screen("odyssey_profile_select") {
        OdysseyProfileSelectScreen(
            navController = navController,
            odysseyViewModel = odysseyViewModel,
        )
    }
}

/** A destination with an opaque background, so screens sliding over each other during
 * navigation (and the predictive back gesture) don't show through one another. */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(route, arguments) { backStackEntry ->
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        content(backStackEntry)
    }
}
