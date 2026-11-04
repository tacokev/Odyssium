package com.skintclub.odyssey.mabel

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What Mabel knows and says, from mabel.json (written by mabel_lines.py on a PC). */
data class MabelData(
    val version: Int = 0,
    val openers: List<String> = emptyList(),
    @SerializedName("part_lines") val partLines: List<String> = emptyList(),
    @SerializedName("taste_lines") val tasteLines: List<String> = emptyList(),
    @SerializedName("liked_lines") val likedLines: List<String> = emptyList(),
    @SerializedName("resume_lines") val resumeLines: List<String> = emptyList(),
    val vibes: List<Vibe> = emptyList(),
    /** By content id. */
    val episodes: Map<String, EpisodeInfo> = emptyMap(),
) {
    data class Vibe(
        val id: String = "",
        /** As shown on screen: "Novacom". */
        val name: String = "",
        /** As she says it after "let's switch it up with": "a Novacom vibe". */
        val spoken: String = "",
        @SerializedName("switch_lines") val switchLines: List<String> = emptyList(),
        /** A story arc, played in episode order. */
        val ordered: Boolean = false,
    )

    data class EpisodeInfo(
        val title: String = "",
        val number: Int? = null,
        /** [part, of] for a multi-part story. */
        val part: List<Int>? = null,
        val summary: String? = null,
        val arc: String? = null,
        val season: String? = null,
        val vibes: List<String> = emptyList(),
        val intros: List<String> = emptyList(),
    )
}

/** Mabel's lines: imported from mabel.json, and kept in the app's files. */
@Singleton
class MabelLibrary
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val file = File(context.filesDir, "mabel/mabel.json")

        private val _data = MutableStateFlow<MabelData?>(null)
        val data: StateFlow<MabelData?> = _data.asStateFlow()

        private val _importing = MutableStateFlow(false)
        val importing: StateFlow<Boolean> = _importing.asStateFlow()
        private val _importError = MutableStateFlow<String?>(null)
        val importError: StateFlow<String?> = _importError.asStateFlow()

        init {
            scope.launch {
                if (file.exists()) {
                    _data.value = runCatching { parse(file.readText()) }
                        .onFailure { Timber.w(it, "Couldn't read mabel.json") }
                        .getOrNull()
                }
            }
        }

        fun import(uri: Uri) {
            if (_importing.value) return
            _importing.value = true
            _importError.value = null
            scope.launch {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
                        ?: error("Couldn't open the file")
                    val parsed = parse(text)
                    file.parentFile?.mkdirs()
                    file.writeText(text)
                    _data.value = parsed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Mabel lines import failed")
                    _importError.value = e.message ?: e.javaClass.simpleName
                } finally {
                    _importing.value = false
                }
            }
        }

        fun delete() {
            scope.launch {
                file.delete()
                _data.value = null
            }
        }

        private fun parse(text: String): MabelData {
            val data = Gson().fromJson(text, MabelData::class.java) ?: error("The file is empty")
            check(data.episodes.isNotEmpty() && data.vibes.isNotEmpty()) { "That isn't a mabel.json file" }
            return data
        }
    }
