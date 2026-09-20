package com.skintclub.odyssey.ui.player

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.skintclub.odyssey.constants.DarkModeKey
import com.skintclub.odyssey.constants.PlayerBackgroundStyle
import com.skintclub.odyssey.models.MediaMetadata
import com.skintclub.odyssey.ui.screens.settings.DarkMode
import com.skintclub.odyssey.ui.theme.extractGradientColors
import com.skintclub.odyssey.utils.rememberEnumPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a per-song gradient palette for [PlayerBackgroundStyle.GRADIENT] mini player
 * backgrounds, mirroring the extraction the full player already does.
 */
@Composable
fun rememberMiniPlayerGradientColors(
    mediaMetadata: MediaMetadata?,
    style: PlayerBackgroundStyle,
): List<Color> {
    val context = LocalContext.current
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    var gradientColors by remember { mutableStateOf<List<Color>>(emptyList()) }

    LaunchedEffect(mediaMetadata?.id, style, darkTheme, isSystemInDarkTheme) {
        if (style == PlayerBackgroundStyle.GRADIENT) {
            withContext(Dispatchers.IO) {
                val result =
                    (
                        context.imageLoader
                            .execute(
                                ImageRequest
                                    .Builder(context)
                                    .data(mediaMetadata?.thumbnailUrl)
                                    .allowHardware(false)
                                    .build(),
                            ).drawable as? BitmapDrawable
                    )?.bitmap?.extractGradientColors(
                        darkTheme = darkTheme == DarkMode.ON || (darkTheme == DarkMode.AUTO && isSystemInDarkTheme),
                    )
                result?.let { gradientColors = it }
            }
        } else {
            gradientColors = emptyList()
        }
    }

    return gradientColors
}

/**
 * Draws the selected mini player background style behind the pill's content. No-op for
 * [PlayerBackgroundStyle.DEFAULT] — the pill's own flat surface color shows through.
 */
@Composable
fun MiniPlayerBackgroundLayer(
    style: PlayerBackgroundStyle,
    mediaMetadata: MediaMetadata?,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
) {
    when (style) {
        PlayerBackgroundStyle.GRADIENT -> {
            if (gradientColors.size >= 2) {
                Box(
                    modifier =
                        modifier
                            .fillMaxSize()
                            .background(Brush.linearGradient(gradientColors)),
                )
            }
        }
        // Apple Music's full multi-layer treatment is reserved for the fullscreen player;
        // at pill size a blurred crop reads the same and keeps this option non-broken here.
        PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.APPLE_MUSIC -> {
            Box(modifier = modifier.fillMaxSize()) {
                AsyncImage(
                    model = mediaMetadata?.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .blur(24.dp),
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                )
            }
        }
        PlayerBackgroundStyle.DEFAULT -> {}
    }
}
