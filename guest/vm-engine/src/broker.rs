//! The guest half of the host network broker.
//!
//! The engine never dials the network in the VM. It accepts a preconnected
//! stream from the VM host on vsock port 5001; the host has already dialed the
//! real SSH endpoint and relays bytes between TCP and this socket.

#![allow(unsafe_code)]

use std::fs::File;
use std::future::Future;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd};
use std::pin::Pin;
use std::ptr::null_mut;
use std::sync::Arc;
use std::task::{Context, Poll};

use tokio::io::unix::AsyncFd;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
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
                set_nonblocking(fd)?;
                Ok(file)
            })
            .await
            .map_err(io::Error::other)??;
            Ok(Dialed {
                stream: Box::new(Vsock::new(file)?),
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

fn set_nonblocking(fd: i32) -> io::Result<()> {
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    if flags < 0 {
        return Err(io::Error::last_os_error());
    }
    if unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

struct Vsock {
    fd: AsyncFd<File>,
}

impl Vsock {
    // tokio 1.54 deprecates AsyncFd::new in favour of an I/O-safety API that
    // is not available on the pinned version's Android target yet.
    #[allow(deprecated)]
    fn new(file: File) -> io::Result<Self> {
        Ok(Vsock {
            fd: AsyncFd::new(file)?,
        })
    }
}

impl AsyncRead for Vsock {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let mut guard = match self.fd.poll_read_ready(cx) {
            Poll::Ready(Ok(g)) => g,
            Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
            Poll::Pending => return Poll::Pending,
        };
        let unfilled = buf.initialize_unfilled();
        match guard.try_io(|inner| {
            let n = unsafe {
                libc::read(
                    inner.get_ref().as_raw_fd(),
                    unfilled.as_mut_ptr().cast(),
                    unfilled.len(),
                )
            };
            if n < 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(n as usize)
            }
        }) {
            Ok(Ok(n)) => {
                buf.advance(n);
                Poll::Ready(Ok(()))
            }
            Ok(Err(e)) => Poll::Ready(Err(e)),
            Err(_) => Poll::Pending,
        }
    }
}

impl AsyncWrite for Vsock {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        data: &[u8],
    ) -> Poll<io::Result<usize>> {
        let mut guard = match self.fd.poll_write_ready(cx) {
            Poll::Ready(Ok(g)) => g,
            Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
            Poll::Pending => return Poll::Pending,
        };
        match guard.try_io(|inner| {
            let n = unsafe {
                libc::write(
                    inner.get_ref().as_raw_fd(),
                    data.as_ptr().cast(),
                    data.len(),
                )
            };
            if n < 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(n as usize)
            }
        }) {
            Ok(result) => Poll::Ready(result),
            Err(_) => Poll::Pending,
        }
    }

    fn poll_flush(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
        let fd = self.fd.get_ref().as_raw_fd();
        unsafe { libc::shutdown(fd, libc::SHUT_WR) };
        Poll::Ready(Ok(()))
    }
}
