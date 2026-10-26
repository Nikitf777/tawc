# Mesa GLES compositor renderer + `zwp_linux_dmabuf_v1`

**Status:** Part A implemented as an experimental server-side spike
(2026-10-26), including the Settings switch. Mesa surfaceless EGL +
Zink/Turnip initialises, the `zwp_linux_dmabuf_v1` global is advertised, and
the ASurfaceControl output path renders. AHB input import and the full A7
matrix are not yet validated; Part B is not started. See
[notes/mesa-renderer.md](../notes/mesa-renderer.md).

Add an opt-in Mesa-based GLES renderer for the compositor. Unlike the
vendor-GLES renderer, it can import client dma-bufs, so the compositor
can advertise `zwp_linux_dmabuf_v1` alongside `android_wlegl`.

Scope: **Adreno/KGSL only**. No freedreno-on-MSM, no Mali/PowerVR/Intel,
no x86_64 AVD. The setting is **off by default** and hidden on devices
where the Mesa path is unavailable.

The work is split into:

- **Part A — server side**: the compositor's Mesa renderer, dmabuf
  protocol, settings, and build/assets. Testable on its own with the
  external-connections branch + Termux clients.
- **Part B — client side**: glibc Turnip/Zink in the rootfs, a new
  client `GraphicsBackend`, assets/env, and tests. Not needed to land
  Part A.

libhybris is **not** removed. The existing `LIBHYBRIS` and
`LIBHYBRIS_ZINK` backends and Xwayland/TAWC-DRI stay as they are.

## Decisions up front

- “Mesa GLES” means **Mesa EGL/GLES with Zink**, using **Turnip** as the
  Vulkan driver. Mesa's freedreno Gallium driver has no KGSL backend in
  this Mesa version; Zink-on-Turnip is the only Mesa GLES path that can
  open an Adreno KGSL device.
- The compositor renders Mesa GLES **offscreen**. Zink's Android
  presentation path is explicitly disabled in Mesa (`Kopper won't work
  on Android without extra platform level support` in
  `egl_dri2.c::dri2_detect_swrast_kopper`), and Turnip has no Android
  WSI in an app-private build. Output is an AHB rendered by Mesa and
  presented by `ASurfaceTransaction` (`ASurfaceControl` from the host
  `ANativeWindow`). A vendor-GLES blit is a **fallback only**, not the
  target: it adds a full-screen GPU pass, memory, latency, and a
  vendor-GL dependency, and has no architectural advantage over
  `ASurfaceTransaction`. Do not implement both as production paths.
- One compositor, one `wl_display`. `android_wlegl` and
  `zwp_linux_dmabuf_v1` are per-buffer choices, not per-client. No
  second compositor/router.
- `zwp_linux_dmabuf_v1` is advertised **only** when the Mesa renderer
  initialised successfully. Vendor mode keeps the current globals.
- `android_wlegl` stays advertised in both modes. In Mesa mode its AHBs
  must be imported through Mesa; if that import fails for a buffer, the
  buffer is rejected (or the server falls back to vendor mode), never
  handed to a second compositor.
- The server setting is separate from the existing per-client
  `GraphicsBackend` setting. They are independent and can be toggled
  separately.
- Do not depend on `linux-drm-syncobj-v1`: it needs a DRM syncobj fd and
  there is no `/dev/dri`. Fencing is a separate, explicit part of the
  plan.

## Part A — server side

### A0. Spike / decision gate (do this first)

Build a throwaway server-side Mesa Zink + Turnip stack and validate the
following on the physical Adreno device:

1. Private `libEGL_mesa.so` + `libvulkan_tawc.so` (Khronos loader) +
   `libvulkan_freedreno.so` (Turnip **ICD**, not the Android HAL build)
   load in the app process (absolute paths from `nativeLibraryDir` /
   `filesDir`).
2. `eglGetPlatformDisplayEXT(EGL_PLATFORM_SURFACELESS_MESA, ...)` /
   `eglInitialize` with `MESA_LOADER_DRIVER_OVERRIDE=zink` and the
   private Turnip loader produces a surfaceless or pbuffer ES context.
   `GlesRenderer::new` succeeds and reports Zink + Turnip. This needs
   the no-DRM surfaceless patch (see A1); stock surfaceless requires a
   DRM device, and `LIBGL_ALWAYS_SOFTWARE` makes Zink request a CPU
   device.
