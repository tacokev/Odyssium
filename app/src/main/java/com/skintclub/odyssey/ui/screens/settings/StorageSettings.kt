package com.skintclub.odyssey.ui.screens.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.skintclub.odyssey.LocalDownloadUtil
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.constants.MaxImageCacheSizeKey
import com.skintclub.odyssey.constants.MaxSongCacheSizeKey
import com.skintclub.odyssey.extensions.tryOrNull
import com.skintclub.odyssey.playback.ExoDownloadService
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.ListPreference
import com.skintclub.odyssey.ui.component.PreferenceEntry
import com.skintclub.odyssey.ui.component.PreferenceGroupTitle
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.ui.utils.formatFileSize
import com.skintclub.odyssey.utils.rememberPreference
import com.skintclub.odyssey.viewmodels.OdysseyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalCoilApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StorageSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    odysseyViewModel: OdysseyViewModel,
) {
    val context = LocalContext.current
    val imageDiskCache = context.imageLoader.diskCache ?: return
    val playerCache = LocalPlayerConnection.current?.service?.playerCache ?: return
    val downloadCache = LocalPlayerConnection.current?.service?.downloadCache ?: return
    val downloadUtil = LocalDownloadUtil.current
    val clubClient = remember { ClubClient(context) }

    val coroutineScope = rememberCoroutineScope()

    /** The clear button waiting on its confirmation: what to ask, and what it does. */
    var confirmClear by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    fun confirm(message: String, action: () -> Unit): () -> Unit = { confirmClear = message to action }
    var membershipExpiry by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(Unit) {
        membershipExpiry = clubClient.getMembershipExpiry()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
    val (maxImageCacheSize, onMaxImageCacheSizeChange) = rememberPreference(key = MaxImageCacheSizeKey, defaultValue = 512)
    val (maxSongCacheSize, onMaxSongCacheSizeChange) = rememberPreference(key = MaxSongCacheSizeKey, defaultValue = 1024)

    var imageCacheSize by remember {
        mutableStateOf(imageDiskCache.size)
    }
    var playerCacheSize by remember {
        mutableStateOf(tryOrNull { playerCache.cacheSpace } ?: 0)
    }
    var downloadCacheSize by remember {
        mutableStateOf(tryOrNull { downloadCache.cacheSpace } ?: 0)
    }
    val imageCacheProgress by animateFloatAsState(
        targetValue = (imageCacheSize.toFloat() / imageDiskCache.maxSize).coerceIn(0f, 1f),
        label = "",
    )
    val playerCacheProgress by animateFloatAsState(
        targetValue = (playerCacheSize.toFloat() / (maxSongCacheSize * 1024 * 1024L)).coerceIn(0f, 1f),
        label = "",
    )

    LaunchedEffect(imageDiskCache) {
        while (isActive) {
            delay(500)
            imageCacheSize = imageDiskCache.size
        }
    }
    LaunchedEffect(playerCache) {
        while (isActive) {
            delay(500)
            playerCacheSize = tryOrNull { playerCache.cacheSpace } ?: 0
        }
    }
    LaunchedEffect(downloadCache) {
        while (isActive) {
            delay(500)
            downloadCacheSize = tryOrNull { downloadCache.cacheSpace } ?: 0
        }
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        if (membershipExpiry != null) {
            Text(
                text = "Downloaded episodes and the episode cache will be cleared on " +
                    "${membershipExpiry!!.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))} " +
                    "unless your club membership is renewed before then.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        PreferenceGroupTitle(
            title = "Downloaded episodes",
        )

        BulkDownloadPreference(odysseyViewModel)

        Text(
            text = stringResource(R.string.size_used, formatFileSize(downloadCacheSize)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.clear_all_downloads)) },
            onClick = confirm("Delete all downloaded episodes (${formatFileSize(downloadCacheSize)})?") {
                // Going through DownloadService (same call every individual delete button
                // uses) rather than clearing downloadCache directly - the cache is just where
                // the bytes live, but DownloadManager keeps its own separate index of what's
                // "downloaded" that every download indicator in the app actually reads from.
                // Wiping the cache without telling DownloadManager left that index (and the
                // whole app's downloaded/downloading state) stale until next restart.
                downloadUtil.downloads.value.keys.forEach { id ->
                    DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
                }
            },
        )

        PreferenceGroupTitle(
            title = "Episode Cache",
        )

        if (maxSongCacheSize == -1) {
            Text(
                text = stringResource(R.string.size_used, formatFileSize(playerCacheSize)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        } else {
            LinearProgressIndicator(
                progress = { playerCacheProgress },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
            )

            Text(
                text =
                    stringResource(
                        R.string.size_used,
                        "${formatFileSize(playerCacheSize)} / ${formatFileSize(
                            maxSongCacheSize * 1024 * 1024L,
                        )}",
                    ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        ListPreference(
            title = { Text(stringResource(R.string.max_cache_size)) },
            selectedValue = maxSongCacheSize,
            values = listOf(128, 256, 512, 1024, 2048, 4096, 8192, -1),
            valueText = {
                if (it == -1) stringResource(R.string.unlimited) else formatFileSize(it * 1024 * 1024L)
            },
            onValueSelected = onMaxSongCacheSizeChange,
        )

        PreferenceEntry(
            title = { Text("Clear episode cache") },
            onClick = confirm("Clear the episode cache? Episodes in it will have to stream again.") {
                coroutineScope.launch(Dispatchers.IO) {
                    playerCache.keys.forEach { key ->
                        playerCache.removeResource(key)
                    }
                }
            },
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.image_cache),
        )

        LinearProgressIndicator(
            progress = { imageCacheProgress },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
        )

        Text(
            text = stringResource(R.string.size_used, "${formatFileSize(imageCacheSize)} / ${formatFileSize(imageDiskCache.maxSize)}"),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        ListPreference(
            title = { Text(stringResource(R.string.max_cache_size)) },
            selectedValue = maxImageCacheSize,
            values = listOf(128, 256, 512, 1024, 2048, 4096, 8192),
            valueText = { formatFileSize(it * 1024 * 1024L) },
            onValueSelected = onMaxImageCacheSizeChange,
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.clear_image_cache)) },
            onClick = confirm("Clear the image cache? Covers will load from the internet again.") {
                coroutineScope.launch(Dispatchers.IO) {
                    imageDiskCache.clear()
                }
            },
        )
    }

    confirmClear?.let { (message, action) ->
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = null
                        action()
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = null }) { Text("Cancel") }
            },
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.storage)) },
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
