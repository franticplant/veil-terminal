//! `veil-engine-host`: the engine in a process of its own.
//!
//!   veil-engine-host [--version NAME] --data-dir DIR --stdio
//!   veil-engine-host --data-dir DIR --unix PATH     (one connection at a time)
//!   veil-engine-host --net-broker PATH              (host TCP relay)
//!
//! Frames on the stream are `veil-wire` frames of engine protocol messages.
//! In the Microdroid payload the same `serve` runs on a vsock connection.

use std::os::unix::net::UnixListener;
use std::path::PathBuf;
use std::process::ExitCode;

use veil_engine::EngineConfig;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut data_dir: Option<PathBuf> = None;
    let mut version = format!("{} (engine-host)", env!("CARGO_PKG_VERSION"));
    let mut mode: Option<(String, Option<String>)> = None;
    let mut memory_mb = 0u64;
    let mut nofile = 256u64;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--data-dir" => {
                data_dir = args.get(i + 1).map(PathBuf::from);
                i += 1;
            }
            "--version" => {
                if let Some(v) = args.get(i + 1) {
                    version = v.clone();
                }
                i += 1;
            }
            "--memory-limit-mb" => {
                memory_mb = args.get(i + 1).and_then(|v| v.parse().ok()).unwrap_or(0);
                i += 1;
            }
            "--nofile" => {
                nofile = args.get(i + 1).and_then(|v| v.parse().ok()).unwrap_or(256);
                i += 1;
            }
            "--stdio" => mode = Some(("stdio".into(), None)),
            "--net-broker" => {
                mode = Some(("net-broker".into(), args.get(i + 1).cloned()));
                i += 1;
            }
            "--unix" => {
                mode = Some(("unix".into(), args.get(i + 1).cloned()));
                i += 1;
            }
            other => {
                eprintln!("veil-engine-host: unknown argument {other}");
                return ExitCode::from(2);
            }
        }
        i += 1;
    }
    for warning in veil_engine_host::limits::apply(memory_mb, nofile) {
        eprintln!("veil-engine-host: limit not applied: {warning}");
    }
    let config = EngineConfig {
        version,
        data_dir,
        dialer: None,
        host_keys: None,
    };
    let result = match mode {
        Some((m, _)) if m == "stdio" => {
            veil_engine_host::serve(config, std::io::stdin(), std::io::stdout())
        }
        Some((m, Some(path))) if m == "net-broker" => {
            veil_engine_host::broker::serve(std::path::Path::new(&path))
        }
        Some((_, Some(path))) => {
            let _ = std::fs::remove_file(&path);
            let listener = match UnixListener::bind(&path) {
                Ok(l) => l,
                Err(e) => {
                    eprintln!("veil-engine-host: cannot listen on {path}: {e}");
                    return ExitCode::FAILURE;
                }
            };
            loop {
                let (sock, _) = match listener.accept() {
                    Ok(s) => s,
                    Err(e) => break Err(e),
                };
                let reader = match sock.try_clone() {
                    Ok(r) => r,
                    Err(e) => break Err(e),
                };
                if let Err(e) = veil_engine_host::serve(config.clone(), reader, sock) {
                    eprintln!("veil-engine-host: connection ended: {e}");
                }
            }
        }
        _ => {
            eprintln!(
                "usage: veil-engine-host [--version NAME] [--data-dir DIR] [--memory-limit-mb N] [--nofile N] --stdio | --unix PATH | --net-broker PATH"
            );
            return ExitCode::from(2);
        }
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("veil-engine-host: {e}");
            ExitCode::FAILURE
        }
    }
}
