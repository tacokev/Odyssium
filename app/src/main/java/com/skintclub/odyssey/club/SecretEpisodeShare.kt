package com.skintclub.odyssey.club

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * Sharing secret episodes: a plain-text "share code" holding the episodes' CDN paths, sent
 * through any app. Pasting the message into the search bar, or sharing it to Odyssium,
 * imports them (see SecretEpisodesStore.importShared). No web page or server is involved -
 * the code is the whole payload: the paths, minus their shared prefix, deflated and base64'd.
 */

private const val SHARE_CODE_PREFIX = "odyssium:secret:"
private const val PATH_PREFIX = "private/audio/episode/"
private val shareCodeRegex = Regex("""${Regex.escape(SHARE_CODE_PREFIX)}([A-Za-z0-9_-]+)""")
private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

/** What importing a share code did. */
data class SecretImportResult(val added: Int, val replaced: Int, val unchanged: Int) {
    val total get() = added + replaced + unchanged

    /** "Added 2 secret episodes, replaced 1" or similar, for a toast. */
    fun summary(): String {
        val parts = buildList {
            if (added > 0) add("added $added")
            if (replaced > 0) add("replaced $replaced")
            if (unchanged > 0) add("$unchanged already there")
        }
        if (parts.isEmpty()) return "No secret episodes to add"
        val text = parts.joinToString(", ")
        val noun = if (total == 1) "secret episode" else "secret episodes"
        return text.replaceFirstChar { it.uppercase() } + " ($total $noun)"
    }
}

/** The share code for the episodes at [paths]. */
fun encodeSecretShare(paths: List<String>): String {
    val joined = paths.joinToString("\n") { it.removePrefix(PATH_PREFIX) }.toByteArray(Charsets.UTF_8)
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    deflater.setInput(joined)
    deflater.finish()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
    deflater.end()
    return SHARE_CODE_PREFIX + Base64.encodeToString(out.toByteArray(), BASE64_FLAGS)
}

/** The episode paths in the first share code found anywhere in [text] (it may be a whole
 * message around the code), or null if there isn't a valid one. */
fun decodeSecretShare(text: String): List<String>? {
    val encoded = shareCodeRegex.find(text)?.groupValues?.get(1) ?: return null
    return runCatching {
        val bytes = Base64.decode(encoded, BASE64_FLAGS)
        val inflater = Inflater()
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, n)
            // A real code is a few KB at most; stop on anything absurd.
            if (out.size() > 1_000_000) break
        }
        inflater.end()
        out.toString(Charsets.UTF_8.name())
            .split('\n')
            .mapNotNull { line -> line.trim().takeIf { it.isNotEmpty() }?.let { parseSecretEpisodePath(PATH_PREFIX + it) } }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/** Whether [text] holds a share code (valid or not), so it isn't treated as a search. */
fun containsSecretShareCode(text: String): Boolean = shareCodeRegex.containsMatchIn(text)

/** The message sent when sharing [episodes]: just the share code. */
fun secretShareMessage(episodes: List<SecretEpisode>): String = encodeSecretShare(episodes.map { it.path })
