package com.skintclub.odyssey.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
import com.skintclub.odyssey.constants.*
import com.skintclub.odyssey.constants.ThemeColorSourceKey
import com.skintclub.odyssey.ui.component.EnumListPreference
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.ListPreference
import com.skintclub.odyssey.ui.component.PreferenceGroupTitle
import com.skintclub.odyssey.ui.component.SwitchPreference
import com.skintclub.odyssey.utils.rememberEnumPreference
import com.skintclub.odyssey.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (themeColorSource, onThemeColorSourceChange) = rememberThemeColorSource()
    var pureBlack by rememberPreference(PureBlackKey, defaultValue = false)
    val (playerStyle, onPlayerStyleChange) =
        rememberEnumPreference(PlayerStyleKey, defaultValue = PlayerStyle.DEFAULT)
    val (playerBackground, onPlayerBackgroundChange) =
        rememberEnumPreference(PlayerBackgroundStyleKey, defaultValue = PlayerBackgroundStyle.DEFAULT)
    val (miniPlayerStyle, onMiniPlayerStyleChange) =
        rememberEnumPreference(MiniPlayerStyleKey, defaultValue = MiniPlayerStyle.DEFAULT)
    val (miniPlayerBackground, onMiniPlayerBackgroundChange) =
        rememberEnumPreference(MiniPlayerBackgroundStyleKey, defaultValue = PlayerBackgroundStyle.DEFAULT)
    var pureBlackMiniPlayer by rememberPreference(PureBlackMiniPlayerKey, defaultValue = false)
    val (albumGridColumns, onAlbumGridColumnsChange) =
        rememberEnumPreference(AlbumGridColumnsKey, defaultValue = AlbumGridColumns.TWO)
    var albumGridConnectedSquares by rememberPreference(AlbumGridConnectedSquaresKey, defaultValue = false)
    var compactNavigationBar by rememberPreference(CompactNavigationBarKey, defaultValue = false)

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        PreferenceGroupTitle(title = stringResource(R.string.theme))

        ListPreference(
            title = { Text("Theme colors") },
            icon = { Icon(painterResource(R.drawable.palette), null) },
            selectedValue = themeColorSource,
            // Wallpaper colors (Material You) only exist on Android 12+.
            values = ThemeColorSource.entries.filter {
                it != ThemeColorSource.WALLPAPER || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            },
            valueText = {
                when (it) {
                    ThemeColorSource.WALLPAPER -> "Wallpaper"
                    ThemeColorSource.ARTWORK -> "Playing episode"
                    ThemeColorSource.DEFAULT -> "Default"
                }
            },
            onValueSelected = onThemeColorSourceChange,
        )
        SwitchPreference(
            title = { Text(stringResource(R.string.pure_black)) },
            icon = { Icon(painterResource(R.drawable.dark_mode), null) },
            checked = pureBlack,
            onCheckedChange = { pureBlack = it }
        )
        EnumListPreference(
            title = { Text(stringResource(R.string.album_grid_columns)) },
            icon = { Icon(painterResource(R.drawable.grid_view), null) },
            selectedValue = albumGridColumns,
            onValueSelected = onAlbumGridColumnsChange,
            valueText = {
                when (it) {
                    AlbumGridColumns.TWO -> stringResource(R.string.two_columns)
                    AlbumGridColumns.THREE -> stringResource(R.string.three_columns)
                }
            },
        )
        SwitchPreference(
            title = { Text(stringResource(R.string.album_grid_connected_squares)) },
            icon = { Icon(painterResource(R.drawable.grid_view), null) },
            checked = albumGridConnectedSquares,
            onCheckedChange = { albumGridConnectedSquares = it },
        )
        SwitchPreference(
            title = { Text(stringResource(R.string.compact_navigation_bar)) },
            icon = { Icon(painterResource(R.drawable.expand_less), null) },
            checked = compactNavigationBar,
            onCheckedChange = { compactNavigationBar = it },
        )

        PreferenceGroupTitle(title = stringResource(R.string.player))

        EnumListPreference(
            title = { Text(stringResource(R.string.player_style)) },
            icon = { Icon(painterResource(R.drawable.play), null) },
            selectedValue = playerStyle,
            onValueSelected = onPlayerStyleChange,
            valueText = {
                when (it) {
                    PlayerStyle.DEFAULT -> stringResource(R.string.default_)
                    PlayerStyle.APPLE -> stringResource(R.string.apple_music)
                    PlayerStyle.M3E -> stringResource(R.string.player_style_m3e)
                }
            },
        )

        EnumListPreference(
            title = { Text(stringResource(R.string.player_background_style)) },
            icon = { Icon(painterResource(R.drawable.gradient), null) },
            selectedValue = playerBackground,
            onValueSelected = onPlayerBackgroundChange,
            valueText = {
                when (it) {
                    PlayerBackgroundStyle.DEFAULT -> stringResource(R.string.default_)
                    PlayerBackgroundStyle.GRADIENT -> stringResource(R.string.gradient)
                    PlayerBackgroundStyle.BLUR -> stringResource(R.string.blur)
                    PlayerBackgroundStyle.APPLE_MUSIC -> stringResource(R.string.apple_music)
                }
            },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        EnumListPreference(
            title = { Text(stringResource(R.string.mini_player_style)) },
            icon = { Icon(painterResource(R.drawable.queue_music), null) },
            selectedValue = miniPlayerStyle,
            onValueSelected = onMiniPlayerStyleChange,
            valueText = {
                when (it) {
                    MiniPlayerStyle.DEFAULT -> stringResource(R.string.default_)
                    MiniPlayerStyle.MODERN -> stringResource(R.string.modern)
                    MiniPlayerStyle.APPLE -> stringResource(R.string.apple_music)
                }
            },
        )

        if (miniPlayerStyle != MiniPlayerStyle.DEFAULT) {
            ListPreference(
                title = { Text(stringResource(R.string.mini_player_background_style)) },
                icon = { Icon(painterResource(R.drawable.gradient), null) },
                selectedValue = miniPlayerBackground,
                values =
                    listOf(
                        PlayerBackgroundStyle.DEFAULT,
                        PlayerBackgroundStyle.GRADIENT,
                        PlayerBackgroundStyle.BLUR,
                    ),
                onValueSelected = onMiniPlayerBackgroundChange,
                valueText = {
                    when (it) {
                        PlayerBackgroundStyle.DEFAULT -> stringResource(R.string.default_)
                        PlayerBackgroundStyle.GRADIENT -> stringResource(R.string.gradient)
                        PlayerBackgroundStyle.BLUR -> stringResource(R.string.blur)
                        PlayerBackgroundStyle.APPLE_MUSIC -> stringResource(R.string.apple_music)
                    }
                },
            )
            SwitchPreference(
                title = { Text(stringResource(R.string.pure_black_mini_player)) },
                icon = { Icon(painterResource(R.drawable.dark_mode), null) },
                checked = pureBlackMiniPlayer,
                onCheckedChange = { pureBlackMiniPlayer = it },
            )
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.appearance)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = { },
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

/** Where the app's accent colors come from. */
enum class ThemeColorSource {
    /** The system's Material You palette, from the wallpaper (Android 12+). */
    WALLPAPER,

    /** Extracted from the playing episode's cover; wallpaper colors when nothing's playing. */
    ARTWORK,

    /** The app's own fixed blue. */
    DEFAULT,
}

/** [ThemeColorSourceKey], defaulting to whatever the old on/off dynamic theme switch was set to
 * (on -> [ThemeColorSource.ARTWORK], off -> [ThemeColorSource.WALLPAPER]) so upgrading keeps
 * the same look. */
@Composable
fun rememberThemeColorSource(): MutableState<ThemeColorSource> {
    val legacyDynamicTheme by rememberPreference(DynamicThemeKey, defaultValue = true)
    return rememberEnumPreference(
        ThemeColorSourceKey,
        defaultValue = if (legacyDynamicTheme) ThemeColorSource.ARTWORK else ThemeColorSource.WALLPAPER,
    )
}

enum class DarkMode {
    AUTO,
    ON,
    OFF,
}

enum class NavigationTab {
    HOME,
    EXPLORE,
    LIBRARY,
}

enum class PlayerTextAlignment {
    SIDED,
    CENTER,
}

enum class LyricsPosition {
    CENTER,
    LEFT,
    RIGHT,
}