3. `dmabuf` import works: import a dma-buf created by Termux/Turnip
   (`VK_EXT_external_memory_dma_buf`) through Mesa's
   `EGL_EXT_image_dma_buf_import` and sample it.
4. AHB import works: use an AHB produced by the existing
   `android_wlegl` path and convert it to a dma-buf fd + modifier
   (`AHardwareBuffer_getNativeHandle` + u_gralloc/`gmsm` metadata) →
   `EGL_EXT_image_dma_buf_import`. Do not rely on Mesa's Android EGL /
   `EGL_ANDROID_image_native_buffer`; AHB handling stays in the
   compositor, not Mesa.
5. Render into an AHB-backed texture and present it:
   - Target: `ASurfaceTransaction_setBuffer` on an `ASurfaceControl`
     derived from the existing `ANativeWindow`
     (`ASurfaceControl_createFromWindow`, or Kotlin
     `SurfaceView.getSurfaceControl` + NDK), with an acquire fence and
     buffer-release handling. Validate per-host creation/destruction,
     resize, multi-window, rotation, and dataspace/transform.
   - Fallback only if the SurfaceControl path cannot be made reliable:
     import the output AHB into the existing vendor GLES context and
     blit it to the host `EGLSurface`. Treat this as a temporary
     de-risking path, not a shippable second backend.

**Decision gate:** if (2), (3), and either AHB import strategy in (4)
do not work, stop and revisit. If (5)'s `ASurfaceTransaction` path
works, skip the vendor blit entirely. If only the vendor blit works,
ship it as a documented fallback with its extra copy/dependency. The
fallback designs for a failed Mesa GLES spike are (a) Turnip Vulkan
renderer instead of GLES, or (b) Mesa Zink as a dmabuf→AHB import
bridge with the current vendor `GlesRenderer` unchanged. Do not start
A1–A7 until the spike proves the core.

**Termux build/use check (2026-10-26).** A stock
`-Dplatforms=android` build was attempted in Termux:

- Meson setup with `-Dplatforms=android -Dandroid-stub=true
  -Dplatform-sdk-version=35 -Dgallium-drivers=zink -Dvulkan-drivers=
  -Degl=enabled -Dgles2=enabled -Dglvnd=disabled` succeeds.
- The native Termux clang defaults to Android API 24. `AHardwareBuffer`
  and `sync_file_info` are API 26+, so the build must use
  `--target=aarch64-linux-android35` (c/cpp/link args). Link with
  `-landroid-shmem`. Direct Python scripts such as
  `src/compiler/isaspec/{decode,encode}.py` need
  `termux-fix-shebang` (or an equivalent wrapper).
- `libEGL.so`, `libGLESv2.so`, `libgallium_dri.so`, and the Android
  Turnip HAL `libvulkan_freedreno.so` build successfully.
- **Runtime:** the Android EGL platform (`eglGetDisplay(EGL_DEFAULT_DISPLAY)`)
  fails with `EGL_NOT_INITIALIZED` in Termux: there is no DRM/gralloc
  display for `droid_initialize`. Stock surfaceless also fails without
  a DRM device; `LIBGL_ALWAYS_SOFTWARE=1` only makes Zink request a CPU
  (lavapipe) device. A no-DRM surfaceless patch is required (see A1).
- The Android Turnip build exports the **HAL module**
  (`vulkan-android.sym`), not `vk_icdGetInstanceProcAddr`; the Termux
  Khronos loader cannot load it via `VK_ICD_FILENAMES`. Use the existing
  Termux Linux Turnip ICD for Zink/dmabuf tests, or build a private ICD
  variant with the normal `vulkan.sym` (see A1).
- Real `libnativewindow`/AHB staging in Termux hits system
  verneed/namespace issues; the stub AHB functions do not allocate. AHB
  and `ASurfaceTransaction` testing belongs in the TAWC app, not Termux.

Conclusion: Termux is useful for **build verification and
surfaceless-Zink/dmabuf experiments** with an ICD Turnip, but not for
the Android EGL/AHB path. The server design should use surfaceless EGL +
AHB→dmabuf, not `-Dplatforms=android` EGL.

### A1. Build and package the server-side Mesa stack

