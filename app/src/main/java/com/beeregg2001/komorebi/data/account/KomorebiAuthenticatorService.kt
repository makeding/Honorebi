package com.beeregg2001.komorebi.data.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import com.beeregg2001.komorebi.MainActivity

class KomorebiAuthenticatorService : Service() {
    private val authenticator by lazy { object : AbstractAccountAuthenticator(this) {
        override fun editProperties(response: AccountAuthenticatorResponse, accountType: String): Bundle = Bundle()
        override fun addAccount(response: AccountAuthenticatorResponse, accountType: String, authTokenType: String?, requiredFeatures: Array<out String>?, options: Bundle?): Bundle = Bundle().apply {
            putParcelable(AccountManager.KEY_INTENT, Intent(this@KomorebiAuthenticatorService, MainActivity::class.java))
        }
        override fun confirmCredentials(response: AccountAuthenticatorResponse, account: Account, options: Bundle?): Bundle = Bundle()
        override fun getAuthToken(response: AccountAuthenticatorResponse, account: Account, authTokenType: String, options: Bundle?): Bundle = Bundle()
        override fun getAuthTokenLabel(authTokenType: String): String = authTokenType
        override fun updateCredentials(response: AccountAuthenticatorResponse, account: Account, authTokenType: String?, options: Bundle?): Bundle = Bundle()
        override fun hasFeatures(response: AccountAuthenticatorResponse, account: Account, features: Array<out String>): Bundle = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
    } }
    override fun onBind(intent: Intent): IBinder = authenticator.iBinder
}
