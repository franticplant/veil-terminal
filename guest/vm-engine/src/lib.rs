//! The engine payload for Microdroid.
//!
//! Microdroid calls `AVmPayload_main`; the payload listens on AF_VSOCK port
//! 5000 for the engine protocol and port 5001 for the host network broker,
//! then runs the same `veil_engine_host::serve()` loop the native process uses.
//! The protocol and engine do not change: only the pipes differ.

#![allow(unsafe_code)]

mod broker;

use std::fs::File;
use std::os::fd::{AsRawFd, FromRawFd};
use std::ptr::null_mut;
use std::sync::Arc;

use veil_engine::{EngineConfig, NoHostKeys};

const ENGINE_PORT: u32 = 5000;
const BROKER_PORT: u32 = 5001;

/// Notify Microdroid only if the loader exported the callback. The host also
/// retries `connectVsock`, so a missing callback does not hide a ready engine.
unsafe fn notify_ready() {
    let name = b"AVmPayload_notifyPayloadReady\0";
    let symbol = unsafe { libc::dlsym(libc::RTLD_DEFAULT, name.as_ptr().cast()) };
    if !symbol.is_null() {
        let notify: unsafe extern "C" fn() = unsafe { std::mem::transmute(symbol) };
        unsafe { notify() };
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn AVmPayload_main() -> i32 {
    unsafe {
        // The broker relay may close while a session task is still flushing.
        // A broken write must not terminate the whole engine process.
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
        let listener = match broker::listen(ENGINE_PORT) {
            Ok(l) => l,
            Err(e) => {
                eprintln!("veil-vm-engine: cannot listen on {ENGINE_PORT}: {e}");
                return 1;
            }
        };
        let broker_listener = match broker::listen(BROKER_PORT) {
            Ok(l) => l,
            Err(e) => {
                eprintln!("veil-vm-engine: cannot listen on {BROKER_PORT}: {e}");
                return 2;
            }
        };
        eprintln!("veil-vm-engine: listening on vsock {ENGINE_PORT} and {BROKER_PORT}");
        notify_ready();

        let connection = libc::accept(listener.as_raw_fd(), null_mut(), null_mut());
        if connection < 0 {
            eprintln!("veil-vm-engine: engine accept failed");
            return 3;
        }
        eprintln!("veil-vm-engine: host connected");
        let reader = File::from_raw_fd(connection);
        let writer = match reader.try_clone() {
            Ok(w) => w,
            Err(_) => return 4,
        };
        let config = EngineConfig {
            version: "veil-engine-host (microdroid broker)".into(),
            data_dir: None,
            dialer: Some(Arc::new(broker::BrokeredDialer::new(broker_listener))),
            host_keys: Some(Arc::new(NoHostKeys)),
        };
        eprintln!("veil-vm-engine: starting serve");
        for warning in veil_engine_host::limits::apply(0, 256) {
            eprintln!("veil-vm-engine: limit not applied: {warning}");
        }
        let result = veil_engine_host::serve(config, reader, writer);
        eprintln!("veil-vm-engine: serve returned {result:?}");
        match result {
            Ok(()) => 0,
            Err(_) => 5,
        }
    }
}