Keep this separate from the existing glibc chroot Mesa assets. All
outputs target Android bionic and ride in `jniLibs/<abi>/`.

- **Mesa GL (Zink) — surfaceless, not Android EGL**, with the pinned
  `deps/mesa`:
  - `-Dplatforms=surfaceless -Dgallium-drivers=zink -Degl=enabled
    -Dgles2=enabled -Dvulkan-drivers= -Dglvnd=disabled`.
  - No `android-stub`, `libnativewindow`, `libhardware`, or
    `libu_gralloc` in this build; AHB handling is compositor-side.
  - `libdrm` must be available for `HAVE_LIBDRM` /
    `EGL_EXT_image_dma_buf_import` advertisements. Build the pinned
    libdrm for Android or add a narrowly scoped Mesa patch; document the
    choice.
  - Stage `libEGL.so`, `libGLESv2.so`, the DRI driver library (the
    build's `libgallium_dri.so`/`zink_dri.so` or versioned
    `libgallium-*.so`, depending on Mesa), and support libs. Verify
    which artifact actually exports the DRI extension symbols before
    staging.
  - **Required Mesa patch:** a no-DRM surfaceless variant of
    `06-tawc-zink-nokms.patch`. Stock `dri2_initialize_surfaceless`
    only falls back to `surfaceless_probe_device_sw()` when
    `LIBGL_ALWAYS_SOFTWARE` is set, and that also makes Zink request a
    CPU device. The patch must allow hardware Zink with
    `fd_render_gpu = -1` (as the Wayland no-KMS patch does) without
    setting `ForceSoftware`/`ZINK_USE_LAVAPIPE`.
- **Turnip** (same pinned Mesa, separate build):
  `-Dplatforms=android -Dandroid-stub=true -Dvulkan-drivers=freedreno
  -Dfreedreno-kmds=kgsl -Dgallium-drivers= -Dplatform-sdk-version=35`.
  This produces the HAL module used by the AOSP loader, **not** a
  Khronos ICD; its version script exports HAL symbols, not
  `vk_icd*`. For an app-private Khronos loader, build a private ICD
  variant with the normal `vulkan.sym` (or a small wrapper) and verify
  `vk_icdGetInstanceProcAddr`/`vk_icdNegotiateLoaderICDInterfaceVersion`
  are exported. AHB support is **not** required from Turnip, because
  AHBs are converted to dma-bufs compositor-side; `VK_EXT_external_memory_dma_buf`
  and `VK_EXT_image_drm_format_modifier` are enough.
- **Private Khronos loader** pinned as a new `deps.list` entry (or a
  documented vendored copy), Android build, no WSI required. Stage as
  `libvulkan_tawc.so` so it cannot shadow the system `libvulkan.so`.
- **Mesa patches**:
  - `07-...`: Zink's Vulkan library loader must honour
    `TAWC_VULKAN_LIB` (absolute path to `libvulkan_tawc.so`) instead of
    always `dlopen("libvulkan.so")`.
  - `08-...`: no-DRM surfaceless Zink (see above).
  - If needed for AHB import/output, a small helper that exposes
    `u_gralloc`-equivalent AHB metadata (format/stride/modifier) to the
    Rust/C importer; do not duplicate QCOM UBWC guessing in two places
    if it can be avoided.
- **Smithay patch** (in the TAWC Smithay fork): `egl_ffi` must be able
  to load a caller-selected libEGL path (`TAWC_EGL_LIB`) instead of the
  hard-coded `libEGL.so.1`. Set the env before the `LazyLock`/`Once`
  initialises; vendor mode leaves it unset.
- **Packaging**: this Mesa build should not stage `libnativewindow` /
  `libhardware` / `libsync` / `liblog` stubs at all. The compositor
  already uses the real platform libraries for AHBs and
  `ASurfaceControl`; Mesa only needs `libdrm`, `libandroid-shmem`, and
  the standard bionic libraries.
- Add a Gradle task/build wrapper to invoke the new Mesa surfaceless
  build; stage per-ABI outputs under `jniLibs/arm64-v8a/` (aarch64 only
  for this feature). Follow `scripts/build-gfxstream-backend.sh` for
  the NDK/cross-file pattern and `scripts/build-mesa-gfxstream.sh` for
  the pinned Mesa + patch flow.
