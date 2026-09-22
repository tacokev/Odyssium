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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
import com.skintclub.odyssey.constants.ClubAudioLoadMode
import com.skintclub.odyssey.constants.ClubAudioLoadModeKey
import com.skintclub.odyssey.constants.ShowEpisodeNumberKey
import com.skintclub.odyssey.ui.component.EnumListPreference
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.SwitchPreference
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.utils.rememberEnumPreference
import com.skintclub.odyssey.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClubContentSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (audioLoadMode, onAudioLoadModeChange) = rememberEnumPreference(ClubAudioLoadModeKey, defaultValue = ClubAudioLoadMode.SIGNED_COOKIE)
    val (showEpisodeNumber, onShowEpisodeNumberChange) = rememberPreference(ShowEpisodeNumberKey, defaultValue = false)

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        EnumListPreference(
            title = { Text("Load audio from:") },
            icon = { Icon(painterResource(R.drawable.play), null) },
            selectedValue = audioLoadMode,
            valueText = {
                when (it) {
                    ClubAudioLoadMode.SIGNED_COOKIE -> "Signed cookie - Fast but unstable"
                    ClubAudioLoadMode.CONTENT_REQUEST -> "Content request - Slow but stable"
                }
            },
            onValueSelected = onAudioLoadModeChange,
        )
        SwitchPreference(
            title = { Text("Show episode number") },
            description = "Use the episode's numbered short title (e.g. \"#571: For Better or for Worse\") instead of its plain name, everywhere an episode title is shown - including the randomizer.",
            checked = showEpisodeNumber,
            onCheckedChange = onShowEpisodeNumberChange,
        )
    }

    TopAppBar(
        title = { Text("Content") },
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
