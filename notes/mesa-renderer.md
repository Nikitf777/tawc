# Mesa GLES server renderer (experimental)

The compositor can render with an app-private Mesa GLES stack instead of the
vendor EGL driver. This is the Part A server side of
[plans/mesa-gles-renderer.md](../plans/mesa-gles-renderer.md). It is selected
through Settings → Graphics driver → Compositor renderer on supported
aarch64 Adreno/KGSL debug builds; release builds keep the vendor renderer.
`adb shell setprop debug.tawc.server_renderer vendor` (or `mesa`) and the
`TAWC_SERVER_RENDERER` process env still override the setting for debug
harnesses. The choice is fixed for the process lifetime (Smithay's EGL
loader binds one libEGL), so restart the app after changing it.
`scripts/build-release-apk.sh` passes `-PtawcMesaServer=false`, so release
APKs still ship the vendor renderer and no server Mesa assets until the
device test matrix is green.

## Stack

- `libEGL.so` + `libGLESv2.so` + `libgallium-25.3.6.so`: Mesa built for
  Android bionic with `-Dplatforms= -Dgallium-drivers=zink`, no Android EGL
  platform. EGL is opened through Smithay's patched loader
  (`TAWC_EGL_LIB` points at the private `libEGL.so`).
- `libvulkan_tawc.so`: minimal loader shim. It forwards
  `vkGetInstanceProcAddr`/`vkGetDeviceProcAddr` to Turnip's
  `vk_icdGetInstanceProcAddr`; Zink opens it via `TAWC_VULKAN_LIB`.
- `libvulkan_freedreno.so`: Turnip built with the KGSL KMD and Khronos ICD
  symbols (`-Dtawc-vulkan-icd=true`). AHB support is intentionally absent;
  Turnip only needs `VK_EXT_external_memory_dma_buf`.
- `libdrm.so`: built for Android bionic so Mesa compiles `HAVE_LIBDRM` and
  advertises `EGL_EXT_image_dma_buf_import`.
- Smithay patch: `deps/smithay-patches/smithay/01-tawc-egl-lib-path.patch`
  makes `egl_ffi` honour `TAWC_EGL_LIB` instead of hard-coding `libEGL.so`.
- Mesa patches: `07-tawc-zink-vulkan-lib.patch` (Zink `TAWC_VULKAN_LIB`),
  `08-tawc-surfaceless-zink.patch` (hardware Zink with no DRM),
  `09-tawc-vulkan-icd-symbols.patch` (Khronos ICD symbols),
  `10-tawc-kgsl-sync-wait.patch` (NDK 27 `sync_wait`),
  `11-tawc-turnip-no-ahb.patch` (no Android gralloc in the private ICD).

Build: `scripts/build-mesa-server.sh` → `build/mesa-server-aarch64/mesa-server.tar`.
Gradle's `buildMesaServerArm64-v8a` packs it as
`assets/mesa-server/arm64-v8a/mesa-server.tar`; `CompositorService` extracts
it to `<filesDir>/mesa-server/`. All private libs use `$ORIGIN` rpaths, so
neither `libEGL.so` nor `libGLESv2.so` shadows the system vendor libraries.

## Rendering and output

Mesa initialises a surfaceless EGL context (`EGL_MESA_platform_surfaceless`)
and renders offscreen. Each `OutputHost` owns an `ASurfaceControl` derived
from its `ANativeWindow` and two `R8G8B8A8` AHBs. The AHB is imported as a
dma-buf render target (`AHardwareBuffer_getNativeHandle` +
`EGL_EXT_image_dma_buf_import`), the compositor renders into it, `glFinish`es,
and presents it with `ASurfaceTransaction`; the buffer is reused only after
the *previous* transaction's OnComplete callback and release fence. If the
AHB dma-buf metadata is not available, the target falls back to the older CPU
`glReadPixels` + AHB-copy path.

`android_wlegl` buffers are imported through their wire dma-buf metadata:
`libsync` fd duplication, QCOM `gmsm` UBWC flag when present, and
`EGL_EXT_image_dma_buf_import`. `zwp_linux_dmabuf_v1` v3 is advertised only
when the Mesa renderer initialised and reports a non-empty import format set
(87 format/modifier pairs on the test Adreno device). Vendor mode keeps the
old globals and does not load any Mesa libs.

## Performance (2026-10-26)

`vkcube --c 2400` from Termux through `tawc-run`:

| Output path | Elapsed | Steady-state |
|---|---|---|
| CPU `glReadPixels` + AHB copy | 15.25s for 600 | ~39 fps |
| Direct AHB dma-buf render target | 20.9s for 2400 | ~120 fps |

The display was running at 120 Hz (`vsync_period_ns≈8.34ms`). The earlier
~37 fps was the readback path forcing a GPU sync and a 9 MB CPU copy every
frame, not Turnip/Zink client rendering.

## Current limitations

- Output calls `glFinish` before `ASurfaceTransaction`; an EGL fence would
  avoid blocking the compositor thread, but 120 Hz is already reached.
- The AHB dma-buf metadata path depends on `AHardwareBuffer_getNativeHandle`
  being exported by the device's `libnativewindow`; otherwise the readback
  fallback is used.
- `android_wlegl` AHB import uses the QCOM `gmsm` handle marker for the
  modifier. Without that marker the import omits the modifier attribute,
  which is only correct for linear buffers.
- `android_wlegl` AHB import is implemented but was not exercised on device
  in the first bring-up (no rootfs installed). `tawc_gfxstream` AHBs use
  `AHardwareBuffer_getNativeHandle`; if the platform hides it, gfxstream
  clients fail in Mesa mode.
- Mesa init failure is fatal for that compositor run (the log names the
  error) rather than automatically falling back to vendor in-process.
- The Settings row is hidden when `MesaServerSupport.isSupported()` is false.
  A persisted Mesa value falls back to vendor on those devices.

## Verified on 2026-10-26

Device: realme RMX3371 (Adreno, Android 14, `me.phie.tawc.dev`).

- `TAWC_SERVER_RENDERER` default selected Mesa.
- Mesa surfaceless EGL + Zink + Turnip initialised; 87 dmabuf formats.
- `zwp_linux_dmabuf_v1` global created.
- `ASurfaceControl` output rendered the compositor background
  (`#1B1B22`) to the device.