- Add build flags (`GRAPHICS_MESA_SERVER_ENABLED`) and keep the feature
  out of release builds until the spike and integration tests pass.

### A2. Settings and runtime plumbing

- New persisted setting, separate from `Settings.graphicsBackend`:
  - `enum ServerRenderer { VENDOR_GLES("vendor"), MESA_GLES("mesa") }`
  - key `server_renderer`; default `VENDOR_GLES`.
  - debug/test store support.
- Settings UI: a row in the Graphics card (“Compositor renderer”) with
  a warning:
  - “Mesa GLES (experimental) — Adreno only. Adds
    `zwp_linux_dmabuf_v1`; requires a compositor restart.”
  - Hidden/disabled unless `MesaServerSupport.isSupported()`.
  - The existing client `GraphicsBackend` radio group remains unchanged.
- `MesaServerSupport.isSupported()`:
  - `BuildConfig.GRAPHICS_MESA_SERVER_ENABLED`;
  - `arm64-v8a` (no x86_64 server Mesa);
  - `/dev/kgsl-3d0` exists and is openable;
  - Qualcomm/Adreno check (`Build.SOC_MANUFACTURER` on API 31+,
    `Build.MANUFACTURER`/`ro.board.platform`/`/dev/kgsl` fallback).
  - Debug override for emulator/CI only if needed; not user-facing.
- Setting default off. If a persisted `MESA_GLES` is no longer
  supported (downgrade, non-Adreno, assets missing), fall back to
  `VENDOR_GLES`.
- Pass the setting into `nativeStartCompositor` as an extra argument
  (ordinal or key). Rust stores it in `TawcState`/`run_compositor` and
  passes it to `LazyRenderState::spawn(mode)` / `RenderState::init(mode)`.
- Changing the setting requires restarting the compositor:
  - simplest acceptable: Settings says “takes effect after restart”, and
    the next start uses it;
  - better: a “Restart compositor” action/`nativeStopCompositor` +
    service restart, if it can be done without disturbing running
    sessions. Do not silently restart the whole app.
- If Mesa initialisation fails:
  - log a clear error;
  - fall back to the vendor renderer for that compositor run;
  - do not advertise dmabuf;
  - keep the persisted setting but surface a one-time warning/notification
    or a Settings note. Never leave a half-initialised Mesa/dma-buf
    state.
- Protocol globals are chosen from the **effective** renderer, not the
  persisted setting: vendor fallback must not advertise
  `zwp_linux_dmabuf_v1`.

### A3. Mesa renderer state and importers

Refactor `RenderState` so vendor and Mesa share the compositor-facing
API but have separate GPU state.

- `RenderState` becomes an enum/struct with two backends:
  - `Vendor { renderer: GlesRenderer (system EGL), importer,
    plain_shader, tint_shader, host EGL surfaces }` — current behaviour.
  - `Mesa { renderer: GlesRenderer (private Mesa surfaceless EGL +
    Zink/Turnip), importer, shaders, Mesa output targets }`.
- Mesa EGL/GL setup:
  - `egl_android.rs` gains a Mesa variant: `TAWC_EGL_LIB` must already
    be set; use `eglGetPlatformDisplayEXT(EGL_PLATFORM_SURFACELESS_MESA,
    EGL_DEFAULT_DISPLAY, NULL)` (not `EGL_DEFAULT_DISPLAY`/Android EGL);
    try `eglMakeCurrent(NO_SURFACE, NO_SURFACE, context)`, fall back to
    a 1x1 pbuffer.
  - `MESA_LOADER_DRIVER_OVERRIDE=zink`, `TAWC_VULKAN_LIB`, and
    `VK_ICD_FILENAMES` are set before any Mesa/Zink init (preferably in
    `nativeStartCompositor`/`run_compositor`, before `LazyRenderState`).
    The Mesa build must include the no-DRM surfaceless patch (A1).
  - Do not initialise the Mesa stack when the mode is vendor (leave
    system EGL untouched).
