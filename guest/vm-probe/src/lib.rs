//! The first Microdroid payload: a vsock echo used to prove the VM transport.
//!
//! Microdroid calls `AVmPayload_main` after boot. The payload listens on
//! AF_VSOCK port 5000, tells the manager it is ready, accepts one connection
//! from the host and echoes bytes until the host closes it.
//!
//! It is deliberately not the SSH engine yet. Once this round-trips, the same
//! entry point can start `veil-engine-host::serve()` on the accepted socket.

// The vsock socket API is raw libc; the unsafe surface is confined to this
// crate, as with the JNI bridge.
#![allow(unsafe_code)]

use std::fs::File;
use std::io::{Read, Write};
use std::mem::size_of;
use std::os::fd::FromRawFd;
use std::ptr::null_mut;

/// Linux's AF_VSOCK. libc does not expose it on every Android target.
const AF_VSOCK: i32 = 40;
/// Bind to any local CID; the host connects to the VM's assigned CID.
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

/// The payload library calls back into Microdroid's loader when its listener is
/// ready. dlsym keeps the crate linkable outside the VM (for tests and lint).
unsafe fn notify_ready() {
    let name = b"AVmPayload_notifyPayloadReady\0";
    let symbol = unsafe { libc::dlsym(libc::RTLD_DEFAULT, name.as_ptr().cast()) };
    if !symbol.is_null() {
        let notify: unsafe extern "C" fn() = unsafe { std::mem::transmute(symbol) };
        unsafe { notify() };
    }
}

/// Entry point Microdroid runs in the guest.
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
        notify_ready();

        let connection = libc::accept(listener, null_mut(), null_mut());
        if connection < 0 {
            libc::close(listener);
            return 4;
        }
        let mut stream = File::from_raw_fd(connection);
        let _ = stream.write_all(b"veil-vm-probe ready\n");
        let mut buf = [0u8; 4096];
        loop {
            match stream.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    if stream.write_all(&buf[..n]).is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
        libc::close(listener);
    }
    0
}
