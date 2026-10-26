package me.phie.tawc

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Whether this build/device can use the server-side Mesa GLES renderer.
 *
 * This is the same gate the Rust side applies at startup: the APK must ship
 * the private Mesa/Turnip stack, the ABI must be aarch64, and the Adreno KGSL
 * device must be openable. The Settings row is hidden when this is false.
 */
object MesaServerSupport {
    fun isSupported(): Boolean {
        if (!BuildConfig.GRAPHICS_MESA_SERVER_ENABLED) return false
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return false

        val kgsl = File("/dev/kgsl-3d0")
        if (!kgsl.exists()) return false

        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.orEmpty()
        } else {
            ""
        }
        val qualcomm = soc.contains("qualcomm", ignoreCase = true) ||
            Build.HARDWARE.contains("qcom", ignoreCase = true) ||
            Build.BOARD.contains("qcom", ignoreCase = true)
        if (!qualcomm) return false

        return try {
            val fd = Os.open(kgsl.path, OsConstants.O_RDWR, 0)
            Os.close(fd)
            true
        } catch (_: Exception) {
            false
        }
    }
}
