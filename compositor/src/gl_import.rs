//! GL/EGL extension loading and texture import.
//!
//! Primary purpose: import AHardwareBuffers as GlesTextures via
//! eglGetNativeClientBufferANDROID + eglCreateImageKHR + glEGLImageTargetTexture2DOES.

use std::ffi::{c_void, CString};
use log::error;

use smithay::backend::egl::ffi as egl_ffi;
use smithay::backend::renderer::gles::GlesRenderer;
use smithay::backend::renderer::gles::GlesTexture;
use smithay::utils::Size;

// EGL constants for AHB import (not in Smithay's generated bindings)
const EGL_NATIVE_BUFFER_ANDROID: u32 = 0x3140;
const EGL_IMAGE_PRESERVED_KHR: i32 = 0x30D2;

// GL constants
const GL_TEXTURE_EXTERNAL_OES: u32 = 0x8D65;
const GL_TEXTURE_MIN_FILTER: u32 = 0x2801;
const GL_TEXTURE_MAG_FILTER: u32 = 0x2800;
const GL_LINEAR: i32 = 0x2601;

// Function pointer types
type FnEglGetNativeClientBufferANDROID = unsafe extern "C" fn(*const c_void) -> *const c_void;
type FnGlGenTextures = unsafe extern "C" fn(i32, *mut u32);
type FnGlDeleteTextures = unsafe extern "C" fn(i32, *const u32);
type FnGlBindTexture = unsafe extern "C" fn(u32, u32);
type FnGlTexParameteri = unsafe extern "C" fn(u32, u32, i32);
type FnGlEGLImageTargetTexture2DOES = unsafe extern "C" fn(u32, *const c_void);
type FnGlGetError = unsafe extern "C" fn() -> u32;
type FnGlReadPixels = unsafe extern "C" fn(i32, i32, i32, i32, u32, u32, *mut c_void);
type FnGlPixelStorei = unsafe extern "C" fn(u32, i32);

/// GL entry points needed by the Mesa dmabuf/readback paths. Loaded from
/// `TAWC_GLES_LIB` (the private Mesa libGLESv2) when set, otherwise the
/// system libGLESv2. Leaks the library handle: the context lives for the
/// life of the compositor run.
#[derive(Clone, Copy)]
pub struct GlFuncs {
    pub gen_textures: FnGlGenTextures,
    pub delete_textures: FnGlDeleteTextures,
    pub bind_texture: FnGlBindTexture,
    pub tex_parameteri: FnGlTexParameteri,
    pub egl_image_target_texture_2d: FnGlEGLImageTargetTexture2DOES,
    pub get_error: FnGlGetError,
    pub read_pixels: FnGlReadPixels,
    pub pixel_storei: FnGlPixelStorei,
    pub finish: unsafe extern "C" fn(),
}

impl GlFuncs {
    pub fn load(_renderer: &GlesRenderer) -> Result<Self, String> {
        static FUNCS: std::sync::OnceLock<Result<GlFuncs, String>> = std::sync::OnceLock::new();
        FUNCS
            .get_or_init(|| Self::load_uncached())
            .clone()
    }

