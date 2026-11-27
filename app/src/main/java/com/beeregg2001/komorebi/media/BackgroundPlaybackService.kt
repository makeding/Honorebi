package com.beeregg2001.komorebi.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.beeregg2001.komorebi.MainActivity
import com.beeregg2001.komorebi.R

/**
 * Foreground service that keeps audio playback alive when the app moves to the
 * background or the screen turns off, and displays a MediaStyle notification
 * linked to the active Media3 [MediaSession].
 */
@OptIn(UnstableApi::class)
class BackgroundPlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "onStartCommand action=${intent.action}")
                val needsForegroundContract = synchronized(stateLock) {
                    stateMachine.isAwaitingForegroundStart()
                }
                if (needsForegroundContract) {
                    val state = synchronized(stateLock) { stateMachine.currentDesiredState() }
                    runCatching { enterForeground(state) }
                }
                synchronized(stateLock) {
                    stateMachine.onStopActionHandled()
                    if (activeService === this) {
                        activeService = null
                    }
                }
                onStopRequested?.invoke()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PLAY_PAUSE -> {
                Log.i(TAG, "onStartCommand action=${intent.action}")
                onTogglePlayPauseRequested?.invoke()
                return START_NOT_STICKY
            }
            ACTION_SYNC -> {
                val state = synchronized(stateLock) {
                    val current = stateMachine.currentDesiredState()
                    if (current.title.isEmpty() && current.subtitle.isEmpty()) {
                        BackgroundPlaybackNotificationState(
                            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                            subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty(),
                            isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true),
                        )
                    } else {
                        current
                    }
                }
                try {
                    enterForeground(state)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enter foreground for background playback", e)
                    synchronized(stateLock) {
                        stateMachine.onForegroundStartFailed()
                        if (activeService === this) {
                            activeService = null
                        }
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }

                val shouldStopImmediately = synchronized(stateLock) {
                    val stopNow = stateMachine.onForegroundStarted(state)
                    activeService = if (stopNow) null else this
                    stopNow
                }
                if (shouldStopImmediately) {
                    stopForegroundCompat()
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_NOT_STICKY
            }
            else -> {
                val needsForegroundContract = synchronized(stateLock) {
                    stateMachine.isAwaitingForegroundStart()
                }
                if (needsForegroundContract) {
                    val state = synchronized(stateLock) { stateMachine.currentDesiredState() }
                    runCatching { enterForeground(state) }
                    synchronized(stateLock) {
                        stateMachine.onStopActionHandled()
                        if (activeService === this) {
                            activeService = null
                        }
                    }
                    stopForegroundCompat()
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        synchronized(stateLock) {
            if (activeService === this) {
                activeService = null
                stateMachine.onForegroundServiceDestroyed()
            }
        }
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun enterForeground(state: BackgroundPlaybackNotificationState) {
        val notification = buildNotification(state.title, state.subtitle, state.isPlaying)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    private fun updateNotification(state: BackgroundPlaybackNotificationState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = buildNotification(state.title, state.subtitle, state.isPlaying)
        runCatching {
            manager.notify(NOTIFICATION_ID, notification)
        }.onFailure { e ->
            Log.w(TAG, "Failed to update background playback notification", e)
        }
    }

    private fun stopNow() {
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "バックグラウンド再生",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "バックグラウンド再生コントロール"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        title: String,
        subtitle: String,
        isPlaying: Boolean,
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            REQ_CONTENT,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleIntent = Intent(this, BackgroundPlaybackService::class.java).apply {
            action = ACTION_TOGGLE_PLAY_PAUSE
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            REQ_TOGGLE,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, BackgroundPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQ_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title.ifBlank { getString(R.string.app_name) })
            .setContentText(subtitle.ifBlank { "バックグラウンド再生中" })
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "一時停止" else "再生",
                togglePendingIntent,
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                stopPendingIntent,
            )

        val session = activeMediaSession
        if (session != null) {
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1)
            )
        }

        return builder.build()
    }

    companion object {
        private const val TAG = "BgPlaybackService"
        private const val CHANNEL_ID = "komorebi_background_playback"
        private const val NOTIFICATION_ID = 4102

        private const val ACTION_SYNC = "com.beeregg2001.komorebi.action.BG_PLAYBACK_SYNC"
        private const val ACTION_STOP = "com.beeregg2001.komorebi.action.BG_PLAYBACK_STOP"
        private const val ACTION_TOGGLE_PLAY_PAUSE = "com.beeregg2001.komorebi.action.BG_PLAYBACK_TOGGLE"

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_IS_PLAYING = "is_playing"

        private const val REQ_CONTENT = 4101
        private const val REQ_TOGGLE = 4102
        private const val REQ_STOP = 4103

        private val stateLock = Any()
        private val stateMachine = BackgroundPlaybackServiceStateMachine()
        private var activeService: BackgroundPlaybackService? = null

        @Volatile
        private var activeMediaSession: MediaSession? = null

        @Volatile
        var onStopRequested: (() -> Unit)? = null

        @Volatile
        var onTogglePlayPauseRequested: (() -> Unit)? = null

        fun setActiveSession(session: MediaSession?) {
            activeMediaSession = session
        }

        fun sync(
            context: Context,
            active: Boolean,
            title: String = "",
            subtitle: String = "",
            isPlaying: Boolean = true,
        ) {
            val appContext = context.applicationContext
            val nextState = BackgroundPlaybackNotificationState(
                title = title,
                subtitle = subtitle,
                isPlaying = isPlaying,
            )
            val serviceSnapshot: BackgroundPlaybackService?
            val action = synchronized(stateLock) {
                val computed = stateMachine.onSyncRequested(active = active, state = nextState)
                serviceSnapshot = activeService
                if (computed == BackgroundPlaybackSyncAction.STOP_SERVICE) {
                    activeService = null
                }
                computed
            }

            when (action) {
                BackgroundPlaybackSyncAction.NONE -> Unit
                BackgroundPlaybackSyncAction.STOP_SERVICE -> {
                    if (serviceSnapshot != null) {
                        runCatching { serviceSnapshot.stopNow() }
                    } else {
                        runCatching {
                            appContext.stopService(Intent(appContext, BackgroundPlaybackService::class.java))
                        }
                    }
                }
                BackgroundPlaybackSyncAction.UPDATE_NOTIFICATION -> {
                    if (serviceSnapshot != null) {
                        serviceSnapshot.updateNotification(nextState)
                    } else {
                        // Defensive fallback if service reference was unexpectedly cleared.
                        val intent = buildSyncIntent(appContext, nextState)
                        runCatching {
                            ContextCompat.startForegroundService(appContext, intent)
                        }.onFailure { e ->
                            synchronized(stateLock) { stateMachine.onStartForegroundServiceFailed() }
                            Log.w(TAG, "Unable to refresh BackgroundPlaybackService", e)
                        }
                    }
                }
                BackgroundPlaybackSyncAction.START_FOREGROUND_SERVICE -> {
                    val intent = buildSyncIntent(appContext, nextState)
                    runCatching {
                        ContextCompat.startForegroundService(appContext, intent)
                    }.onFailure { e ->
                        synchronized(stateLock) { stateMachine.onStartForegroundServiceFailed() }
                        Log.w(TAG, "Unable to start BackgroundPlaybackService", e)
                    }
                }
            }
        }

        private fun buildSyncIntent(
            appContext: Context,
            state: BackgroundPlaybackNotificationState,
        ): Intent = Intent(appContext, BackgroundPlaybackService::class.java).apply {
            action = ACTION_SYNC
            putExtra(EXTRA_TITLE, state.title)
            putExtra(EXTRA_SUBTITLE, state.subtitle)
            putExtra(EXTRA_IS_PLAYING, state.isPlaying)
        }
    }
}