- AHB importer:
  - Parameterise `AhbTextureImporter` by the GLES library path so the
    Mesa path loads Mesa's GLESv2 rather than the system/vendor one.
  - Do **not** use Mesa's Android EGL/`EGL_ANDROID_image_native_buffer`.
    Convert each AHB (from `android_wlegl` or `tawc_gfxstream`) to
    dma-buf fd + format/stride/modifier (u_gralloc/`gmsm` metadata) and
    import it with `EGL_EXT_image_dma_buf_import`/`ImportDma`.
  - Keep AHB ownership/release (`WleglBufferData`) unchanged; the
    dma-buf fd is only borrowed for the import.
- dmabuf import:
  - With the Mesa `GlesRenderer`, Smithay's `ImportDma` uses
    `EGL_EXT_image_dma_buf_import`; verify `dmabuf_formats()` is
    non-empty and that modifiers round-trip for QCOM.
  - Add a `DmabufBufferData` only if the import path needs custom
    user-data; otherwise use Smithay's renderer import and
    `WaylandSurfaceRenderElement`.
- `android_wlegl`/gfxstream/SHM:
  - Keep `WleglBufferData` and the AHB user-data path; in Mesa mode the
    AHB is converted to a dma-buf and imported by Mesa.
  - Keep SHM import unchanged.
  - Add a `SurfaceKind` tint for dmabuf (debug only), if the existing
    tint feature is to remain useful.

### A4. Output path

The Mesa renderer has no `EGLSurface` on the Android host; it renders
into an AHB and presents that.

- Add a per-host Mesa output target:
  - allocate 1–2 AHBs (`AHardwareBuffer_allocate`,
    `R8G8B8A8_UNORM`, `GPU_FRAMEBUFFER | GPU_SAMPLED_IMAGE`, plus
    composer/overlay usage as needed);
  - create a Mesa `GlesTexture` (2D, render-target) from the AHB;
  - bind with `GlesRenderer::bind(texture)` and render with the existing
    `GlesFrame`/`draw_render_elements` path;
  - preserve the existing output size/scale/`Transform::Flipped180`
    handling.
- **Target present path: `ASurfaceTransaction`.**
  Create an `ASurfaceControl` from the host `ANativeWindow`, set the
  rendered AHB in an `ASurfaceTransaction`, apply it, and recycle the
  AHB on the buffer-release callback. This is zero-copy (one AHB
  reaches SurfaceFlinger), removes vendor GLES from the Mesa path, and
  avoids the extra full-screen blit/pass. Gate on the A0 spike.
  - Need to validate `SurfaceView`/`ANativeWindow` → `ASurfaceControl`
    ownership, per-host create/destroy, resize, multi-window, rotation,
    dataspace (sRGB), transform, and buffer release on the target
    Android versions. If `ASurfaceControl_createFromWindow` is not
    enough, use `SurfaceView.getSurfaceControl()` + NDK transaction.
- **Fallback: vendor-GLES blit, only if the SurfaceControl path cannot
  be made reliable.** Import the rendered AHB into the existing vendor
  GLES context (`AhbTextureImporter`), blit a full-screen quad to the
  host `EGLSurface`, then `eglSwapBuffers`. This reuses the current
  EGLSurface/host lifecycle and is the lower-risk implementation, but
  it adds a full-screen GPU copy, extra AHB/window-buffer memory, an
  extra fence/latency stage, and keeps a vendor GLES dependency in the
  Mesa path. It has no performance or architecture advantage over
  `ASurfaceTransaction`; do not ship both.
- Fences:
  - Mesa render → output AHB: use `glFinish`/a GL fence initially; move
    to `EGL_ANDROID_native_fence_sync`/`EGL_KHR_fence_sync` if the
    present path accepts a fence fd.
  - `ASurfaceTransaction_setBuffer` gets the acquire fence; recycle
    only after release.
- Resize/destroy: destroy and recreate the AHB/FBO/EGLImage when the
  host surface size changes; drain in-flight presents first.
- Multi-window: each `OutputHost` gets its own output target; only the
  foreground host is rendered, matching the current behaviour.

### A5. `zwp_linux_dmabuf_v1`

- Advertise the global only when the effective renderer is Mesa and
  `renderer.dmabuf_formats()` is non-empty.
- Start with Smithay's **v3** `DmabufState::create_global` and
  format/modifier events; no `DmabufFeedbackBuilder` initially. There
  is no DRM `dev_t` on this device, and v3 is sufficient for Mesa WSI.
  Add feedback later only if a client requires it, using a documented
  synthetic `dev_t`/format table.
