package com.stratum.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.stratum.feature.forge.PoseRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a generation run alive while the app is not being looked at.
 *
 * Moving the run off the screen's scope stops it being cancelled when the
 * forge closes, and that alone is not enough: an app in the background with no
 * foreground service is a process the system may reclaim at any time, and it
 * reclaims the ones using memory first -- which, during a run, is this one. A
 * quarter of an hour of paid-for generation would then vanish because somebody
 * answered a message.
 *
 * The service does no work. It holds the process open and says what is
 * happening, and the run continues to live in [PoseRun]. Keeping the work out
 * of the service is what lets the screen and the notification watch the same
 * progress without either owning the other.
 */
class PoseGenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        scope.launch {
            PoseRun.progress.collectLatest { progress ->
                // Re-posted rather than replaced: updating the same
                // notification id keeps one entry that counts up, instead of
                // forty-two notifications arriving one per frame.
                notificationManager().notify(NOTIFICATION_ID, notificationFor(progress.label, progress.done, progress.total))
            }
        }

        scope.launch {
            PoseRun.running.collectLatest { running ->
                // Stops itself when the run ends, whether it finished, failed
                // or was abandoned. A foreground service that outlives its
                // reason is a permanent notification the person cannot
                // dismiss and did not ask for.
                if (!running) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val progress = PoseRun.progress.value
        startForegroundCompat(notificationFor(progress.label, progress.done, progress.total))
        // Not restarted if the system kills us: the run died with the process,
        // and coming back to life with no work to do would be a notification
        // about nothing. The poses already drawn are on disk and the forge
        // picks up from them.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notificationFor(label: String, done: Int, total: Int): Notification {
        val name = label.ifBlank { "Character" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Drawing $name")
            .setContentText(if (total > 0) "$done of $total poses" else "Starting")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .apply { if (total > 0) setProgress(total, done, false) }
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sprite generation",
            // Low: it is a progress bar for something the person started and
            // is waiting on, not news. Anything higher interrupts them to say
            // that the thing they asked for is still happening.
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "sprite_generation"
        private const val NOTIFICATION_ID = 4201

        /**
         * Starts the service for a run that is already under way.
         *
         * Failure is swallowed on purpose. Background start restrictions
         * differ by version and vendor, and a refusal here costs the run its
         * protection from being reclaimed -- it does not stop it. Crashing the
         * app over a notification would be a far worse trade than generating
         * without one.
         */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, PoseGenerationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