    fn load_uncached() -> Result<Self, String> {
        unsafe {
            let lib_name = std::env::var("TAWC_GLES_LIB")
                .unwrap_or_else(|_| "libGLESv2.so".to_string());
            let gles_lib = libloading::Library::new(&lib_name)
                .map_err(|e| format!("Failed to load {}: {}", lib_name, e))?;

            let load_gl = |name: &[u8]| -> Result<*const c_void, String> {
                let sym: libloading::Symbol<*const c_void> = gles_lib
                    .get(name)
                    .map_err(|e| format!("{}: {}", String::from_utf8_lossy(&name[..name.len() - 1]), e))?;
                let ptr = *sym;
                if ptr.is_null() {
                    Err(format!(
                        "{} is null",
                        String::from_utf8_lossy(&name[..name.len() - 1])
                    ))
                } else {
                    Ok(ptr)
                }
            };

            let load_egl = |name: &str| -> Result<*const c_void, String> {
                let cname = CString::new(name).unwrap();
                let ptr = egl_ffi::egl::GetProcAddress(cname.as_ptr()) as *const c_void;
                if ptr.is_null() {
                    Err(format!("{} not available", name))
                } else {
                    Ok(ptr)
                }
            };

            let egl_image_target_texture_2d = load_gl(b"glEGLImageTargetTexture2DOES\0")
                .or_else(|_| load_egl("glEGLImageTargetTexture2DOES"))?;

            let funcs = Self {
                gen_textures: std::mem::transmute(load_gl(b"glGenTextures\0")?),
                delete_textures: std::mem::transmute(load_gl(b"glDeleteTextures\0")?),
                bind_texture: std::mem::transmute(load_gl(b"glBindTexture\0")?),
                tex_parameteri: std::mem::transmute(load_gl(b"glTexParameteri\0")?),
                egl_image_target_texture_2d: std::mem::transmute(egl_image_target_texture_2d),
                get_error: std::mem::transmute(load_gl(b"glGetError\0")?),
                read_pixels: std::mem::transmute(load_gl(b"glReadPixels\0")?),
                pixel_storei: std::mem::transmute(load_gl(b"glPixelStorei\0")?),
                finish: std::mem::transmute(load_gl(b"glFinish\0")?),
            };
            std::mem::forget(gles_lib);
            Ok(funcs)
        }
    }
}

/// Loaded EGL/GL extension function pointers for texture import and creation.
#[derive(Clone, Copy)]
pub struct AhbTextureImporter {
    egl_get_native_client_buffer: FnEglGetNativeClientBufferANDROID,
    gl_gen_textures: FnGlGenTextures,
    gl_bind_texture: FnGlBindTexture,
    gl_tex_parameteri: FnGlTexParameteri,
    gl_egl_image_target_texture_2d_oes: FnGlEGLImageTargetTexture2DOES,
    gl_get_error: FnGlGetError,
}

impl AhbTextureImporter {
    /// Load all required EGL/GL extension function pointers.
    /// Must be called after EGL is initialized.
    pub fn new() -> Result<Self, String> {
        unsafe {
            // Load GL core functions from libGLESv2.so (eglGetProcAddress may
            // return stubs for core functions on some Android drivers)
            let gles_lib = libloading::Library::new("libGLESv2.so")
                .map_err(|e| format!("Failed to load libGLESv2.so: {}", e))?;

            let load_gl = |lib: &libloading::Library, name: &[u8]| -> Result<*const c_void, String> {
                let sym: libloading::Symbol<*const c_void> = lib.get(name)
                    .map_err(|e| format!("{}: {}", String::from_utf8_lossy(&name[..name.len()-1]), e))?;
                let ptr = *sym;
                if ptr.is_null() {
                    Err(format!("{} is null", String::from_utf8_lossy(&name[..name.len()-1])))
                } else {
                    Ok(ptr)
                }
            };

            let load_egl = |name: &str| -> Result<*const c_void, String> {
                let cname = CString::new(name).unwrap();
                let ptr = egl_ffi::egl::GetProcAddress(cname.as_ptr()) as *const c_void;
                if ptr.is_null() {
                    Err(format!("{} not available", name))
                } else {
                    Ok(ptr)
                }
            };

            let importer = Self {
                egl_get_native_client_buffer: std::mem::transmute::<*const c_void, FnEglGetNativeClientBufferANDROID>(
                    load_egl("eglGetNativeClientBufferANDROID")?
                ),
                gl_gen_textures: std::mem::transmute::<*const c_void, FnGlGenTextures>(load_gl(&gles_lib, b"glGenTextures\0")?),
                gl_bind_texture: std::mem::transmute::<*const c_void, FnGlBindTexture>(load_gl(&gles_lib, b"glBindTexture\0")?),
                gl_tex_parameteri: std::mem::transmute::<*const c_void, FnGlTexParameteri>(load_gl(&gles_lib, b"glTexParameteri\0")?),
                gl_egl_image_target_texture_2d_oes: std::mem::transmute::<*const c_void, FnGlEGLImageTargetTexture2DOES>(
                    load_egl("glEGLImageTargetTexture2DOES")?
                ),
                gl_get_error: std::mem::transmute::<*const c_void, FnGlGetError>(load_gl(&gles_lib, b"glGetError\0")?),
            };

            // Leak the library handle to keep it loaded (it's already loaded by the process anyway)
            std::mem::forget(gles_lib);

            Ok(importer)
        }
    }

