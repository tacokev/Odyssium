package com.skintclub.odyssey.transcripts

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/** One episode's transcript, flattened for searching: every line's text joined by '\n' into
 * [text], with where each line starts in it, when it's spoken and who says it. */
class TranscriptSearchEpisode(
    val id: String,
    val text: String,
    private val lineOffsets: IntArray,
    private val lineStartMs: IntArray,
    private val lineSpeakers: Array<String?>,
) {
    val lineCount get() = lineOffsets.size

    fun lineOf(offset: Int): Int {
        var low = 0
        var high = lineOffsets.lastIndex
        var found = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lineOffsets[mid] <= offset) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    fun lineStart(line: Int) = lineOffsets[line]
    fun lineEnd(line: Int) = if (line + 1 < lineOffsets.size) lineOffsets[line + 1] - 1 else text.length
    fun startMs(line: Int) = lineStartMs[line].toLong()
    fun speaker(line: Int) = lineSpeakers[line]
}

/** A transcript line that has the query in it. [snippet] is (a window of) the line, with the
 * match at [matchStart] until [matchEnd]. */
data class TranscriptMatch(
    val episodeId: String,
    val startMs: Long,
    val speaker: String?,
    val snippet: String,
    val matchStart: Int,
    val matchEnd: Int,
)

/** A search's [matches] to show (the first few hundred), and how many lines in how many
 * episodes match in all. */
data class TranscriptSearchResults(
    val matches: List<TranscriptMatch>,
    val totalLines: Int,
    val totalEpisodes: Int,
)

sealed interface TranscriptSearchState {
    data class Indexing(val done: Int, val total: Int) : TranscriptSearchState
    class Ready(val episodes: Map<String, TranscriptSearchEpisode>) : TranscriptSearchState
    data class Failed(val message: String) : TranscriptSearchState
}

/**
 * Searching every imported transcript at once. The first search reads the whole transcriptions
 * zip into a compact in-memory index (a few seconds); after that each query is a plain scan of
 * the text. Re-indexed whenever a different transcriptions file is imported.
 */
object TranscriptSearch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<TranscriptSearchState?>(null)
    private var job: Job? = null
    private var indexedEntries: Map<String, String>? = null

    /** Starts indexing if it hasn't been (or the imported file changed). */
    fun state(context: Context): StateFlow<TranscriptSearchState?> {
        val appContext = context.applicationContext
        val entries = Transcripts.episodeIds(appContext).value
        synchronized(this) {
            if (entries != null && entries !== indexedEntries) {
                indexedEntries = entries
                job?.cancel()
                job = scope.launch { build(appContext, entries) }
            }
        }
        return _state.asStateFlow()
    }

    private suspend fun build(context: Context, entries: Map<String, String>) {
        _state.value = TranscriptSearchState.Indexing(0, entries.size)
        try {
            val episodes = HashMap<String, TranscriptSearchEpisode>(entries.size * 2)
            ZipFile(Transcripts.file(context)).use { zip ->
                var done = 0
                for ((id, entryName) in entries) {
                    coroutineContext.ensureActive()
                    val transcript = try {
                        Transcripts.read(zip, entryName)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        null
                    }
                    transcript?.let { flatten(id, it) }?.let { episodes[id] = it }
                    done++
                    if (done % 25 == 0) _state.value = TranscriptSearchState.Indexing(done, entries.size)
                }
            }
            _state.value = TranscriptSearchState.Ready(episodes)
        } catch (e: IOException) {
            e.printStackTrace()
            _state.value = TranscriptSearchState.Failed("Couldn't read the transcripts")
        }
    }

    private fun flatten(id: String, transcript: Transcript): TranscriptSearchEpisode? {
        val speakers = transcript.speakers.orEmpty()
        val lines = transcript.utterances.orEmpty()
            .filter { !it.text.isNullOrBlank() }
            .sortedBy { it.start }
        if (lines.isEmpty()) return null
        val builder = StringBuilder()
        val offsets = IntArray(lines.size)
        val starts = IntArray(lines.size)
        val names = arrayOfNulls<String>(lines.size)
        val nameCache = HashMap<String, String?>()
        lines.forEachIndexed { i, utterance ->
            if (i > 0) builder.append('\n')
            offsets[i] = builder.length
            // A line can't hold a newline of its own or it would split in two.
            builder.append(utterance.text!!.trim().replace('\n', ' '))
            starts[i] = (utterance.start * 1000).toInt()
            names[i] = utterance.speaker?.let { key ->
                nameCache.getOrPut(key) { speakers[key]?.name?.takeIf { it.isNotBlank() }?.trim() }
            }
        }
        return TranscriptSearchEpisode(id, builder.toString(), offsets, starts, names)
    }

    /**
     * Lines of [episodes] (in that order) containing [rawQuery]: the first [limit] of them as
     * [TranscriptMatch]es to show, plus how many lines and episodes match in all.
     * See [TranscriptMatcher] for how it matches: case, apostrophes and punctuation don't
     * matter, and words match whole words except the last one while it's still being typed.
     * Checks for cancellation as it goes so a newer keystroke can replace it.
     */
    suspend fun search(
        index: Map<String, TranscriptSearchEpisode>,
        episodeOrder: List<String>,
        rawQuery: String,
        limit: Int = 300,
    ): TranscriptSearchResults {
        val query = TranscriptQuery.parse(rawQuery) ?: return TranscriptSearchResults(emptyList(), 0, 0)
        val results = ArrayList<TranscriptMatch>()
        var totalLines = 0
        var totalEpisodes = 0
        for (id in episodeOrder) {
            coroutineContext.ensureActive()
            val episode = index[id] ?: continue
            val text = episode.text
            var from = 0
            var linesHere = 0
            while (true) {
                val (at, end) = TranscriptMatcher.find(text, query, from) ?: break
                val line = episode.lineOf(at)
                // Past the limit lines are only counted - building a snippet for each would be wasted.
                if (results.size < limit) results.add(matchIn(episode, line, at, end))
                linesHere++
                // One result per line, however often it says it.
                from = episode.lineEnd(line) + 1
            }
            totalLines += linesHere
            if (linesHere > 0) totalEpisodes++
        }
        return TranscriptSearchResults(results, totalLines, totalEpisodes)
    }

    private fun matchIn(episode: TranscriptSearchEpisode, line: Int, at: Int, end: Int): TranscriptMatch {
        val lineStart = episode.lineStart(line)
        val lineEnd = episode.lineEnd(line)
        val matchEnd = minOf(end, lineEnd)
        // Long lines show a window around the match, cut at word boundaries.
        var start = lineStart
        var stop = lineEnd
        val before = 60
        val after = 100
        if (at - start > before) {
            start = at - before
            episode.text.indexOf(' ', start).takeIf { it in start until at }?.let { start = it + 1 }
        }
        if (stop - matchEnd > after) {
            stop = matchEnd + after
            episode.text.lastIndexOf(' ', stop).takeIf { it > matchEnd }?.let { stop = it }
        }
        val prefix = if (start > lineStart) "…" else ""
        val suffix = if (stop < lineEnd) "…" else ""
        return TranscriptMatch(
            episodeId = episode.id,
            startMs = episode.startMs(line),
            speaker = episode.speaker(line),
            snippet = prefix + episode.text.substring(start, stop) + suffix,
            matchStart = prefix.length + (at - start),
            matchEnd = prefix.length + (matchEnd - start),
        )
    }
}