internal data class BackgroundPlaybackNotificationState(
    val title: String = "",
    val subtitle: String = "",
    val isPlaying: Boolean = true,
)

internal enum class BackgroundPlaybackSyncAction {
    NONE,
    START_FOREGROUND_SERVICE,
    UPDATE_NOTIFICATION,
    STOP_SERVICE,
}

internal class BackgroundPlaybackServiceStateMachine {
    private enum class Phase {
        IDLE,
        STARTING,
        STARTING_PENDING_STOP,
        FOREGROUND,
    }

    private var phase: Phase = Phase.IDLE
    private var desiredState: BackgroundPlaybackNotificationState = BackgroundPlaybackNotificationState()
    private var postedState: BackgroundPlaybackNotificationState? = null

    fun currentDesiredState(): BackgroundPlaybackNotificationState = desiredState

    fun onSyncRequested(
        active: Boolean,
        state: BackgroundPlaybackNotificationState,
    ): BackgroundPlaybackSyncAction {
        if (!active) {
            return when (phase) {
                Phase.IDLE -> BackgroundPlaybackSyncAction.NONE
                Phase.STARTING -> {
                    // startForegroundService() is already in flight; do NOT call stopService()
                    // before onStartCommand() satisfies startForeground(), or Android will throw
                    // ForegroundServiceDidNotStartInTimeException.
                    phase = Phase.STARTING_PENDING_STOP
                    BackgroundPlaybackSyncAction.NONE
                }
                Phase.STARTING_PENDING_STOP -> BackgroundPlaybackSyncAction.NONE
                Phase.FOREGROUND -> {
                    phase = Phase.IDLE
                    postedState = null
                    BackgroundPlaybackSyncAction.STOP_SERVICE
                }
            }
        }

        desiredState = state
        return when (phase) {
            Phase.IDLE -> {
                phase = Phase.STARTING
                BackgroundPlaybackSyncAction.START_FOREGROUND_SERVICE
            }
            Phase.STARTING -> BackgroundPlaybackSyncAction.NONE
            Phase.STARTING_PENDING_STOP -> {
                phase = Phase.STARTING
                BackgroundPlaybackSyncAction.NONE
            }
            Phase.FOREGROUND -> {
                if (postedState == state) {
                    BackgroundPlaybackSyncAction.NONE
                } else {
                    postedState = state
                    BackgroundPlaybackSyncAction.UPDATE_NOTIFICATION
                }
            }
        }
    }

    fun onStartForegroundServiceFailed() {
        if (phase == Phase.STARTING || phase == Phase.STARTING_PENDING_STOP) {
            phase = Phase.IDLE
            postedState = null
        }
    }

    fun onForegroundStarted(posted: BackgroundPlaybackNotificationState): Boolean {
        return when (phase) {
            Phase.STARTING_PENDING_STOP, Phase.IDLE -> {
                phase = Phase.IDLE
                postedState = null
                true
            }
            Phase.STARTING, Phase.FOREGROUND -> {
                phase = Phase.FOREGROUND
                postedState = posted
                false
            }
        }
    }

    fun onForegroundStartFailed() {
        phase = Phase.IDLE
        postedState = null
    }

    fun onStopActionHandled() {
        phase = Phase.IDLE
        postedState = null
    }

    fun onForegroundServiceDestroyed() {
        if (phase == Phase.FOREGROUND) {
            phase = Phase.IDLE
            postedState = null
        }
    }

    fun isAwaitingForegroundStart(): Boolean =
        phase == Phase.STARTING || phase == Phase.STARTING_PENDING_STOP
}
