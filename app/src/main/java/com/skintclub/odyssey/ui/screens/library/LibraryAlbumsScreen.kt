package com.skintclub.odyssey.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.constants.AlbumViewTypeKey
import com.skintclub.odyssey.constants.LibraryViewType
import com.skintclub.odyssey.ui.component.AlbumGridItem
import com.skintclub.odyssey.ui.component.AlbumListItem
import com.skintclub.odyssey.ui.component.LocalMenuState
import com.skintclub.odyssey.ui.menu.AlbumMenu
import com.skintclub.odyssey.utils.rememberEnumPreference
import com.skintclub.odyssey.viewmodels.LibraryAlbumsViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryAlbumsScreen(
    navController: NavController,
    viewModel: LibraryAlbumsViewModel = hiltViewModel(),
) {
    val haptic = LocalHapticFeedback.current
    val menuState = LocalMenuState.current

    val albums by viewModel.allAlbums.collectAsState()
    val viewType by rememberEnumPreference(AlbumViewTypeKey, defaultValue = LibraryViewType.GRID)

    LazyVerticalGrid(
        columns = if (viewType == LibraryViewType.GRID) GridCells.Fixed(2) else GridCells.Fixed(1),
        contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).asPaddingValues(),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = albums,
            key = { it.id }
        ) { album ->
            if (viewType == LibraryViewType.GRID) {
                AlbumGridItem(
                    album = album,
                    modifier = Modifier.combinedClickable(
                        onClick = { navController.navigate("album/${album.id}") },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuState.show {
                                AlbumMenu(
                                    originalAlbum = album,
                                    navController = navController,
                                    onDismiss = menuState::dismiss
                                )
                            }
                        }
                    )
                )
            } else {
                AlbumListItem(
                    album = album,
                    modifier = Modifier.combinedClickable(
                        onClick = { navController.navigate("album/${album.id}") },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuState.show {
                                AlbumMenu(
                                    originalAlbum = album,
                                    navController = navController,
                                    onDismiss = menuState::dismiss
                                )
                            }
                        }
                    )
                )
            }
        }
    }
}
