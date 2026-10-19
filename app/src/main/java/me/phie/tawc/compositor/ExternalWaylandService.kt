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
 * The AIDL path is gated in [callerAllowed], not by a manifest
 * permission: the Termux-side helper is an `app_process` process with no
 * package, so it cannot be granted one. The Termux launcher uses
 * [onStartCommand] instead (it cannot bind), which AMS delivers without
 * the original caller identity; see the note there.
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
     * record. It starts this service with a Binder token instead, then
     * calls the existing `IExternalWayland.openClient()` on it. That
     * synchronous Binder call is what carries the fd; an Intent cannot
     * (AMS rejects `startService` intents with file descriptors).
     *
     * By the time this runs the binder caller is AMS, so
     * `callerAllowed()` cannot gate it.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getBundleExtra(EXTRA_HOST)?.getBinder(KEY_HOST)
        if (host != null) {
            try {
                val pfd = IExternalWayland.Stub.asInterface(host).openClient()
                if (pfd != null && !NativeBridge.nativeAdoptClientFd(pfd.detachFd())) {
                    Log.w(TAG, "external Wayland: compositor refused the client fd")
                }
            } catch (e: Exception) {
                Log.w(TAG, "external Wayland: host callback failed", e)
            }
        } else {
            Log.w(TAG, "external Wayland: startService without host binder")
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

        /** Matches WaylandRun's EXTRA_HOST / KEY_HOST. */
        const val EXTRA_HOST = "me.phie.tawc.extra.HOST"
        const val KEY_HOST = "host"
    }
}
