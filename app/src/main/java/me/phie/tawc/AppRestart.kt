package me.phie.tawc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * Relaunch the app process. The compositor's EGL library is bound once per
 * process, so applying a new server renderer requires a fresh process.
 *
 * Schedule a broadcast first: killing the process from inside its own
 * activity would otherwise cancel the launch. The broadcast receiver sends
 * the activity PendingIntent with the background-activity-start opt-in that
 * AlarmManager's own activity PendingIntent lacks on Android 14+.
 */
object AppRestart {
    internal const val REQUEST_CODE = 0x54415743 // "TAWC"

    fun restart(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, AppRestartReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            app,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        app.getSystemService(AlarmManager::class.java)
            .set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 300, pending)
        Process.killProcess(Process.myPid())
    }
}
