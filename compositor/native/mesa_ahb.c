/*
 * AHardwareBuffer + ASurfaceControl helpers for the Mesa server renderer.
 *
 * Output path: Mesa renders into a GL texture, we copy the pixels into an
 * AHardwareBuffer (CPU readback) and hand that AHB to SurfaceFlinger via an
 * ASurfaceControl transaction. This deliberately avoids AHB->dma-buf
 * metadata guessing for our own output buffers; input AHB metadata comes
 * from android_wlegl's wire handle instead.
 */

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "tawc-native"
#define LOGE(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define TAWC_DRM_FORMAT_MOD_INVALID UINT64_MAX
#define TAWC_DRM_FORMAT_MOD_QCOM_COMPRESSED 0x0500000000000001ULL

/* surface_control.h uses C++ references in a C header (ASurfaceControl
 * setGeometry/setCrop), so declare the small C ABI subset we need. */
typedef struct ASurfaceControl ASurfaceControl;
typedef struct ASurfaceTransaction ASurfaceTransaction;
typedef struct ASurfaceTransactionStats ASurfaceTransactionStats;
typedef void (*ASurfaceTransaction_OnComplete)(void *, ASurfaceTransactionStats *);
typedef struct ANativeWindow ANativeWindow;

ASurfaceControl *ASurfaceControl_createFromWindow(ANativeWindow *parent, const char *name);
void ASurfaceControl_release(ASurfaceControl *surface_control);
ASurfaceTransaction *ASurfaceTransaction_create(void);
void ASurfaceTransaction_delete(ASurfaceTransaction *transaction);
void ASurfaceTransaction_setBuffer(ASurfaceTransaction *transaction,
                                   ASurfaceControl *surface_control,
                                   AHardwareBuffer *buffer, int acquire_fence_fd);
void ASurfaceTransaction_setBufferDataSpace(ASurfaceTransaction *transaction,
                                            ASurfaceControl *surface_control,
                                            int32_t data_space);
void ASurfaceTransaction_setBufferTransparency(ASurfaceTransaction *transaction,
                                               ASurfaceControl *surface_control,
                                               int8_t transparency);
void ASurfaceTransaction_setOnComplete(ASurfaceTransaction *transaction,
                                       void *context,
                                       ASurfaceTransaction_OnComplete func);
void ASurfaceTransaction_apply(ASurfaceTransaction *transaction);

#define TAWC_ADATASPACE_SRGB 142671872
#define TAWC_ASURFACE_TRANSPARENCY_OPAQUE 2

struct tawc_native_handle {
    int version;
    int numFds;
    int numInts;
    int data[0];
};

typedef const struct tawc_native_handle *(*fn_get_native_handle)(const AHardwareBuffer *);

static fn_get_native_handle g_get_native_handle;
static int g_loaded;

