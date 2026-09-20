package com.skintclub.innertube.pages

import com.skintclub.innertube.models.SongItem

data class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
)
