package com.skintclub.odyssey.lyrics

import android.content.Context
import com.skintclub.odyssey.constants.EnableKugouKey
import com.skintclub.odyssey.utils.dataStore
import com.skintclub.odyssey.utils.get
import com.skintclub.kugou.KuGou

object KuGouLyricsProvider : LyricsProvider {
    override val name = "Kugou"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableKugouKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
    ): Result<String> = KuGou.getLyrics(title, artist, duration)

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        KuGou.getAllLyrics(title, artist, duration, callback)
    }
}
