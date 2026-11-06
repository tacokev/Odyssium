package com.skintclub.odyssey.transcripts

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Who an utterance's speaker label stands for. Null in [Transcript.speakers] for speakers the
 * transcriber couldn't name. */
data class TranscriptSpeaker(
    val name: String? = null,
    val actor: String? = null,
    @SerializedName("club_character_id") val clubCharacterId: String? = null,
)

/** One line of an episode: [start] and [end] are in seconds; [speaker] is a key of
 * [Transcript.speakers] (e.g. "SPEAKER_04"). */
data class TranscriptUtterance(
    val start: Double = 0.0,
    val end: Double = 0.0,
    val speaker: String? = null,
    val text: String? = null,
)

/** An episode's transcript, as one `<content id>.json` entry of the imported zip. */
data class Transcript(
    val id: String? = null,
    val speakers: Map<String, TranscriptSpeaker?>? = null,
    val utterances: List<TranscriptUtterance>? = null,
)

/**
 * The imported transcriptions file (Settings > Content > Transcriptions): a zip of
 * `<club content id>.json` files, kept zipped in the app's files dir and read one episode at a
 * time.
 */
object Transcripts {
    private const val FILE_NAME = "transcripts.zip"
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** Zip entry name by content id. Null until the imported file has been indexed. */
    private val _entries = MutableStateFlow<Map<String, String>?>(null)
    private var indexStarted = false

    private val cache = object : LinkedHashMap<String, Transcript>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Transcript>?) = size > 4
    }

    internal fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Each content id that has a transcript, to its zip entry - empty when none is imported,
     * null while the imported file is still being indexed. */
    fun episodeIds(context: Context): StateFlow<Map<String, String>?> {
        val appContext = context.applicationContext
        synchronized(this) {
            if (!indexStarted) {
                indexStarted = true
                scope.launch { mutex.withLock { _entries.value = indexOrEmpty(file(appContext)) } }
            }
        }
        return _entries.asStateFlow()
    }

    private fun index(zip: File): Map<String, String> =
        ZipFile(zip).use { zipFile ->
            zipFile.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }
                .associate { it.name.substringAfterLast('/').substringBeforeLast('.') to it.name }
        }

    private fun indexOrEmpty(zip: File): Map<String, String> =
        if (!zip.exists()) emptyMap() else try {
            index(zip)
        } catch (e: IOException) {
            e.printStackTrace()
            emptyMap()
        }

    /** Copies the zip at [uri] in as the transcriptions file, replacing any earlier one.
     * Returns how many episodes it has; throws if it isn't a transcriptions zip. */
    suspend fun import(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        mutex.withLock {
            val target = file(appContext)
            val temp = File(appContext.filesDir, "$FILE_NAME.importing")
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { input.copyTo(it) }
                } ?: throw IOException("Couldn't open the file")
                val entries = try {
                    index(temp)
                } catch (e: IOException) {
                    throw IOException("That isn't a zip file")
                }
                val sample = entries.values.firstOrNull() ?: throw IOException("No transcripts in that file")
                val parsed = ZipFile(temp).use { zip -> read(zip, sample) }
                if (parsed?.utterances == null) throw IOException("No transcripts in that file")
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                }
                synchronized(cache) { cache.clear() }
                _entries.value = entries
                indexStarted = true
                entries.size
            } finally {
                temp.delete()
            }
        }
    }

    /** Deletes the imported transcriptions file. */
    suspend fun remove(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            file(context.applicationContext).delete()
            synchronized(cache) { cache.clear() }
            _entries.value = emptyMap()
            indexStarted = true
        }
    }

    /** The transcript of content [id], or null if there isn't one. */
    suspend fun load(context: Context, id: String): Transcript? = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[id] }?.let { return@withContext it }
        val entries = episodeIds(context).value ?: mutex.withLock { _entries.value }
        val entryName = entries?.get(id) ?: return@withContext null
        val transcript = mutex.withLock {
            try {
                ZipFile(file(context.applicationContext)).use { read(it, entryName) }
            } catch (e: IOException) {
                e.printStackTrace()
                null
            }
        } ?: return@withContext null
        synchronized(cache) { cache[id] = transcript }
        transcript
    }

    internal fun read(zip: ZipFile, entryName: String): Transcript? {
        val entry = zip.getEntry(entryName) ?: return null
        return zip.getInputStream(entry).bufferedReader().use { gson.fromJson(it, Transcript::class.java) }
    }
}
