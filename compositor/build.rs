fn main() {
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();

    if target_os == "android" {
        // Locally-built static xkbcommon for the compositor. Built by
        // `scripts/build-libxkbcommon.sh`, which clones a pinned upstream tag
        // into the (gitignored) `deps/libxkbcommon/` checkout and runs meson
        // with our two Android cross-files. arm64 build lives in
        // `deps/libxkbcommon/builddir/`, x86_64 (emulator) in
        // `deps/libxkbcommon/builddir-x86_64/`.
        let target_arch = std::env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();
        let manifest_dir = std::env::var("CARGO_MANIFEST_DIR").unwrap();
        let repo_root = std::path::Path::new(&manifest_dir)
            .parent().expect("compositor crate must have a parent");
        let builddir_name = match target_arch.as_str() {
            "aarch64" => "builddir",
            "x86_64" => "builddir-x86_64",
            other => panic!("unsupported android target arch: {}", other),
        };
        let xkb_dir = repo_root.join("deps").join("libxkbcommon").join(builddir_name);
        if !xkb_dir.join("libxkbcommon.a").is_file() {
            panic!(
                "libxkbcommon static lib missing at {} — run `scripts/build-libxkbcommon.sh{}` first",
                xkb_dir.display(),
                if target_arch == "x86_64" { " --abi=x86_64" } else { "" },
            );
        }
        println!("cargo:rustc-link-search=native={}", xkb_dir.display());
        println!("cargo:rustc-link-lib=static=xkbcommon");

        // C helper that turns an android_wlegl native_handle_t into an
        // AHardwareBuffer via AHardwareBuffer_createFromHandle (dlsym'd at
        // runtime from libnativewindow.so). Uses only the public NDK
        // surface — no vendored platform headers needed.
        cc::Build::new()
            .file("native/wlegl_import.c")
            .flag("-std=c11")
            .flag("-Wno-unused-parameter")
            .compile("tawc_wlegl_import");

        // AHB + ASurfaceControl helpers for the Mesa server renderer.
        cc::Build::new()
            .file("native/mesa_ahb.c")
            .flag("-std=c11")
            .flag("-Wno-unused-parameter")
            .compile("tawc_mesa_ahb");

        // Dependencies of the C helpers.
        // libhardware isn't in the NDK — we dlopen it at runtime in the helper.
        println!("cargo:rustc-link-lib=dylib=log");
        println!("cargo:rustc-link-lib=dylib=dl");
        println!("cargo:rustc-link-lib=dylib=nativewindow");
        println!("cargo:rustc-link-lib=dylib=android");

        println!("cargo:rerun-if-changed=native/wlegl_import.c");
        println!("cargo:rerun-if-changed=native/mesa_ahb.c");
    }
}
