package com.skintclub.odyssey.mabel

import com.skintclub.odyssey.viewmodels.EpisodeProgress
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** One thing in a Mabel session: her speaking, or an episode. */
sealed interface MabelSegment {
    /** [vibe] is the vibe's on-screen name, shown under "Mabel" while she talks. */
    data class Speak(val text: String, val vibe: String?) : MabelSegment

    data class Play(val episode: Map<String, Any>) : MabelSegment
}

/** What the listener's been playing, for picking what they'd like. */
data class MabelHistory(
    /** Content ids played, with when; newest first, repeats allowed. */
    val plays: List<Pair<String, Instant>> = emptyList(),
    val progress: Map<String, EpisodeProgress> = emptyMap(),
    val liked: Set<String> = emptySet(),
)

/**
 * Plans a listening session, one vibe at a time: Mabel picks a vibe, says why, introduces the
 * first episode, and plays two to four episodes from it (a multi-part story all the way
 * through), then switches to another.
 *
 * Vibes are picked by what the listener's been enjoying ([MabelHistory]: finished and liked
 * episodes count for their vibes, ones given up on count against), with one in four picked
 * from the ones they haven't been listening to, so it isn't only their favorites. Christmas
 * and other seasonal vibes come up in their season and hardly ever out of it. Episodes they
 * finished lately are left out, and the first thing she offers is to pick a multi-part story
 * back up where they left off.
 *
 * [pool] is every episode she can play (the randomizer's filters, except that later parts are
 * allowed: a story is always played from part one, or from where the listener left off).
 */