- Implement `DmabufHandler` for `TawcState`; import via the Mesa
  `GlesRenderer` (`ImportDma`) and reject formats/modifiers the
  renderer cannot import.
- `android_wlegl` stays registered in both modes. It and dmabuf can be
  used by the same client on different buffers; the compositor imports
  each per `wl_buffer`.
- Vendor mode: no dmabuf global, current behaviour unchanged.
- Do not implement `linux-drm-syncobj-v1` in this plan. Document the
  implicit-sync limitation and the client/test workaround (clients that
  wait before commit, or an explicit-wait patch).

### A6. Adreno gating, fallback, and non-goals

- Server Mesa build/UI only for Adreno/KGSL (`/dev/kgsl-3d0`); hide on
  x86_64, non-Qualcomm, and non-KGSL devices.
- If the setting is enabled but support/init fails, fall back to vendor
  and keep `zwp_linux_dmabuf_v1` unregistered.
- Vendor mode must remain bit-for-bit the current behaviour: no Mesa
  assets loaded, no extra EGL context, no protocol changes.
- No attempt to support freedreno GL on KGSL, other vendors, or
  `VK_KHR_android_surface` app-privately. If a Vulkan renderer is ever
  needed, that is a separate plan.

### A7. Server-side tests

Use the external-connections branch locally; no Part B assets required.

1. **Vendor control:** Mesa setting off → existing integration suite
   passes; `zwp_linux_dmabuf_v1` is absent; `android_wlegl`, SHM,
   gfxstream unchanged.
2. **Mesa init:** enable setting + restart; logs show surfaceless EGL +
   Zink + Turnip; `android_wlegl` rootfs app (lxterminal/Firefox)
   renders; AHB→dmabuf import path used. (Android EGL is not used and is
   not expected to initialize without DRM/gralloc.)
3. **dmabuf via Termux:** merge/rebase the external-connections branch;
   from Termux run a Turnip Wayland client (`vkcube`, or a minimal
   Vulkan test) with `WAYLAND_DISPLAY` pointing at the compositor.
   Verify `zwp_linux_dmabuf_v1` is bound, dma-bufs are imported, frames
   are correct, and `vkcube --c N` exits. If Termux's Mesa Zink is
   available, also test a GL client (`weston-simple-egl`, `gtk4-demo`).
4. **Both simultaneously:** run a libhybris/AHB rootfs app and a
   Termux/Turnip dmabuf app; both must render, resize, and survive
   focus changes.
5. **Fallback:** force Mesa init failure; compositor falls back to
   vendor, no dmabuf global, rootfs apps still work.
6. **Gating:** non-Adreno/x86_64 target hides the setting; persisted
   Mesa setting falls back.
7. **Restart/settings:** toggling requires/does a compositor restart;
   no crash/black screen; no stale renderer state.
8. **Regression:** multi-window, rotation/scale, IME/input, gfxstream
   AHB, screenshots/pixel tests, and the magenta SHM diagnostic.
9. **Performance note:** record Zink cost vs vendor for a rootfs app
   and a Termux dmabuf client (target path has no output copy).
10. **Termux caveats:** use Termux's existing **Linux Turnip ICD**, not
    the Android HAL build, for Zink/dmabuf clients. Termux's AHB/system
    libnativewindow namespace is not representative; do not use it to
    judge `android_wlegl`/`ASurfaceTransaction`. Those must be tested
    in the TAWC app.

## Part B — client side

Only needed for clients to use Mesa directly. libhybris remains for
`LIBHYBRIS`, `LIBHYBRIS_ZINK`, Xwayland/TAWC-DRI, and vendor-only apps.

### B1. Build glibc Mesa assets

Extend `scripts/build-mesa-gfxstream.sh` (or add a sibling script) with:

- **Turnip (glibc, Wayland WSI):**
  `-Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl -Dplatforms=wayland
  -Dgallium-drivers=`, plus the existing glibc sysroot/cross setup.
  Install `libvulkan_freedreno.so` + ICD JSON under a new
  `/usr/lib/mesa-turnip/` prefix.
- **Mesa Zink (glibc):** reuse the existing `build-<abi>-mesa-zink`
  output (`libEGL_mesa`, `libgallium`, `zink_dri`) and the no-KMS patch.
