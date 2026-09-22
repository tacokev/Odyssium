package com.skintclub.odyssey.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

@Composable
fun OdysseyAlbumScreen(
    navController: NavController,
    albumName: String,
    viewModel: OdysseyViewModel,
) {
    val albums by viewModel.albums.collectAsState()
    val groupings by viewModel.groupings.collectAsState()
    val episodes = remember(albums, albumName) { albums.find { it.name == albumName }?.episodes.orEmpty() }
    val grouping = remember(groupings, albumName) { groupings.firstOrNull { it.type == "Album" && it.name == albumName } }

    OdysseyEpisodeListScreen(navController, viewModel, albumName, episodes, grouping)
}
