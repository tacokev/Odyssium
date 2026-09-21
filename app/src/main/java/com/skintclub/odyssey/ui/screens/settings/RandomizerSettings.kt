package com.skintclub.odyssey.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.club.ClubPlaylistOption
import com.skintclub.odyssey.constants.ExcludeBonusEpisodesKey
import com.skintclub.odyssey.constants.ExcludeSubsequentPartsKey
import com.skintclub.odyssey.constants.RandomizerListMode
import com.skintclub.odyssey.constants.RandomizerListModeKey
import com.skintclub.odyssey.constants.RandomizerNonRepeatingKey
import com.skintclub.odyssey.constants.RandomizerPlayOnPressKey
import com.skintclub.odyssey.constants.RandomizerPlaylistIdKey
import com.skintclub.odyssey.ui.component.EnumListPreference
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.ListPreference
import com.skintclub.odyssey.ui.component.SwitchPreference
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.utils.rememberEnumPreference
import com.skintclub.odyssey.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RandomizerSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val clubClient = remember { ClubClient(context) }

    val (excludeBonusEpisodes, onExcludeBonusEpisodesChange) = rememberPreference(ExcludeBonusEpisodesKey, defaultValue = true)
    val (excludeSubsequentParts, onExcludeSubsequentPartsChange) = rememberPreference(ExcludeSubsequentPartsKey, defaultValue = true)
    val (listMode, onListModeChange) = rememberEnumPreference(RandomizerListModeKey, defaultValue = RandomizerListMode.OFF)
    val (selectedPlaylistId, onSelectedPlaylistIdChange) = rememberPreference(RandomizerPlaylistIdKey, defaultValue = "")
    val (playOnPress, onPlayOnPressChange) = rememberPreference(RandomizerPlayOnPressKey, defaultValue = false)
    val (nonRepeating, onNonRepeatingChange) = rememberPreference(RandomizerNonRepeatingKey, defaultValue = true)

    var playlists by remember { mutableStateOf<List<ClubPlaylistOption>>(emptyList()) }
    LaunchedEffect(Unit) {
        playlists = clubClient.playlistsFrom(clubClient.getCachedGroupings())
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        SwitchPreference(
            title = { Text("Exclude bonus episodes") },
            description = "Keep the random episode button from picking episodes marked as bonus content.",
            checked = excludeBonusEpisodes,
            onCheckedChange = onExcludeBonusEpisodesChange,
        )
        SwitchPreference(
            title = { Text("Exclude Subsequent Parts") },
            description = "For multipart episodes, only part 1 can be picked — never part 2, 3, etc.",
            checked = excludeSubsequentParts,
            onCheckedChange = onExcludeSubsequentPartsChange,
        )
        SwitchPreference(
            title = { Text("Non-Repeating Randomizer") },
            description = "Shuffle through every eligible episode once before any repeat, instead of a fresh independent pick each time.",
            checked = nonRepeating,
            onCheckedChange = onNonRepeatingChange,
        )
        SwitchPreference(
            title = { Text("Play on press") },
            description = "Start playing the picked episode immediately instead of opening its details page first.",
            checked = playOnPress,
            onCheckedChange = onPlayOnPressChange,
        )
        EnumListPreference(
            title = { Text("Blacklist/Whitelist") },
            icon = null,
            selectedValue = listMode,
            valueText = {
                when (it) {
                    RandomizerListMode.OFF -> "Off"
                    RandomizerListMode.BLACKLIST -> "Blacklist — never pick from a playlist"
                    RandomizerListMode.WHITELIST -> "Whitelist — only pick from a playlist"
                }
            },
            onValueSelected = onListModeChange,
        )

        if (listMode != RandomizerListMode.OFF) {
            ListPreference(
                title = { Text("Playlist") },
                selectedValue = selectedPlaylistId,
                values = listOf("") + playlists.map { it.id },
                valueText = { id ->
                    if (id.isBlank()) {
                        "None selected"
                    } else {
                        playlists.firstOrNull { it.id == id }?.let { playlist ->
                            if (playlist.isUserCreated) "${playlist.name} (mine)" else playlist.name
                        } ?: "Unknown playlist"
                    }
                },
                onValueSelected = onSelectedPlaylistIdChange,
            )
        }
    }

    TopAppBar(
        title = { Text("Randomizer") },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}
