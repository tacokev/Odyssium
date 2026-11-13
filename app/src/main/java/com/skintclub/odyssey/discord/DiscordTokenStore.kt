package com.skintclub.odyssey.discord

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the Discord sign-in, encrypted with a key that never leaves the Android Keystore
 * (adapted from Metrolist's Discord integration, GPL-3.0).
 */
object DiscordTokenStore {
    private const val PREFS = "discord_token"
    private const val ACCESS = "access_token"
    private const val REFRESH = "refresh_token"
    private const val EXPIRES_AT = "expires_at"
    private const val KEY_ALIAS = "odyssium_discord_token_key"
    private const val IV_SIZE = 12

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    val accessToken: String? get() = read(ACCESS)
    val refreshToken: String? get() = read(REFRESH)

    /** Unix seconds when the access token expires, 0 if unknown. */
    val expiresAt: Long get() = prefs?.getLong(EXPIRES_AT, 0L) ?: 0L

    fun save(tokens: DiscordTokens) {
        val access = encrypt(tokens.accessToken) ?: return
        prefs?.edit {
            putString(ACCESS, access)
            if (tokens.refreshToken.isNotEmpty()) encrypt(tokens.refreshToken)?.let { putString(REFRESH, it) }
            if (tokens.expiresInSec > 0) putLong(EXPIRES_AT, System.currentTimeMillis() / 1000 + tokens.expiresInSec)
        }
    }

    fun clear() {
        prefs?.edit { remove(ACCESS); remove(REFRESH); remove(EXPIRES_AT) }
    }

    private fun read(name: String): String? = prefs?.getString(name, null)?.let(::decrypt)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }.onFailure { Timber.e(it, "Discord token encrypt failed") }.getOrNull()

    private fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE))
    }.onFailure { Timber.e(it, "Discord token decrypt failed") }.getOrNull()
}
