//! The guest half of the host network broker.
//!
//! The engine never dials the network in the VM. It accepts a preconnected
//! stream from the VM host on vsock port 5001; the host has already dialed the
//! real SSH endpoint and relays bytes between TCP and this socket.

#![allow(unsafe_code)]

use std::collections::{HashMap, VecDeque};
use std::fs::File;
use std::future::Future;
use std::io::{self, Read, Write};
use std::os::fd::{AsRawFd, FromRawFd};
use std::pin::Pin;
use std::ptr::null_mut;
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::{mpsc, oneshot};
use veil_engine::{Dialed, Dialer};
use veil_obs::LogRing;

type Endpoint = (String, u16, u64);

#[derive(Default)]
struct BrokerState {
    /// Accepts that arrived before the guest engine asked for them. The last
    /// component is the frontend's connection lease; zero is legacy/ambient.
    ready: HashMap<Endpoint, VecDeque<File>>,
    /// Engine dials waiting for the host connection with this endpoint/lease.
    waiting: HashMap<Endpoint, VecDeque<oneshot::Sender<File>>>,
}

/// A blocking vsock listener plus one dispatcher. The host writes the endpoint
/// header before the guest engine necessarily starts its dial, and several
/// sessions may open at once, so a single accept loop routes connections by
/// endpoint instead of letting independent dial tasks race on accept(2).
pub struct BrokeredDialer {
    state: Arc<Mutex<BrokerState>>,
    log: Option<Arc<LogRing>>,
}

impl BrokeredDialer {
    #[allow(dead_code)] // The monolith uses `new`; the workers payload uses the logging constructor.
    pub fn new(listener: File) -> Self {
        Self::new_with_log(listener, None)
    }

    pub fn new_with_log(listener: File, log: Option<Arc<LogRing>>) -> Self {
        let state = Arc::new(Mutex::new(BrokerState::default()));
        let listener = Arc::new(listener);
        let thread_state = state.clone();
        let thread_log = log.clone();
        std::thread::Builder::new()
            .name("veil-broker-accept".into())
            .spawn(move || accept_loop(listener, thread_state, thread_log))
            .expect("spawn broker accept thread");
        BrokeredDialer { state, log }
    }

    #[allow(dead_code)] // The workers payload uses this; the monolith does not.
    /// A broker fed by another process. No listener or accept thread is
    /// started; the supervisor's broker receiver calls [`ingest`](Self::ingest)
    /// with each stream received over its private IPC channel.
    pub fn new_passive(log: Option<Arc<LogRing>>) -> Self {
        Self {
            state: Arc::new(Mutex::new(BrokerState::default())),
            log,
        }
    }

    #[allow(dead_code)] // The workers payload uses this; the monolith does not.
    /// Queue one already-accepted stream from the broker process.
    pub fn ingest(&self, host: String, port: u16, connection_id: u64, file: File) {
        if let Some(log) = &self.log {
            log.info(
                "broker",
                0,
                &format!("accepted {host}:{port} lease {connection_id}"),
            );
        }
        let key = (host.clone(), port, connection_id);
        let mut st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let mut waiter = st.waiting.get_mut(&key).and_then(|q| q.pop_front());
        if waiter.is_none() && connection_id == 0 {
            let fallback = st
                .waiting
                .keys()
                .find(|(h, p, _)| h == &host && *p == port)
                .cloned();
            if let Some(fallback) = fallback {
                waiter = st.waiting.get_mut(&fallback).and_then(|q| q.pop_front());
            }
        }
        if let Some(tx) = waiter {
            if tx.send(file).is_err() {
                eprintln!("veil-vm-engine: broker dialer vanished");
            }
        } else {
            let ready = st.ready.entry(key).or_default();
            ready.push_back(file);
            if ready.len() > 16 {
                ready.pop_front();
            }
        }
    }
}

fn accept_loop(listener: Arc<File>, state: Arc<Mutex<BrokerState>>, log: Option<Arc<LogRing>>) {
    loop {
        let fd = unsafe { libc::accept(listener.as_raw_fd(), null_mut(), null_mut()) };
        if fd < 0 {
            let error = io::Error::last_os_error();
            if let Some(log) = &log {
                log.warn("broker", 0, &format!("accept failed: {error}"));
            }
            eprintln!("veil-vm-engine: broker accept failed: {error}");
            continue;
        }
        let mut file = unsafe { File::from_raw_fd(fd) };
        let (host, port, connection_id) = match read_header(&mut file) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("veil-vm-engine: broker header failed: {e}");
                continue;
            }
        };
        if let Some(log) = &log {
            log.info(
                "broker",
                0,
                &format!("accepted {host}:{port} lease {connection_id}"),
            );
        }
        eprintln!("veil-vm-engine: broker accepted {host}:{port} lease {connection_id}");
        let key = (host.clone(), port, connection_id);
        let mut st = state.lock().unwrap_or_else(|e| e.into_inner());
        let mut waiter = st.waiting.get_mut(&key).and_then(|q| q.pop_front());
        if waiter.is_none() && connection_id == 0 {
            // Legacy clients that do not send a lease may still use the old
            // endpoint queue. New clients always send a nonzero lease.
            let fallback = st
                .waiting
                .keys()
                .find(|(h, p, _)| h == &host && *p == port)
                .cloned();
            if let Some(fallback) = fallback {
                waiter = st.waiting.get_mut(&fallback).and_then(|q| q.pop_front());
            }
        }
        if let Some(tx) = waiter {
            if tx.send(file).is_err() {
                eprintln!("veil-vm-engine: broker dialer vanished");
            }
        } else {
            let ready = st.ready.entry(key).or_default();
            ready.push_back(file);
            if ready.len() > 16 {
                ready.pop_front();
            }
        }
    }
}

