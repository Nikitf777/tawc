package me.phie.tawc

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * Relaunch the app process so the compositor loads the selected EGL library.
 *
 * A background alarm/broadcast cannot bring the singleTask MainActivity back
 * on Android 14 (BAL_BLOCK). Start a transparent trampoline in a separate
 * process while the current Activity is still visible, kill this process,
 * and let the trampoline start MainActivity once the old process is gone.
 */
object AppRestart {
    internal const val EXTRA_OLD_PID = "me.phie.tawc.extra.OLD_PID"

    fun restart(context: Context) {
        val app = context.applicationContext
        val oldPid = Process.myPid()
        val intent = Intent(app, RestartActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_OLD_PID, oldPid)
        }
        app.startActivity(intent)
        // Give the trampoline process a moment to start before this process
        // disappears. It waits for this pid to vanish before starting the UI.
        Handler(Looper.getMainLooper()).postDelayed({
            Process.killProcess(oldPid)
        }, 800)
    }
}
