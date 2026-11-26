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
                onStopRequested?.invoke()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PLAY_PAUSE -> {
                onTogglePlayPauseRequested?.invoke()
                return START_STICKY
            }
            ACTION_SYNC -> {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
                val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)
                val notification = buildNotification(title, subtitle, isPlaying)
                try {
                    val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else {
                        0
                    }
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enter foreground for background playback", e)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
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
            .setDeleteIntent(stopPendingIntent)
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

        @Volatile
        private var activeMediaSession: MediaSession? = null

        @Volatile
        private var isRunning: Boolean = false

        @Volatile
        private var lastTitle: String? = null

        @Volatile
        private var lastSubtitle: String? = null

        @Volatile
        private var lastIsPlaying: Boolean? = null

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
            if (!active) {
                lastTitle = null
                lastSubtitle = null
                lastIsPlaying = null
                if (isRunning) {
                    isRunning = false
                    runCatching {
                        appContext.stopService(Intent(appContext, BackgroundPlaybackService::class.java))
                    }
                }
                return
            }
            if (isRunning &&
                lastTitle == title &&
                lastSubtitle == subtitle &&
                lastIsPlaying == isPlaying
            ) {
                return
            }
            isRunning = true
            lastTitle = title
            lastSubtitle = subtitle
            lastIsPlaying = isPlaying
            val intent = Intent(appContext, BackgroundPlaybackService::class.java).apply {
                action = ACTION_SYNC
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            runCatching {
                ContextCompat.startForegroundService(appContext, intent)
            }.onFailure { e ->
                isRunning = false
                lastTitle = null
                lastSubtitle = null
                lastIsPlaying = null
                Log.w(TAG, "Unable to start BackgroundPlaybackService", e)
            }
        }
    }
}
