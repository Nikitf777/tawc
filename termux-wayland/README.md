# termux-wayland

Termux-side launcher that opens a Wayland client window inside the TAWC
compositor (`wayland-run foot`). Target app: the debug build
`me.phie.tawc.dev` unless `TAWC_PACKAGE=me.phie.tawc` is set.

## Why it works this way

The app exposes `ExternalWaylandService`, but Termux **cannot
`bindService` to it**. Termux runs this launcher through `app_process`,
which is not an Android app process; AMS has no application record for
it, and `ActiveServices.bindServiceLocked` throws
`SecurityException: Unable to find app for caller` for any caller it
does not know. The host-side `Context`/`ActivityThread` tricks do not
help, because the caller identity AMS checks is the Binder thread, not
the borrowed package name.

`startService` is the exception: `ActiveServices.startServiceLocked`
treats a `null` caller as foreground, which is exactly how Termux's own
`am` command works.

An `Intent` still cannot carry the fd: `ActivityManagerService.startService`
rejects any intent for which `Intent.hasFileDescriptors()` is true
(`IllegalArgumentException: File descriptors passed in Intent`). A
`Binder` is not a file descriptor, so the intent carries a **Binder
token** instead. The app then calls the existing
`IExternalWayland.openClient()` on that token. That is an ordinary
synchronous Binder call, and *it* can carry a `ParcelFileDescriptor`:
the launcher creates the socketpair in `openClient()`, returns the
compositor end to the app, and keeps the client end for the child.

`app_process` starts its Binder thread pool in `AppRuntime.onStarted()`
before invoking `main()`, so the launcher can receive that call while its
main thread waits.

```
Termux (wayland-run)                                TAWC app
────────────────────                                ────────
Bundle{host binder} ── startService(Intent) ──▶ ExternalWaylandService
     ▲                                            onStartCommand()
     │                                              reads host binder
     │  openClient()  ◀──────────────────────────────┘
     │  (synchronous Binder call; socketpair created here)
     ├── returns compositor end ─────────────────▶ detachFd()
     │                                             nativeAdoptClientFd()
     └── keeps client end
WAYLAND_SOCKET=fd ──▶ exec(client)                 insert_client()
```

### Why it is a DEX, not a shell script or a C binary

The handshake has to travel through Android's `Intent`/`Bundle` and a
Binder callback. There is no fd-carrying `am` flag, and building that
wire format in a lower-level language means reimplementing `Intent`'s
private parcel format, pinned to one Android version. So the launcher is
JVM bytecode run by `app_process` — Java or Kotlin, it makes no
difference — and the compile-only stubs keep the build to a JDK plus
`d8`.

## Required app change

`ExternalWaylandService.onBind`/AIDL cannot be reached from Termux (no
bind), but `onStartCommand` can. It reads the host binder and calls the
same `openClient()` from the app side (the plan's
`nativeAdoptClientFd` JNI is reused as-is):

```kotlin
companion object {
    const val EXTRA_HOST = "me.phie.tawc.extra.HOST"
    const val KEY_HOST = "host"
}

override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val host = intent?.getBundleExtra(EXTRA_HOST)?.getBinder(KEY_HOST)
    if (host != null) {
        try {
            val pfd = IExternalWayland.Stub.asInterface(host).openClient()
            if (pfd != null) {
                NativeBridge.nativeAdoptClientFd(pfd.detachFd())
            }
        } catch (e: Exception) {
            Log.w("tawc", "external Wayland: host callback failed", e)
        }
    } else {
        Log.w("tawc", "ExternalWaylandService started without $EXTRA_HOST")
    }
    stopSelf(startId)
    return START_NOT_STICKY
}
```

`stopSelf` is important: the service must not stay started. Return
`START_NOT_STICKY` so a process death does not replay a dead handshake.

## Build and run

`wayland-run` runs straight from this directory. It uses the
`tawc-wayland.jar` beside it and rebuilds it when the sources are newer,
so there is no install step:

```sh
termux-wayland/wayland-run foot
termux-wayland/wayland-run weston-simple-shm
```

`TAWC_PACKAGE=me.phie.tawc` selects the release app instead of the dev
build; `TAWC_WAYLAND_JAR=...` uses a different jar.

The build is a separate script if you want it explicitly. `src/` needs
only a JDK and `d8`; the launcher touches a handful of Android types, so
when no `android.jar` is around it compiles against the compile-only
stubs in `stubs/`:

```sh
pkg install openjdk-17 d8 zip       # Termux
bash termux-wayland/build.sh        # -> termux-wayland/tawc-wayland.jar
```

On a PC, or to type-check against the real SDK, point it at `android.jar`
instead:

```sh
ANDROID_HOME=$HOME/Android/Sdk termux-wayland/build.sh
# or: ANDROID_JAR=.../android.jar termux-wayland/build.sh
```

Only `src/` classes are dexed; the stubs are a compile-only classpath.

## Notes and limits

- One client per `wayland-run`; `WAYLAND_SOCKET` is single-shot.
- `onStartCommand` cannot see the original caller uid: the start is
  delivered by AMS, so `Binder.getCallingUid()` is TAWC itself. Any app
  can therefore start the exported service and get a compositor
  connection. Acceptable for a dev feature; a manifest permission would
  only help callers that have a manifest, which `app_process` does not.
- The client must connect with `wl_display_connect(NULL)` so
  `libwayland-client` honours `WAYLAND_SOCKET`.
- The compositor end is dup'd into the Binder reply; the launcher closes
  its copy and the app detaches its copy into native. When the client
  exits, the launcher's copy is already closed, so the compositor sees
  EOF.
