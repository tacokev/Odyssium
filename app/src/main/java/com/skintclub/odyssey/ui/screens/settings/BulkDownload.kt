package com.skintclub.odyssey.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skintclub.odyssey.LocalDownloadUtil
import com.skintclub.odyssey.R
import com.skintclub.odyssey.ui.component.PreferenceEntry
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

/**
 * Settings > Storage > "Bulk Download": downloads every episode, or every one the randomizer
 * can pick. While one runs, this shows its progress (and the download notification does too,
 * see ExoDownloadService) with a way to cancel it.
 */
@Composable
fun BulkDownloadPreference(odysseyViewModel: OdysseyViewModel) {
    val downloadUtil = LocalDownloadUtil.current
    val bulk by downloadUtil.bulkDownload.collectAsState()
    var showChoices by remember { mutableStateOf(false) }
    var confirmEveryEpisode by remember { mutableStateOf(false) }

    val progress = bulk
    if (progress != null) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = "Downloading ${progress.finished} of ${progress.total} episodes",
                style = MaterialTheme.typography.titleMedium,
            )
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            Text(
                text = progress.currentEpisode?.let { "Now: $it" } ?: "Waiting to start...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress.failed > 0) {
                Text(
                    text = "${progress.failed} failed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        PreferenceEntry(
            title = { Text("Cancel bulk download") },
            description = "Episodes already downloaded stay downloaded.",
            icon = { Icon(painterResource(R.drawable.close), null) },
            onClick = downloadUtil::cancelBulkDownload,
        )
    } else {
        PreferenceEntry(
            title = { Text("Bulk Download") },
            icon = { Icon(painterResource(R.drawable.download), null) },
            onClick = { showChoices = true },
        )
    }

    if (showChoices) {
        val everyCount = remember { odysseyViewModel.everyEpisode().size }
        val randomizerCount = remember { odysseyViewModel.randomizerEpisodes().size }
        AlertDialog(
            onDismissRequest = { showChoices = false },
            title = { Text("Download:") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BulkChoice("Every Episode", everyCount) {
                        showChoices = false
                        confirmEveryEpisode = true
                    }
                    BulkChoice("Every Randomizer Episode", randomizerCount) {
                        showChoices = false
                        downloadUtil.startBulkDownload(odysseyViewModel.randomizerEpisodes())
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChoices = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmEveryEpisode) {
        AlertDialog(
            onDismissRequest = { confirmEveryEpisode = false },
            text = { Text("Requires 20+ GB of space, continue?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEveryEpisode = false
                        downloadUtil.startBulkDownload(odysseyViewModel.everyEpisode())
                    },
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEveryEpisode = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun BulkChoice(label: String, count: Int, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = count > 0,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label)
            Text("$count episodes", style = MaterialTheme.typography.bodySmall)
        }
    }
}
