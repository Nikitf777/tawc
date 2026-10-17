# External Wayland clients over Binder fd passing

## Goal

Let a Wayland client running under Termux's uid (10549) render into
TAWC's Smithay compositor, on an unrooted device, without moving the
compositor out of the app and without a userspace byte relay.

This is the no-root half of `notes/android.md` "Wayland Socket Sharing":

> **Binder fd passing (preferred):** Compositor creates a `socketpair()`,
> passes one end to Termux via a ContentProvider or bound Service as a
> `ParcelFileDescriptor`. No `connect()` syscall occurs, so SELinux is
> never triggered.

## Why the existing socket cannot be reused

`activation.rs` binds `$TAWC_APP_SHARE_DIR/wayland-0`
(`app_paths.rs:29`) and chmods it `0777`. That is already as open as it
can be. Termux still cannot reach it:

1. **DAC:** `/data/data/me.phie.tawc` is `0700`; Termux cannot traverse
   into `share/`.
2. **SELinux:** a cross-app `connect()` between two `untrusted_app`
   processes is denied on Android 9+ (different MLS categories).

Both are checked on `connect()`. A file descriptor passed over Binder
never calls `connect()`, so neither applies.

## Design

One `SOCK_STREAM` socketpair per external client:

```
Termux client ──fd──▶ launcher ──fd over Binder──▶ ExternalWaylandService
                                                        │
                                              nativeAdoptClientFd
                                                        │
                                          calloop channel (OwnedFd)
                                                        │
                                     insert_client(UnixStream, ClientState)
```

Binder carries only the descriptor. Every Wayland byte stays on the
socketpair. The compositor keeps doing exactly what it does for the
in-container clients; the only new input is "here is a connected stream,
adopt it".

Scope: a launcher helper per client (e.g. `wayland-run foot`). A
long-lived `wayland-0` path inside Termux is a follow-up (see the end).

## App changes (the actual patch)

Six files, nothing in `activation.rs`.

### 1. AIDL

New `app/src/main/aidl/me/phie/tawc/compositor/IExternalWayland.aidl`:

```aidl
package me.phie.tawc.compositor;

import android.os.ParcelFileDescriptor;

interface IExternalWayland {
    /**
     * Returns one end of a fresh SOCK_STREAM socketpair. The compositor
     * keeps the other end and inserts it as a Wayland client.
     */
    ParcelFileDescriptor openClient();
}
```

### 2. Service

New `app/src/main/java/me/phie/tawc/compositor/ExternalWaylandService.kt`:

```kotlin
package me.phie.tawc.compositor

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import me.phie.tawc.BuildConfig

/**
 * Exported fd source for Wayland clients outside TAWC's uid.
 *
 * A Termux process cannot connect to `share/wayland-0` (DAC traversal +
 * SELinux cross-app connect), so it asks here for one end of a socketpair.
 * The other end goes into the compositor's display through
 * [NativeBridge.nativeAdoptClientFd]; no connect() ever happens.
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

            // Rust owns the near end from here. If no compositor thread
            // exists it queues the fd and asks Kotlin to start one.
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
        // Sets TAWC_* env, caches the app context for the reverse-JNI
        // onActivationRequested callback, and starts the socket holder.
        // Idempotent; must precede the first openClient().
        CompositorService.ensureActivation(this)

        // Optional: start the compositor now instead of on the first
        // nativeAdoptClientFd queue miss.
        CompositorService.ensureRunning(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * No custom permission: the Termux-side helper is an `app_process`
     * process, not an installed package, so it cannot hold a manifest
     * permission. Check the uid's packages instead.
     */
    private fun callerAllowed(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        if (packages.any { it == "com.termux" }) return true
        // Lets the PC-side prototype run from `adb shell`.
        return BuildConfig.DEBUG && packages.any { it == "com.android.shell" }
    }
}
```

`CompositorService.ensureActivation` and `ensureRunning` are already
public in its companion object. Nothing else in that file changes.

### 3. Manifest

In `app/src/main/AndroidManifest.xml`, next to `CompositorService`:

```xml
<service
    android:name=".compositor.ExternalWaylandService"
    android:exported="true" />
```

`exported` is required for Termux to bind. The uid allowlist in
`openClient()` is the access control. Do not add a custom permission: an
`app_process` caller has no manifest and cannot be granted one.

### 4. Native declaration

In `app/src/main/java/me/phie/tawc/compositor/NativeBridge.kt`, beside
`nativeStartActivation`:

```kotlin
/** Adopt one end of a client socketpair (Binder fd passing). The fd is
 *  owned by native after this call. False only if it could not be
 *  queued (too many pending, or the compositor is gone). */
external fun nativeAdoptClientFd(fd: Int): Boolean
```

### 5. Rust

#### `compositor/src/lib.rs`

Add the import:

```rust
use std::os::fd::{FromRawFd, OwnedFd};
```

Add the static next to `STATE_QUERY_SENDER`:

