package com.skintclub.odyssey.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.LocalPlayerConnection
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.playback.queues.OdysseyQueue
import com.skintclub.odyssey.playback.queues.extractAlbumYear
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaItem
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaMetadata
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

/** A fullscreen, poster-style episode page: cover art fading into the background at the
 * top, then title/year/description/actions below. Reached both from the home screen's
 * random-episode shuffle button and from an episode's "Details" menu item, so both land
 * on the exact same screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OdysseyEpisodeDetailsScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
    albumName: String,
    episodeIndex: Int,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val albums by viewModel.albums.collectAsState()

    val album = albums.find { it.name == albumName } ?: return
    val episode = album.episodes.getOrNull(episodeIndex) ?: return
    val metadata = odysseyEpisodeToMediaMetadata(context, episode)
    val year = extractAlbumYear(episode)
    val contentId = (episode["id"] as? String)?.takeIf { it.isNotBlank() }
    val clubClient = remember { ClubClient(context) }
    var showTitleMenu by remember { mutableStateOf(false) }
    var responseToShow by remember { mutableStateOf<String?>(null) }

    responseToShow?.let { raw ->
        ContentResponseDialog(raw, onDismiss = { responseToShow = null })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Box {
                if (metadata.thumbnailUrl != null) {
                    AsyncImage(
                        model = metadata.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.55f to Color.Transparent,
                                    1f to MaterialTheme.colorScheme.background,
                                ),
                            ),
                        ),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)),
            ) {
                Box {
                    Text(
                        text = metadata.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { if (contentId != null) showTitleMenu = true },
                        ),
                    )
                    DropdownMenu(
                        expanded = showTitleMenu,
                        onDismissRequest = { showTitleMenu = false },
                    ) {
                        // The response the extra details below were parsed from - only there
                        // once they've loaded.
                        val raw = contentId?.let { clubClient.cachedContentDetails(it)?.rawJson }
                        DropdownMenuItem(
                            text = { Text("View response") },
                            enabled = raw != null,
                            onClick = {
                                showTitleMenu = false
                                responseToShow = raw
                            },
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (year != null) {
                        Text(
                            text = year,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "  •  ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = TextDecoration.Underline,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            navController.navigate("odyssey_album/${Uri.encode(album.name)}")
                        },
                    )
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = {
                            playerConnection.playQueue(
                                OdysseyQueue(
                                    context = context,
                                    episodes = album.episodes,
                                    startIndex = episodeIndex,
                                ),
                            )
                        },
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.play),
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.play))
                    }

                    OutlinedButton(
                        onClick = {
                            playerConnection.playNext(odysseyEpisodeToMediaItem(context, episode))
                        },
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.playlist_play),
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.play_next))
                    }
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    text = metadata.description?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.no_description_available),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(Modifier.height(24.dp))

                if (contentId != null) {
                    OdysseyEpisodeExtraDetails(navController, contentId, albums)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        IconButton(
            onClick = navController::navigateUp,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.35f),
                contentColor = Color.White,
            ),
            modifier = Modifier
                .padding(12.dp)
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)),
        ) {
            Icon(
                painter = painterResource(R.drawable.arrow_back),
                contentDescription = null,
            )
        }

    }
}

/** The raw content-detail response, pretty-printed, selectable, and copyable. */
@Composable
private fun ContentResponseDialog(raw: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val pretty = remember(raw) {
        runCatching { GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(raw)) }
            .getOrDefault(raw)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Response") },
        text = {
            SelectionContainer {
                Text(
                    text = pretty,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(pretty)) }) { Text("Copy") }
        },
    )
}
