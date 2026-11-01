package me.phie.tawc

import android.content.Context
import java.io.File

/**
 * App-owned filesystem layout resolved from Android runtime paths.
 *
 * `dataDir` can differ from `/data/data/<pkg>` under Android multi-user or
 * profile installs, so app-owned paths should be derived from Android's
 * runtime dirs rather than package-name literals.
 */
data class AppPaths(
    val dataDir: File,
    val filesDir: File,
    val shareDir: File,
    val xkbDir: File,
) {
    val waylandSocket: File get() = File(shareDir, "wayland-0")

    companion object {
        fun from(context: Context): AppPaths {
            val appContext = context.applicationContext
            val dataDir = appContext.dataDir
            return AppPaths(
                dataDir = dataDir,
                filesDir = appContext.filesDir,
                shareDir = File(dataDir, "share"),
                xkbDir = File(appContext.filesDir, "xkb"),
            )
        }
    }
}