    /// Import an AHardwareBuffer as a GlesTexture.
    ///
    /// The EGL context must be current on the calling thread.
    /// `raw_display` is the raw EGLDisplay pointer.
    pub fn import_ahb(
        &self,
        renderer: &GlesRenderer,
        raw_display: *const c_void,
        ahb: *mut ndk_sys::AHardwareBuffer,
        width: i32,
        height: i32,
    ) -> Result<GlesTexture, String> {
        unsafe {
            let client_buffer = (self.egl_get_native_client_buffer)(ahb as *const c_void);
            if client_buffer.is_null() {
                return Err("eglGetNativeClientBufferANDROID returned null".into());
            }
            self.import_client_buffer(renderer, raw_display, client_buffer, width, height)
        }
    }

    /// Import an EGLClientBuffer (typically an `ANativeWindowBuffer*`) as a
    /// GlesTexture via `eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID, ...)`.
    ///
    /// The EGL context must be current on the calling thread.
    pub unsafe fn import_client_buffer(
        &self,
        renderer: &GlesRenderer,
        raw_display: *const c_void,
        client_buffer: *const c_void,
        width: i32,
        height: i32,
    ) -> Result<GlesTexture, String> {
        // Clear any prior GL errors
        while (self.gl_get_error)() != 0 {}

        let attribs: [i32; 3] = [
            EGL_IMAGE_PRESERVED_KHR, egl_ffi::egl::TRUE as i32,
            egl_ffi::egl::NONE as i32,
        ];
        let egl_image = egl_ffi::egl::CreateImageKHR(
            raw_display,
            egl_ffi::egl::NO_CONTEXT,
            EGL_NATIVE_BUFFER_ANDROID,
            client_buffer,
            attribs.as_ptr(),
        );
        if egl_image.is_null() {
            let err = egl_ffi::egl::GetError();
            return Err(format!("eglCreateImageKHR failed, EGL error: 0x{:X}", err));
        }

        let mut tex: u32 = 0;
        (self.gl_gen_textures)(1, &mut tex);
        if tex == 0 {
            egl_ffi::egl::DestroyImageKHR(raw_display, egl_image);
            return Err("glGenTextures failed".into());
        }

        (self.gl_bind_texture)(GL_TEXTURE_EXTERNAL_OES, tex);
        (self.gl_egl_image_target_texture_2d_oes)(GL_TEXTURE_EXTERNAL_OES, egl_image);

        let gl_err = (self.gl_get_error)();
        if gl_err != 0 {
            egl_ffi::egl::DestroyImageKHR(raw_display, egl_image);
            error!("GL error after EGLImageTargetTexture2DOES: 0x{:X}", gl_err);
            return Err(format!("glEGLImageTargetTexture2DOES failed: GL error 0x{:X}", gl_err));
        }

        // The GL texture holds its own reference to the underlying buffer.
        // Destroy the intermediate EGLImage to avoid leaking one per import.
        egl_ffi::egl::DestroyImageKHR(raw_display, egl_image);

        (self.gl_tex_parameteri)(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        (self.gl_tex_parameteri)(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        (self.gl_bind_texture)(GL_TEXTURE_EXTERNAL_OES, 0);

        let size = Size::from((width, height));
        let texture = GlesTexture::from_raw_with_flags(
            renderer, None, false, true, false, tex, size,
        );

        Ok(texture)
    }
}
