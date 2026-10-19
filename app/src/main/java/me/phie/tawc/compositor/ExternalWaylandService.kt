package me.phie.tawc.compositor

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import me.phie.tawc.BuildConfig

/**
 * Exported fd source for Wayland clients outside TAWC's uid.
 *
 * A Termux process cannot connect to `share/wayland-0`: `/data/data/<pkg>`
 * is `0700` (DAC) and a cross-app `connect()` between two `untrusted_app`
 * domains is denied by SELinux. So the client asks here for one end of a
 * socketpair; the other end goes into the compositor's display through
 * [NativeBridge.nativeAdoptClientFd]. No `connect()` ever happens, so
 * neither check applies.
 *
 * Access is gated in [callerAllowed], not by a manifest permission: the
 * Termux-side helper is an `app_process` process with no package, so it
 * cannot be granted one.
 */
class ExternalWaylandService : Service() {

    private val binder = ExternalBinder()

    inner class ExternalBinder : IExternalWayland.Stub() {
        override fun openClient(): ParcelFileDescriptor {
            if (!callerAllowed()) {
                throw SecurityException(
                    "external Wayland: uid ${Binder.getCallingUid()} is not allowed"
                )
            }

            val pair = ParcelFileDescriptor.createSocketPair()

            // Native owns the near end from here. With no compositor
            // thread it queues the fd and asks Kotlin to start one.
            if (!NativeBridge.nativeAdoptClientFd(pair[1].detachFd())) {
                pair[0].close()
                throw IllegalStateException("compositor refused the client fd")
            }

            // Binder dups this into the caller's process.
            return pair[0]
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Sets the TAWC_* env, caches the app context for the reverse-JNI
        // onActivationRequested callback, and starts the socket holder.
        // Idempotent; must precede the first openClient().
        CompositorService.ensureActivation(this)

        // Start the compositor now rather than on the first
        // nativeAdoptClientFd queue miss.
        CompositorService.ensureRunning(this)
    }

    /**
     * Termux's `wayland-run` cannot bindService: it is an `app_process`
     * process, and AMS rejects binds from callers without a process
     * record. It therefore starts this service with one end of a
     * socketpair in [EXTRA_CLIENT_FD]. By the time this runs the binder
     * caller is AMS, so `callerAllowed()` cannot gate it.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        @Suppress("DEPRECATION")
        val pfd = intent?.getParcelableExtra<ParcelFileDescriptor>(EXTRA_CLIENT_FD)
        if (pfd != null) {
            // Native owns the fd after detachFd(), including the failure
            // paths, so do not close it here.
            if (!NativeBridge.nativeAdoptClientFd(pfd.detachFd())) {
                Log.w(TAG, "external Wayland: compositor refused the client fd")
            }
        } else {
            Log.w(TAG, "external Wayland: startService without $EXTRA_CLIENT_FD")
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun callerAllowed(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        if (packages.any { it == "com.termux" }) return true
        // Lets the PC-side prototype run from `adb shell` (uid 2000).
        return BuildConfig.DEBUG && packages.any { it == "com.android.shell" }
    }

    companion object {
        private const val TAG = "tawc"

        /** Intent extra carrying the client's ParcelFileDescriptor. */
        const val EXTRA_CLIENT_FD = "me.phie.tawc.extra.CLIENT_FD"
    }
}
