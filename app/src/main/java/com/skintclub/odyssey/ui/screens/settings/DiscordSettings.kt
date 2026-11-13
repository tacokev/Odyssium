package com.skintclub.odyssey.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
import com.skintclub.odyssey.constants.DiscordPresenceKey
import com.skintclub.odyssey.discord.DiscordRpc
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.PreferenceEntry
import com.skintclub.odyssey.ui.component.SwitchPreference
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscordSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (discordPresence, onDiscordPresenceChange) = rememberPreference(DiscordPresenceKey, defaultValue = false)
    val context = LocalContext.current
    val signedIn by DiscordRpc.signedIn.collectAsState()
    val userName by DiscordRpc.userName.collectAsState()
    val error by DiscordRpc.error.collectAsState()

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        if (signedIn) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.discord_signed_in)) },
                description = userName?.let { stringResource(R.string.discord_signed_in_as, it) },
                icon = { Icon(painterResource(R.drawable.discord), null) },
                trailingContent = {
                    TextButton(onClick = DiscordRpc::signOut) { Text(stringResource(R.string.discord_sign_out)) }
                },
            )
        } else {
            PreferenceEntry(
                title = { Text(stringResource(R.string.discord_sign_in)) },
                description = stringResource(R.string.discord_sign_in_desc),
                icon = { Icon(painterResource(R.drawable.discord), null) },
                onClick = { context.findActivity()?.let(DiscordRpc::signIn) },
            )
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        SwitchPreference(
            title = { Text(stringResource(R.string.discord_presence)) },
            description = stringResource(R.string.discord_presence_desc),
            icon = { Icon(painterResource(R.drawable.play), null) },
            checked = discordPresence,
            onCheckedChange = onDiscordPresenceChange,
            isEnabled = signedIn,
        )
        Text(
            text = stringResource(R.string.discord_unofficial_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.discord_integration)) },
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
