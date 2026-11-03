package com.skintclub.odyssey.ui.screens

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.NavController
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

/**
 * The randomizer's episode page: every pick so far side by side, the current one showing.
 * Swiping follows the finger to the previous (left to right) or next pick, and the page it
 * settles on becomes the current one. A new pick from the dice button slides in from the
 * right. Horizontally scrolling rows on a page (characters, recommendations) scroll first.
 */
@Composable
fun RandomizerPager(
    navController: NavController,
    viewModel: OdysseyViewModel,
) {
    val history by viewModel.randomizerHistory.collectAsState()
    val picks = history.picks
    if (picks.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = history.position.coerceAtLeast(0)) { picks.size }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect(viewModel::setRandomizerPosition)
    }
    // A new pick (or a swipe settling) moves the history; follow it when it's somewhere else.
    LaunchedEffect(history.position, picks.size) {
        if (history.position >= 0 && history.position != pagerState.settledPage) {
            pagerState.animateScrollToPage(history.position)
        }
    }

    HorizontalPager(
        state = pagerState,
        key = { page -> "$page/${picks.getOrNull(page)}" },
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val (albumName, episodeIndex) = picks.getOrNull(page) ?: return@HorizontalPager
        OdysseyEpisodeDetailsScreen(
            navController = navController,
            viewModel = viewModel,
            albumName = albumName,
            episodeIndex = episodeIndex,
        )
    }
}
