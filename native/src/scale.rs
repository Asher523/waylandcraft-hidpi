use smithay::{
    reexports::wayland_server::{
        Resource,
        protocol::wl_surface::WlSurface,
    },
    wayland::compositor::{SurfaceAttributes, SurfaceData, with_states},
};
use std::sync::OnceLock;

/// HiDPI scale factor for the virtual output.
///
/// Set `WAYLANDCRAFT_SCALE=2` before launching Minecraft to make all apps
/// render at 2x and therefore appear twice as large (and twice as sharp)
/// in-world and in the window manager screen. Defaults to 1, which keeps the
/// original behaviour exactly.
pub fn output_scale() -> i32 {
    static SCALE: OnceLock<i32> = OnceLock::new();
    *SCALE.get_or_init(|| {
        std::env::var("WAYLANDCRAFT_SCALE")
            .ok()
            .and_then(|v| v.trim().parse::<i32>().ok())
            .filter(|v| (1..=8).contains(v))
            .unwrap_or(1)
    })
}

/// Convert a physical (buffer) pixel value into the logical size that gets
/// sent to clients through xdg configure events.
pub fn px_to_logical(v: i32) -> i32 {
    (v / output_scale()).max(1)
}

/// The buffer scale committed by a client for the given surface.
///
/// Clients that support HiDPI set this to the output scale and use a buffer
/// that is `scale` times larger than the surface's logical size. This is the
/// authoritative factor for converting between the two coordinate spaces.
pub fn surface_scale(surface: &WlSurface) -> i32 {
    if !surface.is_alive() {
        return 1;
    }

    with_states(surface, surface_scale_from_data)
}

/// Same as [`surface_scale`], but for a `SurfaceData` reference that is already
/// available — e.g. inside a surface-tree traversal, where calling
/// `with_states` on the very surface being visited would deadlock on smithay's
/// non-reentrant user-data lock.
pub fn surface_scale_from_data(data: &SurfaceData) -> i32 {
    let mut guard = data.cached_state.get::<SurfaceAttributes>();
    guard.current().buffer_scale.max(1)
}
