package com.beeregg2001.komorebi.data

import android.content.Context
import android.util.Log
import com.beeregg2001.komorebi.common.UrlBuilder
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.beeregg2001.komorebi.data.model.NHKExclusionMode
import com.beeregg2001.komorebi.data.model.NHKExclusionState
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import com.beeregg2001.komorebi.data.account.AccountRecoverySummary
import com.beeregg2001.komorebi.data.account.KomorebiAccount
import com.beeregg2001.komorebi.data.account.shouldPromptAccountRecovery
import com.beeregg2001.komorebi.data.account.summarizeRecoverableAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import com.beeregg2001.komorebi.data.model.CmSkipMode
import com.beeregg2001.komorebi.data.api.interceptor.CloudflareAccessConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val ACCOUNT_MIGRATED = booleanPreferencesKey("system_account_migrated")
private val ACCOUNT_CREATED = booleanPreferencesKey("system_account_created")
private val PENDING_ACCOUNT_RECOVERY = booleanPreferencesKey("pending_account_recovery")
private val accountKeys = listOf("backend_type", "edcb_ip", "edcb_port", "edcb_http_port", "edcb_record_play_method", "epgstation_ip", "epgstation_port", "konomi_ip", "konomi_port", "mirakurun_ip", "mirakurun_port", "preferred_stream_source", "cf_access_client_id", "cf_access_client_secret", "smb_server_list")

private fun Preferences.accountSettings(): Map<String, String> = accountKeys.mapNotNull { key ->
    this[stringPreferencesKey(key)]?.let { key to it }
}.toMap()

private fun Preferences.hasConfiguredSettings(): Boolean =
    configuredConnectionSources().isNotEmpty() ||
        this[SettingsRepository.BACKEND_TYPE] == "TIF" ||
        !this[SettingsRepository.EPGSTATION_IP].isNullOrBlank()

private fun androidx.datastore.preferences.core.MutablePreferences.syncAccount(account: KomorebiAccount) {
    val values = accountSettings()
    val existing = account.read()
    val configured = hasConfiguredSettings()
    if (existing != null || (this[ACCOUNT_CREATED] != true && configured)) {
        account.update(create = existing == null) { it.copy(settings = values) }
        this[ACCOUNT_CREATED] = true
    }
}

private class AccountSettingsMigration(context: Context) : DataMigration<Preferences> {
    private val account = KomorebiAccount(context)
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[ACCOUNT_MIGRATED] != true
    override suspend fun migrate(currentData: Preferences): Preferences {
        val result = currentData.toMutablePreferences()
        val record = account.read()
        if (shouldPromptAccountRecovery(currentData.hasConfiguredSettings(), record)) {
            result[PENDING_ACCOUNT_RECOVERY] = true
        } else {
            result[PENDING_ACCOUNT_RECOVERY] = false
            result.syncAccount(account)
        }
        result[ACCOUNT_MIGRATED] = true
        return result
    }
    override suspend fun cleanUp() = Unit
}

private val Context.dataStore by preferencesDataStore(name = "settings", produceMigrations = { listOf(AccountSettingsMigration(it)) })
private val LEGACY_FAVORITE_BASEBALL_TEAMS = stringPreferencesKey("favorite_baseball_teams")
private val RECORD_CACHE_OWNER = stringPreferencesKey("record_cache_owner")
private const val LEGACY_BASEBALL_STARTUP_TAB = "プロ野球"
private const val DEFAULT_STARTUP_TAB = "ホーム"

internal fun normalizeStartupTab(value: String?): String =
    if (value == LEGACY_BASEBALL_STARTUP_TAB) DEFAULT_STARTUP_TAB else value ?: DEFAULT_STARTUP_TAB

