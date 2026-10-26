package me.phie.tawc

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Alarm target for [AppRestart]. Runs in a fresh process after the old one
 * has been killed, then starts MainActivity with the BAL opt-in Android 14+
 * requires for a PendingIntent activity launch.
 */
class AppRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val restart = Intent.makeRestartActivityTask(
            ComponentName(app, MainActivity::class.java),
        )
        val pending = PendingIntent.getActivity(
            app,
            AppRestart.REQUEST_CODE,
            restart,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
            pending.send(app, 0, null, null, null, null, options.toBundle())
        } else {
            pending.send(app, 0, null)
        }
    }
}