```rust
/// Fds handed over by `ExternalWaylandService`, plus the running
/// compositor's calloop sender. One mutex so a start cannot miss an fd
/// queued concurrently.
static EXTERNAL_CLIENTS: Mutex<(
    Option<smithay::reexports::calloop::channel::Sender<OwnedFd>>,
    Vec<OwnedFd>,
)> = Mutex::new((None, Vec::new()));
```

In `nativeStartCompositor`, immediately after
`*STATE_QUERY_SENDER.lock().unwrap() = Some(state_query_sender);`:

```rust
    let (external_sender, external_channel) =
        smithay::reexports::calloop::channel::channel::<OwnedFd>();
    {
        let mut slot = EXTERNAL_CLIENTS.lock().unwrap();
        slot.0 = Some(external_sender);
        let pending = std::mem::take(&mut slot.1);
        let sender = slot.0.as_ref().unwrap();
        for fd in pending {
            // A dead channel means this compositor run is already
            // exiting; the client sees EOF and reconnects.
            let _ = sender.send(fd);
        }
    }
```

Add it to `run_compositor`'s parameter list after `state_query_channel`
(note that `run_compositor` puts `surface_event_channel` before
`state_query_channel`):

```rust
    surface_event_channel: smithay::reexports::calloop::channel::Channel<SurfaceEvent>,
    state_query_channel: smithay::reexports::calloop::channel::Channel<StateQueryResponse>,
    external_channel: smithay::reexports::calloop::channel::Channel<OwnedFd>,
    initial_scale: f64,
```

Pass it in the same position at the `std::thread::spawn` call site:

```rust
            surface_event_channel,
            state_query_channel,
            external_channel,
            initial_scale,
```

Then pass it to `event_loop::run` after `state_query_channel` (here
`surface_event_channel` comes *after* `state_query_channel`):

```rust
        touch_channel, pointer_channel, text_input_channel, clipboard_channel, state_query_channel,
        external_channel,
        surface_event_channel,
        &RUNNING,
```

and add the matching parameter to `event_loop::run`:

```rust
    state_query_channel: Channel<mpsc::Sender<String>>,
    external_channel: Channel<OwnedFd>,
    surface_event_channel: Channel<SurfaceEvent>,
```

In `clear_senders()`:

```rust
    EXTERNAL_CLIENTS.lock().unwrap().0 = None;
```

New JNI entry point (next to `nativeStartCompositor`):

```rust
/// Take one end of a client socketpair from `ExternalWaylandService`.
/// The Java caller has already `detachFd()`d it, so this call owns it.
/// With no compositor running the fd is queued and Kotlin is asked to
/// start one; `nativeStartCompositor` drains the queue.
#[unsafe(no_mangle)]
pub extern "system" fn Java_me_phie_tawc_compositor_NativeBridge_nativeAdoptClientFd(
    mut env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jboolean {
    init_native_logging();
    cache_jni_globals(&mut env);

    // SAFETY: ParcelFileDescriptor.detachFd() transferred ownership.
    let fd = unsafe { OwnedFd::from_raw_fd(fd) };

    {
        let mut slot = EXTERNAL_CLIENTS.lock().unwrap();
        if let Some(sender) = slot.0.as_ref() {
            return sender.send(fd).is_ok() as jboolean;
        }
        if slot.1.len() < 32 {
            slot.1.push(fd);
        } else {
            return 0;
        }
    }

    crate::call_native_bridge_void("onActivationRequested", "()V", &[]);
    1
}
```

No `app_paths` or display access happens on this JNI thread; the fd
literally waits for the compositor thread.

#### `compositor/src/event_loop.rs`

Imports:

```rust
use std::os::fd::OwnedFd;
use std::os::unix::net::UnixStream;
```

`event_loop::run` gains the parameter:

```rust
    external_channel: Channel<OwnedFd>,
```

Add this source just before the listener source:

```rust
    // --- Source 8: externally supplied client fds ---
    // One end of a socketpair per external client. These are ordinary
    // Wayland streams; only the transport differs.
    loop_handle.insert_source(external_channel, |event, _, data: &mut TawcState| {
        let ChannelEvent::Msg(fd) = event else { return };
        let stream = UnixStream::from(fd);
        if let Err(e) = stream.set_nonblocking(true) {
            error!("external client: set_nonblocking failed: {}", e);
            return;
        }
        let client_state = ClientState::new(data.client_count.clone(), data.client_ids.clone());
        if let Err(e) = data
            .display_handle
            .insert_client(stream, Arc::new(client_state))
        {
            error!("external client: insert_client failed: {}", e);
        }
    })?;
```

That is the same `insert_client` call as the listener path
(`event_loop.rs:1143-1148`), so globals, seats, outputs, input and
rendering are unchanged.

### 6. Gradle

If AIDL is not already on, add it in `app/build.gradle.kts`:

```kotlin
    buildFeatures {
        buildConfig = true
        aidl = true
    }
```

