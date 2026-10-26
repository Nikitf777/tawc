//! Mesa GLES server-renderer helpers.
//!
//! The Mesa path renders offscreen with Zink and presents through
//! `ASurfaceControl`. Input `android_wlegl` buffers are converted to dma-buf
//! fds using the wire handle metadata and imported with
//! `EGL_EXT_image_dma_buf_import`.

use std::ffi::c_void;
use std::os::fd::RawFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use log::{info, warn};
use smithay::backend::allocator::Fourcc;
use smithay::backend::egl::ffi as egl_ffi;
use smithay::backend::renderer::gles::ffi as gl_ffi;
use smithay::backend::renderer::gles::{GlesRenderer, GlesTexture};
use smithay::backend::renderer::Offscreen;
use smithay::utils::{Buffer as BufferCoord, Size};

use crate::gl_import::GlFuncs;

const EGL_LINUX_DMA_BUF_EXT: u32 = 0x3270;
const EGL_LINUX_DRM_FOURCC_EXT: i32 = 0x3271;
const EGL_DMA_BUF_PLANE0_FD_EXT: i32 = 0x3272;
const EGL_DMA_BUF_PLANE0_OFFSET_EXT: i32 = 0x3273;
const EGL_DMA_BUF_PLANE0_PITCH_EXT: i32 = 0x3274;
const EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT: i32 = 0x3443;
const EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT: i32 = 0x3444;
const EGL_IMAGE_PRESERVED_KHR: i32 = 0x30D2;
const TAWC_DRM_FORMAT_MOD_INVALID: u64 = u64::MAX;

/// Metadata needed to import one dma-buf plane.
#[derive(Clone, Copy, Debug)]
pub struct DmaBufInfo {
    /// Borrowed for the duration of import; owned by the buffer user-data.
    pub fd: RawFd,
    pub fourcc: u32,
    pub stride: u32,
    pub modifier: u64,
    pub width: u32,
    pub height: u32,
}

/// Import an AHB-derived dma-buf as a GL texture. The EGL context must be
/// current (or make_current must succeed) before the caller uses the texture.
pub fn import_dmabuf_texture(
    renderer: &mut GlesRenderer,
    info: &DmaBufInfo,
) -> Result<GlesTexture, String> {
    unsafe {
        renderer
            .egl_context()
            .make_current()
            .map_err(|e| format!("make_current: {:?}", e))?;
    }

    let gl = GlFuncs::load(renderer)?;
    let display = renderer.egl_context().display().get_display_handle();

    let mut attribs: Vec<i32> = vec![
        egl_ffi::egl::WIDTH as i32,
        info.width as i32,
        egl_ffi::egl::HEIGHT as i32,
        info.height as i32,
        EGL_LINUX_DRM_FOURCC_EXT,
        info.fourcc as i32,
        EGL_DMA_BUF_PLANE0_FD_EXT,
        info.fd,
        EGL_DMA_BUF_PLANE0_OFFSET_EXT,
        0,
        EGL_DMA_BUF_PLANE0_PITCH_EXT,
        info.stride as i32,
    ];
    if info.modifier != TAWC_DRM_FORMAT_MOD_INVALID {
        attribs.push(EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT);
        attribs.push(info.modifier as u32 as i32);
        attribs.push(EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT);
        attribs.push((info.modifier >> 32) as u32 as i32);
    }
    attribs.push(EGL_IMAGE_PRESERVED_KHR);
    attribs.push(egl_ffi::egl::TRUE as i32);
    attribs.push(egl_ffi::egl::NONE as i32);

    let image = unsafe {
        egl_ffi::egl::CreateImageKHR(
            **display,
            egl_ffi::egl::NO_CONTEXT,
            EGL_LINUX_DMA_BUF_EXT,
            std::ptr::null(),
            attribs.as_ptr(),
        )
    };
    if image.is_null() {
        let err = unsafe { egl_ffi::egl::GetError() };
        return Err(format!(
            "eglCreateImageKHR(dma-buf {}x{} fourcc=0x{:x} stride={} modifier=0x{:x}) failed: EGL error 0x{:x}",
            info.width, info.height, info.fourcc, info.stride, info.modifier, err
        ));
    }

    let mut tex: u32 = 0;
    unsafe {
        (gl.gen_textures)(1, &mut tex);
        if tex == 0 {
            egl_ffi::egl::DestroyImageKHR(**display, image);
            return Err("glGenTextures failed".into());
        }
        (gl.bind_texture)(gl_ffi::TEXTURE_2D, tex);
        (gl.egl_image_target_texture_2d)(gl_ffi::TEXTURE_2D, image);
    }

    let gl_err = unsafe { (gl.get_error)() };
    unsafe {
        egl_ffi::egl::DestroyImageKHR(**display, image);
    }
    if gl_err != 0 {
        unsafe { (gl.delete_textures)(1, &tex) };
        return Err(format!(
            "glEGLImageTargetTexture2DOES failed: GL error 0x{:x}",
            gl_err
        ));
    }

    unsafe {
        (gl.tex_parameteri)(gl_ffi::TEXTURE_2D, gl_ffi::TEXTURE_MIN_FILTER, gl_ffi::LINEAR as i32);
        (gl.tex_parameteri)(gl_ffi::TEXTURE_2D, gl_ffi::TEXTURE_MAG_FILTER, gl_ffi::LINEAR as i32);
        (gl.bind_texture)(gl_ffi::TEXTURE_2D, 0);
    }

    let size = Size::<i32, BufferCoord>::from((info.width as i32, info.height as i32));
    let texture = unsafe {
        GlesTexture::from_raw_with_flags(renderer, Some(gl_ffi::RGBA8), true, false, false, tex, size)
    };
    Ok(texture)
}

