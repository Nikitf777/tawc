package me.phie.tawc

import android.app.Application
import me.phie.tawc.compositor.CompositorRuntime

/** Thin host application: the compositor owns its own runtime setup. */
class TawcApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The restart trampoline needs no process-wide services; keep its
        // process light so it can relaunch the main process quickly.
        if (Application.getProcessName().endsWith(":restart")) return
        CompositorRuntime.initialize(this)
    }
}
