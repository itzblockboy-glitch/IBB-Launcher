package dev.ibb.launcher.game

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.ibb.launcher.IbbApplication
import dev.ibb.launcher.R
import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.ui.MainActivity

/**
 * Foreground service that keeps a running session alive.
 *
 * Android will happily kill a background process holding 1–2 GB of JVM heap; a `specialUse`
 * foreground service with `IMPORTANCE_LOW` is the documented way to signal "this is an active
 * user-visible session". No work happens here — the JVM owns its own threads; the service only
 * holds the process priority and shows the (silent) notification.
 */
class GameService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val versionId = intent?.getStringExtra(EXTRA_VERSION).orEmpty()
        startForeground(NOTIFICATION_ID, buildNotification(versionId))
        IbbLog.i(TAG, "Foreground session started for $versionId")
        return START_NOT_STICKY
    }

    private fun buildNotification(versionId: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, IbbApplication.CHANNEL_GAME)
            .setSmallIcon(R.drawable.ic_stat_game)
            .setContentTitle(getString(R.string.notification_game_running))
            .setContentText(versionId.ifBlank { "Minecraft" })
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    companion object {
        private const val TAG = "GameService"
        private const val NOTIFICATION_ID = 0x1BB
        private const val EXTRA_VERSION = "version_id"

        fun start(context: Context, versionId: String) {
            val intent = Intent(context, GameService::class.java).putExtra(EXTRA_VERSION, versionId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GameService::class.java))
        }
    }
}
