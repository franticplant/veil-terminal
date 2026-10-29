//! A native host-side network broker.
//!
//! The app connects over a Unix socket, sends `host\nport\n`, and this process
//! opens the TCP connection and relays bytes. It is the same no-JVM process
//! model as the engine host and keeps the network socket out of the UI process.

use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;
use std::thread;

/// Serves until the listener fails. One thread per accepted app connection.
pub fn serve(path: &Path) -> io::Result<()> {
    let _ = std::fs::remove_file(path);
    let listener = UnixListener::bind(path)?;
    for connection in listener.incoming() {
        match connection {
            Ok(stream) => {
                thread::Builder::new()
                    .name("veil-net-broker".into())
                    .spawn(move || {
                        if let Err(e) = handle(stream) {
                            eprintln!("veil-net-broker: connection ended: {e}");
                        }
                    })?;
            }
            Err(e) => eprintln!("veil-net-broker: accept failed: {e}"),
        }
    }
    Ok(())
}

fn handle(mut app: UnixStream) -> io::Result<()> {
    let host = read_line(&mut app)?;
    let port = read_line(&mut app)?;
    let port: u16 = port
        .parse()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad port"))?;
    let tcp = TcpStream::connect((host.as_str(), port))?;
    app.write_all(b"OK\n")?;
    app.flush()?;
    let mut app_read = app.try_clone()?;
    let mut tcp_write = tcp.try_clone()?;
    let upload = thread::Builder::new()
        .name("veil-net-up".into())
        .spawn(move || {
            let _ = io::copy(&mut app_read, &mut tcp_write);
        })?;
    let mut tcp_read = tcp;
    io::copy(&mut tcp_read, &mut app)?;
    let _ = upload.join();
    Ok(())
}

/// Reads one newline-terminated line without consuming bytes after it.
fn read_line(stream: &mut UnixStream) -> io::Result<String> {
    let mut out = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        if stream.read(&mut byte)? == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "broker header",
            ));
        }
        if byte[0] == b'\n' {
            break;
        }
        if out.len() >= 256 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "broker header too long",
            ));
        }
        out.push(byte[0]);
    }
    String::from_utf8(out)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "broker header is not UTF-8"))
}
