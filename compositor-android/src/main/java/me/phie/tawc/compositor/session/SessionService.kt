package me.phie.tawc.compositor.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.phie.tawc.compositor.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the process in the foreground while [SessionHolds] has a reason
 * (i.e. while the compositor is running).
 *
 * `onCreate` must stay trivial — `startForegroundService` gives ~5 s to
 * reach `startForeground`.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stopped = false

    override fun onCreate() {
        super.onCreate()
        SessionHolds.serviceStarted(this)
        ensureChannel()
        goForeground()
        scope.launch {
            SessionHolds.reasons.collect { reasons ->
                if (stopped) return@collect
                if (reasons.isEmpty() && SessionHolds.serviceStopIfIdle(this@SessionService)) {
                    stopped = true
                    ServiceCompat.stopForeground(this@SessionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    notifyNow()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService must be answered, even when already
        // in the foreground.
        goForeground()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        SessionHolds.serviceStopped(this)
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun notifyNow() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.session_notification_title))
            .setContentText(describe(SessionSummary.of(SessionHolds.reasons.value)))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        homePendingIntent()?.let(builder::setContentIntent)
        return builder.build()
    }

    private fun describe(s: SessionSummary): String =
        if (s.windows > 0) {
            resources.getQuantityString(R.plurals.session_windows, s.windows, s.windows)
        } else {
            getString(R.string.session_display_server)
        }

    /** Open the host app, if it has a launcher activity. */
    private fun homePendingIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.session_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.session_channel_description)
                    setShowBadge(false)
                },
            )
        }
        try { nm.deleteNotificationChannel(LEGACY_CHANNEL_ID) } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "tawc"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "tawc_session"
        private const val LEGACY_CHANNEL_ID = "tawc_compositor"

        private val loggedStartFailure = AtomicBoolean(false)

        /**
         * Hook [SessionHolds] up to this service. `Application.onCreate`.
         *
         * Starts always run on the main thread, the same thread that
         * decides to stop, so a start cannot land between "nothing is
         * held" and `stopSelf`.
         */
        fun install(context: Context) {
            val app = context.applicationContext
            val main = Handler(Looper.getMainLooper())
            SessionHolds.starter = {
                if (Looper.myLooper() == Looper.getMainLooper()) start(app) else main.post { start(app) }
            }
        }

        private fun start(app: Context) {
            // Released again before we got here: nothing to protect.
            if (SessionHolds.reasons.value.isEmpty()) return
            try {
                app.startForegroundService(Intent(app, SessionService::class.java))
            } catch (e: IllegalStateException) {
                if (loggedStartFailure.compareAndSet(false, true)) {
                    Log.w(TAG, "session service start refused; running unprotected: ${e.message}")
                }
            }
        }
    }
}