- Stage both in per-ABI APK assets. The distro's Vulkan loader and
  libwayland are used; if a distro lacks the loader, ship a glibc
  Khronos loader asset (document the dependency).
- No Android/bionic assets in Part B; that is Part A.

### B2. New client backend

- Add `GraphicsBackend.MESA_TURNIP` (`key = "mesa-turnip"`, display
  “Mesa (Turnip)”, detail string). Keep it out of `DEFAULT`; build-gate
  with `GRAPHICS_MESA_TURNIP_ENABLED`.
- Extend `EnabledGraphicsBackends` and the Settings radio group; hidden
  if not built / not Adreno-KGSL.
- Add `MesaTurnipInstallProvider` (or extend `MesaZinkInstallProvider`)
  to lay the Turnip ICD + JSON and the Mesa-Zink tree into each rootfs,
  with the same `tawcStamp`/copy-vs-bind rules as the existing providers.
- `RootfsEnv.MESA_TURNIP`:
  - `LD_LIBRARY_PATH` includes `/usr/lib/mesa-zink` (and a Turnip
    library dir if needed); **exclude** `/usr/lib/hybris` and
    `/usr/lib/hybris-vulkan-only`.
  - `VK_ICD_FILENAMES` points at the Turnip JSON.
  - `MESA_LOADER_DRIVER_OVERRIDE=zink` for GL/GLES.
  - `__EGL_VENDOR_LIBRARY_FILENAMES` points at Mesa's
    `50_mesa.json`, as `LIBHYBRIS_ZINK` does.
  - Do not set vendor/libhybris/gl-shims variables.
- Ensure `/dev/kgsl-3d0` and `/dev/ion` are visible in all install
  methods (tawcroot binds `/dev`; verify proot/chroot). If a method does
  not expose them, add an explicit bind or document the backend as
  tawcroot-only.
- No `android_wlegl`-specific code in the client: Mesa clients present
  Wayland dma-bufs. The server handles `android_wlegl` for other
  clients.

### B3. Client-side tests

- `vulkaninfo` in the rootfs: Turnip enumerates; no vendor/libhybris.
- `vkcube`: Turnip + Mesa Wayland WSI + `zwp_linux_dmabuf_v1`; frames
  correct; no AHB.
- `eglinfo`/`gtk4-demo`/`weston-simple-egl`: Zink on Turnip; GL/GLES
  works; dmabuf path.
- Firefox (if in scope): Wayland + Zink/Turnip; compare with the
  libhybris backend.
- Both protocols: run a libhybris AHB app and a Mesa dmabuf app in the
  same rootfs/session; both render.
- External connections: run the same Mesa clients from Termux; this is
  also the Part A test.
- Negative tests: Mesa client with vendor server (no dmabuf) should
  fail cleanly or fall back, not hang; Mesa init failure should not
  break other backends.
- Keep `LIBHYBRIS`/`LIBHYBRIS_ZINK` tests green; do not change defaults.

## Order and dependencies

1. **A0 spike** (hard gate).
2. **A1 assets/build + A2 settings/runtime**.
3. **A3 Mesa renderer/importers + A4 output** (target
   `ASurfaceTransaction`; vendor blit only if A0 rejects it).
4. **A5 dmabuf protocol**.
5. **A6 gating/fallback + A7 server tests** (external-connections
   branch + Termux; rootfs libhybris AHB).
6. **B1/B2/B3** client assets/backend after Part A works; can be
   developed in parallel once A0/A5 are stable.

Part A must land disabled by default. Part B must land disabled by
default and depend on Part A for dmabuf. Do not remove libhybris.

## Acceptance criteria

- Mesa setting exists, is hidden on non-Adreno, defaults off, requires
  a compositor restart, and falls back to vendor on init failure.
- Vendor mode is unchanged; `zwp_linux_dmabuf_v1` is absent.
- Mesa mode:
  - `android_wlegl` AHB clients render.
  - Termux/Turnip dma-buf clients render via `zwp_linux_dmabuf_v1`.
  - Both can render simultaneously/resize/focus normally.
  - Output is the zero-copy `ASurfaceTransaction` path; vendor GLES is
    not used by the Mesa path (vendor blit only as a documented
    fallback if A0 rejects the SurfaceControl path).
  - No black/half-presented frames; output buffer recycling and fences
    are correct.
