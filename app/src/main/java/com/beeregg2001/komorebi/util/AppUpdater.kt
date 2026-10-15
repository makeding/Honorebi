package com.beeregg2001.komorebi.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(
        val versionName: String,
        val releaseNotes: String,
        val apkUrl: String
    ) : UpdateState()

    data class Downloading(val progressPercentage: Int) : UpdateState()
    object ReadyToInstall : UpdateState()
    data class AwaitingInstallConfirmation(val intent: Intent) : UpdateState()
    object Installing : UpdateState()
    object Cancelled : UpdateState()
    object UpToDate : UpdateState()
    data class Error(val message: String) : UpdateState()
}

@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val installPreferences = context.getSharedPreferences("update_install", Context.MODE_PRIVATE)
    private val downloadMutex = Mutex()
    private val _updateState = MutableStateFlow(restoreInstallState())
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val client = OkHttpClient()

    private val versionJsonUrl =
        "https://raw.githubusercontent.com/makeding/Honorebi/main/version.json"

    // ★ 修正: 引数に receiveBetaUpdates フラグを追加し、デフォルトは false (Stable) とする
    suspend fun checkForUpdates(receiveBetaUpdates: Boolean = false, userInitiated: Boolean = false) = withContext(Dispatchers.IO) {
        if (isInstallPending()) return@withContext
        if (!userInitiated && _updateState.value is UpdateState.Error) return@withContext
        if (_updateState.value is UpdateState.Error) clearFailedInstall()
        installPreferences.edit().remove("error").remove("result").apply()
        _updateState.value = UpdateState.Checking
        try {
            val request = Request.Builder().url(versionJsonUrl).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val jsonString = response.body?.string() ?: return@withContext
                val rootJson = JSONObject(jsonString)

                // ★ 修正: ベータ版の受信が有効で、かつJSON内に "beta" オブジェクトが定義されていればそちらをパースする
                val targetJson = if (receiveBetaUpdates && rootJson.has("beta")) {
                    rootJson.getJSONObject("beta")
                } else {
                    rootJson
                }

                val latestVersionCode = targetJson.getInt("versionCode")
                val latestVersionName = targetJson.getString("versionName")
                val releaseNotes = targetJson.getString("releaseNotes")
                val abiUrls = targetJson.optJSONObject("apkUrls")
                val apkUrl = UpdateApkSelector.select(
                    supportedAbis = Build.SUPPORTED_ABIS.toList(),
                    abiUrls = abiUrls?.let { urls ->
                        urls.keys().asSequence().associateWith { urls.optString(it) }
                    }.orEmpty(),
                    universalUrl = targetJson.getString("apkUrl")
                )

                val currentVersionCode = getCurrentVersionCode()

                if (latestVersionCode > currentVersionCode) {
                    _updateState.value =
                        UpdateState.UpdateAvailable(latestVersionName, releaseNotes, apkUrl)
                } else {
                    _updateState.value = UpdateState.UpToDate
                }
            } else {
                _updateState.value = UpdateState.Error("アップデート情報の取得に失敗しました")
            }
        } catch (e: Exception) {
            Log.e("AppUpdater", "Update check failed", e)
            _updateState.value = UpdateState.Error("ネットワークエラーが発生しました")
        }
    }

    suspend fun downloadAndInstallUpdate(apkUrl: String) = withContext(Dispatchers.IO) {
        if (!downloadMutex.tryLock()) return@withContext
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        var committed = false
        try {
            if (isInstallPending()) return@withContext
            if (_updateState.value is UpdateState.Error) clearFailedInstall()
            _updateState.value = UpdateState.Downloading(0)
            installPreferences.edit().remove("error").remove("result").apply()
            client.newCall(Request.Builder().url(apkUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "APKのダウンロードに失敗しました (HTTP ${response.code})" }
                val body = checkNotNull(response.body) { "ダウンロードしたAPKが空です" }
                val length = body.contentLength()
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setAppLabel(context.applicationInfo.loadLabel(context.packageManager))
                    setAppIcon(context.applicationInfo.loadIcon(context.packageManager).toBitmap(128, 128))
                    if (length > 0) setSize(length)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    }
                }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    var copied = 0L
                    session.openWrite("base.apk", 0, length.takeIf { it > 0 } ?: -1).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8 * 1024)
                            var lastProgress = 0
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count == -1) break
                                output.write(buffer, 0, count)
                                copied += count
                                val progress = if (length > 0) ((copied * 100) / length).toInt().coerceIn(0, 100) else 0
                                if (progress > lastProgress) {
                                    _updateState.value = UpdateState.Downloading(progress)
                                    lastProgress = progress
                                }
                            }
                        }
                        session.fsync(output)
                    }
                    check(copied > 0 && (length <= 0 || copied == length)) { "APKのダウンロードが完了していません" }
                    check(installPreferences.edit().putInt("session_id", sessionId)
                        .putLong("previous_version", getCurrentVersionCode()).remove("confirmation").remove("result").commit()) {
                        "インストール情報を保存できませんでした"
                    }
                    _updateState.value = UpdateState.ReadyToInstall
                    val callback = Intent(context, UpdateInstallReceiver::class.java).apply {
                        action = INSTALL_RESULT_ACTION
                        data = Uri.parse("honorebi-update://session/$sessionId")
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    val result = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                    session.commit(result.intentSender)
                    committed = true
                }
            }
        } catch (e: CancellationException) {
            if (!committed) _updateState.value = UpdateState.Idle
            throw e
        } catch (e: Exception) {
            Log.e("AppUpdater", "Update staging failed", e)
            setInstallError("更新の準備に失敗しました: ${e.localizedMessage ?: "不明なエラー"}。更新を再確認してください [UPDATE_PREPARE]")
        } finally {
            if (!committed && sessionId >= 0) {
                runCatching { installer.abandonSession(sessionId) }
                    .onFailure { Log.w("AppUpdater", "Failed to abandon session $sessionId", it) }
                if (installPreferences.getInt("session_id", -1) == sessionId) {
                    installPreferences.edit().remove("session_id").commit()
                }
            }
            downloadMutex.unlock()
        }
    }

    private fun restoreInstallState(): UpdateState {
        installPreferences.getString("error", null)?.let { return UpdateState.Error(it) }
        installPreferences.getString("confirmation", null)?.let {
            return try {
                UpdateState.AwaitingInstallConfirmation(Intent.parseUri(it, 0))
            } catch (e: Exception) {
                UpdateState.Error("更新の確認画面を復元できませんでした。更新を再確認してください [INSTALL_RESTORE]")
            }
        }
        val sessionId = installPreferences.getInt("session_id", -1)
        if (sessionId >= 0) {
            if (getCurrentVersionCode() > installPreferences.getLong("previous_version", Long.MAX_VALUE)) {
                installPreferences.edit().remove("session_id").putString("result", "success").commit()
                return UpdateState.UpToDate
            }
            if (context.packageManager.packageInstaller.getSessionInfo(sessionId) != null) return UpdateState.Installing
            // A missing session alone never establishes success. A late callback may still replace this error.
            val error = "更新が完了したことを確認できませんでした。更新を再確認してください [INSTALL_INTERRUPTED]"
            installPreferences.edit().putString("error", error).commit()
            return UpdateState.Error(error)
        }
        return when (installPreferences.getString("result", null)) {
            "success" -> UpdateState.UpToDate
            "cancelled" -> UpdateState.Cancelled
            else -> UpdateState.Idle
        }
    }

    private fun isInstallPending(): Boolean {
        if (_updateState.value is UpdateState.Downloading) return true
        if (_updateState.value is UpdateState.Error) return false
        return installPreferences.getInt("session_id", -1) >= 0
    }

    /** Called only by the resumed activity, so a background download never steals the screen. */
    fun launchInstallConfirmation(activity: android.app.Activity) {
        val waiting = _updateState.value as? UpdateState.AwaitingInstallConfirmation ?: return
        val sessionId = installPreferences.getInt("session_id", -1)
        try {
            activity.startActivity(waiting.intent)
            installPreferences.edit().remove("confirmation").commit()
            _updateState.value = UpdateState.Installing
        } catch (e: Exception) {
            Log.e("AppUpdater", "Install confirmation failed", e)
            failConfirmation(sessionId, "確認画面を開けませんでした")
        }
    }

    internal fun handleInstallResult(intent: Intent) {
        if (intent.action != INSTALL_RESULT_ACTION) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sessionId < 0 || sessionId != installPreferences.getInt("session_id", -1)) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    failConfirmation(sessionId, "確認画面がありません")
                    return
                }
                installPreferences.edit().putString("confirmation", confirmation.toUri(0)).commit()
                _updateState.value = UpdateState.AwaitingInstallConfirmation(confirmation)
            }
            else -> {
                installPreferences.edit().remove("session_id").remove("confirmation").remove("error").commit()
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> {
                        installPreferences.edit().putString("result", "success").commit()
                        _updateState.value = UpdateState.UpToDate
                    }
                    PackageInstaller.STATUS_FAILURE_ABORTED -> {
                        installPreferences.edit().putString("result", "cancelled").commit()
                        _updateState.value = UpdateState.Cancelled
                    }
                    else -> {
                        val reason = when (status) {
                            PackageInstaller.STATUS_FAILURE_STORAGE -> "空き容量が不足しています"
                            PackageInstaller.STATUS_FAILURE_CONFLICT -> "インストール済みアプリと署名などが一致しません"
                            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "この端末に対応していないAPKです"
                            PackageInstaller.STATUS_FAILURE_INVALID -> "APKを検証できませんでした"
                            PackageInstaller.STATUS_FAILURE_BLOCKED -> "端末の設定またはポリシーでブロックされました"
                            else -> "Androidがインストールを完了できませんでした"
                        }
                        Log.w("AppUpdater", "Install failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                        setInstallError("更新をインストールできませんでした。$reason。更新を再確認してください [INSTALL_STATUS_$status]")
                    }
                }
            }
        }
    }

    private fun failConfirmation(sessionId: Int, reason: String) {
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
        installPreferences.edit().remove("session_id").remove("confirmation").commit()
        setInstallError("更新を開始できませんでした。$reason。更新を再確認してください [INSTALL_CONFIRMATION]")
    }

    private fun setInstallError(message: String) {
        installPreferences.edit().putString("error", message).remove("result").commit()
        _updateState.value = UpdateState.Error(message)
    }

    private fun clearFailedInstall() {
        val sessionId = installPreferences.getInt("session_id", -1)
        if (sessionId >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
        installPreferences.edit().remove("session_id").remove("confirmation").commit()
    }

    private fun getCurrentVersionCode(): Long {
        return try {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            0
        }
    }

    fun resetState() {
        if (!isInstallPending()) {
            if (_updateState.value is UpdateState.Error) clearFailedInstall()
            installPreferences.edit().remove("error").remove("result").apply()
            _updateState.value = UpdateState.Idle
        }
    }

    internal companion object {
        const val INSTALL_RESULT_ACTION = "com.beeregg2001.komorebi.UPDATE_INSTALL_RESULT"
    }
}
