package com.skintclub.odyssey.ui.menu

import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.models.MediaMetadata
import com.skintclub.odyssey.ui.component.GridMenu
import com.skintclub.odyssey.ui.component.GridMenuItem
import com.skintclub.odyssey.ui.component.MediaMetadataListItem

@Composable
fun OdysseyEpisodeMenu(
    navController: NavController,
    mediaMetadata: MediaMetadata,
    mediaItem: MediaItem,
    albumName: String,
    episodeIndex: Int,
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return

    MediaMetadataListItem(
        mediaMetadata = mediaMetadata,
        modifier = Modifier,
    )

    HorizontalDivider()

    GridMenu(
        contentPadding =
            PaddingValues(
                start = 8.dp,
                top = 8.dp,
                end = 8.dp,
                bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
            ),
    ) {
        GridMenuItem(
            icon = R.drawable.playlist_play,
            title = R.string.play_next,
        ) {
            onDismiss()
            playerConnection.playNext(mediaItem)
        }
        GridMenuItem(
            icon = R.drawable.queue_music,
            title = R.string.add_to_queue,
        ) {
            onDismiss()
            playerConnection.addToQueue(mediaItem)
        }
        GridMenuItem(
            icon = R.drawable.info,
            title = R.string.details,
        ) {
            onDismiss()
            navController.navigate("odyssey_episode/${Uri.encode(albumName)}/$episodeIndex")
        }
    }
}
