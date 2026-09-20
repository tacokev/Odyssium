package com.skintclub.odyssey.playback.queues

import androidx.media3.common.MediaItem
import com.skintclub.odyssey.models.MediaMetadata

class YouTubeQueue(
    @Suppress("UNUSED_PARAMETER") endpoint: Any? = null,
    override val preloadItem: MediaMetadata? = null,
) : Queue {
    override suspend fun getInitialStatus(): Queue.Status = Queue.Status(null, emptyList(), 0)
    override fun hasNextPage(): Boolean = false
    override suspend fun nextPage(): List<MediaItem> = emptyList()
}
