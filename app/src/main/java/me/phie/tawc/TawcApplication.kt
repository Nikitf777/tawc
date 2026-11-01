package me.phie.tawc

import android.app.Application
import android.util.Log
import me.phie.tawc.compositor.CompositorService
import me.phie.tawc.session.SessionService
import kotlin.concurrent.thread

/** Process-wide entry point: settings, the session service and native activation. */
class TawcApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The restart trampoline needs no process-wide services; keep its
        // process light so it can relaunch the main process quickly.
        if (Application.getProcessName().endsWith(":restart")) return

        Settings.init(this)
        SessionService.install(this)
        thread(name = "tawc-startup", isDaemon = true) {
            // Extract the compositor's bundled assets and export the
            // TAWC_* environment the native side reads its paths from.
            // Touching NativeBridge loads the large compositor library,
            // which shouldn't block onCreate.
            try {
                CompositorService.ensureActivation(this)
            } catch (t: Throwable) {
                Log.w(TAG, "compositor activation start failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "tawc"
    }
}
