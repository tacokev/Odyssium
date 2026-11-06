package com.skintclub.odyssey.mabel

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.skintclub.odyssey.constants.MABEL_MONOTONE_DEFAULT
import com.skintclub.odyssey.constants.MabelMonotoneKey
import com.skintclub.odyssey.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Mabel's voice: a Piper voice model run on the device by sherpa-onnx. It isn't part of the
 * app; it's imported from a zip of the model (`*.onnx`, with sherpa-onnx's metadata),
 * `tokens.txt`, and `espeak-ng-data/`, which turns text into the phonemes the model speaks.
 */
@Singleton
class MabelVoice
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val dir = File(context.filesDir, "mabel/voice")
        private val clipDir = File(context.cacheDir, "mabel")

        /** Whether a voice has been imported. */
        private val _isAvailable = MutableStateFlow(File(dir, MODEL).exists())
        val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

        /** The import in progress (true), or why the last one failed. */
        private val _importing = MutableStateFlow(false)
        val importing: StateFlow<Boolean> = _importing.asStateFlow()
        private val _importError = MutableStateFlow<String?>(null)
        val importError: StateFlow<String?> = _importError.asStateFlow()

        /** Loaded on first use; generating isn't thread-safe, so it's used one at a time. */
        private var tts: OfflineTts? = null
        private val ttsLock = Mutex()

        fun importZip(uri: Uri) {
            if (_importing.value) return
            _importing.value = true
            _importError.value = null
            scope.launch {
                val temp = File(context.filesDir, "mabel/voice.tmp")
                try {
                    temp.deleteRecursively()
                    temp.mkdirs()
                    val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open the file")
                    ZipInputStream(input.buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.isDirectory) continue
                            // Wherever the files are in the zip, they're kept as model.onnx,
                            // tokens.txt and espeak-ng-data/...
                            val name = entry.name.replace('\\', '/')
                            val target = when {
                                name.endsWith(".onnx") -> File(temp, MODEL)
                                name.substringAfterLast('/') == TOKENS -> File(temp, TOKENS)
                                "$ESPEAK_DATA/" in "/$name" ->
                                    File(temp, ESPEAK_DATA + "/" + name.substringAfter("$ESPEAK_DATA/"))
                                else -> continue
                            }
                            if (!target.canonicalPath.startsWith(temp.canonicalPath)) continue
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                    check(File(temp, MODEL).exists()) { "No .onnx model in the zip" }
                    check(File(temp, TOKENS).exists()) { "No tokens.txt in the zip" }
                    keepSingleCharacterTokens(File(temp, TOKENS))
                    check(File(temp, ESPEAK_DATA).isDirectory) { "No espeak-ng-data in the zip" }
                    ttsLock.withLock {
                        tts?.release()
                        tts = null
                        dir.deleteRecursively()
                        check(temp.renameTo(dir)) { "Couldn't save the voice" }
                        clipDir.deleteRecursively()
                    }
                    _isAvailable.value = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Mabel voice import failed")
                    _importError.value = e.message ?: e.javaClass.simpleName
                    temp.deleteRecursively()
                } finally {
                    _importing.value = false
                }
            }
        }

        fun delete() {
            scope.launch {
                ttsLock.withLock {
                    tts?.release()
                    tts = null
                    dir.deleteRecursively()
                    clipDir.deleteRecursively()
                }
                _isAvailable.value = false
            }
        }

        /** Speaks [text] into a WAV file and returns it; the same text is only spoken once and
         * then reused. Null if there's no voice or it couldn't be spoken. */
        suspend fun speak(text: String): File? = withContext(Dispatchers.IO) {
            if (!_isAvailable.value) return@withContext null
            // Rounded, so a slider nudge doesn't respeak everything; part of the clip's name, so
            // clips spoken at another setting aren't reused.
            val monotone = ((context.dataStore.data.first()[MabelMonotoneKey] ?: MABEL_MONOTONE_DEFAULT)
                .coerceIn(0f, 1f) * 20).roundToInt() / 20f
            val file = File(clipDir, "${sha1("flat|$monotone|$text")}.wav")
            if (file.exists()) return@withContext file
            try {
                ttsLock.withLock {
                    // Spoken while this waited for its turn.
                    if (file.exists()) return@withLock
                    val engine = tts ?: load().also { tts = it }
                    val audio = engine.generate(text)
                    val samples = PitchFlattener.flatten(audio.samples, audio.sampleRate, monotone)
                    clipDir.mkdirs()
                    val temp = File(clipDir, "${file.name}.tmp")
                    writeWav(temp, samples, audio.sampleRate)
                    Timber.d("Mabel said (%.1f s, %d%% monotone): %s",
                        samples.size / audio.sampleRate.toFloat(), (monotone * 100).roundToInt(), text)
                    temp.renameTo(file)
                }
                file
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "Mabel couldn't say %s", text)
                null
            }
        }

        /** Speaks [text] in the background, so it's ready by the time it's played. */
        fun prepare(text: String) {
            scope.launch { speak(text) }
        }

        private fun load(): OfflineTts =
            OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = File(dir, MODEL).absolutePath,
                            tokens = File(dir, TOKENS).absolutePath,
                            dataDir = File(dir, ESPEAK_DATA).absolutePath,
                        ),
                        numThreads = 2,
                    ),
                ),
            )

        private companion object {
            const val MODEL = "model.onnx"
            const val TOKENS = "tokens.txt"
            const val ESPEAK_DATA = "espeak-ng-data"

            /** sherpa-onnx reads one character per token, and ends the whole app on a line it
             * can't read. Piper 1.5 voices also list merged diphthongs ("aɪ 161"), which are
             * only used when their config has "vowel_clusters"; those lines are dropped. */
            fun keepSingleCharacterTokens(file: File) {
                val lines = file.readLines().filter { line ->
                    val symbol = line.substringBeforeLast(' ')
                    line.isNotEmpty() && symbol.codePointCount(0, symbol.length) == 1
                }
                file.writeText(lines.joinToString("\n", postfix = "\n"))
            }

            fun sha1(text: String): String =
                MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

            /** 16-bit mono PCM. */
            fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
                val dataSize = samples.size * 2
                DataOutputStream(file.outputStream().buffered()).use { out ->
                    fun intLE(value: Int) = out.writeInt(Integer.reverseBytes(value))
                    fun shortLE(value: Int) = out.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
                    out.writeBytes("RIFF")
                    intLE(36 + dataSize)
                    out.writeBytes("WAVE")
                    out.writeBytes("fmt ")
                    intLE(16)
                    shortLE(1)
                    shortLE(1)
                    intLE(sampleRate)
                    intLE(sampleRate * 2)
                    shortLE(2)
                    shortLE(16)
                    out.writeBytes("data")
                    intLE(dataSize)
                    samples.forEach { sample -> shortLE((sample.coerceIn(-1f, 1f) * 32767).roundToInt()) }
                }
            }
        }
    }