impl BrokeredDialer {
    fn dial_impl<'a>(
        &'a self,
        host: &'a str,
        port: u16,
        connection_id: u64,
        exact_lease: bool,
    ) -> Pin<Box<dyn Future<Output = io::Result<Dialed>> + Send + 'a>> {
        let state = self.state.clone();
        let log = self.log.clone();
        let key = (host.to_string(), port, connection_id);
        Box::pin(async move {
            if let Some(log) = &log {
                log.info(
                    "broker",
                    0,
                    &format!("waiting for {host}:{port} lease {connection_id}"),
                );
            }
            eprintln!("veil-vm-engine: broker waiting for {host}:{port} lease {connection_id}");
            let (tx, rx) = oneshot::channel();
            let ready_file = {
                let mut st = state.lock().unwrap_or_else(|e| e.into_inner());
                let mut found = st.ready.get_mut(&key).and_then(|queue| queue.pop_front());
                if found.is_none() && !exact_lease {
                    let fallback = st
                        .ready
                        .keys()
                        .find(|(h, p, _)| h == host && *p == port)
                        .cloned();
                    if let Some(fallback) = fallback {
                        found = st
                            .ready
                            .get_mut(&fallback)
                            .and_then(|queue| queue.pop_front());
                    }
                }
                if found.is_none() {
                    st.waiting.entry(key.clone()).or_default().push_back(tx);
                }
                found
            };
            let file = match ready_file {
                Some(file) => file,
                None => rx.await.map_err(|_| {
                    io::Error::new(io::ErrorKind::BrokenPipe, "broker dispatcher stopped")
                })?,
            };
            if let Some(log) = &log {
                log.info(
                    "broker",
                    0,
                    &format!("handed {host}:{port} lease {connection_id} to a session"),
                );
            }
            eprintln!("veil-vm-engine: broker accepted {host}:{port} lease {connection_id}");
            Ok(Dialed {
                stream: Box::new(BrokeredStream::new(file)?),
                peer: None,
            })
        })
    }
}

impl Dialer for BrokeredDialer {
    fn dial<'a>(
        &'a self,
        host: &'a str,
        port: u16,
    ) -> Pin<Box<dyn Future<Output = io::Result<Dialed>> + Send + 'a>> {
        self.dial_impl(host, port, 0, false)
    }

    fn dial_with_connection<'a>(
        &'a self,
        host: &'a str,
        port: u16,
        connection_id: u64,
    ) -> Pin<Box<dyn Future<Output = io::Result<Dialed>> + Send + 'a>> {
        self.dial_impl(host, port, connection_id, connection_id != 0)
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

/// Reads the broker handshake. The versioned form is
/// `veil-broker-v1`, host, port, lease; the legacy form starts with host.
fn read_header(file: &mut File) -> io::Result<(String, u16, u64)> {
    let first = read_line(file)?;
    let (host, port_line) = if first == "veil-broker-v1" {
        (read_line(file)?, read_line(file)?)
    } else {
        (first, read_line(file)?)
    };
    let port = port_line
        .parse::<u16>()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad broker port"))?;
    let connection_id = read_line(file)?
        .parse::<u64>()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad broker lease"))?;
    Ok((host, port, connection_id))
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
    incoming: mpsc::Receiver<io::Result<Vec<u8>>>,
    buffered: Vec<u8>,
    offset: usize,
    outgoing: Option<mpsc::UnboundedSender<Vec<u8>>>,
}

impl BrokeredStream {
    fn new(file: File) -> io::Result<Self> {
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn lease_routing_never_hands_out_another_sessions_stream() {
        let dialer = BrokeredDialer::new_passive(None);
        dialer.ingest(
            "example.org".into(),
            22,
            7,
            File::open("/dev/null").unwrap(),
        );
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();

        let wrong = runtime.block_on(async {
            tokio::time::timeout(
                Duration::from_millis(100),
                dialer.dial_with_connection("example.org", 22, 8),
            )
            .await
        });
        assert!(
            wrong.is_err(),
            "a different lease must not receive the stream"
        );

        let right = runtime.block_on(async {
            tokio::time::timeout(
                Duration::from_millis(500),
                dialer.dial_with_connection("example.org", 22, 7),
            )
            .await
        });
        assert!(right.is_ok(), "the matching lease should get its stream");
    }
}
