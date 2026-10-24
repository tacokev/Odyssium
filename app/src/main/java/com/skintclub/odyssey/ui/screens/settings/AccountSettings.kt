package com.skintclub.odyssey.ui.screens.settings

import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.CLUB_WEB_HOST
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.constants.ClubProfileNameKey
import com.skintclub.odyssey.constants.ClubProfilePictureKey
import com.skintclub.odyssey.constants.ResumeFromHistoryKey
import com.skintclub.odyssey.constants.ShowClubHistoryKey
import com.skintclub.odyssey.constants.ShowClubPlaylistsKey
import com.skintclub.odyssey.constants.SyncEpisodeProgressKey
import com.skintclub.odyssey.constants.SyncPlaylistsToClubKey
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.PreferenceEntry
import com.skintclub.odyssey.ui.component.SwitchPreference
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.utils.rememberPreference
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val clubClient = remember { ClubClient(context) }
    val isLoggedIntoClub by clubClient.isLoggedInFlow().collectAsState(initial = false)
    var showClubPlaylists by rememberPreference(ShowClubPlaylistsKey, defaultValue = true)
    var syncPlaylistsToClub by rememberPreference(SyncPlaylistsToClubKey, defaultValue = false)
    var syncEpisodeProgress by rememberPreference(SyncEpisodeProgressKey, defaultValue = true)
    var showClubHistory by rememberPreference(ShowClubHistoryKey, defaultValue = true)
    var resumeFromHistory by rememberPreference(ResumeFromHistoryKey, defaultValue = true)
    val profileName by rememberPreference(ClubProfileNameKey, defaultValue = "")
    val profilePicture by rememberPreference(ClubProfilePictureKey, defaultValue = "")

    // Picks up the name and picture for a profile selected before they were kept, or changed
    // on the website since.
    LaunchedEffect(isLoggedIntoClub) {
        if (!isLoggedIntoClub) return@LaunchedEffect
        try {
            clubClient.refreshSelectedProfile()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        PreferenceEntry(
            title = { Text(if (isLoggedIntoClub) "Logout" else "Login") },
            icon = {
                Image(
                    painter = painterResource(R.drawable.club_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(24.dp),
                )
            },
            onClick = {
                if (isLoggedIntoClub) {
                    navController.navigate("odyssey_logout")
                } else {
                    navController.navigate("odyssey_login")
                }
            },
        )

        if (isLoggedIntoClub) {
            PreferenceEntry(
                title = { Text("Switch profile") },
                description = profileName.takeIf { it.isNotBlank() }?.let { "Current profile: $it" },
                icon = {
                    if (profilePicture.isBlank()) {
                        Icon(painterResource(R.drawable.person), null)
                    } else {
                        AsyncImage(
                            model = profilePicture,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape),
                        )
                    }
                },
                onClick = { navController.navigate("odyssey_profile_select") },
            )

            ClubLinksPreference()

            SwitchPreference(
                title = { Text("Show club playlists in library") },
                description = "Show your club playlists in the library and \"Add to playlist\". Playlists made in the app always show.",
                icon = { Icon(painterResource(R.drawable.queue_music), null) },
                checked = showClubPlaylists,
                onCheckedChange = { showClubPlaylists = it },
            )

            SwitchPreference(
                title = { Text("Sync playlists") },
                description = "Keep a copy of each playlist made in the app on the club, for this profile.",
                icon = { Icon(painterResource(R.drawable.sync), null) },
                checked = syncPlaylistsToClub,
                onCheckedChange = { syncPlaylistsToClub = it },
            )

            SwitchPreference(
                title = { Text("Sync episode progress") },
                description = "Save how far you've listened to the club every 30 seconds, on pause, and when an episode finishes.",
                icon = { Icon(painterResource(R.drawable.history), null) },
                checked = syncEpisodeProgress,
                onCheckedChange = { syncEpisodeProgress = it },
            )

            SwitchPreference(
                title = { Text("Show Club listening history") },
                description = "List the episodes you've listened to on the club in History, alongside the ones played in the app.",
                icon = { Icon(painterResource(R.drawable.history), null) },
                checked = showClubHistory,
                onCheckedChange = { showClubHistory = it },
            )

            SwitchPreference(
                title = { Text("Resume from History") },
                description = "Tapping an episode you didn't finish in History starts it where you left off.",
                icon = { Icon(painterResource(R.drawable.play), null) },
                checked = resumeFromHistory,
                onCheckedChange = { resumeFromHistory = it },
            )
        }
    }

    TopAppBar(
        title = { Text("Account") },
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

/** "Open club links in the app": from Android 12 on, a link to a domain the app can't verify
 * (the club doesn't list this app) opens in the browser until it's enabled by hand under the
 * app's "Open by default" settings - this goes straight there, and shows whether it's on.
 * Earlier versions ask which app to use on the first tap, so there's nothing to set. */
@Composable
private fun ClubLinksPreference() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun linksEnabled(): Boolean {
        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return false
        val state = runCatching { manager.getDomainVerificationUserState(context.packageName) }.getOrNull() ?: return false
        return state.hostToStateMap[CLUB_WEB_HOST] in setOf(
            DomainVerificationUserState.DOMAIN_STATE_SELECTED,
            DomainVerificationUserState.DOMAIN_STATE_VERIFIED,
        )
    }

    var enabled by remember { mutableStateOf(linksEnabled()) }
    // Re-checked on coming back from the system settings page.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = linksEnabled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PreferenceEntry(
        title = { Text("Open club links in the app") },
        description = if (enabled) {
            "On - Adventures in Odyssey links open here."
        } else {
            "Off - tap, then \"Add link\" and check $CLUB_WEB_HOST."
        },
        icon = { Icon(painterResource(R.drawable.add_link), null) },
        onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        },
    )
}
