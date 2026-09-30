package com.skintclub.odyssey.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.skintclub.odyssey.club.isSecretEpisodeId
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

/** Whether episode [episodeId] is liked: its club bookmark, or for a secret episode its local
 * flag - the same state [OdysseyViewModel.setEpisodeLiked] sets and the player menu's "Like"
 * reads. Read from the groupings rather than the albums so podcasts (in no album) count too. */
@Composable
fun rememberEpisodeLiked(viewModel: OdysseyViewModel, episodeId: String?): Boolean {
    val groupings by viewModel.groupings.collectAsState()
    val secretState by viewModel.secretEpisodesState.collectAsState()
    return remember(groupings, secretState, episodeId) {
        when {
            episodeId == null -> false
            isSecretEpisodeId(episodeId) -> secretState.episodes.any { it.id == episodeId && it.liked }
            else -> groupings.any { grouping -> grouping.contentList.any { it.id == episodeId && it.isBookmarked } }
        }
    }
}