## Termux side (needed to exercise it)

A small `app_process` program, the same shape as `am` and the
Termux:X11 loader. It must do four things:

1. **Get a `Context` that claims `com.termux`.** `app_process` has no
   app context, but `ActivityThread.systemMain()` creates a usable one,
   and `createPackageContext("com.termux", 0)` gives it the right
   `getOpPackageName()`. Compile against a hidden stub
   (`android.app.ActivityThread`), exactly like
   `termux-x11/shell-loader/stub`. If `bindService` rejects the borrowed
   package, fall back to the `TermuxAm` reflective
   `IActivityManager` route.

2. **Bind to the service.**

   ```java
   Intent i = new Intent().setComponent(new ComponentName(
       System.getenv().getOrDefault("TAWC_PACKAGE", "me.phie.tawc.dev"),
       "me.phie.tawc.compositor.ExternalWaylandService"));
   ctx.bindService(i, connection, Context.BIND_AUTO_CREATE);
   ```

   Wait for `onServiceConnected` (the process needs a main `Looper`).

3. **Get the fd.**

   ```java
   IExternalWayland w = IExternalWayland.Stub.asInterface(service);
   ParcelFileDescriptor pfd = w.openClient();
   ```

   Copy the app's `IExternalWayland.aidl` into the helper and let `aidl`
   generate the interface; it is self-contained.

4. **Exec the client with the fd.**

   ```java
   // Binder may have set CLOEXEC on the received fd.
   android.system.Os.fcntlInt(
       pfd.getFileDescriptor(), android.system.OsConstants.F_SETFD, 0);

   ProcessBuilder pb = new ProcessBuilder(args);
   pb.environment().put("WAYLAND_SOCKET", Integer.toString(pfd.getFd()));
   pb.inheritIO();
   int rc = pb.start().waitFor();
   ```

   `libwayland-client` checks `WAYLAND_SOCKET` **before**
   `WAYLAND_DISPLAY`, takes ownership of the fd, and uses it as the
   display transport. The client must call `wl_display_connect(NULL)`;
   all normal toolkits do.

`WAYLAND_SOCKET` only works for one client. A `wayland-run <cmd>`
wrapper (bind, `openClient`, exec) is the minimal usable interface.

Build the helper like Termux:X11's loader: `javac` against
`android.jar` + a hidden stub, `d8` to `classes.dex`, zip as an APK,
`chmod 0400` it (Android 14 rejects executing writable dex), then run

```sh
CLASSPATH=$PREFIX/libexec/wayland-bridge/wayland-bridge.apk \
  app_process / --nice-name=wayland-run com.termux.wayland.WaylandLaunch "$@"
```

## Build and test on the PC

1. Build the debug APK (`scripts/build-app.sh`, or
   `./gradlew :app:assembleDebug`) and install it.
2. Start a client through the helper. The first `openClient()` may race
   compositor startup; the fd is queued and the client blocks in
   `wl_display_connect` until the display exists.
3. Verify:
   - `wayland-info` prints `wl_compositor`, `wl_shm`, `xdg_wm_base`,
     `wl_seat`, `wl_output`.
   - `weston-simple-shm` or `foot` opens a TAWC window.
   - `adb logcat -s tawc-native` has no `insert_client failed`.
   - `nativeQueryState` reports one client.
4. For a no-Termux smoke test, run the same helper from `adb shell`
   (uid 2000); `BuildConfig.DEBUG` allows `com.android.shell`.

## Risks and notes

- **Identity is lost.** `SO_PEERCRED` on an app-created socketpair
  reports TAWC itself, not the Termux client. Do not build uid-based
  policy on it. (The listener-adoption variant preserves the real uid.)
- **One fd per client.** This is deliberate; no listener or relay.
- **Queue cap 32.** More pending fds are refused with a closed pair.
  The client sees EOF.
- **Compositor restarts.** `clear_senders()` drops the sender; a
  restart recreates the channel and drains the queue. A send that loses
  the race with teardown just closes that client.
- **No permission on the service.** The uid allowlist is the gate.
  Tighten it before shipping outside a dev build.
- **`WAYLAND_SOCKET` is invisible to `WAYLAND_DISPLAY`.** A client that
  passes an explicit socket name will ignore it.
- **Root shortcut.** With root, tawc's existing chroot path already
  connects directly (notes/android.md). This patch is for no-root.

## Follow-ups (not in this patch)

- **Listener adoption:** Termux creates `$XDG_RUNTIME_DIR/wayland-0`,
  passes the *listening* fd; TAWC adds a second `Generic<UnixListener>`
  source. Gives the normal `WAYLAND_DISPLAY` UX and real peer
  credentials.
- **Relay:** a long-lived Termux helper exposes a path and calls
  `openClient()` per accept. Costs a userspace copy; avoids changing
  every launch command.
- **Protocol-level access control:** pass the client's uid/pid as an
  `openClient` argument and enforce it in the service.
