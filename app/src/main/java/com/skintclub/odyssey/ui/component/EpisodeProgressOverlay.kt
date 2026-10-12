package com.skintclub.odyssey.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.skintclub.odyssey.R
import com.skintclub.odyssey.constants.ThumbnailCornerRadius
import com.skintclub.odyssey.viewmodels.EpisodeProgress

/** Over a list thumbnail: a check in the corner once [progress] is complete, otherwise a bar
 * along the bottom showing how much was listened to. */
@Composable
fun BoxScope.EpisodeProgressOverlay(progress: EpisodeProgress) {
    if (progress.completed) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(2.dp)
                .size(16.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        ) {
            Icon(
                painter = painterResource(R.drawable.done),
                contentDescription = "Completed",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(12.dp),
            )
        }
        return
    }
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(bottomStart = ThumbnailCornerRadius, bottomEnd = ThumbnailCornerRadius))
            .background(Color.Black.copy(alpha = 0.5f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.fraction)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** "Completed", or how much is left ("12 min left"). */
fun episodeProgressText(progress: EpisodeProgress): String {
    if (progress.completed) return "Completed"
    if (progress.lengthMs <= 0) return "In progress"
    val minutesLeft = ((progress.lengthMs - progress.positionMs).coerceAtLeast(0) + 59_999) / 60_000
    return "${minutesLeft.coerceAtLeast(1)} min left"
}
