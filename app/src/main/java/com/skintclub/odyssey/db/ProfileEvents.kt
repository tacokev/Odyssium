package com.skintclub.odyssey.db

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.skintclub.odyssey.constants.ClubViewerIdKey
import com.skintclub.odyssey.constants.ListenHistoryAssignedKey
import com.skintclub.odyssey.db.entities.EventWithSong
import com.skintclub.odyssey.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * The selected club profile's listen history, newest first, switching along with the profile.
 *
 * History from before it was kept per profile has no profile. The first time a profile is
 * selected, all of it goes to that profile, since that's whose it most likely was. That happens
 * only once, so a later profile doesn't pick up anything played without one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun MusicDatabase.eventsForCurrentProfile(context: Context): Flow<List<EventWithSong>> =
    context.dataStore.data
        .map { it[ClubViewerIdKey] }
        .distinctUntilChanged()
        .onEach { viewerId ->
            if (viewerId == null) return@onEach
            val dataStore = context.dataStore
            if (dataStore.data.first()[ListenHistoryAssignedKey] == true) return@onEach
            withContext(Dispatchers.IO) { assignUnownedEvents(viewerId) }
            dataStore.edit { it[ListenHistoryAssignedKey] = true }
        }
        .flatMapLatest { viewerId -> events(viewerId) }
