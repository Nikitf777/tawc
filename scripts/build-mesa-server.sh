#!/bin/bash
# Build the compositor's app-private Mesa GLES stack for Android bionic:
#   - libEGL.so / libGLESv2.so / libgallium-*.so with Zink (surfaceless)
#   - libvulkan_freedreno.so (Turnip, KGSL, Khronos ICD symbols)
#   - libvulkan_tawc.so (minimal loader shim for the Turnip ICD)
#   - libdrm.so (Mesa's HAVE_LIBDRM / EGL_EXT_image_dma_buf_import)
#
# Output: build/mesa-server-aarch64/mesa-server.tar, packed by Gradle into
# assets/mesa-server/arm64-v8a/mesa-server.tar and extracted by
# CompositorService into <filesDir>/mesa-server/.
#
# aarch64 only: the server Mesa path is Adreno/KGSL-gated.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/deps.sh
source "$SCRIPT_DIR/lib/deps.sh"
# shellcheck source=lib/repro.sh
source "$SCRIPT_DIR/lib/repro.sh"

CLEAN=0
for arg in "$@"; do
    case "$arg" in
        --clean) CLEAN=1 ;;
        --abi=aarch64) ;;
        *) echo "ERROR: unknown arg: $arg (only --abi=aarch64 and --clean)" >&2; exit 1 ;;
    esac
done

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
NDK_DIR="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
[ -d "$NDK_DIR" ] || { echo "ERROR: no NDK at $ANDROID_HOME/ndk/*" >&2; exit 1; }
NDK_BIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=29
TRIPLE="aarch64-linux-android"
CC_BIN="$NDK_BIN/${TRIPLE}${API}-clang"
CXX_BIN="$NDK_BIN/${TRIPLE}${API}-clang++"
AR_BIN="$NDK_BIN/llvm-ar"
STRIP_BIN="$NDK_BIN/llvm-strip"
[ -x "$CC_BIN" ] || { echo "ERROR: NDK clang missing at $CC_BIN" >&2; exit 1; }

for tool in meson ninja pkg-config patchelf; do
    command -v "$tool" >/dev/null || { echo "ERROR: '$tool' not on PATH" >&2; exit 1; }
done

MESA_DIR="$(dep_dir mesa)"
LIBDRM_DIR="$(dep_dir libdrm)"
PATCH_DIR="$REPO_DIR/deps/mesa-patches/mesa"
OUT="$REPO_DIR/build/mesa-server-aarch64"
STAGE="$OUT/stage"
PREFIX="$OUT/install"
LIBDRM_PREFIX="$OUT/libdrm-prefix"
SHIMS="$OUT/include-shims"
PC="$OUT/pkgconfig"

if [ "$CLEAN" = "1" ]; then
    echo "==> wiping $OUT and Android Mesa builddirs"
    rm -rf "$OUT" "$MESA_DIR/build-android-server-aarch64" \
        "$MESA_DIR/build-android-turnip-aarch64" \
        "$LIBDRM_DIR/build-android-aarch64"
fi

mkdir -p "$OUT" "$PREFIX" "$LIBDRM_PREFIX" "$SHIMS/cutils" "$SHIMS/log" \
    "$SHIMS/android" "$PC" "$STAGE"

