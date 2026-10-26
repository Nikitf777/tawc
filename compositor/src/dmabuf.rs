//! `zwp_linux_dmabuf_v1` support for the Mesa server renderer.
//!
//! The global is only created when the Mesa renderer is active and its
//! `ImportDma` format set is non-empty. Vendor mode keeps today's globals.

use log::{info, warn};
use smithay::backend::allocator::Format;
use smithay::backend::renderer::ImportDma;
use smithay::backend::allocator::dmabuf::Dmabuf;
use smithay::wayland::dmabuf::{DmabufGlobal, DmabufHandler, DmabufState, ImportNotifier};

use crate::compositor::TawcState;

impl DmabufHandler for TawcState {
    fn dmabuf_state(&mut self) -> &mut DmabufState {
        &mut self.dmabuf_state
    }

    fn dmabuf_imported(
        &mut self,
        _global: &DmabufGlobal,
        dmabuf: Dmabuf,
        notifier: ImportNotifier,
    ) {
        let imported = match self.render.get() {
            Some(render) if render.kind.is_mesa() => {
                render.renderer.import_dmabuf(&dmabuf, None).is_ok()
            }
            _ => false,
        };
        if imported {
            let _ = notifier.successful::<Self>();
        } else {
            notifier.failed();
        }
    }
}

impl TawcState {
    /// Create the v3 dmabuf global from the renderer's supported formats.
    /// No-op in vendor mode, when the renderer failed to initialise, or when
    /// the renderer reports no importable formats.
    pub fn init_dmabuf_global(&mut self, kind: crate::render::RendererKind) {
        if !kind.is_mesa() || self.dmabuf_global.is_some() {
            return;
        }
        let formats: Vec<Format> = match self.render.get() {
            Some(render) if render.kind.is_mesa() => {
                render.renderer.dmabuf_formats().into_iter().collect()
            }
            _ => return,
        };
        if formats.is_empty() {
            warn!("Mesa renderer reports no dmabuf import formats; not advertising zwp_linux_dmabuf_v1");
            return;
        }
        info!(
            "Advertising zwp_linux_dmabuf_v1 with {} format/modifier pairs",
            formats.len()
        );
        self.dmabuf_global = Some(
            self.dmabuf_state
                .create_global::<Self>(&self.display_handle, formats),
        );
    }
}
