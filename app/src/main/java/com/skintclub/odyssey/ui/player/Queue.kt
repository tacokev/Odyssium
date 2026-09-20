package com.skintclub.odyssey.ui.player

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.constants.ListItemHeight
import com.skintclub.odyssey.constants.QueueEditLockKey
import com.skintclub.odyssey.extensions.metadata
import com.skintclub.odyssey.extensions.move
import com.skintclub.odyssey.extensions.togglePlayPause
import com.skintclub.odyssey.extensions.toggleRepeatMode
import com.skintclub.odyssey.models.MediaMetadata
import com.skintclub.odyssey.ui.component.BottomSheet
import com.skintclub.odyssey.ui.component.BottomSheetState
import com.skintclub.odyssey.ui.component.LocalMenuState
import com.skintclub.odyssey.ui.component.MediaMetadataListItem
import com.skintclub.odyssey.ui.menu.PlayerMenu
import com.skintclub.odyssey.utils.makeTimeString
import com.skintclub.odyssey.utils.rememberPreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@SuppressLint("UnrememberedMutableState")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Queue(
    state: BottomSheetState,
    playerBottomSheetState: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    onBackgroundColor: Color,
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current

    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()

    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsState()

    val selectedSongs: MutableList<MediaMetadata> = mutableStateListOf()
    var selection by remember {
        mutableStateOf(false)
    }

    var locked by rememberPreference(QueueEditLockKey, defaultValue = false)

    val snackbarHostState = remember { SnackbarHostState() }
    var dismissJob: Job? by remember { mutableStateOf(null) }

    BottomSheet(
        state = state,
        brushBackgroundColor =
            Brush.verticalGradient(
                listOf(
                    Color.Unspecified,
                    Color.Unspecified,
                ),
            ),
        modifier = modifier,
        collapsedContent = {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.systemBars
                                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        ),
            ) {
                IconButton(onClick = { state.expandSoft() }) {
                    Icon(
                        painter = painterResource(R.drawable.expand_less),
                        tint = onBackgroundColor,
                        contentDescription = null,
                    )
                }
            }
        },
    ) {
        val queueTitle by playerConnection.queueTitle.collectAsState()
        val queueWindows by playerConnection.queueWindows.collectAsState()
        val mutableQueueWindows = remember { mutableStateListOf<Timeline.Window>() }
        val queueLength =
            remember(queueWindows) {
                queueWindows.sumOf { it.mediaItem.metadata?.duration ?: 0 }
            }

        val coroutineScope = rememberCoroutineScope()

        val headerItems = 1
        val lazyListState = rememberLazyListState()
        var dragStartQueueIndex by remember { mutableIntStateOf(-1) }
        val reorderableState =
            rememberReorderableLazyListState(lazyListState) { from, to ->
                if (to.index >= headerItems && from.index >= headerItems) {
                    mutableQueueWindows.move(
                        from.index - headerItems,
                        to.index - headerItems,
                    )
                }
            }

        LaunchedEffect(queueWindows) {
            mutableQueueWindows.apply {
                clear()
                addAll(queueWindows)
            }
        }

        LaunchedEffect(mutableQueueWindows) {
            if (currentWindowIndex != -1) {
                lazyListState.scrollToItem(currentWindowIndex)
            }
        }

        LazyColumn(
            state = lazyListState,
            contentPadding =
                WindowInsets.systemBars
                    .add(
                        WindowInsets(
                            top = ListItemHeight + 12.dp + 8.dp,
                            bottom = ListItemHeight + 8.dp,
                        ),
                    ).asPaddingValues(),
            modifier =
                Modifier
                    .background(backgroundColor)
                    .nestedScroll(state.preUpPostDownNestedScrollConnection),
        ) {
            item {
                Spacer(
                    modifier =
                        Modifier
                            .animateContentSize()
                            .height(if (selection) 48.dp else 0.dp),
                )
            }

            itemsIndexed(
                items = mutableQueueWindows,
                key = { _, item -> item.uid.hashCode() },
            ) { index, window ->
                ReorderableItem(
                    reorderableState,
                    key = window.uid.hashCode(),
                ) { _ ->
                    val currentItem by rememberUpdatedState(window)
                    val dismissBoxState =
                        rememberSwipeToDismissBoxState(
                            positionalThreshold = { totalDistance ->
                                totalDistance
                            },
                            confirmValueChange = { dismissValue ->
                                if (dismissValue == SwipeToDismissBoxValue.StartToEnd ||
                                    dismissValue == SwipeToDismissBoxValue.EndToStart
                                ) {
                                    playerConnection.player.removeMediaItem(currentItem.firstPeriodIndex)
                                    dismissJob?.cancel()
                                    dismissJob =
                                        coroutineScope.launch {
                                            val snackbarResult =
                                                snackbarHostState.showSnackbar(
                                                    message =
                                                        context.getString(
                                                            R.string.removed_song_from_playlist,
                                                            currentItem.mediaItem.metadata?.title,
                                                        ),
                                                    actionLabel = context.getString(R.string.undo),
                                                    duration = SnackbarDuration.Short,
                                                )
                                            if (snackbarResult == SnackbarResult.ActionPerformed) {
                                                playerConnection.player.addMediaItem(currentItem.mediaItem)
                                                playerConnection.player.moveMediaItem(
                                                    mutableQueueWindows.size,
                                                    currentItem.firstPeriodIndex,
                                                )
                                            }
                                        }
                                }
                                true
                            },
                        )

                    val content: @Composable () -> Unit = {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            MediaMetadataListItem(
                                mediaMetadata = window.mediaItem.metadata!!,
                                isSelected = selection && window.mediaItem.metadata!! in selectedSongs,
                                isActive = index == currentWindowIndex,
                                isPlaying = isPlaying,
                                trailingContent = {
                                    if (!locked) {
                                        IconButton(
                                            onClick = { },
                                            modifier =
                                                Modifier
                                                    .draggableHandle(
                                                        onDragStarted = {
                                                            dragStartQueueIndex =
                                                                mutableQueueWindows.indexOfFirst { it.uid == window.uid }
                                                        },
                                                        onDragStopped = {
                                                            val toQueueIndex =
                                                                mutableQueueWindows.indexOfFirst { it.uid == window.uid }
                                                            if (dragStartQueueIndex != -1 &&
                                                                toQueueIndex != -1 &&
                                                                dragStartQueueIndex != toQueueIndex
                                                            ) {
                                                                if (!playerConnection.player.shuffleModeEnabled) {
                                                                    playerConnection.player.moveMediaItem(dragStartQueueIndex, toQueueIndex)
                                                                } else {
                                                                    playerConnection.player.setShuffleOrder(
                                                                        DefaultShuffleOrder(
                                                                            queueWindows
                                                                                .map { it.firstPeriodIndex }
                                                                                .toMutableList()
                                                                                .move(dragStartQueueIndex, toQueueIndex)
                                                                                .toIntArray(),
                                                                            System.currentTimeMillis(),
                                                                        ),
                                                                    )
                                                                }
                                                            }
                                                            dragStartQueueIndex = -1
                                                        },
                                                    ),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.drag_handle),
                                                contentDescription = null,
                                            )
                                        }
                                    }
                                },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (selection) {
                                                    if (window.mediaItem.metadata!! in selectedSongs) {
                                                        selectedSongs.remove(window.mediaItem.metadata!!)
                                                    } else {
                                                        selectedSongs.add(window.mediaItem.metadata!!)
                                                    }
                                                } else {
                                                    if (index == currentWindowIndex) {
                                                        playerConnection.player.togglePlayPause()
                                                    } else {
                                                        playerConnection.player.seekToDefaultPosition(
                                                            window.firstPeriodIndex,
                                                        )
                                                        playerConnection.player.playWhenReady = true
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                menuState.show {
                                                    PlayerMenu(
                                                        mediaMetadata = window.mediaItem.metadata!!,
                                                        navController = navController,
                                                        playerBottomSheetState = playerBottomSheetState,
                                                        isQueueTrigger = true,
                                                        onShowDetailsDialog = {
                                                            // Details dialog logic removed
                                                        },
                                                        onDismiss = menuState::dismiss,
                                                    )
                                                }
                                            },
                                        ),
                            )
                        }
                    }

                    if (locked) {
                        content()
                    } else {
                        SwipeToDismissBox(
                            state = dismissBoxState,
                            backgroundContent = {},
                        ) {
                            content()
                        }
                    }
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .background(
                        MaterialTheme.colorScheme
                            .surfaceContainer
                            .copy(alpha = 0.95f),
                    ).windowInsetsPadding(
                        WindowInsets.systemBars
                            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    ),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .height(ListItemHeight)
                        .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = queueTitle.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                AnimatedVisibility(
                    visible = !selection,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                ) {
                    Row {
                        IconButton(
                            onClick = {
                                selection = true
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.select),
                                contentDescription = null,
                            )
                        }

                        IconButton(
                            onClick = { locked = !locked },
                            modifier = Modifier.padding(horizontal = 6.dp),
                        ) {
                            Icon(
                                painter = painterResource(if (locked) R.drawable.lock else R.drawable.lock_open),
                                contentDescription = null,
                            )
                        }
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.n_song, queueWindows.size, queueWindows.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Text(
                        text = makeTimeString(queueLength * 1000L),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            AnimatedVisibility(
                visible = selection,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Row(
                    modifier =
                        Modifier
                            .height(48.dp)
                            .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val count = selectedSongs.size
                    Text(text = "$count elements selected", modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            if (count == mutableQueueWindows.size) {
                                selectedSongs.clear()
                            } else {
                                queueWindows
                                    .filter { it.mediaItem.metadata!! !in selectedSongs }
                                    .forEach {
                                        selectedSongs.add(it.mediaItem.metadata!!)
                                    }
                            }
                        },
                    ) {
                        Icon(
                            painter =
                                painterResource(
                                    if (count == mutableQueueWindows.size) {
                                        R.drawable.deselect
                                    } else {
                                        R.drawable.select_all
                                    },
                                ),
                            contentDescription = null,
                        )
                    }

                    IconButton(
                        onClick = {
                            selection = false
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = null,
                        )
                    }
                }
            }
        }

        val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()

        Box(
            modifier =
                Modifier
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f))
                    .fillMaxWidth()
                    .height(
                        ListItemHeight +
                            WindowInsets.systemBars
                                .asPaddingValues()
                                .calculateBottomPadding(),
                    ).align(Alignment.BottomCenter)
                    .clickable {
                        state.collapseSoft()
                    }.windowInsetsPadding(
                        WindowInsets.systemBars
                            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ).padding(12.dp),
        ) {
            IconButton(
                modifier = Modifier.align(Alignment.CenterStart),
                onClick = {
                    coroutineScope
                        .launch {
                            lazyListState.animateScrollToItem(
                                if (playerConnection.player.shuffleModeEnabled) playerConnection.player.currentMediaItemIndex else 0,
                            )
                        }.invokeOnCompletion {
                            playerConnection.player.shuffleModeEnabled = !playerConnection.player.shuffleModeEnabled
                        }
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.shuffle),
                    contentDescription = null,
                    modifier = Modifier.alpha(if (shuffleModeEnabled) 1f else 0.5f),
                )
            }

            Icon(
                painter = painterResource(R.drawable.expand_more),
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center),
            )

            IconButton(
                modifier = Modifier.align(Alignment.CenterEnd),
                onClick = playerConnection.player::toggleRepeatMode,
            ) {
                Icon(
                    painter =
                        painterResource(
                            when (repeatMode) {
                                Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL -> R.drawable.repeat
                                Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                                else -> throw IllegalStateException()
                            },
                        ),
                    contentDescription = null,
                    modifier = Modifier.alpha(if (repeatMode == Player.REPEAT_MODE_OFF) 0.5f else 1f),
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .padding(
                        bottom =
                            ListItemHeight +
                                WindowInsets.systemBars
                                    .asPaddingValues()
                                    .calculateBottomPadding(),
                    ).align(Alignment.BottomCenter),
        )
    }
}
