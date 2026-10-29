//! Serves one engine over a byte stream.
//!
//! This is the shape the engine takes once it leaves the app's process: its
//! own process today (a pipe or a Unix socket to the app), the Microdroid
//! payload later (vsock). The engine itself does not change; only the pipe
//! does, exactly as the JNI pipe carries the same encoded messages in-process.

use std::io::{Read, Write};
use std::thread;
use std::time::Duration;

pub mod limits;

use veil_engine::{Engine, EngineConfig, Recv};
use veil_wire::{read_frame, write_frame};

/// Runs an engine for one frontend connection until either side ends it.
///
/// `reader` and `writer` are the two directions of the stream (for a socket,
/// the socket and a clone of it). Returns when the frontend closes its side
/// or the stream fails.
pub fn serve(
    config: EngineConfig,
    mut reader: impl Read + Send + 'static,
    mut writer: impl Write + Send + 'static,
) -> std::io::Result<()> {
    let engine = std::sync::Arc::new(
        Engine::start(config).map_err(|e| std::io::Error::other(e.to_string()))?,
    );
    let out = engine.clone();
    let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let stopped = stop.clone();
    // Engine -> stream. Stops when the engine closes, the stream fails, or
    // the frontend's side has ended (`stop`).
    let pump = thread::Builder::new()
        .name("veil-engine-out".into())
        .spawn(move || -> std::io::Result<()> {
            while !stopped.load(std::sync::atomic::Ordering::Relaxed) {
                match out.recv(Duration::from_millis(200)) {
                    Recv::Message(m) => write_frame(&mut writer, &m)?,
                    Recv::Timeout => {}
                    Recv::Closed => return Ok(()),
                }
            }
            Ok(())
        })?;
    // Stream -> engine, on this thread.
    let result = loop {
        match read_frame(&mut reader) {
            Ok(Some(msg)) => {
                if engine.send(msg).is_err() {
                    break Ok(());
                }
            }
            Ok(None) => break Ok(()),
            Err(e) => break Err(e),
        }
    };
    stop.store(true, std::sync::atomic::Ordering::Relaxed);
    let _ = pump.join();
    // The last handle: shut the engine down (it ends its sessions).
    if let Ok(engine) = std::sync::Arc::try_unwrap(engine) {
        engine.shutdown();
    }
    result
}