# ── Build-time header shims ──
# Mesa's Android target assumes AOSP's cutils/log/properties/sync headers.
# The app does not ship libcutils/libhardware; tracing and property lookups
# are compiled out, and sync_wait is a local poll wrapper (NDK 27 dropped it
# from android/sync.h).
cat >"$SHIMS/cutils/trace.h" <<'EOF'
#pragma once
#include <stdint.h>
#define ATRACE_TAG_GRAPHICS 0
static inline void atrace_init(void) {}
static inline void atrace_begin(uint64_t tag, const char *name) { (void)tag; (void)name; }
static inline void atrace_end(uint64_t tag) { (void)tag; }
static inline void atrace_async_begin(uint64_t tag, const char *name, int32_t cookie) { (void)tag; (void)name; (void)cookie; }
static inline void atrace_async_end(uint64_t tag, const char *name, int32_t cookie) { (void)tag; (void)name; (void)cookie; }
EOF
cat >"$SHIMS/log/log.h" <<'EOF'
#pragma once
#include <android/log.h>
#ifndef LOG_PRI
#define LOG_PRI(prio, tag, ...) __android_log_print(prio, tag, __VA_ARGS__)
#endif
EOF
cat >"$SHIMS/cutils/properties.h" <<'EOF'
#pragma once
#include <stdint.h>
#include <string.h>
#define PROPERTY_KEY_MAX 32
#define PROPERTY_VALUE_MAX 92
static inline int property_get(const char *key, char *value, const char *default_value) {
   (void)key;
   if (value) {
      if (default_value) strncpy(value, default_value, PROPERTY_VALUE_MAX - 1);
      else value[0] = '\0';
      value[PROPERTY_VALUE_MAX - 1] = '\0';
   }
   return 0;
}
static inline int8_t property_get_bool(const char *key, int8_t default_value) { (void)key; return default_value; }
static inline int64_t property_get_int64(const char *key, int64_t default_value) { (void)key; return default_value; }
static inline int32_t property_get_int32(const char *key, int32_t default_value) { (void)key; return default_value; }
EOF
cat >"$SHIMS/android/sync.h" <<'EOF'
#pragma once
#include_next <android/sync.h>
#include <poll.h>
#include <time.h>
#include <errno.h>
#ifndef TAWC_SYNC_WAIT_DEFINED
#define TAWC_SYNC_WAIT_DEFINED
#ifdef __cplusplus
extern "C" {
#endif
static inline int sync_wait(int fd, int timeout)
{
   struct pollfd fds = { .fd = fd, .events = POLLIN };
   int ret;
   do {
      ret = poll(&fds, 1, timeout);
   } while (ret == -1 && errno == EINTR);
   if (ret > 0 && (fds.revents & (POLLERR | POLLNVAL))) { errno = EINVAL; return -1; }
   if (ret == 0) { errno = ETIME; return -1; }
   return ret > 0 ? 0 : -1;
}
#ifdef __cplusplus
}
#endif
#endif
EOF

cat >"$OUT/cross.txt" <<EOF
[binaries]
c = '$CC_BIN'
cpp = '$CXX_BIN'
ar = '$AR_BIN'
strip = '$STRIP_BIN'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
c_link_args = ['-Wl,-z,max-page-size=16384', '-llog', '-lsync']
cpp_link_args = ['-Wl,-z,max-page-size=16384', '-llog', '-lsync']
EOF

# ── libdrm for Android ──
dep_ensure libdrm
if [ ! -f "$LIBDRM_PREFIX/usr/lib/libdrm.so" ]; then
    rm -rf "$LIBDRM_DIR/build-android-aarch64" "$LIBDRM_PREFIX"
    mkdir -p "$LIBDRM_PREFIX"
    meson setup "$LIBDRM_DIR/build-android-aarch64" "$LIBDRM_DIR" \
        --cross-file "$OUT/cross.txt" --prefix=/usr --libdir=lib \
        -Dtests=false -Dman-pages=disabled -Dvalgrind=disabled \
        -Dradeon=disabled -Damdgpu=disabled -Dnouveau=disabled -Dvmwgfx=disabled \
        -Dintel=disabled -Detnaviv=disabled -Dfreedreno=disabled -Dvc4=disabled \
        -Dinstall-test-programs=false
    ninja -C "$LIBDRM_DIR/build-android-aarch64"
    DESTDIR="$LIBDRM_PREFIX" ninja -C "$LIBDRM_DIR/build-android-aarch64" install
fi

cat >"$PC/libdrm.pc" <<EOF
prefix=$LIBDRM_PREFIX/usr
exec_prefix=\${prefix}
libdir=\${prefix}/lib
includedir=\${prefix}/include

Name: libdrm
Description: userspace interface to kernel DRM services
Version: 2.4.125
Libs: -L\${libdir} -ldrm
Cflags: -I\${includedir} -I\${includedir}/libdrm
EOF

export PKG_CONFIG_LIBDIR="$PC"
export PKG_CONFIG_PATH="$PC"
export CPATH="$SHIMS:$LIBDRM_PREFIX/usr/include:$LIBDRM_PREFIX/usr/include/libdrm:$REPO_DIR/deps/android-headers"

