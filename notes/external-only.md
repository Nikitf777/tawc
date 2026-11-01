# External-only fork

This branch removes the distro/rootfs product from TAWC. The app is a
standalone Wayland compositor for clients in other Android apps (Termux's
`tawc-run`) and has a single home screen.

## What remains

- `MainActivity`: one scrolling page with external-access policy, compositor
  renderer, pointer emulation, output scale, the SHM tint diagnostic, and a
  **Restart compositor** button.
- `compositor/`: `CompositorService`, `CompositorActivity`,
  `ExternalWaylandService`/`ExternalWaylandAccess`, `NativeBridge`, input and
  clipboard bridges.
- `session/`: `SessionHolds` with a single compositor reason and a minimal
  foreground `SessionService`.
- `RestartActivity`/`AppRestart`: process-level restart in a `:restart`
  trampoline, needed because Android 14 blocks a background alarm/broadcast
  from bringing the singleTask `MainActivity` back.
- Server Mesa asset + xkb data; the compositor Rust build. Mesa is a git
  submodule at `deps/mesa` from `nikitf777/mesa` branch
  `better-android-support`, the upstream Khronos Vulkan loader, and one
  generic Mesa build option (`vulkan-loader-name`).

## What was removed

- `install/`, `launcher/`, `terminal/`, `remote/`, `tasks/`, `ops/`,
  `licenses/`, their Activities/services/receivers and Gradle tasks/assets.
- Rootfs settings and the client graphics-backend enum.
- proot/tawcroot/ando/sftp/debootstrap/libhybris/gfxstream/Mesa-Zink/Xwayland
  packaging. Xwayland is compiled in the compositor but always started
  disabled and is not shipped.
- The debug exec broker (`src/debug/java`), and distro-only unit tests.

Most files under `notes/` still describe upstream TAWC, including the distro
installer, launcher, terminal and rootfs session model. Treat those sections
as historical context for this branch.
