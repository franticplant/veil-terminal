//! The guest half of the host network broker.
//!
//! The engine never dials the network in the VM. It accepts a preconnected
//! stream from the VM host on vsock port 5001; the host has already dialed the
//! real SSH endpoint and relays bytes between TCP and this socket.

#![allow(unsafe_code)]

use std::fs::File;
use std::future::Future;
use std::io::{self, Read, Write};
use std::os::fd::{AsRawFd, FromRawFd};
use std::pin::Pin;
use std::ptr::null_mut;
use std::sync::Arc;
use std::task::{Context, Poll};

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::mpsc;
use veil_engine::{Dialed, Dialer};

/// A blocking vsock listener handed to the guest engine.
pub struct BrokeredDialer {
    listener: Arc<File>,
}

impl BrokeredDialer {
    pub fn new(listener: File) -> Self {
        BrokeredDialer {
            listener: Arc::new(listener),
        }
    }
}

impl Dialer for BrokeredDialer {
    fn dial<'a>(
        &'a self,
        host: &'a str,
        port: u16,
    ) -> Pin<Box<dyn Future<Output = io::Result<Dialed>> + Send + 'a>> {
        let listener = self.listener.clone();
        let want_host = host.to_string();
        Box::pin(async move {
            eprintln!("veil-vm-engine: broker waiting for {host}:{port}");
            let file = tokio::task::spawn_blocking(move || {
                let fd = unsafe { libc::accept(listener.as_raw_fd(), null_mut(), null_mut()) };
                if fd < 0 {
                    return Err(io::Error::last_os_error());
                }
                let mut file = unsafe { File::from_raw_fd(fd) };
                eprintln!("veil-vm-engine: broker accepted");
                let got_host = read_line(&mut file)?;
                let got_port = read_line(&mut file)?;
                eprintln!("veil-vm-engine: broker header {got_host}:{got_port}");
                if got_host != want_host || got_port.parse::<u16>().ok() != Some(port) {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "broker opened the wrong endpoint",
                    ));
                }
                Ok(file)
            })
            .await
            .map_err(io::Error::other)??;
            Ok(Dialed {
                stream: Box::new(BrokeredStream::new(file)?),
                peer: None,
            })
        })
    }
}

/// A vsock listener on a fixed port. Blocking accept is done by the dialer.
pub fn listen(port: u32) -> io::Result<File> {
    const AF_VSOCK: i32 = 40;
    const VMADDR_CID_ANY: u32 = u32::MAX;

    #[repr(C)]
    struct SockaddrVm {
        family: u16,
        reserved: u16,
        port: u32,
        cid: u32,
        zero: [u8; 4],
    }

    unsafe {
        let fd = libc::socket(AF_VSOCK, libc::SOCK_STREAM, 0);
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        let addr = SockaddrVm {
            family: AF_VSOCK as u16,
            reserved: 0,
            port,
            cid: VMADDR_CID_ANY,
            zero: [0; 4],
        };
        if libc::bind(
            fd,
            (&addr as *const SockaddrVm).cast(),
            std::mem::size_of::<SockaddrVm>() as libc::socklen_t,
        ) != 0
        {
            let e = io::Error::last_os_error();
            libc::close(fd);
            return Err(e);
        }
        if libc::listen(fd, 4) != 0 {
            let e = io::Error::last_os_error();
            libc::close(fd);
            return Err(e);
        }
        Ok(File::from_raw_fd(fd))
    }
}

fn read_line(file: &mut File) -> io::Result<String> {
    use std::io::Read;
    let mut out = Vec::new();
    let mut b = [0u8; 1];
    loop {
        if file.read(&mut b)? == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "broker header",
            ));
        }
        if b[0] == b'\n' {
            break;
        }
        if out.len() >= 256 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "broker header too long",
            ));
        }
        out.push(b[0]);
    }
    String::from_utf8(out)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "broker header is not UTF-8"))
}