// ---------------------------------------------------------------------------
// FFI: native/mesa_ahb.c
// ---------------------------------------------------------------------------

#[repr(C)]
struct AhbDmabufInfo {
    fd: i32,
    fourcc: u32,
    stride: u32,
    modifier: u64,
    width: u32,
    height: u32,
}

/// Query the dma-buf view of an AHB. Used by the gfxstream path, where the
/// compositor did not receive a native_handle_t over Wayland.
pub fn ahb_dmabuf_info(ahb: *mut ndk_sys::AHardwareBuffer) -> Option<DmaBufInfo> {
    let mut raw = AhbDmabufInfo {
        fd: -1,
        fourcc: 0,
        stride: 0,
        modifier: 0,
        width: 0,
        height: 0,
    };
    let rc = unsafe { tawc_ahb_dmabuf_info(ahb, &mut raw) };
    if rc != 0 || raw.fd < 0 || raw.fourcc == 0 || raw.stride == 0 {
        return None;
    }
    Some(DmaBufInfo {
        fd: raw.fd,
        fourcc: raw.fourcc,
        stride: raw.stride,
        modifier: raw.modifier,
        width: raw.width,
        height: raw.height,
    })
}

unsafe extern "C" {
    fn tawc_ahb_dmabuf_info(
        ahb: *mut ndk_sys::AHardwareBuffer,
        out: *mut AhbDmabufInfo,
    ) -> i32;
    fn tawc_ahb_alloc(width: u32, height: u32) -> *mut ndk_sys::AHardwareBuffer;
    fn tawc_ahb_release(ahb: *mut ndk_sys::AHardwareBuffer);
    fn tawc_ahb_write_rgba(
        ahb: *mut ndk_sys::AHardwareBuffer,
        pixels: *const c_void,
        width: u32,
        height: u32,
    ) -> i32;
    fn tawc_asurface_create(window: *mut c_void) -> *mut c_void;
    fn tawc_asurface_release(control: *mut c_void);
    fn tawc_asurface_present(
        control: *mut c_void,
        ahb: *mut ndk_sys::AHardwareBuffer,
        on_complete: Option<unsafe extern "C" fn(*mut c_void, *mut c_void)>,
        context: *mut c_void,
    ) -> i32;
}

struct PresentCtx {
    free: Arc<AtomicBool>,
}

unsafe extern "C" fn present_complete(context: *mut c_void, _stats: *mut c_void) {
    if context.is_null() {
        return;
    }
    let ctx = Box::from_raw(context as *mut PresentCtx);
    ctx.free.store(false, Ordering::Release);
}

// ---------------------------------------------------------------------------
// Per-host output
// ---------------------------------------------------------------------------

pub struct MesaTarget {
    pub texture: GlesTexture,
    ahb: *mut ndk_sys::AHardwareBuffer,
    in_flight: Arc<AtomicBool>,
}

impl Drop for MesaTarget {
    fn drop(&mut self) {
        unsafe { tawc_ahb_release(self.ahb) };
    }
}

/// Output state for one Android host window: an ASurfaceControl plus two
/// AHB ping-pong buffers.
pub struct MesaPresent {
    control: *mut c_void,
    targets: Vec<MesaTarget>,
    size: (i32, i32),
}

impl MesaPresent {
    pub fn new(window: *mut c_void) -> Option<Self> {
        let control = unsafe { tawc_asurface_create(window) };
        if control.is_null() {
            warn!("Mesa: ASurfaceControl_createFromWindow failed");
            return None;
        }
        Some(Self {
            control,
            targets: Vec::new(),
            size: (0, 0),
        })
    }

