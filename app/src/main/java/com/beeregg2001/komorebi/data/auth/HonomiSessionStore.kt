package com.beeregg2001.komorebi.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import com.beeregg2001.komorebi.data.account.KomorebiAccount

data class HonomiSession(val origin: String, val token: String, val userId: Int, val userName: String)

@Singleton
class HonomiSessionStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("honomi_session", Context.MODE_PRIVATE)
    private val account = KomorebiAccount(context)

    @Volatile
    private var cachedSession: HonomiSession? = null

    @Volatile
    private var isLoaded: Boolean = false

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            ensureLoaded()
        }
    }

    private fun ensureLoaded() {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            val record = account.read()
            val legacy = if (!preferences.getBoolean(KEY_SESSION_MIGRATED, false)) load() else null
            if (record?.session != null) {
                cachedSession = record.session
            } else if (legacy != null) {
                account.update(create = true) { it.copy(session = legacy) }
                cachedSession = legacy
            }
            preferences.edit().putBoolean(KEY_SESSION_MIGRATED, true)
                .remove(KEY_IV).remove(KEY_TOKEN).remove(KEY_ORIGIN).remove(KEY_USER_ID).remove(KEY_USER_NAME).commit()
            isLoaded = true
        }
    }

    fun current(): HonomiSession? {
        ensureLoaded()
        return account.read()?.session.also { cachedSession = it }
    }

    /** A background request is only valid for the exact authenticated session that queued it. */
    fun isCurrent(session: HonomiSession): Boolean {
        return current() == session
    }

    /** HonomiTV 側で複数台のテレビを区別する、インストール単位の固定 ID。 */
    fun remoteDeviceId(): String {
        preferences.getString(KEY_REMOTE_DEVICE_ID, null)?.let { return it }
        val deviceId = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_REMOTE_DEVICE_ID, deviceId).apply()
        return deviceId
    }

    fun historyOwner(): String? = preferences.getString(KEY_HISTORY_OWNER, null)

    fun markHistoryOwner(session: HonomiSession) {
        preferences.edit().putString(KEY_HISTORY_OWNER, identity(session)).apply()
    }

    fun isHistoryOwner(session: HonomiSession): Boolean = historyOwner() == identity(session)

    @Synchronized
    fun save(session: HonomiSession) {
        account.update(create = true) { it.copy(session = session) }
        preferences.edit().putBoolean(KEY_SESSION_MIGRATED, true).commit()
        cachedSession = session
        isLoaded = true
    }

    @Synchronized
    fun clear() {
        account.update { it.copy(session = null) }
        preferences.edit()
            .remove(KEY_IV)
            .remove(KEY_TOKEN)
            .remove(KEY_ORIGIN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .apply()
        cachedSession = null
        isLoaded = true
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

    private fun identity(session: HonomiSession): String = "${session.origin}|${session.userId}"

    private companion object {
        const val KEY_ALIAS = "komorebi_honomi_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_IV = "iv"
        const val KEY_TOKEN = "token"
        const val KEY_ORIGIN = "origin"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_NAME = "user_name"
        const val KEY_HISTORY_OWNER = "history_owner"
        const val KEY_REMOTE_DEVICE_ID = "remote_device_id"
        const val KEY_SESSION_MIGRATED = "system_account_session_migrated"
    }
}
