package com.skintclub.odyssey.ui.menu

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skintclub.odyssey.LocalDatabase
import com.skintclub.odyssey.R
import com.skintclub.odyssey.constants.ListThumbnailSize
import com.skintclub.odyssey.db.entities.Playlist
import com.skintclub.odyssey.db.entities.PlaylistEntity
import com.skintclub.odyssey.ui.component.ListDialog
import com.skintclub.odyssey.ui.component.ListItem
import com.skintclub.odyssey.ui.component.PlaylistListItem
import com.skintclub.odyssey.ui.component.TextFieldDialog

@Composable
fun AddToPlaylistDialog(
    isVisible: Boolean,
    onAdd: (Playlist) -> Unit,
    onDismiss: () -> Unit,
) {
    val database = LocalDatabase.current
    var playlists by remember {
        mutableStateOf(emptyList<Playlist>())
    }
    var showCreatePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    LaunchedEffect(Unit) {
        database.playlistsByCreateDateAsc().collect {
            playlists = it.asReversed()
        }
    }

    if (isVisible) {
        ListDialog(
            onDismiss = onDismiss,
        ) {
            item {
                ListItem(
                    title = stringResource(R.string.create_playlist),
                    thumbnailContent = {
                        Box(
                            modifier = Modifier.size(ListThumbnailSize)
                        ) {
                            Image(
                                painter = painterResource(R.drawable.add),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                                modifier = Modifier.size(24.dp).align(Alignment.Center),
                            )
                        }
                    },
                    modifier =
                        Modifier.clickable {
                            showCreatePlaylistDialog = true
                        },
                )
            }

            items(playlists) { playlist ->
                PlaylistListItem(
                    playlist = playlist,
                    modifier =
                        Modifier.clickable {
                            onAdd(playlist)
                            onDismiss()
                        },
                )
            }
        }
    }

    if (showCreatePlaylistDialog) {
        TextFieldDialog(
            icon = { Icon(painter = painterResource(R.drawable.add), contentDescription = null) },
            title = { Text(text = stringResource(R.string.create_playlist)) },
            onDismiss = { showCreatePlaylistDialog = false },
            onDone = { playlistName ->
                database.query {
                    insert(
                        PlaylistEntity(
                            name = playlistName,
                        ),
                    )
                }
            },
        )
    }
}