    pub fn is_ready(&self) -> bool {
        !self.control.is_null()
    }

    pub fn ensure_size(&mut self, renderer: &mut GlesRenderer, width: i32, height: i32) -> Result<(), String> {
        if self.size == (width, height) && self.targets.len() == 2 {
            return Ok(());
        }
        self.targets.clear();
        self.size = (width, height);

        for _ in 0..2 {
            let ahb = unsafe { tawc_ahb_alloc(width as u32, height as u32) };
            if ahb.is_null() {
                return Err(format!(
                    "AHardwareBuffer_allocate {}x{} failed",
                    width, height
                ));
            }
            let texture = match renderer.create_buffer(
                Fourcc::Abgr8888,
                Size::<i32, BufferCoord>::from((width, height)),
            ) {
                Ok(texture) => texture,
                Err(e) => {
                    unsafe { tawc_ahb_release(ahb) };
                    return Err(format!("create_buffer {}x{}: {:?}", width, height, e));
                }
            };
            self.targets.push(MesaTarget {
                texture,
                ahb,
                in_flight: Arc::new(AtomicBool::new(false)),
            });
        }
        info!("Mesa output targets ready: {}x{}", width, height);
        Ok(())
    }

    pub fn acquire(&self) -> Option<usize> {
        self.targets
            .iter()
            .position(|t| !t.in_flight.load(Ordering::Acquire))
    }

    pub fn target_mut(&mut self, index: usize) -> Option<&mut MesaTarget> {
        self.targets.get_mut(index)
    }

    /// Copy the GL framebuffer into the target AHB and hand it to
    /// SurfaceFlinger. The caller must have read the pixels into `pixels`
    /// (tightly packed RGBA8, top row first).
    pub fn present(&mut self, index: usize, pixels: &[u8], width: i32, height: i32) -> Result<(), String> {
        let target = self
            .targets
            .get_mut(index)
            .ok_or_else(|| "bad Mesa target index".to_string())?;
        let expected = width as usize * height as usize * 4;
        if pixels.len() != expected {
            return Err(format!(
                "readback size {} != expected {}",
                pixels.len(),
                expected
            ));
        }

        let rc = unsafe {
            tawc_ahb_write_rgba(target.ahb, pixels.as_ptr() as *const c_void, width as u32, height as u32)
        };
        if rc != 0 {
            return Err(format!("AHardwareBuffer write failed: {}", rc));
        }

        target.in_flight.store(true, Ordering::Release);
        let ctx = Box::into_raw(Box::new(PresentCtx {
            free: target.in_flight.clone(),
        }));
        let rc = unsafe {
            tawc_asurface_present(self.control, target.ahb, Some(present_complete), ctx as *mut c_void)
        };
        if rc != 0 {
            target.in_flight.store(false, Ordering::Release);
            drop(unsafe { Box::from_raw(ctx) });
            return Err(format!("ASurfaceTransaction_setBuffer failed: {}", rc));
        }
        Ok(())
    }
}

impl Drop for MesaPresent {
    fn drop(&mut self) {
        self.targets.clear();
        if !self.control.is_null() {
            unsafe { tawc_asurface_release(self.control) };
            self.control = std::ptr::null_mut();
        }
    }
}

/// Read the currently bound framebuffer into a tightly packed RGBA8 buffer,
/// flipping vertically for Android's top-left origin.
pub fn read_pixels(
    renderer: &mut GlesRenderer,
    width: i32,
    height: i32,
) -> Result<Vec<u8>, String> {
    let gl = GlFuncs::load(renderer)?;
    let mut pixels = vec![0u8; width as usize * height as usize * 4];
    unsafe {
        renderer
            .egl_context()
            .make_current()
            .map_err(|e| format!("make_current: {:?}", e))?;
        (gl.pixel_storei)(gl_ffi::PACK_ALIGNMENT, 4);
        (gl.read_pixels)(
            0,
            0,
            width,
            height,
            gl_ffi::RGBA,
            gl_ffi::UNSIGNED_BYTE,
            pixels.as_mut_ptr() as *mut c_void,
        );
    }
    let gl_err = unsafe { (gl.get_error)() };
    if gl_err != 0 {
        return Err(format!("glReadPixels failed: GL error 0x{:x}", gl_err));
    }
    // GL returns bottom-up rows; flip in place.
    let row = width as usize * 4;
    for y in 0..(height as usize / 2) {
        let top = y * row;
        let bottom = (height as usize - 1 - y) * row;
        for i in 0..row {
            pixels.swap(top + i, bottom + i);
        }
    }
    Ok(pixels)
}
