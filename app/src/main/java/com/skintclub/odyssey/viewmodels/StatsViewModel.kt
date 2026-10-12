package com.skintclub.odyssey.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skintclub.odyssey.db.MusicDatabase
import com.skintclub.odyssey.db.entities.EventWithSong
import com.skintclub.odyssey.db.entities.Song
import com.skintclub.odyssey.db.eventsForCurrentProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class EpisodeStats(
    val song: Song,
    val plays: Int,
    val timeMs: Long,
)

data class ListeningStats(
    val totalTimeMs: Long,
    val thisWeekMs: Long,
    val thisMonthMs: Long,
    val totalPlays: Int,
    val episodesPlayed: Int,
    val daysListened: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val averagePerDayMs: Long,
    val longestSessionMs: Long,
    val favoriteWeekday: DayOfWeek?,
    val favoriteHour: Int?,
    val firstListen: LocalDate?,
    val topEpisodes: List<EpisodeStats>,
)

@HiltViewModel
class StatsViewModel
    @Inject
    constructor(
        @ApplicationContext context: Context,
        database: MusicDatabase,
    ) : ViewModel() {
        val stats =
            database
                .eventsForCurrentProfile(context)
                .map(::computeStats)
                .flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.Lazily, null)

        private fun computeStats(events: List<EventWithSong>): ListeningStats {
            val today = LocalDate.now()
            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val monthStart = today.withDayOfMonth(1)

            val days = events.map { it.event.timestamp.toLocalDate() }.toSortedSet()
            val totalTimeMs = events.sumOf { it.event.playTime }

            // A streak is still alive if the last listen was today or yesterday.
            var currentStreak = 0
            var day = if (today in days) today else today.minusDays(1)
            while (day in days) {
                currentStreak++
                day = day.minusDays(1)
            }

            var longestStreak = 0
            var run = 0
            var previous: LocalDate? = null
            for (d in days) {
                run = if (previous != null && previous.plusDays(1) == d) run + 1 else 1
                longestStreak = maxOf(longestStreak, run)
                previous = d
            }

            val topEpisodes =
                events
                    .groupBy { it.song.id }
                    .map { (_, songEvents) ->
                        EpisodeStats(
                            song = songEvents.first().song,
                            plays = songEvents.size,
                            timeMs = songEvents.sumOf { it.event.playTime },
                        )
                    }.sortedWith(compareByDescending<EpisodeStats> { it.timeMs }.thenByDescending { it.plays })

            return ListeningStats(
                totalTimeMs = totalTimeMs,
                thisWeekMs = events.filter { !it.event.timestamp.toLocalDate().isBefore(weekStart) }.sumOf { it.event.playTime },
                thisMonthMs = events.filter { !it.event.timestamp.toLocalDate().isBefore(monthStart) }.sumOf { it.event.playTime },
                totalPlays = events.size,
                episodesPlayed = topEpisodes.size,
                daysListened = days.size,
                currentStreak = currentStreak,
                longestStreak = longestStreak,
                averagePerDayMs = if (days.isEmpty()) 0 else totalTimeMs / days.size,
                longestSessionMs = events.maxOfOrNull { it.event.playTime } ?: 0,
                favoriteWeekday =
                    events
                        .groupBy { it.event.timestamp.dayOfWeek }
                        .maxByOrNull { (_, e) -> e.sumOf { it.event.playTime } }
                        ?.key,
                favoriteHour =
                    events
                        .groupBy { it.event.timestamp.hour }
                        .maxByOrNull { (_, e) -> e.sumOf { it.event.playTime } }
                        ?.key,
                firstListen = days.firstOrNull(),
                topEpisodes = topEpisodes.take(TOP_EPISODES),
            )
        }

        private companion object {
            const val TOP_EPISODES = 10
        }
    }