/// A vsock connection driven by two blocking threads.
///
/// The guest's broker stream is plain SSH traffic. Reading it through
/// `AsyncFd` on this kernel worked only in fits: server packets could sit
/// unread until a client keepalive woke the socket. Two blocking pumps, one
/// per direction, remove that dependency and mirror the blocking reader and
/// writer the engine protocol already uses on vsock port 5000.
struct BrokeredStream {
    fd: i32,
    incoming: mpsc::Receiver<io::Result<Vec<u8>>>,
    buffered: Vec<u8>,
    offset: usize,
    outgoing: Option<mpsc::UnboundedSender<Vec<u8>>>,
}

impl BrokeredStream {
    fn new(file: File) -> io::Result<Self> {
        let fd = file.as_raw_fd();
        let mut read_file = file.try_clone()?;
        let mut write_file = file;
        let (in_tx, in_rx) = mpsc::channel(64);
        let (out_tx, mut out_rx) = mpsc::unbounded_channel::<Vec<u8>>();

        std::thread::Builder::new()
            .name("veil-vsock-read".into())
            .spawn(move || {
                loop {
                    let mut bytes = vec![0u8; 8192];
                    match read_file.read(&mut bytes) {
                        Ok(0) => break,
                        Ok(n) => {
                            bytes.truncate(n);
                            if in_tx.blocking_send(Ok(bytes)).is_err() {
                                break;
                            }
                        }
                        Err(e) => {
                            let _ = in_tx.blocking_send(Err(e));
                            break;
                        }
                    }
                }
            })?;

        std::thread::Builder::new()
            .name("veil-vsock-write".into())
            .spawn(move || {
                while let Some(bytes) = out_rx.blocking_recv() {
                    if let Err(e) = write_file.write_all(&bytes) {
                        eprintln!("veil-vm-engine: vsock write failed: {e}");
                        break;
                    }
                    if let Err(e) = write_file.flush() {
                        eprintln!("veil-vm-engine: vsock flush failed: {e}");
                        break;
                    }
                }
            })?;

        Ok(BrokeredStream {
            fd,
            incoming: in_rx,
            buffered: Vec::new(),
            offset: 0,
            outgoing: Some(out_tx),
        })
    }

    fn copy_buffered(&mut self, buf: &mut ReadBuf<'_>) -> bool {
        if self.offset >= self.buffered.len() {
            return false;
        }
        let n = (self.buffered.len() - self.offset).min(buf.remaining());
        buf.put_slice(&self.buffered[self.offset..self.offset + n]);
        self.offset += n;
        if self.offset == self.buffered.len() {
            self.buffered.clear();
            self.offset = 0;
        }
        true
    }
}

impl Drop for BrokeredStream {
    fn drop(&mut self) {
        // The reader thread can be blocked in read(2). Closing only the Rust
        // File handles would leave the read side open, which keeps the host's
        // broker relay and its descriptors alive after the SSH session ends.
        unsafe {
            libc::shutdown(self.fd, libc::SHUT_RDWR);
        }
    }
}

impl AsyncRead for BrokeredStream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if this.copy_buffered(buf) || buf.remaining() == 0 {
            return Poll::Ready(Ok(()));
        }
        match this.incoming.poll_recv(cx) {
            Poll::Ready(Some(Ok(bytes))) => {
                this.buffered = bytes;
                this.offset = 0;
                this.copy_buffered(buf);
                Poll::Ready(Ok(()))
            }
            Poll::Ready(Some(Err(e))) => Poll::Ready(Err(e)),
            Poll::Ready(None) => Poll::Ready(Ok(())),
            Poll::Pending => Poll::Pending,
        }
    }
}

impl AsyncWrite for BrokeredStream {
    fn poll_write(
        self: Pin<&mut Self>,
        _cx: &mut Context<'_>,
        data: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        let Some(outgoing) = this.outgoing.as_ref() else {
            return Poll::Ready(Err(io::Error::new(
                io::ErrorKind::BrokenPipe,
                "vsock write side is closed",
            )));
        };
        let len = data.len();
        match outgoing.send(data.to_vec()) {
            Ok(()) => Poll::Ready(Ok(len)),
            Err(_) => Poll::Ready(Err(io::Error::new(
                io::ErrorKind::BrokenPipe,
                "vsock writer stopped",
            ))),
        }
    }

    fn poll_flush(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
        self.get_mut().outgoing.take();
        Poll::Ready(Ok(()))
    }
}
