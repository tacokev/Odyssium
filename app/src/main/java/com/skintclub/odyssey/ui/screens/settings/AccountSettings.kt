package com.skintclub.odyssey.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.PreferenceEntry
import com.skintclub.odyssey.ui.utils.backToMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val clubClient = remember { ClubClient(context) }
    val isLoggedIntoClub by clubClient.isLoggedInFlow().collectAsState(initial = false)

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
                icon = { Icon(painterResource(R.drawable.person), null) },
                onClick = { navController.navigate("odyssey_profile_select") },
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
