package me.phie.tawc.compositor

import android.content.Context
import android.util.Log
import kotlin.concurrent.thread
import me.phie.tawc.compositor.session.SessionService

/**
 * Process-wide compositor initialization. A host app should call this from
 * `Application.onCreate` (or any time before starting the compositor).
 *
 * Idempotent. The services also call it defensively so a cold external
 * client start does not depend on host init ordering.
 */
object CompositorRuntime {
    private const val TAG = "tawc"

    @Volatile
    private var started = false

    fun initialize(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (started) return
            started = true
        }
        Settings.init(app)
        SessionService.install(app)
        thread(name = "tawc-startup", isDaemon = true) {
            try {
                CompositorService.ensureActivation(app)
            } catch (t: Throwable) {
                Log.w(TAG, "compositor activation start failed", t)
            }
        }
    }
}