static void
load_symbols(void)
{
    if (g_loaded)
        return;
    g_loaded = 1;
    void *lib = dlopen("libnativewindow.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib)
        return;
    *(void **)(&g_get_native_handle) = dlsym(lib, "AHardwareBuffer_getNativeHandle");
}

static int
format_bpp(uint32_t format)
{
    switch (format) {
    case AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM:
    case AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM:
    case AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM:
        return 4;
    case AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM:
        return 2;
    case AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT:
        return 8;
    default:
        return 0;
    }
}

static uint32_t
format_fourcc(uint32_t format)
{
    switch (format) {
    case AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM:
        return 0x34324241; /* DRM_FORMAT_ABGR8888 */
    case AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM:
        return 0x34324258; /* DRM_FORMAT_XBGR8888 */
    case AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM:
        return 0x36314752; /* DRM_FORMAT_RGB565 */
    case AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM:
        return 0x30334241; /* DRM_FORMAT_ABGR2101010 */
    case AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT:
        return 0x48344241; /* DRM_FORMAT_ABGR16161616F */
    default:
        return 0;
    }
}

struct tawc_ahb_dmabuf {
    int fd;
    uint32_t fourcc;
    uint32_t stride;
    uint64_t modifier;
    uint32_t width;
    uint32_t height;
};

/* Returns 0 and fills out on success. The fd is a dup owned by the caller. */
int
tawc_ahb_dmabuf_info(AHardwareBuffer *ahb, struct tawc_ahb_dmabuf *out)
{
    if (!ahb || !out)
        return -EINVAL;

    load_symbols();
    if (!g_get_native_handle) {
        LOGE("AHB dmabuf: libnativewindow has no AHardwareBuffer_getNativeHandle");
        return -ENOSYS;
    }

    const struct tawc_native_handle *h = g_get_native_handle(ahb);
    if (!h) {
        LOGE("AHB dmabuf: getNativeHandle returned null");
        return -EINVAL;
    }
    if (h->numFds < 1) {
        LOGE("AHB dmabuf: handle numFds=%d numInts=%d", h->numFds, h->numInts);
        return -EINVAL;
    }

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(ahb, &desc);

    int bpp = format_bpp(desc.format);
    uint32_t fourcc = format_fourcc(desc.format);
    if (!bpp || !fourcc) {
        LOGE("AHB dmabuf: unsupported format=0x%x stride=%u", desc.format, desc.stride);
        return -EINVAL;
    }

    uint32_t stride_bytes = desc.stride ? desc.stride * bpp : desc.width * bpp;
    if (!stride_bytes) {
        LOGE("AHB dmabuf: zero stride");
        return -EINVAL;
    }

    uint64_t modifier = TAWC_DRM_FORMAT_MOD_INVALID;
    if (h->numInts >= 2) {
        const int32_t *ints = &h->data[h->numFds];
        /* QCOM gralloc's legacy private_handle_t marker. Modern gralloc4
         * AHB handles still carry it on the devices TAWC targets. */
        if (ints[0] == (int32_t)0x6d736d67) { /* 'gmsm' little endian */
            modifier = (ints[1] & 0x08000000)
                ? TAWC_DRM_FORMAT_MOD_QCOM_COMPRESSED
                : 0; /* DRM_FORMAT_MOD_LINEAR */
        }
    }

    int fd = dup(h->data[0]);
    if (fd < 0) {
        LOGE("AHB dmabuf: dup failed: %d", errno);
        return -errno;
    }

    out->fd = fd;
    out->fourcc = fourcc;
    out->stride = stride_bytes;
    out->modifier = modifier;
    out->width = desc.width;
    out->height = desc.height;
    return 0;
}

AHardwareBuffer *
tawc_ahb_alloc(uint32_t width, uint32_t height)
{
    AHardwareBuffer_Desc desc = {
        .width = width,
        .height = height,
        .layers = 1,
        .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
                 AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
                 AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
        .stride = 0,
        .rfu0 = 0,
        .rfu1 = 0,
    };
    AHardwareBuffer *ahb = NULL;
    if (AHardwareBuffer_allocate(&desc, &ahb) != 0)
        return NULL;
    return ahb;
}

void
tawc_ahb_release(AHardwareBuffer *ahb)
{
    if (ahb)
        AHardwareBuffer_release(ahb);
}

/* Copy tightly packed RGBA8 rows into the AHB, flipping vertically so the
 * GL bottom-left origin becomes Android's top-left. */
int
tawc_ahb_write_rgba(AHardwareBuffer *ahb, const void *pixels,
                    uint32_t width, uint32_t height)
{
    if (!ahb || !pixels || !width || !height)
        return -EINVAL;

    void *dst = NULL;
    int32_t bpp = 0;
    int32_t stride = 0;
    int rc = AHardwareBuffer_lockAndGetInfo(
        ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL,
        &dst, &bpp, &stride);
    if (rc != 0)
        return rc;
    if (!dst || bpp != 4 || stride < (int32_t)(width * 4)) {
        int fence = -1;
        AHardwareBuffer_unlock(ahb, &fence);
        if (fence >= 0)
            close(fence);
        return -EINVAL;
    }

    const uint8_t *src = pixels;
    uint8_t *dst_bytes = dst;
    for (uint32_t y = 0; y < height; y++) {
        memcpy(dst_bytes + (size_t)(height - 1 - y) * stride,
               src + (size_t)y * width * 4, (size_t)width * 4);
    }

    int fence = -1;
    rc = AHardwareBuffer_unlock(ahb, &fence);
    if (fence >= 0)
        close(fence);
    return rc;
}

void *
tawc_asurface_create(void *window)
{
    if (!window)
        return NULL;
    return ASurfaceControl_createFromWindow((ANativeWindow *)window,
                                            "tawc-mesa-output");
}

void
tawc_asurface_release(void *control)
{
    if (control)
        ASurfaceControl_release((ASurfaceControl *)control);
}

int
tawc_asurface_present(void *control, AHardwareBuffer *ahb,
                      ASurfaceTransaction_OnComplete on_complete, void *context)
{
    if (!control || !ahb)
        return -EINVAL;

    ASurfaceTransaction *tx = ASurfaceTransaction_create();
    if (!tx)
        return -ENOMEM;

    ASurfaceTransaction_setBuffer(tx, (ASurfaceControl *)control, ahb, -1);
    ASurfaceTransaction_setBufferDataSpace(tx, (ASurfaceControl *)control,
                                           TAWC_ADATASPACE_SRGB);
    ASurfaceTransaction_setBufferTransparency(
        tx, (ASurfaceControl *)control,
        TAWC_ASURFACE_TRANSPARENCY_OPAQUE);
    if (on_complete)
        ASurfaceTransaction_setOnComplete(tx, context, on_complete);
    ASurfaceTransaction_apply(tx);
    ASurfaceTransaction_delete(tx);
    return 0;
}
