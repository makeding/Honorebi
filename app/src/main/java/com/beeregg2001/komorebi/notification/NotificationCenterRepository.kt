package com.beeregg2001.komorebi.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "NotificationCenterRepo"
internal const val MAX_ARTWORK_DIMENSION_PX = 256
internal const val MAX_APP_ICON_DIMENSION_PX = 96

internal data class RawNotificationRecord(
    val key: String,
    val packageName: String,
    val groupKey: String?,
    val isGroupSummary: Boolean,
    val hasMediaSessionExtra: Boolean,
    val isTransportCategory: Boolean,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTimeMs: Long,
    val isClearable: Boolean,
    val contentIntent: PendingIntent? = null,
    val appIcon: Bitmap? = null,
)

internal data class MediaActionCapabilities(
    val canPlay: Boolean,
    val canPause: Boolean,
    val canSkipToNext: Boolean,
    val canSkipToPrevious: Boolean,
    val canStop: Boolean,
)

internal fun mapPlaybackActions(actions: Long): MediaActionCapabilities {
    val hasPlayPause = (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L
    return MediaActionCapabilities(
        canPlay = hasPlayPause || (actions and PlaybackState.ACTION_PLAY) != 0L,
        canPause = hasPlayPause || (actions and PlaybackState.ACTION_PAUSE) != 0L,
        canSkipToNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
        canSkipToPrevious = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
        canStop = (actions and PlaybackState.ACTION_STOP) != 0L,
    )
}

internal fun filterAndSortNotifications(
    records: List<RawNotificationRecord>,
    ownPackageName: String,
    activeMediaPackages: Set<String>,
): List<RawNotificationRecord> {
    val nonOwn = records.filter { it.packageName != ownPackageName }
    val groupsWithChildren = nonOwn
        .asSequence()
        .filter { !it.isGroupSummary && !it.groupKey.isNullOrBlank() }
        .mapNotNull { it.groupKey }
        .toSet()

    return nonOwn
        .filter { record ->
            if (record.isGroupSummary && record.groupKey != null && record.groupKey in groupsWithChildren) {
                return@filter false
            }
            if (record.hasMediaSessionExtra) {
                return@filter false
            }
            if (record.isTransportCategory && record.packageName in activeMediaPackages) {
                return@filter false
            }
            true
        }
        .sortedByDescending { it.postTimeMs }
}

internal fun downscaleBitmap(
    bitmap: Bitmap,
    maxDimensionPx: Int = MAX_ARTWORK_DIMENSION_PX,
): Bitmap {
    if (maxDimensionPx <= 0) return bitmap
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return bitmap
    val largest = max(width, height)
    if (largest <= maxDimensionPx) return bitmap

    val scale = maxDimensionPx.toFloat() / largest.toFloat()
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
}

@Singleton
class NotificationCenterRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var listenerServiceRef: WeakReference<KomorebiNotificationListenerService>? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var sessionsChangedListenerRegistered = false
    private val activeControllers = mutableMapOf<String, MediaController>()
    private val controllerCallbacks = mutableMapOf<String, MediaController.Callback>()
    private var lastRawNotifications: List<RawNotificationRecord> = emptyList()

    private val componentName: ComponentName by lazy {
        ComponentName(context, KomorebiNotificationListenerService::class.java)
    }

    val adbGrantCommand: String =
        "adb shell cmd notification allow_listener ${context.packageName}/${KomorebiNotificationListenerService::class.java.name}"

    private val _isAccessGranted = MutableStateFlow(checkListenerAccess())
    val isAccessGranted: StateFlow<Boolean> = _isAccessGranted.asStateFlow()

    private val _accessStatus = MutableStateFlow(
        if (_isAccessGranted.value) NotificationAccessStatus.GRANTED else NotificationAccessStatus.REQUIRED,
    )
    val accessStatus: StateFlow<NotificationAccessStatus> = _accessStatus.asStateFlow()

    private val _notifications = MutableStateFlow<List<SystemNotificationItem>>(emptyList())
    val notifications: StateFlow<List<SystemNotificationItem>> = _notifications.asStateFlow()

    private val _externalMediaSessions = MutableStateFlow<List<ExternalMediaSessionState>>(emptyList())
    val externalMediaSessions: StateFlow<List<ExternalMediaSessionState>> = _externalMediaSessions.asStateFlow()

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        synchronized(lock) {
            updateActiveControllersLocked(controllers.orEmpty())
        }
        republishNotifications()
    }

    fun refreshAccessStatus(): Boolean {
        val granted = checkListenerAccess()
        _isAccessGranted.value = granted
        if (granted) {
            _accessStatus.value = NotificationAccessStatus.GRANTED
            val service = synchronized(lock) { listenerServiceRef?.get() }
            if (service != null) {
                synchronized(lock) {
                    if (!sessionsChangedListenerRegistered) {
                        registerMediaSessionListenerLocked()
                    } else {
                        refreshActiveSessionsLocked()
                    }
                }
                onNotificationsChanged(service.safeActiveNotifications())
            }
        } else {
            if (_accessStatus.value != NotificationAccessStatus.SETTINGS_UNAVAILABLE) {
                _accessStatus.value = NotificationAccessStatus.REQUIRED
            }
            synchronized(lock) {
                unregisterMediaSessionListenerLocked()
                lastRawNotifications = emptyList()
            }
            _notifications.value = emptyList()
            _externalMediaSessions.value = emptyList()
        }
        return granted
    }

    internal fun onListenerConnected(service: KomorebiNotificationListenerService) {
        synchronized(lock) {
            listenerServiceRef = WeakReference(service)
            registerMediaSessionListenerLocked()
        }
        _isAccessGranted.value = true
        _accessStatus.value = NotificationAccessStatus.GRANTED
        onNotificationsChanged(service.safeActiveNotifications())
    }

    internal fun onListenerDisconnected(service: KomorebiNotificationListenerService) {
        synchronized(lock) {
            val current = listenerServiceRef?.get()
            if (current == null || current === service) {
                listenerServiceRef = null
                unregisterMediaSessionListenerLocked()
                lastRawNotifications = emptyList()
            }
        }
        val granted = checkListenerAccess()
        _isAccessGranted.value = granted
        if (!granted && _accessStatus.value != NotificationAccessStatus.SETTINGS_UNAVAILABLE) {
            _accessStatus.value = NotificationAccessStatus.REQUIRED
        }
        _notifications.value = emptyList()
        _externalMediaSessions.value = emptyList()
    }

    internal fun onNotificationsChanged(activeNotifications: Array<StatusBarNotification>?) {
        val rawRecords = activeNotifications
            .orEmpty()
            .mapNotNull { sbn -> mapStatusBarNotification(sbn) }
        synchronized(lock) {
            lastRawNotifications = rawRecords
        }
        republishNotifications()
    }

    fun dismissNotification(key: String): Boolean {
        val item = _notifications.value.firstOrNull { it.key == key } ?: return false
        if (!item.isClearable) return false
        val service = synchronized(lock) { listenerServiceRef?.get() } ?: return false
        return runCatching {
            service.cancelNotification(key)
            true
        }.getOrElse { error ->
            Log.w(TAG, "Failed to cancel notification key=$key", error)
            false
        }
    }

    fun clearAllClearable(): Boolean {
        val service = synchronized(lock) { listenerServiceRef?.get() } ?: return false
        return runCatching {
            service.cancelAllNotifications()
            true
        }.getOrElse { error ->
            Log.w(TAG, "Failed to cancel all notifications", error)
            false
        }
    }

    fun openNotification(
        item: SystemNotificationItem,
        onBeforeLaunch: () -> Unit = {},
    ): Boolean {
        val pendingIntent = item.contentIntent ?: return false
        onBeforeLaunch()
        return runCatching {
            pendingIntent.send()
            true
        }.getOrElse { error ->
            Log.w(TAG, "Failed to send notification contentIntent for ${item.packageName}", error)
            false
        }
    }

    fun sendMediaAction(
        sessionKey: String,
        action: MediaTransportAction,
    ): Boolean {
        val controller = synchronized(lock) { activeControllers[sessionKey] } ?: return false
        return dispatchMediaAction(controller, action)
    }

    fun sendMediaAction(
        sessionToken: MediaSession.Token,
        action: MediaTransportAction,
    ): Boolean {
        val controller = synchronized(lock) {
            activeControllers.values.firstOrNull { it.sessionToken == sessionToken }
        } ?: return false
        return dispatchMediaAction(controller, action)
    }

    fun openMediaApp(
        session: ExternalMediaSessionState,
        onBeforeLaunch: () -> Unit = {},
    ): Boolean {
        val pendingIntent = session.sessionActivity
        if (pendingIntent != null) {
            onBeforeLaunch()
            val sent = runCatching {
                pendingIntent.send()
                true
            }.getOrElse { error ->
                Log.w(TAG, "sessionActivity.send() failed for ${session.packageName}", error)
                false
            }
            if (sent) return true
        }

        val pm = context.packageManager
        val launchIntent = runCatching {
            pm.getLeanbackLaunchIntentForPackage(session.packageName)
                ?: pm.getLaunchIntentForPackage(session.packageName)
        }.getOrNull() ?: return false

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onBeforeLaunch()
        return runCatching {
            context.startActivity(launchIntent)
            true
        }.getOrElse { error ->
            Log.w(TAG, "Failed to launch media app ${session.packageName}", error)
            false
        }
    }

    fun openNotificationListenerSettings(
        activityContext: Context = context,
        onBeforeLaunch: () -> Unit = {},
    ): NotificationSettingsOpenResult {
        val pm = activityContext.packageManager
        val listenerIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            if (activityContext === context) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        if (canResolveIntent(pm, listenerIntent)) {
            onBeforeLaunch()
            val launched = runCatching {
                activityContext.startActivity(listenerIntent)
                true
            }.getOrDefault(false)
            if (launched) {
                return NotificationSettingsOpenResult.OPENED_LISTENER_SETTINGS
            }
        }

        val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
            if (activityContext === context) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        if (canResolveIntent(pm, fallbackIntent)) {
            onBeforeLaunch()
            val launched = runCatching {
                activityContext.startActivity(fallbackIntent)
                true
            }.getOrDefault(false)
            if (launched) {
                return NotificationSettingsOpenResult.OPENED_SYSTEM_SETTINGS
            }
        }

        if (!checkListenerAccess()) {
            _accessStatus.value = NotificationAccessStatus.SETTINGS_UNAVAILABLE
        }
        return NotificationSettingsOpenResult.UNAVAILABLE
    }

    private fun canResolveIntent(
        pm: android.content.pm.PackageManager,
        intent: Intent,
    ): Boolean = runCatching {
        intent.resolveActivity(pm) != null
    }.getOrDefault(false)

    private fun dispatchMediaAction(
        controller: MediaController,
        action: MediaTransportAction,
    ): Boolean = runCatching {
        val controls = controller.transportControls
        when (action) {
            MediaTransportAction.PLAY -> controls.play()
            MediaTransportAction.PAUSE -> controls.pause()
            MediaTransportAction.PLAY_PAUSE -> {
                val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                    controls.pause()
                } else {
                    controls.play()
                }
            }
            MediaTransportAction.SKIP_TO_NEXT -> controls.skipToNext()
            MediaTransportAction.SKIP_TO_PREVIOUS -> controls.skipToPrevious()
            MediaTransportAction.STOP -> controls.stop()
        }
        true
    }.getOrElse { error ->
        Log.w(TAG, "Failed to dispatch media action $action for ${controller.packageName}", error)
        false
    }

    private fun checkListenerAccess(): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    private fun registerMediaSessionListenerLocked() {
        val manager = mediaSessionManager
            ?: (context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager)
                .also { mediaSessionManager = it }
            ?: return

        if (!sessionsChangedListenerRegistered) {
            runCatching {
                manager.addOnActiveSessionsChangedListener(
                    activeSessionsListener,
                    componentName,
                    mainHandler,
                )
                sessionsChangedListenerRegistered = true
            }.onFailure { error ->
                Log.w(TAG, "Unable to register OnActiveSessionsChangedListener", error)
            }
        }
        refreshActiveSessionsLocked()
    }

    private fun refreshActiveSessionsLocked() {
        val manager = mediaSessionManager ?: return
        val controllers = runCatching {
            manager.getActiveSessions(componentName)
        }.getOrElse { error ->
            Log.w(TAG, "getActiveSessions failed", error)
            emptyList()
        }
        updateActiveControllersLocked(controllers)
    }

    private fun unregisterMediaSessionListenerLocked() {
        val manager = mediaSessionManager
        if (manager != null && sessionsChangedListenerRegistered) {
            runCatching {
                manager.removeOnActiveSessionsChangedListener(activeSessionsListener)
            }
        }
        sessionsChangedListenerRegistered = false
        clearControllerCallbacksLocked()
    }

    private fun clearControllerCallbacksLocked() {
        for ((key, controller) in activeControllers) {
            val callback = controllerCallbacks[key]
            if (callback != null) {
                runCatching { controller.unregisterCallback(callback) }
            }
        }
        activeControllers.clear()
        controllerCallbacks.clear()
    }

    private fun updateActiveControllersLocked(controllers: List<MediaController>) {
        clearControllerCallbacksLocked()

        val externalControllers = controllers.filter { it.packageName != context.packageName }
        for (controller in externalControllers) {
            val key = controllerSessionKey(controller)
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    publishExternalMediaSessions()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    publishExternalMediaSessions()
                }

                override fun onSessionDestroyed() {
                    synchronized(lock) {
                        activeControllers.remove(key)
                        controllerCallbacks.remove(key)?.let { cb ->
                            runCatching { controller.unregisterCallback(cb) }
                        }
                    }
                    publishExternalMediaSessions()
                    republishNotifications()
                }
            }
            activeControllers[key] = controller
            controllerCallbacks[key] = callback
            runCatching {
                controller.registerCallback(callback, mainHandler)
            }.onFailure { error ->
                Log.w(TAG, "registerCallback failed for ${controller.packageName}", error)
            }
        }

        _externalMediaSessions.value = buildExternalMediaSnapshotsLocked()
    }

    private fun publishExternalMediaSessions() {
        val snapshots = synchronized(lock) {
            buildExternalMediaSnapshotsLocked()
        }
        _externalMediaSessions.value = snapshots
    }

    private fun buildExternalMediaSnapshotsLocked(): List<ExternalMediaSessionState> {
        return activeControllers.entries
            .map { (key, controller) -> mapControllerToState(key, controller) }
            .sortedWith(
                compareByDescending<ExternalMediaSessionState> { it.isPlaying }
                    .thenByDescending { it.isBuffering }
                    .thenBy { it.appLabel.lowercase() },
            )
    }

    private fun mapControllerToState(
        sessionKey: String,
        controller: MediaController,
    ): ExternalMediaSessionState {
        val playbackStateObj = controller.playbackState
        val metadata = controller.metadata
        val stateCode = playbackStateObj?.state ?: PlaybackState.STATE_NONE
        val isPlaying = stateCode == PlaybackState.STATE_PLAYING
        val isBuffering = stateCode == PlaybackState.STATE_BUFFERING
        val actions = mapPlaybackActions(playbackStateObj?.actions ?: 0L)

        val appLabel = resolveAppLabel(controller.packageName, substituteName = null)
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
            ?: appLabel

        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().trim()
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty().trim()
        val displaySubtitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty().trim()
        val subtitle = when {
            displaySubtitle.isNotBlank() -> displaySubtitle
            artist.isNotBlank() && album.isNotBlank() && album != artist -> "$artist · $album"
            artist.isNotBlank() -> artist
            album.isNotBlank() -> album
            else -> appLabel
        }

        val artworkUri = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                ?.takeIf { it.isNotBlank() }

        val rawArtwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val scaledArtwork = rawArtwork?.let { downscaleBitmap(it, MAX_ARTWORK_DIMENSION_PX) }

        val durationMs = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        val positionMs = (playbackStateObj?.position ?: 0L).coerceAtLeast(0L)
        val speed = playbackStateObj?.playbackSpeed ?: if (isPlaying) 1f else 0f
        val lastUpdateMs = playbackStateObj?.lastPositionUpdateTime ?: 0L

        return ExternalMediaSessionState(
            sessionToken = controller.sessionToken,
            sessionKey = sessionKey,
            packageName = controller.packageName,
            appLabel = appLabel,
            title = title,
            subtitle = subtitle,
            playbackState = stateCode,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            isActiveForStrip = isPlaybackStateActiveForStrip(stateCode),
            positionMs = positionMs,
            durationMs = durationMs,
            playbackSpeed = speed,
            lastPositionUpdateTimeMs = lastUpdateMs,
            canPlay = actions.canPlay,
            canPause = actions.canPause,
            canSkipToNext = actions.canSkipToNext,
            canSkipToPrevious = actions.canSkipToPrevious,
            canStop = actions.canStop,
            artwork = scaledArtwork,
            artworkUri = artworkUri,
            sessionActivity = runCatching { controller.sessionActivity }.getOrNull(),
        )
    }

    private fun republishNotifications() {
        val (raw, activeMediaPackages) = synchronized(lock) {
            lastRawNotifications to activeControllers.values.map { it.packageName }.toSet()
        }
        val filtered = filterAndSortNotifications(
            records = raw,
            ownPackageName = context.packageName,
            activeMediaPackages = activeMediaPackages,
        )
        _notifications.value = filtered.map { record ->
            SystemNotificationItem(
                key = record.key,
                packageName = record.packageName,
                appLabel = record.appLabel,
                title = record.title,
                text = record.text,
                postTimeMs = record.postTimeMs,
                isClearable = record.isClearable,
                contentIntent = record.contentIntent,
                appIcon = record.appIcon,
            )
        }
    }

    private fun mapStatusBarNotification(sbn: StatusBarNotification): RawNotificationRecord? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim()
        val normalText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim()
        val text = when {
            bigText.isNotBlank() -> bigText
            normalText.isNotBlank() -> normalText
            else -> subText
        }

        val substituteAppName = extras?.getCharSequence("android.substName")?.toString()?.trim()
        val appLabel = resolveAppLabel(sbn.packageName, substituteAppName)
        val hasMediaSession = extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        val isTransportCategory = notification.category == Notification.CATEGORY_TRANSPORT
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

        return RawNotificationRecord(
            key = sbn.key,
            packageName = sbn.packageName,
            groupKey = sbn.groupKey,
            isGroupSummary = isGroupSummary,
            hasMediaSessionExtra = hasMediaSession,
            isTransportCategory = isTransportCategory,
            appLabel = appLabel,
            title = title.ifBlank { appLabel },
            text = text,
            postTimeMs = sbn.postTime,
            isClearable = sbn.isClearable,
            contentIntent = notification.contentIntent,
            appIcon = loadAppIconBitmap(sbn.packageName),
        )
    }

    private fun resolveAppLabel(packageName: String, substituteName: String?): String {
        val fromPm = runCatching {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo)?.toString()?.trim()
        }.getOrNull()
        return when {
            !fromPm.isNullOrBlank() -> fromPm
            !substituteName.isNullOrBlank() -> substituteName
            else -> packageName
        }
    }

    private fun loadAppIconBitmap(packageName: String): Bitmap? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        drawableToDownscaledBitmap(drawable, MAX_APP_ICON_DIMENSION_PX)
    }.getOrNull()

    private fun drawableToDownscaledBitmap(drawable: Drawable, maxSizePx: Int): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return downscaleBitmap(drawable.bitmap, maxSizePx)
        }
        val intrinsicW = drawable.intrinsicWidth.takeIf { it > 0 } ?: maxSizePx
        val intrinsicH = drawable.intrinsicHeight.takeIf { it > 0 } ?: maxSizePx
        val scale = if (max(intrinsicW, intrinsicH) > maxSizePx) {
            maxSizePx.toFloat() / max(intrinsicW, intrinsicH).toFloat()
        } else {
            1f
        }
        val targetW = (intrinsicW * scale).roundToInt().coerceAtLeast(1)
        val targetH = (intrinsicH * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, targetW, targetH)
        drawable.draw(canvas)
        return bitmap
    }

    private fun controllerSessionKey(controller: MediaController): String =
        "${controller.packageName}:${controller.sessionToken.hashCode()}"
}
