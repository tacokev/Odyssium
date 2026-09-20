package com.skintclub.odyssey.utils

import com.skintclub.odyssey.db.entities.LyricsEntity

object TranslationHelper {
    suspend fun translate(lyrics: LyricsEntity): LyricsEntity = lyrics

    suspend fun clearModels() {}
}