internal fun normalizeCmSkipMode(value: String?): String = CmSkipMode.fromPreference(value).name

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val networkSettingsMutex = Mutex()
    private val _cloudflareAccessConfiguration = MutableStateFlow(CloudflareAccessConfiguration())
    val cloudflareAccessConfiguration: StateFlow<CloudflareAccessConfiguration> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        // Load once on first access (warmed asynchronously in init) so the first network request
        // observes persisted credentials without blocking the main thread during DI construction.
        _cloudflareAccessConfiguration.value = runBlocking {
            context.dataStore.data.first().toCloudflareAccessConfiguration()
        }
        settingsScope.launch {
            context.dataStore.data.collect {
                _cloudflareAccessConfiguration.value = it.toCloudflareAccessConfiguration()
            }
        }
        _cloudflareAccessConfiguration
    }

    init {
        settingsScope.launch(Dispatchers.IO) {
            runCatching { cloudflareAccessConfiguration }
        }
    }
    companion object {
        val BACKEND_TYPE = stringPreferencesKey("backend_type")
        val EDCB_IP = stringPreferencesKey("edcb_ip")
        val EDCB_PORT = stringPreferencesKey("edcb_port")
        val EDCB_HTTP_PORT = stringPreferencesKey("edcb_http_port")

        val EDCB_RECORD_PLAY_METHOD = stringPreferencesKey("edcb_record_play_method")
        val EPGSTATION_IP = stringPreferencesKey("epgstation_ip")
        val EPGSTATION_PORT = stringPreferencesKey("epgstation_port")

        val KONOMI_IP = stringPreferencesKey("konomi_ip")
        val KONOMI_PORT = stringPreferencesKey("konomi_port")
        val MIRAKURUN_IP = stringPreferencesKey("mirakurun_ip")
        val MIRAKURUN_PORT = stringPreferencesKey("mirakurun_port")
        val PREFERRED_STREAM_SOURCE = stringPreferencesKey("preferred_stream_source")
        val COMMENT_SPEED = stringPreferencesKey("comment_speed")
        val COMMENT_FONT_SIZE = stringPreferencesKey("comment_font_size")
        val COMMENT_OPACITY = stringPreferencesKey("comment_opacity")
        val COMMENT_MAX_LINES = stringPreferencesKey("comment_max_lines")
        val COMMENT_DEFAULT_DISPLAY = stringPreferencesKey("comment_default_display")
        val COMMENT_ENGINE = stringPreferencesKey("comment_engine")
        val COMMENT_WORD_COUNTER = stringPreferencesKey("comment_word_counter")
        val LIVE_QUALITY = stringPreferencesKey("live_quality")
        val VIDEO_QUALITY = stringPreferencesKey("video_quality")
        val LIVE_SUBTITLE_DEFAULT = stringPreferencesKey("live_subtitle_default")
        val VIDEO_SUBTITLE_DEFAULT = stringPreferencesKey("video_subtitle_default")
        val SUBTITLE_FONT = stringPreferencesKey("subtitle_font")
        val SUBTITLE_COMMENT_LAYER = stringPreferencesKey("subtitle_comment_layer")
        val AUDIO_OUTPUT_MODE = stringPreferencesKey("audio_output_mode")
        val HDR_RENDER_MODE = stringPreferencesKey("hdr_render_mode")
        val VIDEO_PLAYBACK_SPEED = stringPreferencesKey("video_playback_speed")
        val SMB_SUBTITLE_ENABLED = stringPreferencesKey("smb_subtitle_enabled")

        val PLAYER_UI_MODE = stringPreferencesKey("player_ui_mode")
        val AUTO_CM_SKIP = stringPreferencesKey("auto_cm_skip")
        val RECORDED_SEEK_STEP_SECONDS = stringPreferencesKey("recorded_seek_step_seconds")
        val PREFER_ORIGINAL_MPEG_TS = stringPreferencesKey("prefer_original_mpeg_ts")

        val LAB_ANNICT_INTEGRATION = stringPreferencesKey("lab_annict_integration")
        val LAB_SHOBOCAL_INTEGRATION = stringPreferencesKey("lab_shobocal_integration")
        val LAB_ALLOW_MIRAKURUN_DUAL = stringPreferencesKey("lab_allow_mirakurun_dual")
        val DEFAULT_POST_COMMAND = stringPreferencesKey("default_post_command")
        val POST_RECORDING_BATCH_LIST = stringPreferencesKey("post_recording_batch_list")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val ENABLE_AI_NORMALIZATION = stringPreferencesKey("enable_ai_normalization")

        val HOME_PICKUP_GENRE = stringPreferencesKey("home_pickup_genre")
        val EXCLUDE_PAID_BROADCASTS = stringPreferencesKey("exclude_paid_broadcasts")
        val LATE_NIGHT_WATCH_MODE = stringPreferencesKey("late_night_watch_mode")
        val SPOILER_BLUR_ENABLED = stringPreferencesKey("spoiler_blur_enabled")
        val JIKKYO_FESTIVAL_ALERT = stringPreferencesKey("jikkyo_festival_alert")
        val CM_PANIC_ZAP_ENABLED = stringPreferencesKey("cm_panic_zap_enabled")
        val NHK_EXCLUSION_MODE = stringPreferencesKey("nhk_exclusion_mode")
        val NHK_EXCLUSION_EXPIRES_AT = longPreferencesKey("nhk_exclusion_expires_at")
        val HOME_PICKUP_TIME = stringPreferencesKey("home_pickup_time")
        val STARTUP_TAB = stringPreferencesKey("startup_tab")
        val STARTUP_FOCUS_TARGET = stringPreferencesKey("startup_focus_target")
        val STARTUP_CHANNEL = stringPreferencesKey("startup_channel")
        val PLAYER_CLOCK_MODE = stringPreferencesKey("player_clock_mode")
        val TIME_FORMAT = stringPreferencesKey("time_format")
        val APP_THEME = stringPreferencesKey("app_theme")
        val DEFAULT_RECORD_LIST_VIEW = stringPreferencesKey("default_record_list_view")
        val LAUNCHER_APP_ORDER = stringPreferencesKey("launcher_app_order")
        val LAUNCHER_APP_HIDDEN = stringPreferencesKey("launcher_app_hidden")
        val HIDE_LAUNCHER_APP_LABELS = booleanPreferencesKey("hide_launcher_app_labels")
        val HIDE_APPS_TAB = booleanPreferencesKey("hide_apps_tab")
        val LAUNCHER_APP_COLUMNS = stringPreferencesKey("launcher_app_columns")
        val UI_SOUND_EFFECTS_MODE = stringPreferencesKey("ui_sound_effects_mode")
        val TAB_BOUNDARY_HINT_SEEN_COUNT = intPreferencesKey("tab_boundary_hint_seen_count")

        val RECEIVE_BETA_UPDATES = booleanPreferencesKey("receive_beta_updates")
        val HIDE_SUB_CHANNELS = booleanPreferencesKey("hide_sub_channels")
        val TIF_INTEGRATION_ENABLED = booleanPreferencesKey("tif_integration_enabled")
        val TIF_HIDE_EMPTY_INPUTS = booleanPreferencesKey("tif_hide_empty_inputs")
        val CAST_ACCESS_MODE = stringPreferencesKey("cast_access_mode")
        val TIF_SHOW_TUNER_INPUTS = booleanPreferencesKey("tif_show_tuner_inputs")
        val TIF_NO_SIGNAL_STYLE = stringPreferencesKey("tif_no_signal_style")
        val TIF_NO_SIGNAL_ACCENT_COLOR = stringPreferencesKey("tif_no_signal_accent_color")

        val WATCH_NEXT_ENABLED = booleanPreferencesKey("watch_next_enabled")
        val WATCH_NEXT_NEXT_EPISODE_ENABLED = booleanPreferencesKey("watch_next_next_episode_enabled")
        val PREVIEW_CHANNEL_LIVE_ENABLED = booleanPreferencesKey("preview_channel_live_enabled")
        val PREVIEW_CHANNEL_RECORDINGS_ENABLED = booleanPreferencesKey("preview_channel_recordings_enabled")
        val REMOTE_SHORTCUT_KEYS_ENABLED = booleanPreferencesKey("remote_shortcut_keys_enabled")
        val REMOTE_NUMBER_TUNING_ENABLED = booleanPreferencesKey("remote_number_tuning_enabled")
        val OS_PIP_AUTO_ENTER_ENABLED = booleanPreferencesKey("os_pip_auto_enter_enabled")
        val BACKGROUND_PLAYBACK_MODE = stringPreferencesKey("background_playback_mode")

        val SCREENSAVER_ENABLED = booleanPreferencesKey("screensaver_enabled")
        val SCREENSAVER_TIMEOUT_MINUTES = intPreferencesKey("screensaver_timeout_minutes")
        val SCREENSAVER_PLAYER_TIMEOUT_MINUTES = intPreferencesKey("screensaver_player_timeout_minutes")
        val SCREENSAVER_PLAYER_STYLE = stringPreferencesKey("screensaver_player_style")
        val PLAYER_END_SLEEP_MINUTES = intPreferencesKey("player_end_sleep_minutes")
        val SCREENSAVER_INTERVAL_SECONDS = intPreferencesKey("screensaver_interval_seconds")
        val SCREENSAVER_TRANSITION = stringPreferencesKey("screensaver_transition")
        val SCREENSAVER_IMAGE_URIS = stringPreferencesKey("screensaver_image_uris")
        val SCREENSAVER_SHOW_UPCOMING = booleanPreferencesKey("screensaver_show_upcoming")

        val AVAILABLE_STREAM_QUALITIES = stringPreferencesKey("available_stream_qualities")

        // ★ 変更: 個別のSMBキーを廃止し、JSONリスト用のキーを新設
        val SMB_SERVER_LIST = stringPreferencesKey("smb_server_list")

        // ★ 追加: 番組表の設定キー
        val EPG_COLUMN_COUNT = stringPreferencesKey("epg_column_count")
        val EPG_FONT_SIZE_SCALE = stringPreferencesKey("epg_font_size_scale")
        val EPG_VISIBLE_HOURS = stringPreferencesKey("epg_visible_hours")
        val CF_ACCESS_CLIENT_ID = stringPreferencesKey("cf_access_client_id")
        val CF_ACCESS_CLIENT_SECRET = stringPreferencesKey("cf_access_client_secret")
        val LAST_SEEN_VERSION_CODE = intPreferencesKey("last_seen_version_code")

        // データ放送設定
        val DATA_BROADCASTING_ALLOW_EXTERNAL_NETWORK =
            booleanPreferencesKey("data_broadcasting_allow_external_network")
        val DATA_BROADCASTING_ZIPCODE = stringPreferencesKey("data_broadcasting_zipcode")
        val DATA_BROADCASTING_PREFECTURE_CODE =
            stringPreferencesKey("data_broadcasting_prefecture_code")
        val DATA_BROADCASTING_VIRTUAL_REMOTE_MODE =
            stringPreferencesKey("data_broadcasting_virtual_remote_mode")
        val DATA_BROADCASTING_PRELOAD_B24 =
            booleanPreferencesKey("data_broadcasting_preload_b24")
    }

    val isInitialized: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            val backend = preferences.resolvedBackendType()
            if (backend == "TIF") true
            else if (backend == "EPGSTATION") !preferences[EPGSTATION_IP].isNullOrBlank()
            else preferences.configuredConnectionSources().any { it.backend == backend }
        }

    val isPendingAccountRecovery: Flow<Boolean> = context.dataStore.data
        .map { it[PENDING_ACCOUNT_RECOVERY] == true }

    fun readRecoverableAccountSummary(): AccountRecoverySummary =
        summarizeRecoverableAccount(KomorebiAccount(context).read())

    suspend fun restoreFromSystemAccount() = networkSettingsMutex.withLock {
        val record = KomorebiAccount(context).read()
        val saved = context.dataStore.edit { settings ->
            if (record != null && record.settings.isNotEmpty()) {
                record.settings.forEach { (key, value) ->
                    settings[stringPreferencesKey(key)] = value
                }
                settings[ACCOUNT_CREATED] = true
            }
            settings[PENDING_ACCOUNT_RECOVERY] = false
        }.toCloudflareAccessConfiguration()
        cloudflareAccessConfiguration.first { it == saved }
    }

    suspend fun discardSystemAccountAndStartFresh() = networkSettingsMutex.withLock {
        KomorebiAccount(context).update { KomorebiAccount.Record() }
        context.dataStore.edit { settings ->
            settings[ACCOUNT_CREATED] = false
            settings[PENDING_ACCOUNT_RECOVERY] = false
        }
    }

    val backendType: Flow<String> = context.dataStore.data.map { it.resolvedBackendType() }
    val lastSeenVersionCode: Flow<Int?> = context.dataStore.data.map { it[LAST_SEEN_VERSION_CODE] }
    suspend fun updateLastSeenVersionCode(versionCode: Int) {
        context.dataStore.edit { it[LAST_SEEN_VERSION_CODE] = versionCode }
    }
    val recordCacheOwner: Flow<String?> = context.dataStore.data.map { it[RECORD_CACHE_OWNER] }
    val recordCacheEligible: Flow<Boolean> = context.dataStore.data.map {
        val current = it.connectionSettingsSnapshot().recordCacheIdentity()
        current != null && (it[RECORD_CACHE_OWNER] == null || it[RECORD_CACHE_OWNER] == current)
    }
    suspend fun setRecordCacheOwner(owner: String) {
        context.dataStore.edit { it[RECORD_CACHE_OWNER] = owner }
    }
    val edcbIp: Flow<String> = context.dataStore.data.map { it[EDCB_IP] ?: "" }
    val edcbPort: Flow<String> = context.dataStore.data.map { it[EDCB_PORT] ?: "4510" }
    val edcbHttpPort: Flow<String> = context.dataStore.data.map { it[EDCB_HTTP_PORT] ?: "5510" }
    val edcbRecordPlayMethod: Flow<String> =
        context.dataStore.data.map { it[EDCB_RECORD_PLAY_METHOD] ?: "DIRECT" }
    val epgStationIp: Flow<String> = context.dataStore.data.map { it[EPGSTATION_IP] ?: "" }
    val epgStationPort: Flow<String> = context.dataStore.data.map { it[EPGSTATION_PORT] ?: "8888" }
    val konomiIp: Flow<String> =
        context.dataStore.data.map { it[KONOMI_IP] ?: "https://192-168-xxx-xxx.local.konomi.tv" }
    val konomiPort: Flow<String> = context.dataStore.data.map { it[KONOMI_PORT] ?: "7000" }
    val mirakurunIp: Flow<String> = context.dataStore.data.map { it[MIRAKURUN_IP] ?: "" }
    val mirakurunPort: Flow<String> = context.dataStore.data.map { it[MIRAKURUN_PORT] ?: "40772" }
    val preferredStreamSource: Flow<String> =
        context.dataStore.data.map { it[PREFERRED_STREAM_SOURCE] ?: "KONOMITV" }
    val commentSpeed: Flow<String> = context.dataStore.data.map { it[COMMENT_SPEED] ?: "1.0" }
    val commentFontSize: Flow<String> =
        context.dataStore.data.map { it[COMMENT_FONT_SIZE] ?: "1.0" }
    val commentOpacity: Flow<String> = context.dataStore.data.map { it[COMMENT_OPACITY] ?: "1.0" }
    val commentMaxLines: Flow<String> = context.dataStore.data.map { it[COMMENT_MAX_LINES] ?: "0" }
    val commentDefaultDisplay: Flow<String> =
        context.dataStore.data.map { it[COMMENT_DEFAULT_DISPLAY] ?: "ON" }
    val commentEngine: Flow<String> =
        context.dataStore.data.map { it[COMMENT_ENGINE] ?: "DFM_HARDWARE" }
    val commentWordCounter: Flow<String> =
        context.dataStore.data.map { it[COMMENT_WORD_COUNTER] ?: "ON" }
    val liveQuality: Flow<String> = context.dataStore.data.map { it[LIVE_QUALITY] ?: "1080p-60fps" }
    val videoQuality: Flow<String> =
        context.dataStore.data.map { it[VIDEO_QUALITY] ?: "1080p-60fps" }
    val liveSubtitleDefault: Flow<String> =
        context.dataStore.data.map { it[LIVE_SUBTITLE_DEFAULT] ?: "ON" }
    val videoSubtitleDefault: Flow<String> =
        context.dataStore.data.map { it[VIDEO_SUBTITLE_DEFAULT] ?: "ON" }
    val subtitleFont: Flow<String> =
        context.dataStore.data.map { it[SUBTITLE_FONT] ?: "default" }
    val subtitleCommentLayer: Flow<String> =
        context.dataStore.data.map { it[SUBTITLE_COMMENT_LAYER] ?: "CommentOnTop" }
    val audioOutputMode: Flow<String> =
        context.dataStore.data.map { it[AUDIO_OUTPUT_MODE] ?: "DOWNMIX" }
    val hdrRenderMode: Flow<String> =
        context.dataStore.data.map { it[HDR_RENDER_MODE] ?: "ORIGINAL" }
    val videoPlaybackSpeed: Flow<String> =
        context.dataStore.data.map { it[VIDEO_PLAYBACK_SPEED] ?: "1.0" }
    val smbSubtitleEnabled: Flow<String> =
        context.dataStore.data.map { it[SMB_SUBTITLE_ENABLED] ?: "OFF" }
    val playerUiMode: Flow<String> = context.dataStore.data.map { it[PLAYER_UI_MODE] ?: "CLASSIC" }
    val autoCmSkip: Flow<String> = context.dataStore.data.map { normalizeCmSkipMode(it[AUTO_CM_SKIP]) }
    val recordedSeekStepSeconds: Flow<Int> = context.dataStore.data.map {
        when (it[RECORDED_SEEK_STEP_SECONDS]) {
            "5" -> 5
            "10" -> 10
            "15" -> 15
            else -> 30
        }
    }
    val preferOriginalMpegTs: Flow<String> =
        context.dataStore.data.map { it[PREFER_ORIGINAL_MPEG_TS] ?: "OFF" }
    val labAnnictIntegration: Flow<String> =
        context.dataStore.data.map { it[LAB_ANNICT_INTEGRATION] ?: "OFF" }
    val labShobocalIntegration: Flow<String> =
        context.dataStore.data.map { it[LAB_SHOBOCAL_INTEGRATION] ?: "OFF" }
    val labAllowMirakurunDual: Flow<String> =
        context.dataStore.data.map { it[LAB_ALLOW_MIRAKURUN_DUAL] ?: "OFF" }
    val defaultPostCommand: Flow<String> =
        context.dataStore.data.map { it[DEFAULT_POST_COMMAND] ?: "" }
    val postRecordingBatchList: Flow<String> =
        context.dataStore.data.map { it[POST_RECORDING_BATCH_LIST] ?: "[]" }
    val geminiApiKey: Flow<String> = context.dataStore.data.map { it[GEMINI_API_KEY] ?: "" }
    val enableAiNormalization: Flow<String> =
        context.dataStore.data.map { it[ENABLE_AI_NORMALIZATION] ?: "OFF" }
    val homePickupGenre: Flow<String> =
        context.dataStore.data.map { it[HOME_PICKUP_GENRE] ?: "アニメ" }
    val excludePaidBroadcasts: Flow<String> =
        context.dataStore.data.map { it[EXCLUDE_PAID_BROADCASTS] ?: "OFF" }
    val lateNightWatchMode: Flow<String> =
        context.dataStore.data.map { it[LATE_NIGHT_WATCH_MODE] ?: "OFF" }
    val spoilerBlurEnabled: Flow<String> =
        context.dataStore.data.map { it[SPOILER_BLUR_ENABLED] ?: "OFF" }
    val jikkyoFestivalAlert: Flow<String> =
        context.dataStore.data.map { it[JIKKYO_FESTIVAL_ALERT] ?: "OFF" }
    val cmPanicZapEnabled: Flow<String> =
        context.dataStore.data.map { it[CM_PANIC_ZAP_ENABLED] ?: "OFF" }
    val nhkExclusionPreferences: Flow<NHKExclusionState> = context.dataStore.data.map {
        NHKExclusionState(
            mode = NHKExclusionMode.fromPreference(it[NHK_EXCLUSION_MODE]),
            expiresAtMillis = it[NHK_EXCLUSION_EXPIRES_AT],
        )
    }

    suspend fun saveNHKExclusion(state: NHKExclusionState) {
        context.dataStore.edit {
            it[NHK_EXCLUSION_MODE] = state.mode.name
            if (state.expiresAtMillis == null) it.remove(NHK_EXCLUSION_EXPIRES_AT)
            else it[NHK_EXCLUSION_EXPIRES_AT] = state.expiresAtMillis
        }
    }
    val homePickupTime: Flow<String> = context.dataStore.data.map { it[HOME_PICKUP_TIME] ?: "自動" }
    val startupTab: Flow<String> = context.dataStore.data.map {
        normalizeStartupTab(it[STARTUP_TAB])
    }
    val startupFocusTarget: Flow<StartupFocusTarget> = context.dataStore.data.map {
        StartupFocusTarget.fromPreference(it[STARTUP_FOCUS_TARGET])
    }
    val startupChannel: Flow<String> = context.dataStore.data.map { it[STARTUP_CHANNEL] ?: "OFF" }
    val playerClockMode: Flow<PlayerClockMode> = context.dataStore.data.map {
        PlayerClockMode.fromPreference(it[PLAYER_CLOCK_MODE])
    }
    val timeFormat: Flow<String> = context.dataStore.data.map { it[TIME_FORMAT] ?: "24H" }
    val appTheme: Flow<String> = context.dataStore.data.map { it[APP_THEME] ?: "MONOTONE" }
    val defaultRecordListView: Flow<String> =
        context.dataStore.data.map { it[DEFAULT_RECORD_LIST_VIEW] ?: "LIST" }
    val launcherAppOrder: Flow<String> =
        context.dataStore.data.map { it[LAUNCHER_APP_ORDER] ?: "[]" }
    val launcherAppHidden: Flow<String> =
        context.dataStore.data.map { it[LAUNCHER_APP_HIDDEN] ?: "[]" }
    val hideLauncherAppLabels: Flow<Boolean> =
        context.dataStore.data.map { it[HIDE_LAUNCHER_APP_LABELS] ?: false }
    val hideAppsTab: Flow<Boolean> =
        context.dataStore.data.map { it[HIDE_APPS_TAB] ?: false }
    val launcherAppColumns: Flow<String> =
        context.dataStore.data.map { it[LAUNCHER_APP_COLUMNS] ?: "AUTO" }
    val uiSoundEffectsMode: Flow<com.beeregg2001.komorebi.common.UiSoundEffectMode> =
        context.dataStore.data.map {
            com.beeregg2001.komorebi.common.UiSoundEffectMode.fromPreference(it[UI_SOUND_EFFECTS_MODE])
        }
    val tabBoundaryHintSeenCount: Flow<Int> =
        context.dataStore.data.map { it[TAB_BOUNDARY_HINT_SEEN_COUNT] ?: 0 }

    suspend fun incrementTabBoundaryHintSeenCount(maxCount: Int = 5) {
        context.dataStore.edit { prefs ->
            val current = prefs[TAB_BOUNDARY_HINT_SEEN_COUNT] ?: 0
            if (current < maxCount) {
                prefs[TAB_BOUNDARY_HINT_SEEN_COUNT] = current + 1
            }
        }
    }
    val receiveBetaUpdates: Flow<Boolean> =
        context.dataStore.data.map { it[RECEIVE_BETA_UPDATES] ?: false }
    val hideSubChannels: Flow<Boolean> =
        context.dataStore.data.map { it[HIDE_SUB_CHANNELS] ?: false }
    val tifIntegrationEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[TIF_INTEGRATION_ENABLED] ?: true }
    val castAccessMode: Flow<com.beeregg2001.komorebi.data.remote.CastAccessMode> =
        context.dataStore.data.map {
            it[CAST_ACCESS_MODE]?.let(com.beeregg2001.komorebi.data.remote.CastAccessMode::valueOf)
                ?: com.beeregg2001.komorebi.data.remote.CastAccessMode.ACCOUNT
        }
    val tifHideEmptyInputs: Flow<Boolean> =
        context.dataStore.data.map { it[TIF_HIDE_EMPTY_INPUTS] ?: true }
    val tifShowTunerInputs: Flow<Boolean> =
        context.dataStore.data.map { it[TIF_SHOW_TUNER_INPUTS] ?: false }
    val tifNoSignalStyle: Flow<TifNoSignalStyle> =
        context.dataStore.data.map { TifNoSignalStyle.fromPreference(it[TIF_NO_SIGNAL_STYLE]) }
    val tifNoSignalAccentColor: Flow<com.beeregg2001.komorebi.data.TifNoSignalAccentColor> =
        context.dataStore.data.map {
            com.beeregg2001.komorebi.data.TifNoSignalAccentColor.fromPreference(it[TIF_NO_SIGNAL_ACCENT_COLOR])
        }

    private val defaultDeepTvFeatureSettings = DeepTvFeatureSettings()

    val deepTvFeatureSettings: Flow<DeepTvFeatureSettings> = context.dataStore.data.map { prefs ->
        val legacyOsPip = prefs[OS_PIP_AUTO_ENTER_ENABLED] ?: defaultDeepTvFeatureSettings.osPipAutoEnterEnabled
        val backgroundPlaybackMode = com.beeregg2001.komorebi.data.BackgroundPlaybackMode.fromPreference(
            value = prefs[BACKGROUND_PLAYBACK_MODE],
            legacyOsPipAutoEnter = legacyOsPip,
        )
        DeepTvFeatureSettings(
            watchNextEnabled = prefs[WATCH_NEXT_ENABLED] ?: defaultDeepTvFeatureSettings.watchNextEnabled,
            watchNextNextEpisodeEnabled = prefs[WATCH_NEXT_NEXT_EPISODE_ENABLED] ?: defaultDeepTvFeatureSettings.watchNextNextEpisodeEnabled,
            previewLiveEnabled = prefs[PREVIEW_CHANNEL_LIVE_ENABLED] ?: defaultDeepTvFeatureSettings.previewLiveEnabled,
            previewRecordingsEnabled = prefs[PREVIEW_CHANNEL_RECORDINGS_ENABLED] ?: defaultDeepTvFeatureSettings.previewRecordingsEnabled,
            remoteShortcutKeysEnabled = prefs[REMOTE_SHORTCUT_KEYS_ENABLED] ?: defaultDeepTvFeatureSettings.remoteShortcutKeysEnabled,
            remoteNumberTuningEnabled = prefs[REMOTE_NUMBER_TUNING_ENABLED] ?: defaultDeepTvFeatureSettings.remoteNumberTuningEnabled,
            osPipAutoEnterEnabled = backgroundPlaybackMode == com.beeregg2001.komorebi.data.BackgroundPlaybackMode.PIP,
            backgroundPlaybackMode = backgroundPlaybackMode,
        )
    }

    private fun keyFor(feature: DeepTvFeature): Preferences.Key<Boolean> = when (feature) {
        DeepTvFeature.WATCH_NEXT -> WATCH_NEXT_ENABLED
        DeepTvFeature.WATCH_NEXT_NEXT_EPISODE -> WATCH_NEXT_NEXT_EPISODE_ENABLED
        DeepTvFeature.PREVIEW_LIVE -> PREVIEW_CHANNEL_LIVE_ENABLED
        DeepTvFeature.PREVIEW_RECORDINGS -> PREVIEW_CHANNEL_RECORDINGS_ENABLED
        DeepTvFeature.REMOTE_SHORTCUT_KEYS -> REMOTE_SHORTCUT_KEYS_ENABLED
        DeepTvFeature.REMOTE_NUMBER_TUNING -> REMOTE_NUMBER_TUNING_ENABLED
        DeepTvFeature.OS_PIP_AUTO_ENTER -> OS_PIP_AUTO_ENTER_ENABLED
    }

    suspend fun toggleDeepTvFeature(feature: DeepTvFeature) {
        val key = keyFor(feature)
        val defaultValue = defaultDeepTvFeatureSettings.isEnabled(feature)
        context.dataStore.edit { settings ->
            val current = settings[key] ?: defaultValue
            settings[key] = !current
            if (feature == DeepTvFeature.OS_PIP_AUTO_ENTER) {
                settings[BACKGROUND_PLAYBACK_MODE] = if (!current) {
                    com.beeregg2001.komorebi.data.BackgroundPlaybackMode.PIP.preferenceValue
                } else {
                    com.beeregg2001.komorebi.data.BackgroundPlaybackMode.OFF.preferenceValue
                }
            }
        }
    }

    suspend fun setBackgroundPlaybackMode(mode: com.beeregg2001.komorebi.data.BackgroundPlaybackMode) {
        context.dataStore.edit { settings ->
            settings[BACKGROUND_PLAYBACK_MODE] = mode.preferenceValue
            settings[OS_PIP_AUTO_ENTER_ENABLED] =
                (mode == com.beeregg2001.komorebi.data.BackgroundPlaybackMode.PIP)
        }
    }

    val dataBroadcastingSettings: Flow<DataBroadcastingSettings> = context.dataStore.data.map { prefs ->
        val rawZip = prefs[DATA_BROADCASTING_ZIPCODE].orEmpty()
        val normalizedZip = DataBroadcastingSettings.normalizeZipcode(rawZip).orEmpty()
        val prefectureCode = prefs[DATA_BROADCASTING_PREFECTURE_CODE]?.toIntOrNull()
            ?.takeIf { DataBroadcastingPrefectures.findByCode(it) != null }
        DataBroadcastingSettings(
            allowExternalNetwork = prefs[DATA_BROADCASTING_ALLOW_EXTERNAL_NETWORK] ?: false,
            zipcode = normalizedZip,
            prefectureCode = prefectureCode,
            virtualRemoteMode = DataBroadcastingVirtualRemoteMode.fromPreference(
                prefs[DATA_BROADCASTING_VIRTUAL_REMOTE_MODE]
            ),
            preloadB24InBackground = prefs[DATA_BROADCASTING_PRELOAD_B24] ?: true,
        )
    }

    suspend fun setDataBroadcastingPreloadB24(enabled: Boolean) {
        context.dataStore.edit { settings ->
            settings[DATA_BROADCASTING_PRELOAD_B24] = enabled
        }
    }

    suspend fun setDataBroadcastingAllowExternalNetwork(enabled: Boolean) {
        context.dataStore.edit { settings ->
            settings[DATA_BROADCASTING_ALLOW_EXTERNAL_NETWORK] = enabled
        }
    }

    suspend fun setDataBroadcastingZipcode(zipcode: String) {
        val normalized = DataBroadcastingSettings.normalizeZipcode(zipcode)
            ?: throw IllegalArgumentException("郵便番号は「000-0000」の形式で入力してください。")
        context.dataStore.edit { settings ->
            if (normalized.isEmpty()) {
                settings.remove(DATA_BROADCASTING_ZIPCODE)
            } else {
                settings[DATA_BROADCASTING_ZIPCODE] = normalized
            }
        }
    }

    suspend fun setDataBroadcastingPrefectureCode(code: Int?) {
        context.dataStore.edit { settings ->
            if (code == null || DataBroadcastingPrefectures.findByCode(code) == null) {
                settings.remove(DATA_BROADCASTING_PREFECTURE_CODE)
            } else {
                settings[DATA_BROADCASTING_PREFECTURE_CODE] = code.toString()
            }
        }
    }

    suspend fun setDataBroadcastingVirtualRemoteMode(mode: DataBroadcastingVirtualRemoteMode) {
        context.dataStore.edit { settings ->
            settings[DATA_BROADCASTING_VIRTUAL_REMOTE_MODE] = mode.preferenceValue
        }
    }
    val screensaverEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[SCREENSAVER_ENABLED] ?: false }
    val screensaverTimeoutMinutes: Flow<Int> =
        context.dataStore.data.map { it[SCREENSAVER_TIMEOUT_MINUTES] ?: 5 }
    val screensaverPlayerTimeoutMinutes: Flow<Int> =
        context.dataStore.data.map { it[SCREENSAVER_PLAYER_TIMEOUT_MINUTES] ?: 10 }
    val screensaverPlayerStyle: Flow<String> =
        context.dataStore.data.map { it[SCREENSAVER_PLAYER_STYLE] ?: "SLIDESHOW" }
    val playerEndSleepMinutes: Flow<Int> =
        context.dataStore.data.map { it[PLAYER_END_SLEEP_MINUTES] ?: 60 }
    val screensaverIntervalSeconds: Flow<Int> =
        context.dataStore.data.map { it[SCREENSAVER_INTERVAL_SECONDS] ?: 30 }
    val screensaverTransition: Flow<String> =
        context.dataStore.data.map { it[SCREENSAVER_TRANSITION] ?: "FADE" }
    val screensaverImageUris: Flow<String> =
        context.dataStore.data.map { it[SCREENSAVER_IMAGE_URIS] ?: "[]" }
    val screensaverShowUpcoming: Flow<Boolean> =
        context.dataStore.data.map { it[SCREENSAVER_SHOW_UPCOMING] ?: false }
    val availableStreamQualities: Flow<String> =
        context.dataStore.data.map { it[AVAILABLE_STREAM_QUALITIES] ?: "" }

    // ★ 変更: SMBサーバーのJSONリストを読み出す
    val smbServerList: Flow<String> = context.dataStore.data.map { it[SMB_SERVER_LIST] ?: "[]" }

    // ★ 追加: 番組表設定の読み込み (デフォルト: 7ch, 等倍サイズ)
    val epgColumnCount: Flow<String> = context.dataStore.data.map { it[EPG_COLUMN_COUNT] ?: "7" }
    val epgFontSizeScale: Flow<String> = context.dataStore.data.map { it[EPG_FONT_SIZE_SCALE] ?: "1.0" }
    val epgVisibleHours: Flow<String> = context.dataStore.data.map { it[EPG_VISIBLE_HOURS] ?: "6" }
    val cfAccessClientId: Flow<String> = context.dataStore.data.map { it[CF_ACCESS_CLIENT_ID] ?: "" }
    val cfAccessClientSecret: Flow<String> = context.dataStore.data.map { it[CF_ACCESS_CLIENT_SECRET] ?: "" }

    suspend fun saveString(
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        value: String
    ) = networkSettingsMutex.withLock {
        val saved = context.dataStore.edit { settings ->
            settings[key] = value
            if (key in setOf(BACKEND_TYPE, KONOMI_IP, KONOMI_PORT, EDCB_IP, EDCB_PORT, MIRAKURUN_IP, MIRAKURUN_PORT)) {
                settings[BACKEND_TYPE] = settings.resolvedBackendType()
            }
            if (key.name in accountKeys) settings.syncAccount(KomorebiAccount(context))
        }
            .toCloudflareAccessConfiguration()
        cloudflareAccessConfiguration.first { it == saved }
    }

    suspend fun saveBoolean(
        key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
        value: Boolean
    ) {
        context.dataStore.edit { settings -> settings[key] = value }
    }

    suspend fun connectionSettings(): ConnectionSettingsSnapshot =
        context.dataStore.data.first().connectionSettingsSnapshot()

    /** All source fields and the main system become visible in one DataStore transaction. */
    suspend fun saveConnectionSettings(snapshot: ConnectionSettingsSnapshot, beforeCommit: () -> Unit = {}): ConnectionSettingsSnapshot = networkSettingsMutex.withLock {
        val sources = requireNotNull(snapshot.sources) { "接続先を入力してください。" }
        val networkSources = ConnectionSource.entries.filter { it != ConnectionSource.TIF }
        require(sources.size == networkSources.size &&
            sources.map { it.id }.toSet() == networkSources.map { it.backend }.toSet()) {
            "接続先の種類が不正です。ページを開き直してください。"
        }
        fun port(value: String?, http: Boolean = false): String {
            val numeric = if (http) value?.trim()?.removeSuffix("s") else value?.trim()
            require(numeric?.toIntOrNull() in 1..65535) { "ポート番号は 1〜65535 で入力してください。" }
            return value!!.trim()
        }
        val normalized = sources.associate { source ->
            val host = requireNotNull(source.host) { "IPアドレスを確認してください。" }.trim()
            require(host.none(Char::isWhitespace) && host != ConnectionSourcePolicy.KONOMI_PLACEHOLDER) {
                "IPアドレスまたはURLを確認してください。"
            }
            source.id to ConnectionSourceSettings(source.id, ConnectionSource.entries.first { it.backend == source.id }.label,
                host = host, port = port(source.port),
                httpPort = if (source.id == "EDCB") port(source.httpPort, http = true) else null)
        }
        val k = normalized.getValue("KONOMITV")
        val e = normalized.getValue("EDCB")
        val m = normalized.getValue("MIRAKURUN_ONLY")
        val configured = ConnectionSourcePolicy.configured(k.host, k.port, e.host, e.port, m.host, m.port)
        require(configured.isNotEmpty() || snapshot.mainSystem == "TIF") { "少なくとも1つの接続先を入力してください。" }
        require(snapshot.mainSystem == "TIF" || configured.size == 1 || configured.any { it.backend == snapshot.mainSystem }) {
            "設定済みの接続先からメインシステムを選んでください。"
        }
        require(snapshot.preferredSource in listOf("KONOMITV", "EDCB", "MIRAKURUN")) { "ライブ視聴の優先ソースを確認してください。" }
        require(snapshot.edcbRecordPlayMethod in listOf("DIRECT", "API")) { "録画ファイルの再生方式を確認してください。" }
        val access = requireNotNull(snapshot.cloudflareAccess) { "サービス トークンを確認してください。" }
        val id = access.clientId.filterNot(Char::isWhitespace)
        val submittedSecret = access.clientSecret?.filterNot(Char::isWhitespace)
        require(snapshot.smbServers.map { it.id }.toSet().size == snapshot.smbServers.size) { "SMBサーバーが重複しています。" }
        snapshot.smbServers.forEach {
            require(it.id.isNotBlank() && it.name.isNotBlank() && it.ip.isNotBlank() && it.ip.none(Char::isWhitespace)) {
                "SMBサーバーの表示名とアドレスを入力してください。"
            }
            port(it.port)
        }
        val saved = context.dataStore.edit {
            val secret = submittedSecret ?: it[CF_ACCESS_CLIENT_SECRET].orEmpty()
            require((id.isEmpty() && secret.isEmpty()) || (id.isNotEmpty() && secret.isNotEmpty())) {
                "Client ID と Client Secret を両方入力するか、両方消去してください。"
            }
            require((id + secret).all { character -> character.code in 33..126 }) { "トークンには半角英数字・記号を入力してください。" }
            val oldSmb = it.storedSmbServers().associateBy { server -> server.id }
            val smb = snapshot.smbServers.map { server ->
                com.beeregg2001.komorebi.viewmodel.SmbServer(server.id, server.name.trim(), server.ip.trim(),
                    server.port.trim(), server.user, server.password ?: oldSmb[server.id]?.password.orEmpty())
            }
            beforeCommit()
            it[KONOMI_IP] = k.host; it[KONOMI_PORT] = k.port
            it[EDCB_IP] = e.host; it[EDCB_PORT] = e.port; it[EDCB_HTTP_PORT] = e.httpPort!!
            it[MIRAKURUN_IP] = m.host; it[MIRAKURUN_PORT] = m.port
            it[BACKEND_TYPE] = ConnectionSourcePolicy.resolve(snapshot.mainSystem, configured)
            it[PREFERRED_STREAM_SOURCE] = snapshot.preferredSource
            it[EDCB_RECORD_PLAY_METHOD] = snapshot.edcbRecordPlayMethod
            it[CF_ACCESS_CLIENT_ID] = id; it[CF_ACCESS_CLIENT_SECRET] = secret
            it[SMB_SERVER_LIST] = com.google.gson.Gson().toJson(smb)
            it.syncAccount(KomorebiAccount(context))
        }
        cloudflareAccessConfiguration.first { it == saved.toCloudflareAccessConfiguration() }
        saved.connectionSettingsSnapshot()
    }

    suspend fun saveInt(
        key: androidx.datastore.preferences.core.Preferences.Key<Int>,
        value: Int
    ) {
        context.dataStore.edit { settings -> settings[key] = value }
    }

    /** Access credentials are an atomic pair: incomplete values are never persisted. */
    suspend fun saveCloudflareAccessCredentials(clientId: String, clientSecret: String) = networkSettingsMutex.withLock {
        val id = clientId.filterNot(Char::isWhitespace)
        val secret = clientSecret.filterNot(Char::isWhitespace)
        require((id.isBlank() && secret.isBlank()) || (id.isNotBlank() && secret.isNotBlank())) {
            "Client ID と Client Secret を両方入力するか、両方消去してください。"
        }
        require((id + secret).all { it.code in 33..126 }) { "トークンには半角英数字・記号を入力してください。" }
        val saved = context.dataStore.edit { settings ->
            settings[CF_ACCESS_CLIENT_ID] = id
            settings[CF_ACCESS_CLIENT_SECRET] = secret
            settings.syncAccount(KomorebiAccount(context))
        }.toCloudflareAccessConfiguration()
        // Await the ordered DataStore collector before acknowledging the save.
        cloudflareAccessConfiguration.first { it == saved }
    }

    suspend fun getStreamSourceUrl(source: com.beeregg2001.komorebi.data.model.StreamSource): String {
        val prefs = context.dataStore.data.first()
        var ip = ""
        var port = ""
        when (source) {
            com.beeregg2001.komorebi.data.model.StreamSource.KONOMITV -> {
                ip = prefs[KONOMI_IP] ?: ""
                port = prefs[KONOMI_PORT] ?: "7000"
            }

            com.beeregg2001.komorebi.data.model.StreamSource.MIRAKURUN -> {
                ip = prefs[MIRAKURUN_IP] ?: ""
                port = prefs[MIRAKURUN_PORT] ?: "40772"
            }

            com.beeregg2001.komorebi.data.model.StreamSource.EDCB -> {
                ip = prefs[EDCB_IP] ?: ""
                port = prefs[EDCB_PORT] ?: "4510"
            }
        }
        return com.beeregg2001.komorebi.common.UrlBuilder.formatBaseUrl(ip, port, "http")
    }

    // ★ 修正: 以前は素朴な文字列連結("$ip:$port"等)で組み立てており、
    // スキーム付きURL・末尾スラッシュ付き・ポート込みURLを入力すると不正なURL
    // (例: "https://example.com/:5510"、ポート重複、パスへのポート混入)になっていた。
    // EPGStation側は既にUrlBuilder.formatBaseUrl()でこれらを正しく処理しているため、
    // EDCBも同じ関数に統一する(SSL自動判定のヒューリスティックはそのまま維持)。
    suspend fun getEdcbFullUrl(): String {
        val prefs = context.dataStore.data.first()
        val ip = prefs[EDCB_IP] ?: ""
        val port = prefs[EDCB_HTTP_PORT] ?: "5510"
        if (ip.isEmpty()) return ""
        val protocol = if (port == "5511" || port.endsWith("s")) "https" else "http"
        return com.beeregg2001.komorebi.common.UrlBuilder.formatBaseUrl(ip, port.removeSuffix("s"), protocol)
    }

    suspend fun getStartupTabOnce(): String {
        val preferences = context.dataStore.data.first()
        val storedTab = preferences[STARTUP_TAB]
        if (storedTab == LEGACY_BASEBALL_STARTUP_TAB || preferences.contains(LEGACY_FAVORITE_BASEBALL_TEAMS)) {
            context.dataStore.edit { currentPreferences ->
                if (currentPreferences[STARTUP_TAB] == LEGACY_BASEBALL_STARTUP_TAB) {
                    currentPreferences[STARTUP_TAB] = DEFAULT_STARTUP_TAB
                }
                if (currentPreferences.contains(LEGACY_FAVORITE_BASEBALL_TEAMS)) {
                    currentPreferences.remove(LEGACY_FAVORITE_BASEBALL_TEAMS)
                }
            }
        }
        return normalizeStartupTab(storedTab)
    }

    suspend fun getBackendConfig(source: com.beeregg2001.komorebi.data.model.StreamSource): com.beeregg2001.komorebi.data.model.BackendConfig {
        val prefs = context.dataStore.data.first()
        return when (source) {
            com.beeregg2001.komorebi.data.model.StreamSource.KONOMITV -> com.beeregg2001.komorebi.data.model.BackendConfig.KonomiTv(
                ip = prefs[KONOMI_IP] ?: "", port = prefs[KONOMI_PORT] ?: "7000"
            )

            com.beeregg2001.komorebi.data.model.StreamSource.MIRAKURUN -> com.beeregg2001.komorebi.data.model.BackendConfig.Mirakurun(
                ip = prefs[MIRAKURUN_IP] ?: "", port = prefs[MIRAKURUN_PORT] ?: "40772"
            )

            com.beeregg2001.komorebi.data.model.StreamSource.EDCB -> com.beeregg2001.komorebi.data.model.BackendConfig.Edcb(
                ip = prefs[EDCB_IP] ?: "", port = prefs[EDCB_PORT] ?: "4510"
            )
        }
    }
}

