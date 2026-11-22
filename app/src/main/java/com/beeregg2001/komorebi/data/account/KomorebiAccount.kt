package com.beeregg2001.komorebi.data.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import com.beeregg2001.komorebi.data.auth.HonomiSession
import com.google.gson.Gson

/** The system account owns the recovery profile and session in a single atomic record. */
class KomorebiAccount(context: Context) {
    private val manager = AccountManager.get(context)
    private val gson = Gson()

    data class Record(val settings: Map<String, String> = emptyMap(), val session: HonomiSession? = null)

    fun read(): Record? = synchronized(lock) {
        val account = manager.getAccountsByType(TYPE).firstOrNull() ?: return@synchronized null
        manager.getPassword(account)?.let { gson.fromJson(it, Record::class.java) } ?: Record()
    }

    fun update(create: Boolean = false, change: (Record) -> Record): Boolean = synchronized(lock) {
        val account = manager.getAccountsByType(TYPE).firstOrNull() ?: run {
            if (!create) return@synchronized false
            val newAccount = Account(NAME, TYPE)
            check(manager.addAccountExplicitly(newAccount, gson.toJson(Record()), null))
            newAccount
        }
        val record = manager.getPassword(account)?.let { gson.fromJson(it, Record::class.java) } ?: Record()
        manager.setPassword(account, gson.toJson(change(record)))
        true
    }

    companion object {
        const val TYPE = "com.beeregg2001.Honorebi.account"
        private const val NAME = "Komorebi"
        private val lock = Any()
    }
}
