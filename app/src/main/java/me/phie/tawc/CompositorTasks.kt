package me.phie.tawc

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import me.phie.tawc.compositor.CompositorActivity

/** Removes compositor window tasks left behind by a killed app process. */
object CompositorTasks {
    /**
     * Remove every app task whose base intent is [CompositorActivity].
     *
     * [current] is the caller's task; a compositor task cannot be the caller
     * here, but excluding it keeps the helper safe if that changes.
     */
    fun removeStale(context: Context, current: Activity? = null) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val currentTaskId = current?.taskId ?: -1
        for (task in manager.appTasks) {
            val info = task.taskInfo ?: continue
            if (info.taskId == currentTaskId) continue
            if (info.baseIntent.component?.className == CompositorActivity::class.java.name) {
                task.finishAndRemoveTask()
            }
        }
    }
}
