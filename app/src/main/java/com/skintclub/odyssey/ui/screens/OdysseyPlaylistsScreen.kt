package com.skintclub.odyssey.ui.screens

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.SECRET_EPISODES_ROUTE
import com.skintclub.odyssey.club.SECRET_EPISODES_TITLE
import com.skintclub.odyssey.constants.ShowClubPlaylistsKey
import com.skintclub.odyssey.ui.menu.CreatePlaylistDialog
import com.skintclub.odyssey.ui.utils.appBarScrollBehavior
import com.skintclub.odyssey.ui.utils.collapsingTopAppBarColors
import com.skintclub.odyssey.utils.rememberPreference
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

/** Fixed, always-present tiles shown above the user's own cached playlists. */
private enum class PinnedPlaylist(val route: String, val title: String, val subtitle: String, val icon: Int) {
    LIKED("odyssey_liked", "Liked", "Bookmarked episodes", R.drawable.favorite),
    DOWNLOADED("odyssey_downloaded", "Downloaded", "Downloaded episodes", R.drawable.download),
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OdysseyPlaylistsScreen(
    navController: NavController,
    viewModel: OdysseyViewModel = hiltViewModel(),
) {
    val haptic = LocalHapticFeedback.current
    val playlists by viewModel.playlists.collectAsState()
    val showClubPlaylists by rememberPreference(ShowClubPlaylistsKey, defaultValue = true)
    // Club playlists hide while "Show club playlists in library" is off; ones made in the app always show.
    val userPlaylists = remember(playlists, showClubPlaylists) {
        playlists.filter { it.isUserCreated && (showClubPlaylists || it.isLocal) }
    }
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                viewModel.createPlaylist(name)
                showCreateDialog = false
            },
        )
    }
    val secretEpisodes by viewModel.secretEpisodes.collectAsState()
    val scrollBehavior = appBarScrollBehavior()

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom)
            .asPaddingValues(),
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) {
        items(
            items = PinnedPlaylist.entries,
            key = { it.route },
        ) { pinned ->
            CoverGridTile(
                title = pinned.title,
                subtitle = pinned.subtitle,
                thumbnailUrl = null,
                icon = {
                    Icon(
                        painter = painterResource(pinned.icon),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                    )
                },
                modifier = Modifier.combinedClickable(
                    onClick = { navController.navigate(pinned.route) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                ),
            )
        }

        // Stays a secret until the first one is added from the search bar.
        if (secretEpisodes.isNotEmpty()) {
            item(key = SECRET_EPISODES_ROUTE) {
                CoverGridTile(
                    title = SECRET_EPISODES_TITLE,
                    subtitle = "${secretEpisodes.size} episodes",
                    thumbnailUrl = null,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.lock),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                        )
                    },
                    modifier = Modifier.combinedClickable(
                        onClick = { navController.navigate(SECRET_EPISODES_ROUTE) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                    ),
                )
            }
        }

        items(
            items = userPlaylists,
            key = { it.id },
        ) { playlist ->
            CoverGridTile(
                title = playlist.name,
                subtitle = "${playlist.episodeCount} episodes",
                thumbnailUrl = playlist.imageUrl,
                modifier = Modifier.combinedClickable(
                    onClick = {
                        navController.navigate("odyssey_playlist/${Uri.encode(playlist.id)}")
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                ),
            )
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.filter_library)) },
        actions = {
            IconButton(onClick = { showCreateDialog = true }) {
                Icon(
                    painter = painterResource(R.drawable.add),
                    contentDescription = "New playlist",
                )
            }
            IconButton(onClick = { navController.navigate("stats") }) {
                Icon(
                    painter = painterResource(R.drawable.trending_up),
                    contentDescription = stringResource(R.string.stats),
                )
            }
            IconButton(onClick = { navController.navigate("history") }) {
                Icon(
                    painter = painterResource(R.drawable.history),
                    contentDescription = stringResource(R.string.history),
                )
            }
            IconButton(onClick = { navController.navigate("settings") }) {
                Icon(
                    painter = painterResource(R.drawable.settings),
                    contentDescription = null,
                )
            }
        },
        scrollBehavior = scrollBehavior,
        colors = collapsingTopAppBarColors(),
    )
}
