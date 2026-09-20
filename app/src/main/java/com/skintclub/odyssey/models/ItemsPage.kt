package com.skintclub.odyssey.models

import com.skintclub.innertube.models.YTItem

data class ItemsPage(
    val items: List<YTItem>,
    val continuation: String?,
)
