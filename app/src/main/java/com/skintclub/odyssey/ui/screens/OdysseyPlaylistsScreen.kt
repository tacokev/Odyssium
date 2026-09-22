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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
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
    val userPlaylists = remember(playlists) { playlists.filter { it.isUserCreated } }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom)
            .asPaddingValues(),
        modifier = Modifier.fillMaxSize(),
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
}
