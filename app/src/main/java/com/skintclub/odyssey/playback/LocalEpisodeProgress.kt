package com.skintclub.odyssey.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skintclub.odyssey.constants.ClubViewerIdKey
import com.skintclub.odyssey.utils.dataStore
import com.skintclub.odyssey.viewmodels.EpisodeProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Its own file, apart from the settings (which hold the multi-megabyte groupings cache), since
 * it's written every 30 seconds while an episode plays. */
private val Context.progressDataStore: DataStore<Preferences> by preferencesDataStore(name = "episode_progress")

/** Where a profile got to in one episode, as last played in the app. */
private data class SavedProgress(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val completed: Boolean = false,
    val updatedAtMs: Long = 0,
)

private val gson = Gson()
private val savedProgressType = object : TypeToken<Map<String, SavedProgress>>() {}.type

private fun keyFor(viewerId: String) = stringPreferencesKey("progress_$viewerId")

private fun Preferences.savedProgress(viewerId: String): Map<String, SavedProgress> =
    this[keyFor(viewerId)]
        ?.let { runCatching { gson.fromJson<Map<String, SavedProgress>>(it, savedProgressType) }.getOrNull() }
        .orEmpty()

/**
 * Each profile's progress through the episodes it played in the app, kept on the device. The
 * club's progress only catches up after a sync and a refresh (and never with "Sync episode
 * progress" off), so History uses whichever of the two is newer.
 */
object LocalEpisodeProgress {
    /** Records the selected profile's progress on [contentId]: [positionMs] into it (of
     * [durationMs], when known), or [completed]. Without a selected profile, does nothing. */
    suspend fun save(
        context: Context,
        contentId: String,
        positionMs: Long?,
        durationMs: Long?,
        completed: Boolean,
    ) {
        val viewerId = context.dataStore.data.first()[ClubViewerIdKey] ?: return
        context.progressDataStore.edit { prefs ->
            val saved = prefs.savedProgress(viewerId).toMutableMap()
            val previous = saved[contentId]
            saved[contentId] = SavedProgress(
                positionMs = if (completed) 0 else positionMs ?: previous?.positionMs ?: 0,
                durationMs = durationMs?.takeIf { it > 0 } ?: previous?.durationMs ?: 0,
                completed = completed,
                updatedAtMs = System.currentTimeMillis(),
            )
            prefs[keyFor(viewerId)] = gson.toJson(saved)
        }
    }

    /** The selected profile's progress, by content id, switching along with the profile. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun forCurrentProfile(context: Context): Flow<Map<String, EpisodeProgress>> =
        context.dataStore.data
            .map { it[ClubViewerIdKey] }
            .distinctUntilChanged()
            .flatMapLatest { viewerId ->
                if (viewerId == null) {
                    flowOf(emptyMap())
                } else {
                    context.progressDataStore.data.map { prefs ->
                        prefs.savedProgress(viewerId).mapValues { (_, saved) ->
                            EpisodeProgress(
                                completed = saved.completed,
                                positionMs = saved.positionMs,
                                lengthMs = saved.durationMs,
                                updatedAt = Instant.ofEpochMilli(saved.updatedAtMs),
                            )
                        }
                    }
                }
            }
}