# ── Mesa (surfaceless EGL/GLES + Zink) ──
dep_apply_patches mesa "$PATCH_DIR"
SERVER_BUILD="$MESA_DIR/build-android-server-aarch64"
if [ ! -f "$SERVER_BUILD/build.ninja" ]; then
    rm -rf "$SERVER_BUILD"
    meson setup "$SERVER_BUILD" "$MESA_DIR" \
        --cross-file "$OUT/cross.txt" --prefix=/usr --libdir=lib \
        -Dplatforms= -Dgallium-drivers=zink -Dvulkan-drivers= \
        -Dgles1=disabled -Dgles2=enabled -Dopengl=true -Degl=enabled \
        -Dglx=disabled -Dgbm=disabled -Dshared-glapi=enabled -Dglvnd=disabled \
        -Dllvm=disabled -Dlmsensors=disabled -Dvalgrind=disabled -Dlibunwind=disabled \
        -Dxmlconfig=disabled -Ddisplay-info=disabled -Dvideo-codecs= \
        -Dplatform-sdk-version=$API -Dandroid-stub=false \
        -Dbuildtype=release -Ddefault_library=shared
fi
ninja -C "$SERVER_BUILD"
rm -rf "$PREFIX"
DESTDIR="$PREFIX" ninja -C "$SERVER_BUILD" install

# ── Turnip (KGSL, private Khronos ICD) ──
TURNIP_BUILD="$MESA_DIR/build-android-turnip-aarch64"
if [ ! -f "$TURNIP_BUILD/build.ninja" ]; then
    rm -rf "$TURNIP_BUILD"
    meson setup "$TURNIP_BUILD" "$MESA_DIR" \
        --cross-file "$OUT/cross.txt" --prefix=/usr --libdir=lib \
        -Dplatforms= -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl -Dgallium-drivers= \
        -Dgles1=disabled -Dgles2=disabled -Dopengl=false -Degl=disabled -Dglx=disabled -Dgbm=disabled \
        -Dllvm=disabled -Dlmsensors=disabled -Dvalgrind=disabled -Dlibunwind=disabled \
        -Dxmlconfig=disabled -Ddisplay-info=disabled -Dvideo-codecs= \
        -Dplatform-sdk-version=$API -Dandroid-stub=false \
        -Dbuildtype=release -Ddefault_library=shared
fi
ninja -C "$TURNIP_BUILD" src/freedreno/vulkan/libvulkan_freedreno.so

# ── Stage ──
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -L "$PREFIX/usr/lib/libEGL.so" "$STAGE/libEGL.so"
cp -L "$PREFIX/usr/lib/libGLESv2.so" "$STAGE/libGLESv2.so"
cp -L "$PREFIX"/usr/lib/libgallium-*.so "$STAGE/"
cp -L "$LIBDRM_PREFIX/usr/lib/libdrm.so" "$STAGE/libdrm.so"
cp -L "$TURNIP_BUILD/src/freedreno/vulkan/libvulkan_freedreno.so" "$STAGE/libvulkan_freedreno.so"
LIBCPP="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${TRIPLE}/libc++_shared.so"
cp -L "$LIBCPP" "$STAGE/libc++_shared.so"

# Minimal loader shim. Zink dlopens this by absolute path
# (TAWC_VULKAN_LIB); it forwards to Turnip's vk_icdGetInstanceProcAddr.
"$CC_BIN" -shared -fPIC -std=c11 -O2 \
    -I"$MESA_DIR/include" \
    -Wl,-soname,libvulkan_tawc.so \
    "$REPO_DIR/compositor/native/vulkan_shim.c" \
    -o "$STAGE/libvulkan_tawc.so" -ldl -llog

# Every private lib finds its siblings through $ORIGIN, so the system
# loader search path (and the system libEGL/libGLESv2) stays untouched.
for so in "$STAGE"/*.so; do
    patchelf --set-rpath '$ORIGIN' "$so"
done

# Zink's driver is linked directly into libgallium by this Mesa config;
# no separate dri/ tree is needed.

repro_tar -C "$STAGE" -cf "$OUT/mesa-server.tar" .

echo "==> Mesa server stack staged:"
tar tf "$OUT/mesa-server.tar" | sort
echo "==> $OUT/mesa-server.tar"
