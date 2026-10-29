//! The engine payload for Microdroid.
//!
//! Microdroid calls `AVmPayload_main`; the payload listens on AF_VSOCK port
//! 5000, accepts the host's connection, and runs the same
//! `veil_engine_host::serve()` loop the native process uses. The protocol and
//! engine do not change: only the pipe is a vsock stream instead of stdio.

#![allow(unsafe_code)]

use std::fs::File;
use std::mem::size_of;
use std::os::fd::FromRawFd;
use std::ptr::null_mut;
use std::sync::Arc;
use std::time::Duration;

use veil_engine::{DirectDialer, EngineConfig, NoHostKeys};

const AF_VSOCK: i32 = 40;
const VMADDR_CID_ANY: u32 = u32::MAX;
const PORT: u32 = 5000;

#[repr(C)]
struct SockaddrVm {
    family: u16,
    reserved: u16,
    port: u32,
    cid: u32,
    zero: [u8; 4],
}

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
        let listener = libc::socket(AF_VSOCK, libc::SOCK_STREAM, 0);
        if listener < 0 {
            return 1;
        }
        let addr = SockaddrVm {
            family: AF_VSOCK as u16,
            reserved: 0,
            port: PORT,
            cid: VMADDR_CID_ANY,
            zero: [0; 4],
        };
        if libc::bind(
            listener,
            (&addr as *const SockaddrVm).cast(),
            size_of::<SockaddrVm>() as libc::socklen_t,
        ) != 0
        {
            libc::close(listener);
            return 2;
        }
        if libc::listen(listener, 1) != 0 {
            libc::close(listener);
            return 3;
        }
        eprintln!("veil-vm-engine: listening on vsock {PORT}");
        notify_ready();

        let connection = libc::accept(listener, null_mut(), null_mut());
        if connection < 0 {
            libc::close(listener);
            return 4;
        }
        eprintln!("veil-vm-engine: host connected");
        let reader = File::from_raw_fd(connection);
        let writer = match reader.try_clone() {
            Ok(w) => w,
            Err(_) => {
                libc::close(listener);
                return 5;
            }
        };
        let config = EngineConfig {
            version: "veil-engine-host (microdroid)".into(),
            data_dir: None,
            dialer: Some(Arc::new(DirectDialer {
                timeout: Duration::from_secs(10),
            })),
            host_keys: Some(Arc::new(NoHostKeys)),
        };
        eprintln!("veil-vm-engine: starting serve");
        for warning in veil_engine_host::limits::apply(0, 256) {
            eprintln!("veil-vm-engine: limit not applied: {warning}");
        }
        let result = veil_engine_host::serve(config, reader, writer);
        eprintln!("veil-vm-engine: serve returned {result:?}");
        libc::close(listener);
        match result {
            Ok(()) => 0,
            Err(_) => 6,
        }
    }
}
