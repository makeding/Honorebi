package com.beeregg2001.komorebi.viewmodel

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.CmSkipMode
import com.beeregg2001.komorebi.data.repository.CacheManager
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.ui.player.PlaybackQualityCatalog
import com.beeregg2001.komorebi.util.AppUpdater
import com.beeregg2001.komorebi.util.UpdateState
import com.beeregg2001.komorebi.data.auth.HonomiAuthRepository
import com.beeregg2001.komorebi.data.auth.HonomiSession
import com.beeregg2001.komorebi.data.model.DeviceAuthRequest
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.response.respondText
import io.ktor.server.request.receiveParameters
import io.ktor.http.ContentType
import io.ktor.server.application.call
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import com.beeregg2001.komorebi.setup.*
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Keep
data class PostRecordingBatch(
    val name: String,
    val path: String
)

@Keep
data class SmbServer(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val ip: String,
    val port: String,
    val user: String,
    val password: String
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val syncEngine: RecordSyncEngine,
    private val db: AppDatabase,
    private val honomiAuthRepository: HonomiAuthRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val playbackQualityCatalog: PlaybackQualityCatalog,
    private val cacheManager: CacheManager,
    private val appUpdater: AppUpdater,
    val nhkExclusionRepository: com.beeregg2001.komorebi.data.repository.NHKExclusionRepository,
) : ViewModel() {

    val nhkExclusionState = nhkExclusionRepository.state
    val updateState: StateFlow<UpdateState> = appUpdater.updateState

    fun checkForUpdates() {
        viewModelScope.launch {
            appUpdater.checkForUpdates(settingsRepository.receiveBetaUpdates.first())
        }
    }

    fun setNHKExclusionMode(mode: com.beeregg2001.komorebi.data.model.NHKExclusionMode) {
        viewModelScope.launch { nhkExclusionRepository.setMode(mode) }
    }

    private val gson = Gson()

    private val _dynamicQualities = MutableStateFlow<List<StreamQuality>?>(null)
    val honomiSession: StateFlow<HonomiSession?> = honomiAuthRepository.session
    private val _honomiLoginInProgress = MutableStateFlow(false)
    val honomiLoginInProgress: StateFlow<Boolean> = _honomiLoginInProgress

    val availableQualities: StateFlow<List<StreamQuality>> = combine(
        _dynamicQualities,
        settingsRepository.availableStreamQualities,
        settingsRepository.backendType,
        settingsRepository.liveQuality,
        settingsRepository.videoQuality
    ) { dynamicList, json, backend, currentLive, currentVideo ->
        playbackQualityCatalog.settings(
            backend = backend,
            cachedJson = json,
            selectedLiveQuality = currentLive,
            selectedVideoQuality = currentVideo,
            dynamicQualities = dynamicList,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreamQuality.DEFAULT_QUALITIES)

    val totalRecordCount: StateFlow<Int> = db.recordedProgramDao().getTotalCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lastSyncedAt: StateFlow<Long> = db.syncMetaDao().getSyncMetaFlow()
        .map { it?.lastSyncedAt ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    suspend fun createHonomiPairing(): Result<DeviceAuthRequest> {
        _honomiLoginInProgress.value = true
        return honomiAuthRepository.createPairing().also { _honomiLoginInProgress.value = false }
    }

    suspend fun pollHonomiPairing(pairing: DeviceAuthRequest): Result<HonomiSession?> {
        return honomiAuthRepository.pollPairing(pairing).onSuccess { session ->
            if (session != null) watchHistoryRepository.syncWithServer()
        }
    }

    fun logoutHonomi() = honomiAuthRepository.logout()

    val backendType: StateFlow<String> = settingsRepository.backendType.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "KONOMITV"
    )
    val edcbIp: StateFlow<String> =
        settingsRepository.edcbIp.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val edcbPort: StateFlow<String> = settingsRepository.edcbPort.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "4510"
    )
    val edcbRecordPlayMethod: StateFlow<String> = settingsRepository.edcbRecordPlayMethod.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "API"
    )
    val epgStationIp: StateFlow<String> = settingsRepository.epgStationIp.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ""
    )
    val epgStationPort: StateFlow<String> = settingsRepository.epgStationPort.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "8888"
    )
    val mirakurunIp: StateFlow<String> = settingsRepository.mirakurunIp.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ""
    )
    val mirakurunPort: StateFlow<String> = settingsRepository.mirakurunPort.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ""
    )
    val konomiIp: StateFlow<String> = settingsRepository.konomiIp.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "https://192-168-xxx-xxx.local.konomi.tv"
    )
    val konomiPort: StateFlow<String> = settingsRepository.konomiPort.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "7000"
    )
    val commentSpeed: StateFlow<String> = settingsRepository.commentSpeed.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "1.0"
    )
    val commentFontSize: StateFlow<String> = settingsRepository.commentFontSize.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "1.0"
    )
    val commentOpacity: StateFlow<String> = settingsRepository.commentOpacity.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "1.0"
    )
    val commentMaxLines: StateFlow<String> = settingsRepository.commentMaxLines.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "0"
    )
    val commentDefaultDisplay: StateFlow<String> = settingsRepository.commentDefaultDisplay.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "ON"
    )
    val liveQuality: StateFlow<String> = settingsRepository.liveQuality.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "1080p-60fps"
    )
    val videoQuality: StateFlow<String> = settingsRepository.videoQuality.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "1080p-60fps"
    )
    val liveSubtitleDefault: StateFlow<String> = settingsRepository.liveSubtitleDefault.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "ON"
    )
    val videoSubtitleDefault: StateFlow<String> = settingsRepository.videoSubtitleDefault.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "ON"
    )
    val subtitleFont: StateFlow<String> = settingsRepository.subtitleFont.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "default"
    )
    val videoPlaybackSpeed: StateFlow<String> = settingsRepository.videoPlaybackSpeed.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "1.0"
    )
    val smbSubtitleEnabled: StateFlow<String> = settingsRepository.smbSubtitleEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "OFF"
    )
    val subtitleCommentLayer: StateFlow<String> = settingsRepository.subtitleCommentLayer.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "CommentOnTop"
    )
    val audioOutputMode: StateFlow<String> = settingsRepository.audioOutputMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "DOWNMIX"
    )
    val playerUiMode: StateFlow<String> = settingsRepository.playerUiMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "CLASSIC"
    )
    val autoCmSkip: StateFlow<String> = settingsRepository.autoCmSkip.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "OFF"
    )
    val preferOriginalMpegTs: StateFlow<String> = settingsRepository.preferOriginalMpegTs.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "OFF"
    )
    val labAnnictIntegration: StateFlow<String> = settingsRepository.labAnnictIntegration.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "OFF"
    )
    val labShobocalIntegration: StateFlow<String> =
        settingsRepository.labShobocalIntegration.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            "OFF"
        )
    val labAllowMirakurunDual: StateFlow<String> = settingsRepository.labAllowMirakurunDual.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "OFF"
    )
    val defaultPostCommand: StateFlow<String> = settingsRepository.defaultPostCommand.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ""
    )
    val startupChannel: StateFlow<String> = settingsRepository.startupChannel.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "OFF"
    )
    val timeFormat: StateFlow<String> = settingsRepository.timeFormat.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "24H"
    )
    val startupTab: StateFlow<String> = settingsRepository.startupTab.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "ホーム"
    )
    val appTheme: StateFlow<String> = settingsRepository.appTheme.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "MONOTONE"
    )
    val receiveBetaUpdates: StateFlow<Boolean> = settingsRepository.receiveBetaUpdates.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )
    val isSettingsInitialized: StateFlow<Boolean> = settingsRepository.isInitialized.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        true
    )
    val screensaverEnabled: StateFlow<Boolean> = settingsRepository.screensaverEnabled.stateIn(
        viewModelScope, SharingStarted.Eagerly, false
    )
    val screensaverTimeoutMinutes: StateFlow<Int> = settingsRepository.screensaverTimeoutMinutes.stateIn(
        viewModelScope, SharingStarted.Eagerly, 5
    )
    val screensaverIntervalSeconds: StateFlow<Int> = settingsRepository.screensaverIntervalSeconds.stateIn(
        viewModelScope, SharingStarted.Eagerly, 30
    )
    val screensaverTransition: StateFlow<String> = settingsRepository.screensaverTransition.stateIn(
        viewModelScope, SharingStarted.Eagerly, "FADE"
    )
    val screensaverImageUris: StateFlow<List<String>> = settingsRepository.screensaverImageUris
        .map { json -> runCatching { Gson().fromJson(json, Array<String>::class.java)?.toList().orEmpty() }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val screensaverShowUpcoming: StateFlow<Boolean> = settingsRepository.screensaverShowUpcoming.stateIn(
        viewModelScope, SharingStarted.Eagerly, false
    )

    fun setScreensaverEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveBoolean(SettingsRepository.SCREENSAVER_ENABLED, enabled)
    }

    fun setScreensaverTimeoutMinutes(minutes: Int) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveInt(SettingsRepository.SCREENSAVER_TIMEOUT_MINUTES, minutes)
    }

    fun setScreensaverIntervalSeconds(seconds: Int) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveInt(SettingsRepository.SCREENSAVER_INTERVAL_SECONDS, seconds)
    }

    fun setScreensaverTransition(transition: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(SettingsRepository.SCREENSAVER_TRANSITION, transition)
    }

    fun setScreensaverImageUris(uris: List<String>) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(SettingsRepository.SCREENSAVER_IMAGE_URIS, Gson().toJson(uris.distinct()))
    }

    fun setScreensaverShowUpcoming(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveBoolean(SettingsRepository.SCREENSAVER_SHOW_UPCOMING, enabled)
    }
    val hideSubChannels: StateFlow<Boolean> = settingsRepository.hideSubChannels.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )
    val hideLauncherAppLabels: StateFlow<Boolean> = settingsRepository.hideLauncherAppLabels.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )
    val hideAppsTab: StateFlow<Boolean> = settingsRepository.hideAppsTab.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )
    val launcherAppColumns: StateFlow<String> = settingsRepository.launcherAppColumns.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "AUTO"
    )

    val geminiApiKey: StateFlow<String> = settingsRepository.geminiApiKey.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ""
    )

    // ★ 追加: 番組表設定の StateFlow
    val epgColumnCount: StateFlow<String> = settingsRepository.epgColumnCount.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "7"
    )
    val epgFontSizeScale: StateFlow<String> = settingsRepository.epgFontSizeScale.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "1.0"
    )
    val epgVisibleHours: StateFlow<String> = settingsRepository.epgVisibleHours.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "6"
    )

    val smbServerList: StateFlow<List<SmbServer>> = settingsRepository.smbServerList
        .map { json ->
            try {
                val type = object : TypeToken<List<SmbServer>>() {}.type
                gson.fromJson<List<SmbServer>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val postRecordingBatchList: StateFlow<List<PostRecordingBatch>> =
        settingsRepository.postRecordingBatchList
            .map { json ->
                try {
                    val type = object : TypeToken<List<PostRecordingBatch>>() {}.type
                    gson.fromJson<List<PostRecordingBatch>>(json, type) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    init {
        // ★ 修正: 初期化時の重い同期処理をバックグラウンドに回し、UI描画後に遅らせる
        viewModelScope.launch {
            delay(1500)
            forceSyncStreamQualities()
        }
    }

    private suspend fun forceSyncStreamQualities() {
        withContext(Dispatchers.IO) {
            try {
                val backend = settingsRepository.backendType.first()
                val preLive = settingsRepository.liveQuality.first()
                val preVideo = settingsRepository.videoQuality.first()

                if (backend == "EDCB") {
                    val fetched = playbackQualityCatalog.refreshEdcb()
                    if (fetched.isNotEmpty()) {
                        _dynamicQualities.value = fetched

                        val postLive = settingsRepository.liveQuality.first()
                        val postVideo = settingsRepository.videoQuality.first()

                        if (preLive == postLive && fetched.none { it.value == postLive }) {
                            settingsRepository.saveString(
                                SettingsRepository.LIVE_QUALITY,
                                fetched.first().value
                            )
                        }
                        if (preVideo == postVideo && fetched.none { it.value == postVideo }) {
                            settingsRepository.saveString(
                                SettingsRepository.VIDEO_QUALITY,
                                fetched.first().value
                            )
                        }
                    } else {
                        _dynamicQualities.value = emptyList()
                    }
                } else if (backend == "KONOMITV") {
                    settingsRepository.saveString(SettingsRepository.AVAILABLE_STREAM_QUALITIES, "")
                    val defQualities = StreamQuality.DEFAULT_QUALITIES

                    val postLive = settingsRepository.liveQuality.first()
                    val postVideo = settingsRepository.videoQuality.first()

                    if (preLive == postLive && defQualities.none { it.value == postLive }) {
                        settingsRepository.saveString(
                            SettingsRepository.LIVE_QUALITY,
                            defQualities.first().value
                        )
                    }
                    if (preVideo == postVideo && defQualities.none { it.value == postVideo }) {
                        settingsRepository.saveString(
                            SettingsRepository.VIDEO_QUALITY,
                            defQualities.first().value
                        )
                    }
                } else {
                    settingsRepository.saveString(SettingsRepository.AVAILABLE_STREAM_QUALITIES, "")
                }
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Failed to sync stream qualities to cache", e)
                _dynamicQualities.value = emptyList()
            }
        }
    }

    // ★ 追加: 番組表設定の更新メソッド
    fun updateEpgColumnCount(value: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(SettingsRepository.EPG_COLUMN_COUNT, value)
    }

    fun updateEpgFontSizeScale(value: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(SettingsRepository.EPG_FONT_SIZE_SCALE, value)
    }

    fun updateEpgVisibleHours(value: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(SettingsRepository.EPG_VISIBLE_HOURS, value)
    }

    fun setCmSkipMode(mode: CmSkipMode) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(SettingsRepository.AUTO_CM_SKIP, mode.name)
    }

    fun updateVideoPlaybackSpeed(speed: Float) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(SettingsRepository.VIDEO_PLAYBACK_SPEED, speed.toString())
    }

    fun updateSmbSubtitleEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(
            SettingsRepository.SMB_SUBTITLE_ENABLED,
            if (enabled) "ON" else "OFF"
        )
    }

    private suspend fun connectionConfigurationChanged(
        before: com.beeregg2001.komorebi.data.ConnectionSettingsSnapshot,
        saved: com.beeregg2001.komorebi.data.ConnectionSettingsSnapshot,
    ) {
        try {
            if (before.mainSystem != saved.mainSystem) {
                withContext(Dispatchers.IO) { db.clearAllTables() }
                context.imageLoader.memoryCache?.clear()
                context.imageLoader.diskCache?.clear()
            } else if (before.sources.firstOrNull { it.id == before.mainSystem } != saved.sources.firstOrNull { it.id == saved.mainSystem }) {
                syncEngine.clearDatabase()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { Log.e("SettingsViewModel", "Connection cache refresh failed", error) }
        viewModelScope.launch { forceSyncStreamQualities() }
    }

    fun updateConnectionField(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) =
        viewModelScope.launch(Dispatchers.IO) {
            val before = settingsRepository.connectionSettings()
            settingsRepository.saveString(key, value)
            connectionConfigurationChanged(before, settingsRepository.connectionSettings())
        }

    fun updateBackendType(newType: String) = updateConnectionField(SettingsRepository.BACKEND_TYPE, newType)
    fun updateEdcbIp(ip: String) = updateConnectionField(SettingsRepository.EDCB_IP, ip)
    fun updateEdcbPort(port: String) = updateConnectionField(SettingsRepository.EDCB_PORT, port)
    fun updateMirakurunIp(ip: String) = updateConnectionField(SettingsRepository.MIRAKURUN_IP, ip)
    fun updateKonomiIp(ip: String) = updateConnectionField(SettingsRepository.KONOMI_IP, ip)
    fun updateKonomiPort(port: String) = updateConnectionField(SettingsRepository.KONOMI_PORT, port)

    fun updateEdcbRecordPlayMethod(method: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(SettingsRepository.EDCB_RECORD_PLAY_METHOD, method)
        forceSyncStreamQualities()
    }

    fun triggerFullSync() {
        viewModelScope.launch { syncEngine.clearDatabase() }
    }

    fun clearChannelLogoCache() {
        viewModelScope.launch { cacheManager.clearChannelLogoCache() }
    }

    fun clearThumbnailCache() {
        viewModelScope.launch { cacheManager.clearThumbnailCache() }
    }

    fun clearEpgCache() {
        viewModelScope.launch { cacheManager.clearEpgCache() }
    }

    fun clearAllCache() {
        viewModelScope.launch {
            cacheManager.clearAll()
            syncEngine.clearDatabase()
        }
    }

    fun addPostRecordingBatch(name: String, path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = settingsRepository.postRecordingBatchList.first()
            val currentList = try {
                val type = object : TypeToken<List<PostRecordingBatch>>() {}.type
                gson.fromJson<List<PostRecordingBatch>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }.toMutableList()

            currentList.add(PostRecordingBatch(name, path))
            settingsRepository.saveString(
                SettingsRepository.POST_RECORDING_BATCH_LIST,
                gson.toJson(currentList)
            )
        }
    }

    fun deletePostRecordingBatch(batch: PostRecordingBatch) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = settingsRepository.postRecordingBatchList.first()
            val currentList = try {
                val type = object : TypeToken<List<PostRecordingBatch>>() {}.type
                gson.fromJson<List<PostRecordingBatch>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }.toMutableList()

            currentList.remove(batch)
            settingsRepository.saveString(
                SettingsRepository.POST_RECORDING_BATCH_LIST,
                gson.toJson(currentList)
            )
        }
    }

    fun saveSmbServer(server: SmbServer) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = settingsRepository.smbServerList.first()
            val currentList = try {
                val type = object : TypeToken<List<SmbServer>>() {}.type
                gson.fromJson<List<SmbServer>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }.toMutableList()

            val existingIndex = currentList.indexOfFirst { it.id == server.id }
            if (existingIndex != -1) {
                currentList[existingIndex] = server
            } else {
                currentList.add(server)
            }
            settingsRepository.saveString(
                SettingsRepository.SMB_SERVER_LIST,
                gson.toJson(currentList)
            )
        }
    }

    fun deleteSmbServer(serverId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = settingsRepository.smbServerList.first()
            val currentList = try {
                val type = object : TypeToken<List<SmbServer>>() {}.type
                gson.fromJson<List<SmbServer>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            val newList = currentList.filter { it.id != serverId }
            settingsRepository.saveString(
                SettingsRepository.SMB_SERVER_LIST,
                gson.toJson(newList)
            )
        }
    }

    fun updateAppTheme(themeName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.saveString(
                SettingsRepository.APP_THEME,
                themeName
            )
        }
    }

    fun updateDefaultRecordListView(viewType: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.saveString(
                SettingsRepository.DEFAULT_RECORD_LIST_VIEW,
                viewType
            )
        }
    }

    suspend fun getStartupTabOnce(): String {
        return settingsRepository.getStartupTabOnce()
    }

    fun updateStartupChannel(value: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(
            SettingsRepository.STARTUP_CHANNEL,
            value
        )
    }

    fun updateTimeFormat(value: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(
            SettingsRepository.TIME_FORMAT,
            value
        )
    }

    fun toggleHideSubChannels() {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.saveBoolean(
                SettingsRepository.HIDE_SUB_CHANNELS,
                !settingsRepository.hideSubChannels.first()
            )
        }
    }

    fun toggleHideAppsTab() {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.saveBoolean(
                SettingsRepository.HIDE_APPS_TAB,
                !settingsRepository.hideAppsTab.first()
            )
        }
    }

    fun updateLauncherAppColumns(value: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(SettingsRepository.LAUNCHER_APP_COLUMNS, value)
    }

    private var ktorServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val _localIpAddress = MutableStateFlow(getLocalIpAddress())
    val localIpAddress: StateFlow<String> = _localIpAddress

    private val _connectionSetupState = MutableStateFlow(com.beeregg2001.komorebi.setup.ConnectionSetupUiState())
    val connectionSetupState = _connectionSetupState.asStateFlow()

    fun startConnectionSetup(source: String? = null) {
        try {
            _connectionSetupState.value.session?.revoke()
            _connectionSetupState.value = ConnectionSetupUiState(ConnectionSetupSession(source = source))
            startGeminiLocalServer()
        } catch (error: Exception) {
            _connectionSetupState.value.session?.revoke()
            _connectionSetupState.value = com.beeregg2001.komorebi.setup.ConnectionSetupUiState(
                error = "設定ページを開けませんでした。ネットワーク接続を確認して再試行してください。",
            )
            Log.e("SettingsViewModel", "Connection setup server failed", error)
        }
    }

    private suspend fun saveWebConnectionSettings(snapshot: com.beeregg2001.komorebi.data.ConnectionSettingsSnapshot): com.beeregg2001.komorebi.data.ConnectionSettingsSnapshot {
        val before = settingsRepository.connectionSettings()
        val active = _connectionSetupState.value.session ?: throw SetupExpiredException()
        val saved = settingsRepository.saveConnectionSettings(snapshot) {
            if (!active.accepts(active.token) || _connectionSetupState.value.session !== active) throw SetupExpiredException()
        }
        connectionConfigurationChanged(before, saved)
        return saved.copy(account = com.beeregg2001.komorebi.data.ConnectionAccountSettings(honomiSession.value?.userName))
    }

    fun startGeminiLocalServer() {
        if (ktorServer != null) return
        if (_connectionSetupState.value.session == null) _connectionSetupState.value = ConnectionSetupUiState(ConnectionSetupSession())
        _localIpAddress.value = getLocalIpAddress()

        ktorServer = embeddedServer(CIO, port = 8081) {
            routing {
                connectionSetupRoutes(
                    session = { _connectionSetupState.value.session },
                    read = { settingsRepository.connectionSettings().copy(account = com.beeregg2001.komorebi.data.ConnectionAccountSettings(honomiSession.value?.userName)) },
                    save = { saveWebConnectionSettings(it) },
                    readAsset = { path -> context.assets.open(path).use { it.readBytes() } },
                )
                get("/") { call.respondText(getSetupHtml(), ContentType.Text.Html) }
                post("/submit") {
                    val active = _connectionSetupState.value.session
                    if (active == null) { call.setupExpired(); return@post }
                    try {
                        active.authorized(call.request.headers["X-Setup-Token"]) {
                            val form = call.receiveParameters()
                            val key = form["api_key"].orEmpty()
                            require(key.isNotBlank()) { "APIキーを入力してください。" }
                            if (!active.accepts(active.token)) throw SetupExpiredException()
                            settingsRepository.saveString(SettingsRepository.GEMINI_API_KEY, key)
                        }
                        call.respondText("保存しました。", ContentType.Text.Plain)
                    } catch (error: Exception) { call.setupFailure(error) }
                }
                connectionAccountRoutes(
                    session = { _connectionSetupState.value.session },
                    account = { com.beeregg2001.komorebi.data.ConnectionAccountSettings(honomiSession.value?.userName) },
                    origin = {
                        val config = settingsRepository.connectionSettings().sources.first { it.id == "KONOMITV" }
                        require(config.host.isNotBlank()) { "KonomiTV の接続先を保存してからログインしてください。" }
                        "${config.host}:${config.port}"
                    },
                    create = { createHonomiPairing().getOrThrow() },
                    poll = { pairing, active ->
                        honomiAuthRepository.pollPairing(pairing) { active.accepts(active.token) }.getOrThrow()?.let {
                            viewModelScope.launch { watchHistoryRepository.syncWithServer() }
                            com.beeregg2001.komorebi.data.ConnectionAccountSettings(it.userName)
                        }
                    },
                    logout = { logoutHonomi() },
                )
            }
        }.start(wait = false)
        Log.i("SettingsViewModel", "Started Local Server on port 8081")
    }

    fun stopGeminiLocalServer() {
        _connectionSetupState.value.session?.revoke()
        _connectionSetupState.value = com.beeregg2001.komorebi.setup.ConnectionSetupUiState()
        val stopping = ktorServer
        ktorServer = null
        viewModelScope.launch(Dispatchers.IO) {
            stopping?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
            Log.i("SettingsViewModel", "Stopped Local Server")
        }
    }

    override fun onCleared() {
        _connectionSetupState.value.session?.revoke()
        _connectionSetupState.value = com.beeregg2001.komorebi.setup.ConnectionSetupUiState()
        ktorServer?.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        ktorServer = null
        super.onCleared()
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) return addr.hostAddress
                        ?: ""
                }
            }
        } catch (e: Exception) {
        }
        return "127.0.0.1"
    }

    private fun getSetupHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="ja">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>AIコンシェルジュ設定</title>
                <style>body { font-family: sans-serif; background-color: #f0f2f5; display: flex; justify-content: center; padding: 20px; } .card { background: white; padding: 30px; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.1); width: 100%; max-width: 500px;} h2 { color: #1a73e8; text-align: center; } .step { background: #e8f0fe; padding: 15px; border-radius: 8px; margin-bottom: 20px; } .btn { display: block; width: 100%; padding: 15px; background: #34a853; color: white; text-align: center; text-decoration: none; border-radius: 8px; font-weight: bold; font-size: 16px; margin-top: 10px; box-sizing: border-box; } input { width: 100%; padding: 15px; margin: 10px 0; border: 2px solid #ccc; border-radius: 8px; box-sizing: border-box; font-size: 16px; } button { width: 100%; padding: 15px; background: #1a73e8; color: white; border: none; border-radius: 8px; font-size: 16px; font-weight: bold; cursor: pointer; }</style>
            </head>
            <body>
                <div class="card"><h2>AIコンシェルジュ連携</h2>
                    <div class="step"><p><b>Step 1:</b> 以下のボタンからGoogle AI Studioを開き、APIキー(AIzaSy...)を作成してコピーしてください。</p><a href="https://aistudio.google.com/app/apikey" target="_blank" class="btn">GoogleからAPIキーを取得</a></div>
                    <div class="step"><p><b>Step 2:</b> コピーしたAPIキーを下に貼り付けて、送信ボタンを押してください。</p>
                        <form id="setup-form"><input type="password" name="api_key" placeholder="AIzaSy..." required><button type="submit">テレビに送信する</button></form><p id="result" role="status"></p>
                        <script>
                        const token = location.hash.slice(1);
                        const form = document.getElementById('setup-form');
                        const result = document.getElementById('result');
                        if (!token) { result.textContent = 'テレビで設定ページを開き直してください。'; form.querySelector('button').disabled = true; }
                        form.addEventListener('submit', async (event) => {
                            event.preventDefault(); const button = form.querySelector('button'); button.disabled = true;
                            try {
                                const response = await fetch('/submit', {method: 'POST', headers: {'X-Setup-Token': token}, body: new URLSearchParams(new FormData(form))});
                                if (!response.ok) { const error = await response.json(); throw new Error(error.message); }
                                result.textContent = await response.text();
                            } catch (error) { result.textContent = error.message || '保存できませんでした。再試行してください。'; }
                            finally { button.disabled = false; }
                        });
                        </script>
                    </div>
                </div>
            </body></html>
        """.trimIndent()
    }

}
