package com.skintclub.odyssey.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.club.ClubProfile
import com.skintclub.odyssey.viewmodels.OdysseyViewModel
import kotlinx.coroutines.launch

@Composable
fun OdysseyProfileSelectScreen(
    navController: NavController,
    odysseyViewModel: OdysseyViewModel,
) {
    val context = LocalContext.current
    val clubClient = remember { ClubClient(context) }
    val coroutineScope = rememberCoroutineScope()

    var profiles by remember { mutableStateOf<List<ClubProfile>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSelecting by remember { mutableStateOf(false) }
    var pendingPinProfile by remember { mutableStateOf<ClubProfile?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var isVerifyingPin by remember { mutableStateOf(false) }

    fun finishSelection(
        profile: ClubProfile,
        pin: String? = null,
    ) {
        isSelecting = true
        coroutineScope.launch {
            clubClient.selectProfile(profile, pin)
            odysseyViewModel.refresh()
            navController.navigate(Screens.Home.route) {
                popUpTo(0)
            }
        }
    }

    fun submitPin(profile: ClubProfile, pin: String) {
        isVerifyingPin = true
        pinError = null
        coroutineScope.launch {
            val correct = try {
                clubClient.verifyPin(profile.viewerId, pin)
            } catch (e: Exception) {
                isVerifyingPin = false
                pinError = "Couldn't verify PIN. Please try again."
                return@launch
            }
            isVerifyingPin = false
            if (correct) {
                pendingPinProfile = null
                finishSelection(profile, pin)
            } else {
                pinError = "Incorrect PIN."
                pinInput = ""
            }
        }
    }

    LaunchedEffect(Unit) {
        val fetched = try {
            clubClient.fetchProfiles()
        } catch (e: Exception) {
            errorMessage = "Couldn't load profiles. Please log in again."
            clubClient.logout()
            navController.navigate("odyssey_login") {
                popUpTo(0)
            }
            return@LaunchedEffect
        }
        when {
            fetched.isEmpty() -> errorMessage = "No profiles found on this account."
            // Nothing to choose between - skip straight to the app, same as the official client.
            fetched.size == 1 && !fetched[0].hasPin -> finishSelection(fetched[0])
            else -> profiles = fetched
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Vertical)),
    ) {
        when {
            errorMessage != null -> {
                Text(
                    text = errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }

            profiles == null || isSelecting -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            else -> {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Text(
                        text = "Who's Listening?",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(profiles.orEmpty()) { profile ->
                            ProfileTile(
                                name = profile.username.ifBlank { "Profile" },
                                imageUrl = profile.profilePictureUrl,
                                onClick = {
                                    if (profile.hasPin) {
                                        pinInput = ""
                                        pinError = null
                                        pendingPinProfile = profile
                                    } else {
                                        finishSelection(profile)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingPinProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { if (!isVerifyingPin) pendingPinProfile = null },
            title = { Text("Enter PIN for ${profile.username.ifBlank { "Profile" }}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            pinInput = it.filter(Char::isDigit).take(6)
                            pinError = null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        label = { Text("PIN") },
                        isError = pinError != null,
                        enabled = !isVerifyingPin,
                    )
                    pinError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isVerifyingPin && pinInput.isNotEmpty(),
                    onClick = { submitPin(profile, pinInput) },
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isVerifyingPin,
                    onClick = { pendingPinProfile = null },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ProfileTile(
    name: String,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            if (imageUrl.isNullOrBlank()) {
                Icon(
                    painter = painterResource(R.drawable.person),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(36.dp),
                )
            } else {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