/**
 * Pure mapping from persisted preferences to the network snapshot.  KonomiTV is always reached
 * over HTTPS, so a bare host resolves to https; otherwise Access headers would never attach.
 */
internal fun androidx.datastore.preferences.core.Preferences.toCloudflareAccessConfiguration(): CloudflareAccessConfiguration {
    val prefs = this
    val origins = listOf(
        CloudflareAccessConfiguration.origin(prefs[SettingsRepository.KONOMI_IP].orEmpty(), prefs[SettingsRepository.KONOMI_PORT] ?: "7000", "https"),
        CloudflareAccessConfiguration.origin(prefs[SettingsRepository.MIRAKURUN_IP].orEmpty(), prefs[SettingsRepository.MIRAKURUN_PORT] ?: "40772", "http"),
        CloudflareAccessConfiguration.origin(prefs[SettingsRepository.EDCB_IP].orEmpty(), prefs[SettingsRepository.EDCB_HTTP_PORT] ?: "5510", "http"),
        CloudflareAccessConfiguration.origin(prefs[SettingsRepository.EPGSTATION_IP].orEmpty(), prefs[SettingsRepository.EPGSTATION_PORT] ?: "8888", "http"),
    ).filterNotNull().toSet()
    return CloudflareAccessConfiguration(
        clientId = prefs[SettingsRepository.CF_ACCESS_CLIENT_ID].orEmpty().filterNot(Char::isWhitespace),
        clientSecret = prefs[SettingsRepository.CF_ACCESS_CLIENT_SECRET].orEmpty().filterNot(Char::isWhitespace),
        allowedOrigins = origins,
        backendBaseUrl = com.beeregg2001.komorebi.common.UrlBuilder.formatBaseUrl(
            prefs[SettingsRepository.KONOMI_IP] ?: "https://192-168-xxx-xxx.local.konomi.tv",
            prefs[SettingsRepository.KONOMI_PORT] ?: "7000", "https",
        ),
    )
}
