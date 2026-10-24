package com.skintclub.odyssey.ui.screens

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.skintclub.odyssey.R

private val WelcomeNight = Color(0xFF070D1A)
private val WelcomeNavy = Color(0xFF0C1A38)
private val WelcomeDeepBlue = Color(0xFF0E2F6E)

/** Shown over the whole app the first time it opens logged out of the Club: log in, or skip and
 * use the app without an account (the free radio episodes). Always uses its own dark
 * night-to-blue palette, so it looks the same in light and dark theme. */
@Composable
fun OdysseyWelcomeScreen(
    onLogin: () -> Unit,
    onSkip: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val headerAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(600), label = "headerAlpha")
    val footerAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(600, delayMillis = 400), label = "footerAlpha")

    OdysseyNightBackdrop {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Spacer(Modifier.weight(0.6f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer { alpha = headerAlpha },
            ) {
                Image(
                    painter = painterResource(R.drawable.welcome_app_icon),
                    contentDescription = null,
                    modifier = Modifier
                        .size(128.dp)
                        .clip(RoundedCornerShape(30.dp)),
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(top = 20.dp),
                )
                Text(
                    text = "Stream your favourite Adventures in Odyssey episodes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 24.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 48.dp)
                        .padding(top = 20.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .graphicsLayer { alpha = footerAlpha }
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
            ) {
                Button(
                    onClick = onLogin,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = WelcomeDeepBlue,
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text(
                        text = "Log in",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // The other way in, the same size as "Log in" but quieter: an outline over a faint
                // wash of white, so it reads as a button on the night sky without competing.
                OutlinedButton(
                    onClick = onSkip,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        contentColor = Color.White,
                    ),
                    border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text(
                        text = "Listen for free",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = "Log in with your Adventures in Odyssey Club membership, " +
                        "or listen to this week's radio episodes for free.",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .padding(top = 16.dp),
                )
            }
        }
    }
}

/** The welcome screen's night-blue background, with the town of Odyssey rising out of it along
 * the bottom as it first appears. Fixed colors whatever the app theme, so [content] on it should
 * be light; also keeps the system bar icons light while shown. */
@Composable
fun OdysseyNightBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    DarkSystemBarsWhileShown()

    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val sceneProgress by animateFloatAsState(if (shown) 1f else 0f, tween(1100, delayMillis = 150), label = "scene")
    val sceneRise = with(LocalDensity.current) { 40.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to WelcomeNight,
                    0.5f to WelcomeNavy,
                    1f to WelcomeDeepBlue,
                ),
            ),
    ) {
        Image(
            painter = painterResource(R.drawable.welcome_street),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.BottomCenter,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .graphicsLayer {
                    alpha = sceneProgress
                    translationY = (1f - sceneProgress) * sceneRise
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    // Fades the top of the scene (its bright sky) fully out into the background,
                    // easing in so there's no visible band where it starts...
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.22f to Color.Transparent,
                            0.38f to Color.Black.copy(alpha = 0.25f),
                            0.52f to Color.Black.copy(alpha = 0.7f),
                            0.65f to Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                    // ...and darkens the bottom a little so text over it stays readable.
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.6f to Color.Transparent,
                            1f to WelcomeNight.copy(alpha = 0.65f),
                        ),
                    )
                },
        )

        content()
    }
}

/** [OdysseyNightBackdrop] is dark whatever the app theme, so the system bar icons stay light while
 * it's up and go back to the theme's once it's gone. */
@Composable
private fun DarkSystemBarsWhileShown() {
    val view = LocalView.current
    val window = (LocalContext.current as? Activity)?.window ?: return
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, view)
        val lightStatusBars = controller.isAppearanceLightStatusBars
        val lightNavigationBars = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = lightStatusBars
            controller.isAppearanceLightNavigationBars = lightNavigationBars
        }
    }
}
