package me.phie.tawc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Transparent trampoline in the `:restart` process. Waits for the old app
 * process to die, then starts MainActivity from a visible window so Android's
 * background-activity-launch rules do not block it.
 */
class RestartActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val oldPid = intent.getIntExtra(AppRestart.EXTRA_OLD_PID, -1)
        waitForOldProcess(oldPid, deadline = System.currentTimeMillis() + 5000)
    }

    private fun waitForOldProcess(oldPid: Int, deadline: Long) {
        val alive = oldPid > 0 && oldPid != android.os.Process.myPid() &&
            File("/proc/$oldPid").exists()
        if (!alive || System.currentTimeMillis() >= deadline) {
            CompositorTasks.removeStale(this, this)
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                },
            )
            finish()
            return
        }
        handler.postDelayed({ waitForOldProcess(oldPid, deadline) }, 100)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
