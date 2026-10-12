package com.skintclub.odyssey.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skintclub.odyssey.constants.ShowClubHistoryKey
import com.skintclub.odyssey.db.MusicDatabase
import com.skintclub.odyssey.db.entities.EventWithSong
import com.skintclub.odyssey.db.eventsForCurrentProfile
import com.skintclub.odyssey.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        @ApplicationContext context: Context,
        val database: MusicDatabase,
    ) : ViewModel() {
        private val today = LocalDate.now()
        private val thisMonday = today.with(DayOfWeek.MONDAY)
        private val lastMonday = thisMonday.minusDays(7)

        /** The selected profile's club listening, handed over by the screen (it lives in
         * [OdysseyViewModel], along with the groupings it's read from). */
        private val clubHistory = MutableStateFlow(ClubHistory.EMPTY)

        fun setClubHistory(history: ClubHistory) {
            clubHistory.value = history
        }

        private val showClubHistory =
            context.dataStore.data
                .map { it[ShowClubHistoryKey] ?: true }
                .distinctUntilChanged()

        val history =
            combine(database.eventsForCurrentProfile(context), clubHistory, showClubHistory) { events, club, showClub ->
                val played = events.groupBy { dateAgoOf(it.event.timestamp.toLocalDate()) }
                    .mapValues { (_, events) -> events.distinctBy { it.song.id }.map(HistoryItem::Played) }
                val fromClub = if (showClub) {
                    club.entries
                        .groupBy { entry -> entry.time?.let { dateAgoOf(it.toLocalDate()) } ?: DateAgo.Earlier }
                        .mapValues { (_, entries) -> entries.map(HistoryItem::Club) }
                } else {
                    emptyMap()
                }
                val groups = (played.keys + fromClub.keys)
                    .associateWith { dateAgo ->
                        val playedHere = played[dateAgo].orEmpty()
                        // An episode played in the app is on the club too once its progress
                        // syncs; when both fall in the same section, the app's own play shows.
                        val playedIds = playedHere.mapTo(HashSet()) { it.id }
                        (playedHere + fromClub[dateAgo].orEmpty().filter { it.id !in playedIds })
                            .sortedWith(compareBy(nullsLast(reverseOrder())) { it.time })
                    }.toSortedMap(compareBy(::sortKeyOf))
                HistoryState(groups, club.progress)
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.Lazily, HistoryState.EMPTY)

        private fun dateAgoOf(date: LocalDate): DateAgo {
            val daysAgo = ChronoUnit.DAYS.between(date, today).toInt()
            return when {
                daysAgo == 0 -> DateAgo.Today
                daysAgo == 1 -> DateAgo.Yesterday
                date >= thisMonday -> DateAgo.ThisWeek
                date >= lastMonday -> DateAgo.LastWeek
                else -> DateAgo.Other(date.withDayOfMonth(1))
            }
        }

        private fun sortKeyOf(dateAgo: DateAgo): Long = when (dateAgo) {
            DateAgo.Today -> 0L
            DateAgo.Yesterday -> 1L
            DateAgo.ThisWeek -> 2L
            DateAgo.LastWeek -> 3L
            is DateAgo.Other -> ChronoUnit.DAYS.between(dateAgo.date, today)
            DateAgo.Earlier -> Long.MAX_VALUE
        }
    }

/** One row of History: an episode played in the app, or one listened to on the club. */
sealed interface HistoryItem {
    val id: String

    /** Null only for club progress saved without a time. */
    val time: LocalDateTime?

    data class Played(val event: EventWithSong) : HistoryItem {
        override val id get() = event.song.id
        override val time: LocalDateTime get() = event.event.timestamp
    }

    data class Club(val entry: ClubHistoryEntry) : HistoryItem {
        override val id get() = entry.episode.episode["id"] as? String ?: ""
        override val time get() = entry.time
    }
}

/** History's sections, newest first, and the club's progress on each episode for their rows. */
data class HistoryState(
    val groups: Map<DateAgo, List<HistoryItem>>,
    val progress: Map<String, EpisodeProgress>,
) {
    companion object {
        val EMPTY = HistoryState(emptyMap(), emptyMap())
    }
}

sealed class DateAgo {
    data object Today : DateAgo()

    data object Yesterday : DateAgo()

    data object ThisWeek : DateAgo()

    data object LastWeek : DateAgo()

    /** Club listening without a time, after everything else. */
    data object Earlier : DateAgo()

    class Other(
        val date: LocalDate,
    ) : DateAgo() {
        override fun equals(other: Any?): Boolean {
            if (other is Other) return date == other.date
            return super.equals(other)
        }

        override fun hashCode(): Int = date.hashCode()
    }
}
