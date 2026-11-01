use std::sync::OnceLock;

use log::warn;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AppPaths {
    pub data_dir: String,
    pub files_dir: String,
    pub share_dir: String,
    pub xwayland_dir: String,
    pub xwayland_runtime_dir: String,
    pub xkb_config_root: String,
    pub wayland_socket_path: String,
    pub kumquat_socket_path: String,
}

static APP_PATHS: OnceLock<AppPaths> = OnceLock::new();

pub fn init_from_env() {
    let paths = AppPaths {
        data_dir: require_env("TAWC_APP_DATA_DIR"),
        files_dir: require_env("TAWC_APP_FILES_DIR"),
        share_dir: require_env("TAWC_APP_SHARE_DIR"),
        // External-only builds do not ship Xwayland. Keep the fields for
        // the Xwayland code path, which is inert when they are empty.
        xwayland_dir: std::env::var("TAWC_XWAYLAND_DIR").unwrap_or_default(),
        xwayland_runtime_dir: std::env::var("TAWC_XWAYLAND_RUNTIME_DIR").unwrap_or_default(),
        xkb_config_root: require_env("TAWC_XKB_CONFIG_ROOT"),
        wayland_socket_path: format!("{}/wayland-0", require_env("TAWC_APP_SHARE_DIR")),
        kumquat_socket_path: format!("{}/kumquat-gpu-0", require_env("TAWC_APP_SHARE_DIR")),
    };

    if let Err(new_paths) = APP_PATHS.set(paths) {
        if APP_PATHS.get() != Some(&new_paths) {
            warn!(
                "app paths changed after compositor init; keeping existing paths: old={:?} new={:?}",
                APP_PATHS.get(),
                new_paths,
            );
        }
    }
}

pub fn get() -> &'static AppPaths {
    APP_PATHS
        .get()
        .expect("app paths not initialised before native compositor use")
}

fn require_env(name: &str) -> String {
    std::env::var(name).unwrap_or_else(|_| panic!("{name} not set"))
}
