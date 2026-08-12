package com.beeregg2001.komorebi.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class HonomiSession(val origin: String, val token: String, val userId: Int, val userName: String)

@Singleton
class HonomiSessionStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("honomi_session", Context.MODE_PRIVATE)

    @Volatile
    private var cachedSession: HonomiSession? = load()

    fun current(): HonomiSession? = cachedSession

    @Synchronized
    fun save(session: HonomiSession) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        val encrypted = cipher.doFinal(session.token.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_ORIGIN, session.origin)
            .putInt(KEY_USER_ID, session.userId)
            .putString(KEY_USER_NAME, session.userName)
            .apply()
        cachedSession = session
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
        cachedSession = null
    }

    private fun load(): HonomiSession? = runCatching {
        val iv = Base64.decode(preferences.getString(KEY_IV, null) ?: return null, Base64.NO_WRAP)
        val encrypted = Base64.decode(preferences.getString(KEY_TOKEN, null) ?: return null, Base64.NO_WRAP)
        val origin = preferences.getString(KEY_ORIGIN, null) ?: return null
        val userName = preferences.getString(KEY_USER_NAME, null) ?: return null
        val userId = preferences.getInt(KEY_USER_ID, -1).takeIf { it >= 0 } ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        }
        HonomiSession(origin, String(cipher.doFinal(encrypted), Charsets.UTF_8), userId, userName)
    }.getOrElse {
        preferences.edit().clear().apply()
        null
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "komorebi_honomi_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_IV = "iv"
        const val KEY_TOKEN = "token"
        const val KEY_ORIGIN = "origin"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_NAME = "user_name"
    }
}
