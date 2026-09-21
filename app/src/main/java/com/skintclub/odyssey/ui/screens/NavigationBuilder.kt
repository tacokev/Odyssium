package com.skintclub.odyssey.ui.screens

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.skintclub.odyssey.ui.screens.settings.AboutScreen
import com.skintclub.odyssey.ui.screens.settings.AccountSettings
import com.skintclub.odyssey.ui.screens.settings.AppearanceSettings
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
    composable(Screens.Home.route) {
        HomeScreen(navController, odysseyViewModel)
    }
    composable(Screens.Playlists.route) {
        OdysseyPlaylistsScreen()
    }
    composable(
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
    composable(
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
    composable("history") {
        HistoryScreen(navController)
    }
    composable("stats") {
        StatsScreen(navController)
    }
    composable("settings") {
        SettingsScreen(navController, scrollBehavior)
    }
    composable("settings/appearance") {
        AppearanceSettings(navController, scrollBehavior)
    }
    composable("settings/player") {
        PlayerSettings(navController, scrollBehavior)
    }
    composable("settings/randomizer") {
        RandomizerSettings(navController, scrollBehavior)
    }
    composable("settings/account") {
        AccountSettings(navController, scrollBehavior)
    }
    composable("settings/storage") {
        StorageSettings(navController, scrollBehavior)
    }
    composable("settings/about") {
        AboutScreen(navController, scrollBehavior)
    }
    composable("odyssey_login") {
        OdysseyLoginScreen(navController)
    }
    composable("odyssey_logout") {
        OdysseyLogoutScreen(navController)
    }
    composable("odyssey_profile_select") {
        OdysseyProfileSelectScreen(
            navController = navController,
            odysseyViewModel = odysseyViewModel,
        )
    }
}
