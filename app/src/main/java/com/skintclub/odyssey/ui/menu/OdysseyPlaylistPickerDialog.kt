package com.skintclub.odyssey.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skintclub.odyssey.ui.component.ListDialog
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

/** The "Add to playlist" picker: a plain list of the user's own playlists (those with a
 * viewer_id - official/non-user groupings can't be added to), matching how the randomizer's
 * blacklist/whitelist playlist picker looks and behaves. */
@Composable
fun OdysseyPlaylistPickerDialog(
    viewModel: OdysseyViewModel,
    onDismiss: () -> Unit,
    onPlaylistSelected: (playlistId: String) -> Unit,
) {
    val playlists by viewModel.playlists.collectAsState()
    val userPlaylists = remember(playlists) { playlists.filter { it.isUserCreated } }

    ListDialog(onDismiss = onDismiss) {
        if (userPlaylists.isEmpty()) {
            item {
                Text(
                    text = "No playlists yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        items(userPlaylists) { playlist ->
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlaylistSelected(playlist.id) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
