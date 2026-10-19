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
`am` command works. So `wayland-run` creates a socketpair itself, hands
one end to the app **as an `Intent` extra** (a `ParcelFileDescriptor`),
and runs the client on the other end via `WAYLAND_SOCKET`.

```
Termux (wayland-run)                          TAWC app
────────────────────                          ────────
socketpair() ── app end ──┐
                          │  startService(Intent{EXTRA_CLIENT_FD})
                          └────────────────▶ ExternalWaylandService
                                               onStartCommand()
                                                 detachFd()
                                                 nativeAdoptClientFd()
client end ◀──────────────┘                           │
WAYLAND_SOCKET=fd  ─────────────────────────────▶ insert_client()
   exec(wayland client)
```

### Why it is a DEX, not a shell script or a C binary

The fd has to arrive inside an Android `Intent`. There is no fd-carrying
`am` flag, and every AMS call a non-app caller is allowed to make
(`startActivity`, `startService`, `broadcastIntent`) takes an `Intent`.
Building that `Intent` Parcel in a lower-level language means
reimplementing `Intent`'s private wire format (`Intent.writeToParcel` +
`Bundle` + `ParcelFileDescriptor`), pinned to one Android version. So the
launcher is JVM bytecode run by `app_process` — Java or Kotlin, it makes
no difference — and the compile-only stubs keep the build to a JDK plus
`d8`.

## Required app change

`ExternalWaylandService.onBind`/AIDL is unreachable from Termux and can
be dropped or kept. What the launcher needs is an `onStartCommand` that
consumes the fd (the plan's `nativeAdoptClientFd` JNI is reused as-is):

```kotlin
companion object {
    const val EXTRA_CLIENT_FD = "me.phie.tawc.extra.CLIENT_FD"
}

override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    @Suppress("DEPRECATION")
    val pfd = intent?.getParcelableExtra<ParcelFileDescriptor>(EXTRA_CLIENT_FD)
    if (pfd != null) {
        NativeBridge.nativeAdoptClientFd(pfd.detachFd())
    } else {
        Log.w("tawc", "ExternalWaylandService started without $EXTRA_CLIENT_FD")
    }
    stopSelf(startId)
    return START_NOT_STICKY
}
```

`stopSelf` is important: the service must not stay started. Return
`START_NOT_STICKY` so a process death does not replay a dead fd.

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
- `onStartCommand` cannot see the original caller uid: the fd is
  delivered by AMS, so `Binder.getCallingUid()` is TAWC itself. Any app
  can therefore start the exported service and get a compositor
  connection. Acceptable for a dev feature; a manifest permission would
  only help callers that have a manifest, which `app_process` does not.
- The client must connect with `wl_display_connect(NULL)` so
  `libwayland-client` honours `WAYLAND_SOCKET`.
- The app end of the socketpair is dup'd by Binder on delivery; the
  launcher closes its copy after `startService` returns.