class MabelSession(
    private val data: MabelData,
    pool: List<Map<String, Any>>,
    private val history: MabelHistory,
    private val random: Random = Random.Default,
    private val now: Instant = Instant.now(),
    private val today: LocalDate = LocalDate.now(),
) {
    /** A standalone episode, or every part of a story in order. */
    private class Story(val episodes: List<Pair<Map<String, Any>, MabelData.EpisodeInfo>>) {
        val ids = episodes.map { it.first["id"] as String }
        // In more than half its parts, so one part's stray tag doesn't put a whole story in a vibe.
        val vibes = episodes.flatMap { it.second.vibes.distinct() }
            .groupingBy { it }.eachCount()
            .filterValues { it * 2 > episodes.size }
            .keys
        val number = episodes.mapNotNull { it.second.number }.minOrNull() ?: Int.MAX_VALUE
        val season = episodes.firstNotNullOfOrNull { it.second.season }
        val title = spokenTitle(episodes.first().second.title)
    }

    private val units: List<Story>
    private val unitsByVibe: Map<String, List<Story>>
    private val vibesById = data.vibes.associateBy { it.id }

    /** -1 to 1, by vibe id. */
    private val taste: MutableMap<String, Double>
    private val recentlyFinished: Set<String>

    private val played = mutableSetOf<String>()
    private val recentVibes = ArrayDeque<String>()
    private var lastUnit: Story? = null
    private var started = false

    /** The vibe each episode was played for, and how many times each vibe's been skipped. */
    private val vibeOf = mutableMapOf<String, String>()
    private val skips = mutableMapOf<String, Int>()

    init {
        val known = pool.mapNotNull { episode ->
            val id = episode["id"] as? String ?: return@mapNotNull null
            data.episodes[id]?.let { episode to it }
        }
        val (parts, standalone) = known.partition { it.second.part?.size == 2 }
        units = standalone.map { Story(listOf(it)) } +
            parts.groupBy { (_, info) -> spokenTitle(info.title).lowercase() to info.part!![1] }
                .values
                .map { story -> Story(story.sortedBy { it.second.part!![0] }) }
        unitsByVibe = units.flatMap { unit -> unit.vibes.map { it to unit } }
            .groupBy({ it.first }, { it.second })

        recentlyFinished = history.progress
            .filter { (_, p) -> p.completed && p.updatedAt?.let { Duration.between(it, now).toDays() < FINISHED_LATELY_DAYS } == true }
            .keys
        taste = scoreVibes().toMutableMap()
    }

    /**
     * The listener skipped [contentId] early: its vibe comes up much less for the rest of the
     * session, and the episode's other vibes a little less.
     */
    @Synchronized
    fun onSkipped(contentId: String) {
        timber.log.Timber.d("Mabel: skipped %s (vibe %s)", contentId, vibeOf[contentId])
        vibeOf[contentId]?.let { skips[it] = (skips[it] ?: 0) + 1 }
        data.episodes[contentId]?.vibes?.forEach { vibe ->
            taste[vibe] = ((taste[vibe] ?: 0.0) - 0.3).coerceAtLeast(-1.0)
        }
    }

    private fun scoreVibes(): Map<String, Double> {
        val raw = mutableMapOf<String, Double>()
        fun add(id: String, weight: Double) {
            data.episodes[id]?.vibes?.forEach { raw[it] = (raw[it] ?: 0.0) + weight }
        }
        history.plays.distinctBy { it.first }.forEach { (id, at) ->
            val days = Duration.between(at, now).toHours() / 24.0
            if (days > TASTE_DAYS) return@forEach
            val progress = history.progress[id]
            val weight = when {
                progress?.completed == true -> 1.0
                // Given up on: started more than a day ago and barely listened to.
                progress != null && progress.fraction < 0.15f &&
                    progress.updatedAt?.let { Duration.between(it, now).toHours() > 24 } == true -> -0.6
                else -> 0.5
            }
            add(id, weight * exp(-days / 21))
        }
        history.liked.forEach { add(it, 1.5) }
        val sized = raw.mapValues { (vibe, score) -> score / sqrt(max(1, unitsByVibe[vibe]?.size ?: 1).toDouble()) }
        val top = sized.values.maxOrNull()?.takeIf { it > 0 } ?: return emptyMap()
        return sized.mapValues { (_, score) -> (score / top).coerceIn(-1.0, 1.0) }
    }

    private fun available(unit: Story) = unit.ids.none { it in played } && !unit.ids.all { it in recentlyFinished }

    /** The first stretch: hello, then a story to pick back up if there is one, or a vibe. */
    @Synchronized
    fun start(): List<MabelSegment> {
        started = true
        val opener = data.openers.randomOrNull(random)
        val resume = resumable()
        val block = if (resume != null) {
            val (unit, from) = resume
            val finished = unit.episodes[from - 1].second.part!![0]
            val line = data.resumeLines.randomOrNull(random)
                ?.fill(part = spell(finished), title = unit.title)
                ?: "Last time you got through part ${spell(finished)} of ${unit.title}. Let's keep going."
            play(unit, unit.episodes.drop(from), lead = line, vibe = null)
        } else {
            null
        }
        return block?.let { it.withOpener(opener) } ?: nextBlock().withOpener(opener)
    }

    /** The next vibe, and what she says switching to it. */
    @Synchronized
    fun nextBlock(): List<MabelSegment> {
        if (!started) return start()
        val vibe = pickVibe() ?: run {
            // Heard everything there is: start over.
            played.clear()
            pickVibe()
        } ?: return emptyList()
        recentVibes.addLast(vibe.id)
        while (recentVibes.size > RECENT_VIBES) recentVibes.removeFirst()

        val picked = pickUnits(vibe)
        picked.flatMap { it.ids }.forEach { vibeOf[it] = vibe.id }
        val first = picked.first().ids.first()
        val tasteScore = taste[vibe.id] ?: 0.0
        val lead = buildString {
            val tasteLine = data.tasteLines.randomOrNull(random)
            if (tasteScore >= 0.5 && tasteLine != null && random.nextDouble() < 0.6) {
                append(tasteLine.fill(vibe = vibe.spoken))
            } else {
                append(vibe.switchLines.randomOrNull(random) ?: "Alright, let's switch it up with ${vibe.spoken}.")
                // Something they liked that's in this vibe, other than what's about to play.
                val liked = history.liked
                    .filter { it != first && it in data.episodes && vibe.id in data.episodes[it]!!.vibes }
                    .randomOrNull(random)
                val likedLine = data.likedLines.randomOrNull(random)
                if (liked != null && likedLine != null && random.nextDouble() < 0.5) {
                    append(' ')
                    append(likedLine.fill(title = spokenTitle(data.episodes[liked]!!.title)))
                }
            }
        }
        return picked.flatMapIndexed { index, unit ->
            play(unit, unit.episodes, lead = lead.takeIf { index == 0 }, vibe = vibe.name)
        }
    }

    private fun pickVibe(): MabelData.Vibe? {
        val candidates = data.vibes.filter { vibe ->
            vibe.id !in recentVibes && (unitsByVibe[vibe.id]?.count(::available) ?: 0) >= 2
        }.ifEmpty {
            data.vibes.filter { vibe -> (unitsByVibe[vibe.id]?.count(::available) ?: 0) >= 1 }
        }
        if (candidates.isEmpty()) return null
        val explore = random.nextDouble() < EXPLORE
        val weights = candidates.map { vibe ->
            val score = taste[vibe.id] ?: 0.0
            var weight = if (explore) {
                if (score <= 0.15) 1.0 else 0.05
            } else {
                max(0.1, 1 + 4 * score)
            }
            val vibeUnits = unitsByVibe[vibe.id].orEmpty()
            weight *= 1 + ln(vibeUnits.size.toDouble()) / 3
            // A seasonal vibe: mostly in its season.
            val season = vibeUnits.mapNotNull { it.season }.groupingBy { it }.eachCount().maxByOrNull { it.value }
            if (season != null && season.value * 2 > vibeUnits.size) {
                // A holiday hardly ever out of its season; the time of year just a little.
                val holiday = season.key !in WEATHER
                weight *= when {
                    inSeason(season.key) -> if (holiday) 4.0 else 1.5
                    else -> if (holiday) 0.03 else 0.5
                }
            }
            // Something in common with what just played, so the switch doesn't feel random.
            if (lastUnit?.vibes?.contains(vibe.id) == true) weight *= 1.5
            // Skipped this session.
            skips[vibe.id]?.let { weight *= Math.pow(0.25, it.toDouble()) }
            weight
        }
        return candidates[weightedIndex(weights)]
    }

    /** Two to four episodes from [vibe] (a story counts all its parts). */
    private fun pickUnits(vibe: MabelData.Vibe): List<Story> {
        val options = unitsByVibe[vibe.id].orEmpty().filter(::available)
        val ordered = if (vibe.ordered) options.sortedBy { it.number } else options.shuffled(random)
        val target = 2 + random.nextInt(2)
        val picked = mutableListOf<Story>()
        var count = 0
        for (unit in ordered) {
            if (picked.isNotEmpty() && count + unit.episodes.size > MAX_EPISODES) {
                if (vibe.ordered) break else continue
            }
            picked += unit
            count += unit.episodes.size
            if (count >= target) break
        }
        return picked
    }

    /** A multi-part story the listener finished some (not all) parts of lately: the story,
     * and the index of the part to go on with. */
    private fun resumable(): Pair<Story, Int>? =
        units.filter { it.episodes.size > 1 && it.ids.none { id -> id in played } }
            .mapNotNull { unit ->
                val lastDone = unit.ids.indexOfLast { history.progress[it]?.completed == true }
                if (lastDone < 0 || lastDone == unit.ids.lastIndex) return@mapNotNull null
                if (history.progress[unit.ids[lastDone + 1]]?.completed == true) return@mapNotNull null
                val at = history.progress[unit.ids[lastDone]]?.updatedAt ?: return@mapNotNull null
                if (Duration.between(at, now).toDays() > RESUME_DAYS) return@mapNotNull null
                Triple(unit, lastDone + 1, at)
            }
            .maxByOrNull { it.third }
            ?.let { it.first to it.second }

    /** [episodes] of [unit], with [lead] and an intro before the first, and a "part two" line
     * before each part after it. */
    private fun play(
        unit: Story,
        episodes: List<Pair<Map<String, Any>, MabelData.EpisodeInfo>>,
        lead: String?,
        vibe: String?,
    ): List<MabelSegment> {
        lastUnit = unit
        played += unit.ids
        return episodes.flatMapIndexed { index, (episode, info) ->
            val intro = info.intros.randomOrNull(random) ?: "Up next, ${spokenTitle(info.title)}."
            val line = when {
                index == 0 && lead != null -> "$lead $intro"
                index == 0 -> intro.takeIf { random.nextDouble() < 0.35 }
                else -> info.part?.let { part ->
                    data.partLines.randomOrNull(random)?.fill(part = spell(part[0])) ?: "And now, part ${spell(part[0])}."
                }
            }
            listOfNotNull(line?.let { MabelSegment.Speak(it, vibe) }, MabelSegment.Play(episode))
        }
    }

    private fun List<MabelSegment>.withOpener(opener: String?): List<MabelSegment> {
        if (opener == null) return this
        val first = firstOrNull()
        return if (first is MabelSegment.Speak) {
            listOf(first.copy(text = "$opener ${first.text}")) + drop(1)
        } else {
            listOf(MabelSegment.Speak(opener, null)) + this
        }
    }

    private fun inSeason(season: String): Boolean {
        val month = today.month
        return when (season) {
            "christmas" -> month == Month.DECEMBER || (month == Month.NOVEMBER && today.dayOfMonth > 20)
            "thanksgiving" -> month == Month.NOVEMBER
            "halloween" -> month == Month.OCTOBER
            "easter" -> month == Month.MARCH || month == Month.APRIL
            "new year" -> (month == Month.DECEMBER && today.dayOfMonth > 26) || (month == Month.JANUARY && today.dayOfMonth < 10)
            "valentines day" -> month == Month.FEBRUARY && today.dayOfMonth < 16
            "summer" -> month in listOf(Month.JUNE, Month.JULY, Month.AUGUST)
            "fall", "autumn" -> month in listOf(Month.SEPTEMBER, Month.OCTOBER, Month.NOVEMBER)
            "winter" -> month in listOf(Month.DECEMBER, Month.JANUARY, Month.FEBRUARY)
            "spring" -> month in listOf(Month.MARCH, Month.APRIL, Month.MAY)
            "back to school" -> month == Month.AUGUST || month == Month.SEPTEMBER
            "independence day" -> month == Month.JULY && today.dayOfMonth < 8
            else -> true
        }
    }

    private fun weightedIndex(weights: List<Double>): Int {
        var pick = random.nextDouble() * weights.sum()
        weights.forEachIndexed { index, weight ->
            pick -= weight
            if (pick <= 0) return index
        }
        return weights.lastIndex
    }

    private companion object {
        const val EXPLORE = 0.25
        const val MAX_EPISODES = 4
        const val RECENT_VIBES = 4
        const val TASTE_DAYS = 60
        const val FINISHED_LATELY_DAYS = 45L
        const val RESUME_DAYS = 21L

        val WEATHER = setOf("summer", "fall", "autumn", "winter", "spring")

        val PART_SUFFIX = Regex("""[,:]?\s*\(?Part \w+ of \w+\)?""", RegexOption.IGNORE_CASE)

        fun spokenTitle(title: String) = title.replace(PART_SUFFIX, "").trim()

        val NUMBERS = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve",
        )

        fun spell(n: Int) = NUMBERS.getOrNull(n) ?: n.toString()

        fun String.fill(vibe: String? = null, title: String? = null, part: String? = null): String {
            var text = this
            if (vibe != null) text = text.replace("{vibe}", vibe)
            if (title != null) text = text.replace("{title}", title)
            if (part != null) text = text.replace("{part}", part)
            return text
        }
    }
}
