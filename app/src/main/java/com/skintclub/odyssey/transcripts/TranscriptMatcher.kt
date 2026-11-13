package com.skintclub.odyssey.transcripts

/**
 * How transcript search matches a query against a transcript's text.
 *
 * - Case, apostrophes and punctuation don't matter: "its over for now" finds "It's over. For
 *   now...".
 * - Words match whole words, except the last one while it may still be being typed: "Whit" finds
 *   "Whittaker" until a space (or another word) follows it, after which only "Whit" itself - or
 *   "Whit's", a possessive or contraction of it - matches.
 *
 * Kept free of Android code so it can be checked on its own.
 */
class TranscriptQuery private constructor(
    /** The query's words, lowercased, apostrophes and punctuation dropped. */
    val words: List<String>,
    /** Whether the last word may be only the start of a word (nothing typed after it yet). */
    val lastWordOpen: Boolean,
) {
    companion object {
        /** [raw] as a query, or null if it has fewer than two letters or digits to search for. */
        fun parse(raw: String): TranscriptQuery? {
            val words = mutableListOf<String>()
            val word = StringBuilder()
            for (c in raw) {
                when {
                    c.isLetterOrDigit() -> word.append(c.lowercaseChar())
                    // "it's" is one word, "its", whichever way it's typed.
                    isApostrophe(c) -> {}
                    word.isNotEmpty() -> {
                        words += word.toString()
                        word.clear()
                    }
                }
            }
            if (word.isNotEmpty()) words += word.toString()
            if (words.sumOf { it.length } < 2) return null
            // Anything after the last word - a space, a period - means it's been finished.
            val lastWordOpen = raw.lastOrNull()?.let { it.isLetterOrDigit() || isApostrophe(it) } == true
            return TranscriptQuery(words, lastWordOpen)
        }
    }
}

object TranscriptMatcher {
    /** The first match of [query] in [text] at or after [from], as the start and end (exclusive)
     * of the matched text, or null. A match never crosses a line ('\n'). */
    fun find(text: String, query: TranscriptQuery, from: Int = 0): Pair<Int, Int>? {
        val first = query.words[0][0]
        var i = from
        while (i < text.length) {
            if (text[i].lowercaseChar() == first && startsWord(text, i)) {
                val end = matchAt(text, query, i)
                if (end >= 0) return i to end
            }
            i++
        }
        return null
    }

    /** Where a match of [query] starting at [start] ends (exclusive), or -1. */
    private fun matchAt(text: String, query: TranscriptQuery, start: Int): Int {
        var t = start
        for ((index, word) in query.words.withIndex()) {
            if (index > 0) {
                // Between words: any run of spaces and punctuation, but at least one character.
                val gapStart = t
                while (t < text.length && !text[t].isLetterOrDigit() && text[t] != '\n') t++
                if (t == gapStart || t >= text.length || !text[t].isLetterOrDigit()) return -1
            }
            var w = 0
            while (w < word.length) {
                if (t >= text.length) return -1
                val c = text[t]
                when {
                    // An apostrophe inside a word ("it's") is skipped.
                    isApostrophe(c) && w > 0 && t + 1 < text.length && text[t + 1].isLetterOrDigit() -> t++
                    c.lowercaseChar() == word[w] -> {
                        t++
                        w++
                    }
                    else -> return -1
                }
            }
            val isLast = index == query.words.lastIndex
            if (isLast && query.lastWordOpen) return t
            t = endOfWholeWord(text, t) ?: return -1
        }
        return t
    }

    /** [t] if a word ends there, or just past an "'s" that follows it ("Whit's" for "Whit");
     * null if the word goes on ("Whit" in "Whittaker", "I" in "I've"). */
    private fun endOfWholeWord(text: String, t: Int): Int? {
        if (t >= text.length) return t
        val c = text[t]
        if (c.isLetterOrDigit()) return null
        if (isApostrophe(c) && t + 1 < text.length && text[t + 1].isLetterOrDigit()) {
            val afterS = t + 2
            val possessive = text[t + 1].lowercaseChar() == 's' && (afterS >= text.length || !text[afterS].isLetterOrDigit())
            return if (possessive) afterS else null
        }
        return t
    }

    /** Whether a word starts at [i]: not mid-word, and not the "s" of "it's". */
    private fun startsWord(text: String, i: Int): Boolean {
        if (i == 0) return true
        val previous = text[i - 1]
        if (previous.isLetterOrDigit()) return false
        if (isApostrophe(previous) && i >= 2 && text[i - 2].isLetterOrDigit()) return false
        return true
    }
}

internal fun isApostrophe(c: Char) = c == '\'' || c == '’' || c == '‘' || c == '`'
