package com.skintclub.odyssey.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.skintclub.odyssey.R
import com.skintclub.odyssey.constants.ShowClubPlaylistsKey
import com.skintclub.odyssey.ui.component.ListDialog
import com.skintclub.odyssey.ui.component.TextFieldDialog
import com.skintclub.odyssey.utils.rememberPreference
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

/** The "Add to playlist" picker: "New playlist" (a local one - see
 * [com.skintclub.odyssey.club.LocalPlaylistStore]), then a plain list of the user's own
 * playlists (official/non-user groupings can't be added to), matching how the randomizer's
 * blacklist/whitelist playlist picker looks and behaves. Club playlists are left out while
 * "Show club playlists in library" is off. Adds [episodeIds] to whichever is picked, then
 * calls [onDone]. */
@Composable
fun OdysseyPlaylistPickerDialog(
    viewModel: OdysseyViewModel,
    episodeIds: List<String>,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val playlists by viewModel.playlists.collectAsState()
    val showClubPlaylists by rememberPreference(ShowClubPlaylistsKey, defaultValue = true)
    val userPlaylists = remember(playlists, showClubPlaylists) {
        playlists.filter { it.isUserCreated && (showClubPlaylists || it.isLocal) }
    }
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                viewModel.createPlaylist(name, episodeIds)
                showCreateDialog = false
                onDone()
            },
        )
        return
    }

    ListDialog(onDismiss = onDismiss) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCreateDialog = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.add),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(
                    text = "New playlist",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        items(userPlaylists) { playlist ->
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.addEpisodesToPlaylist(playlist.id, episodeIds)
                        onDone()
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/** Asks for a new playlist's name. */
@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    TextFieldDialog(
        icon = { Icon(painter = painterResource(R.drawable.add), contentDescription = null) },
        title = { Text("New playlist") },
        placeholder = { Text("Playlist name") },
        isInputValid = { it.isNotBlank() },
        onDone = { onCreate(it.trim()) },
        onDismiss = onDismiss,
    )
}