- Client `MESA_TURNIP` backend (if Part B lands) gives Turnip Vulkan and
  Zink GL/GLES without libhybris; libhybris backends and Xwayland still
  work.
- Build is reproducible and per-ABI; x86_64/non-Adreno builds do not
  ship the server Mesa assets.

## Risks / unknowns

- **A0 is the main risk**: surfaceless Zink without DRM + AHB→dmabuf
  import + AHB render target/present. If it fails, the fallback is a
  Turnip Vulkan renderer or a hybrid Zink import bridge; do not assume
  the full Mesa GLES renderer is viable before the spike.
- **Android EGL is a dead end here**: `-Dplatforms=android` compiles,
  but `droid_initialize` needs a DRM/gralloc display and fails in
  Termux; surfaceless + AHB→dmabuf is the intended path. Do not spend
  time making the Android EGL platform work.
- **Turnip HAL vs ICD**: the Android build exports the HAL module, not
  `vk_icd*`; an app-private Khronos loader needs an ICD variant (or the
  system/AOSP loader with the HAL). Verify exported symbols before
  staging.
- **Termux AHB testing is misleading**: real `libnativewindow` staging
  hits verneed/namespace issues and the Mesa android-stub AHB functions
  do not allocate; use TAWC for AHB/ASurfaceTransaction.
- `HAVE_LIBDRM`/libdrm on Android and the EGL dmabuf extension
  advertisements; may require a libdrm Android build or a narrow Mesa
  patch.
- AHB import/output modifiers on QCOM (UBWC vs linear); force linear
  output if necessary, and validate the `android_wlegl` AHB metadata.
- Private loader/EGL paths and Android app namespace
  (`libhardware`/`libnativewindow` resolution); packaging stubs must not
  shadow real system libs.
- **`ASurfaceControl`/`ASurfaceTransaction` viability with
  `SurfaceView`/`ANativeWindow`**: ownership, per-host lifecycle,
  resize, multi-window, dataspace/transform, and buffer release. If
  this is unreliable on the target Android versions, the vendor-GLES
  blit fallback is the only present path; that is a real fallback, not
  the target.
- Fencing without `linux-drm-syncobj-v1`; the output path may rely on
  `glFinish`/client waits initially, which is a performance and
  correctness trade-off.
- Zink + Turnip performance vs vendor GLES. The target
  `ASurfaceTransaction` path does not add an output copy; the
  vendor-blit fallback does (full-screen pass + AHB/window-buffer
  memory).
- Settings restart/fallback UX; hidden-on-unsupported gating must not
  strand a persisted Mesa value.
- Client-side Mesa and libhybris coexistence; Xwayland remains on the
  AHB/libhybris path and is out of scope for this plan.

## Likely file map

- `app/src/main/java/me/phie/tawc/Settings.kt` — `ServerRenderer`,
  persisted key/store/default/fallback.
- `app/src/main/java/me/phie/tawc/SettingsActivity.kt`,
  `ui/GraphicsBackendGroup.kt` or a new row helper — server renderer
  control + restart note.
- `app/src/main/java/me/phie/tawc/compositor/CompositorService.kt`,
  `NativeBridge.kt` — pass server renderer to native; restart
  plumbing.
- `compositor/src/lib.rs`, `render.rs`, `egl_android.rs`,
  `gl_import.rs`, `host.rs`, `wlegl.rs`, `compositor.rs`,
  `event_loop.rs` — Mesa render mode, importers, output targets,
  dmabuf global, per-host lifecycle.
- New `compositor/src/mesa/` (EGL/loader/AHB helpers) and
  `compositor/src/dmabuf.rs`.
- `deps/mesa-patches/mesa/07-*` (Zink Vulkan lib/AHB helpers) and the
  Smithay fork EGL path patch; update `deps/deps.list` pins.
- New build script/Gradle tasks for server Mesa/Turnip/loader and
  glibc Turnip; update `notes/building.md`, `deps/deps.list`.
- `app/src/main/java/me/phie/tawc/install/MesaTurnipInstallProvider.kt`
  (Part B) and `install/RootfsEnv.kt`.
- Tests: existing integration suite + external-connections/Termux
  server tests; new `mesa_turnip` client tests.
