//! The Stage 1 decomposed engine payload.
//!
//! The parent process owns the frontend protocol, the broker listener and
//! session IDs. A minimal spawner process, forked before the supervisor starts
//! any thread, forks one single-use worker per session. Each worker has two
//! Unix sockets: control frames (supervisor <-> worker) and the SSH byte stream
//! (broker relay <-> worker). The broker relay stays in the parent, so a worker
//! never opens a network connection, inherits a vsock listener, or gets another
//! session's stream.
//!
//! The spawner is deliberately single-threaded and never starts a Tokio
//! runtime. That is what makes fork-per-session safe after the supervisor and
//! broker have started their threads.

#![allow(unsafe_code)]

#[path = "../../vm-engine/src/broker.rs"]
mod broker;

use std::fs::File;
use std::io::{self, Read, Write};
use std::mem::{size_of, zeroed};
use std::net::Shutdown;
use std::os::fd::{AsRawFd, FromRawFd, RawFd};
use std::os::unix::net::UnixStream;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::sync::mpsc;
use veil_caps::{DirectDialer, HostKeyStore, NoHostKeys};
use veil_engine::worker::{
    RemoteWorker, SessionWorker, WorkerCommand, WorkerConfig, WorkerFactory,
    serve_worker_with_unix_stream,
};
use veil_engine::{EngineConfig, supervisor::Supervisor};
use veil_obs::LogRing;
use veil_protocol::{check_frame, check_size, decode_client, decode_engine, encode, v1};
use veil_wire::{read_frame, write_frame};

const ENGINE_PORT: u32 = 5000;
const BROKER_PORT: u32 = 5001;
const WORKER_QUEUE: usize = 64;

// ---------------------------------------------------------------------------
// Descriptor passing for the spawner -> supervisor worker handoff.
// ---------------------------------------------------------------------------

fn cmsg_align(n: usize) -> usize {
    let align = size_of::<usize>();
    (n + align - 1) & !(align - 1)
}

fn cmsg_header_len() -> usize {
    cmsg_align(size_of::<libc::cmsghdr>())
}

fn cmsg_space(data_len: usize) -> usize {
    cmsg_align(cmsg_header_len() + data_len)
}

#[allow(dead_code)] // exercised by the fd-passing unit test
fn send_fds(socket: RawFd, fds: &[RawFd]) -> io::Result<()> {
    send_payload_fds(socket, b"W", fds)
}

fn send_payload_fds(socket: RawFd, payload: &[u8], fds: &[RawFd]) -> io::Result<()> {
    let data_len = std::mem::size_of_val(fds);
    let mut iov = libc::iovec {
        iov_base: payload.as_ptr() as *mut _,
        iov_len: payload.len(),
    };
    let mut control = vec![0u8; cmsg_space(data_len)];
    let mut msg: libc::msghdr = unsafe { zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    if !fds.is_empty() {
        msg.msg_control = control.as_mut_ptr().cast();
        msg.msg_controllen = control.len();
        let cmsg = control.as_mut_ptr() as *mut libc::cmsghdr;
        unsafe {
            (*cmsg).cmsg_len = (cmsg_header_len() + data_len) as _;
            (*cmsg).cmsg_level = libc::SOL_SOCKET;
            (*cmsg).cmsg_type = libc::SCM_RIGHTS;
            std::ptr::copy_nonoverlapping(
                fds.as_ptr(),
                (cmsg as *mut u8).add(cmsg_header_len()).cast::<RawFd>(),
                fds.len(),
            );
        }
    }
    let sent = unsafe { libc::sendmsg(socket, &msg, 0) };
    if sent < 0 {
        Err(io::Error::last_os_error())
    } else if sent as usize != payload.len() {
        Err(io::Error::new(
            io::ErrorKind::WriteZero,
            "short fd-passing send",
        ))
    } else {
        Ok(())
    }
}

#[allow(dead_code)] // exercised by the fd-passing unit test
fn recv_fds(socket: RawFd, max: usize) -> io::Result<(u8, Vec<RawFd>)> {
    let (payload, fds) = recv_payload_fds(socket, max)?;
    if payload.len() != 1 {
        for fd in fds {
            unsafe { libc::close(fd) };
        }
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "unexpected fd-passing payload",
        ));
    }
    Ok((payload[0], fds))
}

fn recv_payload_fds(socket: RawFd, max: usize) -> io::Result<(Vec<u8>, Vec<RawFd>)> {
    let mut payload = vec![0u8; 512];
    let mut iov = libc::iovec {
        iov_base: payload.as_mut_ptr().cast(),
        iov_len: payload.len(),
    };
    let mut control = vec![0u8; cmsg_space(max * size_of::<RawFd>())];
    let mut msg: libc::msghdr = unsafe { zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.as_mut_ptr().cast();
    msg.msg_controllen = control.len();
    let received = unsafe { libc::recvmsg(socket, &mut msg, 0) };
    if received <= 0 {
        return Err(io::Error::last_os_error());
    }
    payload.truncate(received as usize);
    if msg.msg_flags & libc::MSG_CTRUNC != 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "fd-passing control message truncated",
        ));
    }

    let mut fds = Vec::new();
    let control_len = msg.msg_controllen as usize;
    let mut offset = 0usize;
    while offset + size_of::<libc::cmsghdr>() <= control_len {
        let cmsg = unsafe { control.as_ptr().add(offset) as *const libc::cmsghdr };
        let cmsg_len = unsafe { (*cmsg).cmsg_len } as usize;
        if cmsg_len < cmsg_header_len() || offset + cmsg_len > control_len {
            break;
        }
        if unsafe { (*cmsg).cmsg_level } == libc::SOL_SOCKET
            && unsafe { (*cmsg).cmsg_type } == libc::SCM_RIGHTS
        {
            let data_len = cmsg_len - cmsg_header_len();
            let count = data_len / size_of::<RawFd>();
            let data = unsafe { (cmsg as *const u8).add(cmsg_header_len()) };
            for i in 0..count {
                let fd = unsafe {
                    std::ptr::read_unaligned(data.add(i * size_of::<RawFd>()) as *const RawFd)
                };
                let rc = unsafe { libc::fcntl(fd, libc::F_SETFD, libc::FD_CLOEXEC) };
                if rc < 0 {
                    for fd in fds {
                        unsafe { libc::close(fd) };
                    }
                    unsafe { libc::close(fd) };
                    return Err(io::Error::last_os_error());
                }
                fds.push(fd);
            }
        }
        let step = cmsg_align(cmsg_len);
        if step == 0 {
            break;
        }
        offset += step;
    }
    Ok((payload, fds))
}

// ---------------------------------------------------------------------------
// Broker process
// ---------------------------------------------------------------------------

/// Broker offers are one length-delimited record plus one SCM_RIGHTS fd:
/// version, host length, host bytes, port, connection lease.
const BROKER_OFFER_VERSION: u8 = 1;

fn encode_broker_offer(host: &str, port: u16, connection_id: u64) -> Option<Vec<u8>> {
    let host = host.as_bytes();
    if host.len() > u8::MAX as usize {
        return None;
    }
    let mut payload = Vec::with_capacity(1 + 1 + host.len() + 2 + 8);
    payload.push(BROKER_OFFER_VERSION);
    payload.push(host.len() as u8);
    payload.extend_from_slice(host);
    payload.extend_from_slice(&port.to_be_bytes());
    payload.extend_from_slice(&connection_id.to_be_bytes());
    Some(payload)
}

fn decode_broker_offer(payload: &[u8]) -> Option<(String, u16, u64)> {
    if payload.len() < 12 || payload[0] != BROKER_OFFER_VERSION {
        return None;
    }
    let host_len = payload[1] as usize;
    if host_len == 0 || payload.len() != 2 + host_len + 2 + 8 {
        return None;
    }
    let host = std::str::from_utf8(&payload[2..2 + host_len])
        .ok()?
        .to_string();
    let port_at = 2 + host_len;
    let port = u16::from_be_bytes([payload[port_at], payload[port_at + 1]]);
    let id_at = port_at + 2;
    let connection_id = u64::from_be_bytes(payload[id_at..id_at + 8].try_into().ok()?);
    Some((host, port, connection_id))
}

fn broker_main(listener: File, control: UnixStream, spawner_fd: RawFd) -> ! {
    unsafe {
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
        libc::close(spawner_fd);
    }
    eprintln!("veil-vm-engine-workers: broker pid {} ready", unsafe {
        libc::getpid()
    });
    loop {
        let fd = unsafe { libc::accept(listener.as_raw_fd(), null_mut(), null_mut()) };
        if fd < 0 {
            eprintln!(
                "veil-vm-engine-workers: broker accept failed: {}",
                io::Error::last_os_error()
            );
            continue;
        }
        let mut file = unsafe { File::from_raw_fd(fd) };
        let (host, port, connection_id) = match read_broker_header(&mut file) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("veil-vm-engine-workers: broker header failed: {e}");
                continue;
            }
        };
        let Some(payload) = encode_broker_offer(&host, port, connection_id) else {
            eprintln!("veil-vm-engine-workers: broker host too long");
            continue;
        };
        if let Err(e) = send_payload_fds(control.as_raw_fd(), &payload, &[file.as_raw_fd()]) {
            eprintln!("veil-vm-engine-workers: broker handoff failed: {e}");
            break;
        }
        eprintln!(
            "veil-vm-engine-workers: broker handed {host}:{port} lease {connection_id} to supervisor"
        );
    }
    unsafe { libc::_exit(0) }
}

fn broker_receiver(control: UnixStream, dialer: Arc<broker::BrokeredDialer>) -> io::Result<()> {
    loop {
        let (payload, fds) = recv_payload_fds(control.as_raw_fd(), 1)?;
        if fds.len() != 1 {
            for fd in fds {
                unsafe { libc::close(fd) };
            }
            continue;
        }
        let Some((host, port, connection_id)) = decode_broker_offer(&payload) else {
            unsafe { libc::close(fds[0]) };
            continue;
        };
        let file = unsafe { File::from_raw_fd(fds[0]) };
        dialer.ingest(host, port, connection_id, file);
    }
}

fn read_broker_header(file: &mut File) -> io::Result<(String, u16, u64)> {
    let first = read_broker_line(file)?;
    let (host, port_line) = if first == "veil-broker-v1" {
        (read_broker_line(file)?, read_broker_line(file)?)
    } else {
        (first, read_broker_line(file)?)
    };
    let port = port_line
        .parse::<u16>()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad broker port"))?;
    let connection_id = read_broker_line(file)?
        .parse::<u64>()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad broker lease"))?;
    Ok((host, port, connection_id))
}

fn read_broker_line(file: &mut File) -> io::Result<String> {
    let mut out = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        if file.read(&mut byte)? == 0 {
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

// ---------------------------------------------------------------------------
// Spawner process
// ---------------------------------------------------------------------------

fn spawner_main(control: UnixStream) -> ! {
    unsafe {
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
    }
    eprintln!("veil-vm-engine-workers: spawner pid {} ready", unsafe {
        libc::getpid()
    });
    loop {
        let mut pollfd = libc::pollfd {
            fd: control.as_raw_fd(),
            events: libc::POLLIN,
            revents: 0,
        };
        let ready = unsafe { libc::poll(&mut pollfd, 1, 200) };
        if ready < 0 {
            let e = io::Error::last_os_error();
            if e.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            eprintln!("veil-vm-engine-workers: spawner poll failed: {e}");
            break;
        }
        if ready > 0 && pollfd.revents & libc::POLLIN != 0 {
            let mut request = [0u8; 1];
            match (&control).read_exact(&mut request) {
                Ok(()) if request[0] == b'S' => {
                    if let Err(e) = spawn_worker(&control) {
                        eprintln!("veil-vm-engine-workers: spawner fork failed: {e}");
                        let mut s = &control;
                        let _ = s.write_all(b"E");
                    }
                }
                Ok(()) => {
                    let mut s = &control;
                    let _ = s.write_all(b"E");
                }
                Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => break,
                Err(e) => {
                    eprintln!("veil-vm-engine-workers: spawner request failed: {e}");
                    break;
                }
            }
        }
        if ready > 0 && pollfd.revents & (libc::POLLHUP | libc::POLLERR | libc::POLLNVAL) != 0 {
            break;
        }
        reap_workers();
    }
    unsafe { libc::_exit(0) }
}

fn spawn_worker(control: &UnixStream) -> io::Result<()> {
    let (parent_control, child_control) = UnixStream::pair()?;
    let (parent_data, child_data) = UnixStream::pair()?;
    let pid = unsafe { libc::fork() };
    if pid < 0 {
        return Err(io::Error::last_os_error());
    }
    if pid == 0 {
        // This process is the worker. It inherits the spawner control socket
        // and the parent ends of its two new channels; close them before the
        // session starts.
        unsafe { libc::close(control.as_raw_fd()) };
        drop(parent_control);
        drop(parent_data);
        worker_child(child_control, child_data);
    }
    drop(child_control);
    drop(child_data);
    let mut payload = [0u8; 5];
    payload[0] = b'W';
    payload[1..].copy_from_slice(&pid.to_be_bytes());
    send_payload_fds(
        control.as_raw_fd(),
        &payload,
        &[parent_control.as_raw_fd(), parent_data.as_raw_fd()],
    )?;
    eprintln!("veil-vm-engine-workers: spawner forked worker pid {pid}");
    Ok(())
}

fn reap_workers() {
    loop {
        let mut status = 0i32;
        let pid = unsafe { libc::waitpid(-1, &mut status, libc::WNOHANG) };
        if pid <= 0 {
            break;
        }
        eprintln!("veil-vm-engine-workers: reaped worker pid {pid} status {status}");
    }
}

// ---------------------------------------------------------------------------
// Worker process
// ---------------------------------------------------------------------------

fn worker_child(control: UnixStream, data: UnixStream) -> ! {
    eprintln!(
        "veil-vm-engine-workers: worker pid {} waiting for session",
        unsafe { libc::getpid() }
    );
    for warning in veil_engine_host::limits::apply(0, 128) {
        // The guest kernel rejects seccomp filter installation; the rlimits,
        // no_new_privs and dumpable settings are still useful.
        if !warning.starts_with("seccomp:") {
            eprintln!("veil-vm-engine-workers: worker limit not applied: {warning}");
        }
    }
    let result = (|| -> io::Result<()> {
        let mut reader = control.try_clone()?;
        let buf = read_frame(&mut reader)?
            .ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "worker start"))?;
        let msg = decode_client(&buf)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))?;
        let Some(v1::client_msg::Body::OpenSession(open)) = msg.body else {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "worker start is not OpenSession",
            ));
        };
        let (cols, rows) = check_size(open.size.as_ref()).unwrap_or((80, 24));
        let keys = open
            .public_keys
            .iter()
            .map(|k| {
                russh::keys::PublicKey::from_openssh(k)
                    .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))
            })
            .collect::<io::Result<Vec<_>>>()?;
        let (out, _out_rx) = mpsc::channel::<Vec<u8>>(1);
        let config = WorkerConfig {
            session_id: msg.request_id,
            host: open.host,
            port: match open.port {
                0 => 22,
                p => p as u16,
            },
            connection_id: open.connection_id,
            jump: open.jump.map(|j| veil_engine::worker::Hop {
                host: j.host,
                port: match j.port {
                    0 => 22,
                    p => p as u16,
                },
            }),
            user: open.username,
            allow_classical_kex: open.allow_classical_kex,
            keys,
            public_keys: open.public_keys,
            cols,
            rows,
            // Replaced with the pre-created data channel inside the worker
            // runtime. This value is never dialed.
            dialer: Arc::new(DirectDialer {
                timeout: Duration::from_secs(1),
            }),
            host_keys: Arc::new(NoHostKeys) as Arc<dyn HostKeyStore>,
            out,
            log: Arc::new(LogRing::new()),
            next_prompt: Arc::new(AtomicU64::new(1)),
        };
        let writer = control.try_clone()?;
        eprintln!(
            "veil-vm-engine-workers: worker pid {} starting session {} {}@{}:{}",
            unsafe { libc::getpid() },
            config.session_id,
            config.user,
            config.host,
            config.port
        );
        let result = serve_worker_with_unix_stream(config, data, reader, writer);
        eprintln!("veil-vm-engine-workers: worker serve returned: {result:?}");
        result
    })();
    match result {
        Ok(()) => unsafe { libc::_exit(0) },
        Err(e) => {
            eprintln!("veil-vm-engine-workers: worker failed: {e}");
            unsafe { libc::_exit(2) }
        }
    }
}

// ---------------------------------------------------------------------------
// Supervisor-side worker factory
// ---------------------------------------------------------------------------

struct RemoteFactory {
    spawner: Mutex<UnixStream>,
    spawner_pid: libc::pid_t,
}

// Linux syscall numbers; both aarch64 and x86_64 use these values.
const SYS_PIDFD_OPEN: libc::c_long = 434;
const SYS_PIDFD_SEND_SIGNAL: libc::c_long = 424;

fn open_pidfd(pid: libc::pid_t) -> Option<RawFd> {
    let fd = unsafe { libc::syscall(SYS_PIDFD_OPEN, pid, 0) };
    if fd < 0 { None } else { Some(fd as RawFd) }
}

/// The supervisor-side worker handle. On close, a live pidfd is polled for a
/// short grace period and then gets SIGKILL. This makes close a bound on
/// process lifetime, not merely a message enqueue.
struct ManagedWorker {
    inner: RemoteWorker,
    pidfd: Option<RawFd>,
}

impl SessionWorker for ManagedWorker {
    fn session_id(&self) -> u64 {
        self.inner.session_id()
    }
    fn stats(&self) -> v1::SessionStats {
        self.inner.stats()
    }
    fn answer_host_key(&self, prompt_id: u64, decision: v1::HostKeyDecision) -> bool {
        self.inner.answer_host_key(prompt_id, decision)
    }
    fn answer_auth(&self, prompt_id: u64, responses: Vec<String>, cancel: bool) -> bool {
        self.inner.answer_auth(prompt_id, responses, cancel)
    }
    fn answer_sign(&self, prompt_id: u64, signature: Vec<u8>, cancel: bool) -> bool {
        self.inner.answer_sign(prompt_id, signature, cancel)
    }
    fn command(&self, command: WorkerCommand) -> Result<(), String> {
        self.inner.command(command)
    }
    fn is_finished(&self) -> bool {
        self.inner.is_finished()
    }
    fn close(&mut self) {
        self.inner.close();
        if let Some(fd) = self.pidfd.take() {
            let _ = std::thread::Builder::new()
                .name("veil-worker-reaper".into())
                .spawn(move || {
                    let mut pfd = libc::pollfd {
                        fd,
                        events: libc::POLLIN,
                        revents: 0,
                    };
                    let mut exited = false;
                    for _ in 0..20 {
                        let rc = unsafe { libc::poll(&mut pfd, 1, 100) };
                        if rc > 0 {
                            exited = true;
                            break;
                        }
                        if rc < 0
                            && std::io::Error::last_os_error().kind()
                                != std::io::ErrorKind::Interrupted
                        {
                            break;
                        }
                    }
                    if !exited {
                        unsafe {
                            libc::syscall(
                                SYS_PIDFD_SEND_SIGNAL,
                                fd,
                                libc::SIGKILL,
                                null_mut::<libc::siginfo_t>(),
                                0,
                            );
                        }
                    }
                    unsafe { libc::close(fd) };
                });
        }
    }
}

impl Drop for ManagedWorker {
    fn drop(&mut self) {
        if let Some(fd) = self.pidfd.take() {
            unsafe { libc::close(fd) };
        }
    }
}

impl RemoteFactory {
    fn new(spawner: UnixStream, spawner_pid: libc::pid_t) -> Self {
        Self {
            spawner: Mutex::new(spawner),
            spawner_pid,
        }
    }

    fn request_worker(&self) -> Result<(UnixStream, UnixStream, libc::pid_t), String> {
        let spawner = self.spawner.lock().unwrap_or_else(|e| e.into_inner());
        {
            let mut s = &*spawner;
            s.write_all(b"S")
                .map_err(|e| format!("spawner request: {e}"))?;
            s.flush()
                .map_err(|e| format!("spawner request flush: {e}"))?;
        }
        let (payload, fds) = recv_payload_fds(spawner.as_raw_fd(), 2)
            .map_err(|e| format!("spawner response: {e}"))?;
        if payload.len() != 5 || payload[0] != b'W' || fds.len() != 2 {
            for fd in fds {
                unsafe { libc::close(fd) };
            }
            return Err("spawner did not return a worker".to_string());
        }
        let pid = libc::pid_t::from_be_bytes([payload[1], payload[2], payload[3], payload[4]]);
        let control = unsafe { UnixStream::from_raw_fd(fds[0]) };
        let data = unsafe { UnixStream::from_raw_fd(fds[1]) };
        Ok((control, data, pid))
    }
}

impl Drop for RemoteFactory {
    fn drop(&mut self) {
        if let Ok(spawner) = self.spawner.get_mut() {
            let _ = spawner.shutdown(Shutdown::Both);
        }
        unsafe {
            libc::waitpid(self.spawner_pid, null_mut(), 0);
        }
    }
}

impl WorkerFactory for RemoteFactory {
    fn start(&self, config: WorkerConfig) -> Result<Box<dyn SessionWorker>, String> {
        config
            .log
            .info("spawner", config.session_id, "requesting a session worker");
        let (control, data, pid) = self.request_worker()?;
        config.log.info(
            "spawner",
            config.session_id,
            &format!("worker channels received (pid {pid})"),
        );
        start_remote_worker(control, data, pid, config)
    }
}

fn start_remote_worker(
    control: UnixStream,
    data: UnixStream,
    pid: libc::pid_t,
    config: WorkerConfig,
) -> Result<Box<dyn SessionWorker>, String> {
    let mut control_writer = control
        .try_clone()
        .map_err(|e| format!("worker control clone: {e}"))?;
    let start = v1::ClientMsg {
        request_id: config.session_id,
        body: Some(v1::client_msg::Body::OpenSession(v1::OpenSession {
            host: config.host.clone(),
            port: config.port as u32,
            username: config.user.clone(),
            connection_id: config.connection_id,
            jump: config.jump.as_ref().map(|j| v1::JumpHost {
                host: j.host.clone(),
                port: j.port as u32,
            }),
            size: Some(v1::TermSize {
                cols: config.cols as u32,
                rows: config.rows as u32,
            }),
            public_keys: config.public_keys.clone(),
            allow_classical_kex: config.allow_classical_kex,
        })),
    };
    write_frame(&mut control_writer, &encode(&start)).map_err(|e| format!("worker start: {e}"))?;

    let (commands, mut commands_rx) = mpsc::channel::<Vec<u8>>(WORKER_QUEUE);
    let stats = Arc::new(Mutex::new(v1::SessionStats {
        session_id: config.session_id,
        host: config.host.clone(),
        port: config.port as u32,
        username: config.user.clone(),
        phase: v1::SessionPhase::Connecting as i32,
        size: Some(v1::TermSize {
            cols: config.cols as u32,
            rows: config.rows as u32,
        }),
        ..Default::default()
    }));
    let finished = Arc::new(AtomicBool::new(false));

    // Commands: supervisor -> worker.
    let mut command_writer = control_writer;
    std::thread::Builder::new()
        .name("veil-worker-command".into())
        .spawn(move || {
            while let Some(frame) = commands_rx.blocking_recv() {
                if write_frame(&mut command_writer, &frame).is_err() {
                    break;
                }
            }
        })
        .map_err(|e| format!("worker command thread: {e}"))?;

    // Worker -> supervisor. EngineStats frames are control-plane only and
    // update the cached SessionStats instead of reaching the frontend.
    let out = config.out.clone();
    let reader_stats = stats.clone();
    let reader_finished = finished.clone();
    let reader_log = config.log.clone();
    let session_id = config.session_id;
    std::thread::Builder::new()
        .name("veil-worker-frames".into())
        .spawn(move || {
            let mut control = control;
            let mut sent_closed = false;
            let mut previous_size: Option<(u32, u32)> = None;
            loop {
                match read_frame(&mut control) {
                    Ok(Some(buf)) => {
                        let mut msg = match decode_engine(&buf) {
                            Ok(msg) => msg,
                            Err(_) => continue,
                        };
                        // The worker is untrusted for protocol purposes: a
                        // compromised worker may try to forge another
                        // session's frames, complete unrelated requests, or
                        // bypass the render contract. Drop anything outside
                        // its own session, then run the same frame check the
                        // in-process engine relies on.
                        let forward = match msg.body.as_ref() {
                            Some(v1::engine_msg::Body::EngineStats(stats)) => {
                                if let Some(one) = stats
                                    .sessions
                                    .iter()
                                    .find(|s| s.session_id == session_id)
                                {
                                    *reader_stats.lock().unwrap_or_else(|e| e.into_inner()) =
                                        one.clone();
                                }
                                false
                            }
                            Some(v1::engine_msg::Body::LogBatch(batch)) => {
                                for entry in &batch.entries {
                                    if entry.session_id != 0 && entry.session_id != session_id {
                                        continue;
                                    }
                                    let level = v1::LogLevel::try_from(entry.level)
                                        .unwrap_or(v1::LogLevel::Info);
                                    let component = format!("worker/{}", entry.component);
                                    reader_log.log(
                                        level,
                                        &component,
                                        session_id,
                                        &entry.message,
                                    );
                                }
                                false
                            }
                            Some(v1::engine_msg::Body::SessionState(state)) => {
                                if state.session_id != session_id {
                                    false
                                } else {
                                    if state.phase == v1::SessionPhase::Closed as i32 {
                                        sent_closed = true;
                                    }
                                    let mut current = reader_stats
                                        .lock()
                                        .unwrap_or_else(|e| e.into_inner());
                                    current.phase = state.phase;
                                    drop(current);
                                    true
                                }
                            }
                            Some(v1::engine_msg::Body::Frame(frame)) => {
                                if frame.session_id != session_id {
                                    false
                                } else if let Err(e) = check_frame(frame, previous_size) {
                                    eprintln!(
                                        "veil-vm-engine-workers: dropping invalid frame from session {session_id}: {e}"
                                    );
                                    false
                                } else {
                                    previous_size =
                                        frame.size.as_ref().map(|s| (s.cols, s.rows));
                                    true
                                }
                            }
                            Some(v1::engine_msg::Body::HostKeyPrompt(p)) => {
                                p.session_id == session_id
                            }
                            Some(v1::engine_msg::Body::AuthPrompt(p)) => {
                                p.session_id == session_id
                            }
                            Some(v1::engine_msg::Body::SignRequest(r)) => {
                                r.session_id == session_id
                            }
                            Some(v1::engine_msg::Body::Bell(b)) => b.session_id == session_id,
                            Some(v1::engine_msg::Body::ClipboardWrite(c)) => {
                                c.session_id == session_id
                            }
                            Some(v1::engine_msg::Body::Error(_)) => true,
                            _ => false,
                        };
                        if forward {
                            msg.request_id = 0;
                            if out.blocking_send(encode(&msg)).is_err() {
                                break;
                            }
                        }
                    }
                    Ok(None) => break,
                    Err(e) if e.kind() == io::ErrorKind::ConnectionReset => break,
                    Err(e) => {
                        eprintln!("veil-vm-engine-workers: worker control read failed: {e}");
                        break;
                    }
                }
            }
            eprintln!("veil-vm-engine-workers: worker control closed for session {session_id}");
            if !sent_closed {
                let msg = v1::EngineMsg {
                    request_id: 0,
                    body: Some(v1::engine_msg::Body::SessionState(v1::SessionState {
                        session_id,
                        phase: v1::SessionPhase::Closed as i32,
                        detail: "worker exited".into(),
                        security: None,
                    })),
                };
                let _ = out.blocking_send(encode(&msg));
            }
            reader_finished.store(true, Ordering::Relaxed);
        })
        .map_err(|e| format!("worker frame thread: {e}"))?;

    // Periodic stats requests. The worker answers with EngineStats; the
    // frame reader above turns that into the cached SessionStats.
    let ticker_commands = commands.clone();
    let ticker_finished = finished.clone();
    std::thread::Builder::new()
        .name("veil-worker-stats".into())
        .spawn(move || {
            while !ticker_finished.load(Ordering::Relaxed) {
                std::thread::sleep(Duration::from_millis(500));
                if ticker_finished.load(Ordering::Relaxed) {
                    break;
                }
                let request = v1::ClientMsg {
                    request_id: 0,
                    body: Some(v1::client_msg::Body::GetStats(v1::GetStats {})),
                };
                if ticker_commands.try_send(encode(&request)).is_err() {
                    break;
                }
            }
        })
        .map_err(|e| format!("worker stats thread: {e}"))?;

    // Data plane: broker stream <-> worker data socket. The worker never gets
    // the broker's vsock descriptor.
    let dialer = config.dialer.clone();
    let host = config.host.clone();
    let port = config.port;
    let data_out = config.out.clone();
    let close_tx = commands.clone();
    let relay_finished = finished.clone();
    tokio::spawn(async move {
        match dialer
            .dial_with_connection(&host, port, config.connection_id)
            .await
        {
            Ok(mut dialed) => {
                if let Err(e) = data.set_nonblocking(true) {
                    fail_session(&data_out, &close_tx, &relay_finished, session_id, e);
                    return;
                }
                let Ok(mut data) = tokio::net::UnixStream::from_std(data) else {
                    fail_session(
                        &data_out,
                        &close_tx,
                        &relay_finished,
                        session_id,
                        io::Error::other("worker data stream rejected"),
                    );
                    return;
                };
                let relay = tokio::io::copy_bidirectional(&mut dialed.stream, &mut data).await;
                eprintln!(
                    "veil-vm-engine-workers: relay ended for session {session_id}: {relay:?}"
                );
                let _ = close_tx
                    .send(encode(&v1::ClientMsg {
                        request_id: 0,
                        body: Some(v1::client_msg::Body::CloseSession(v1::CloseSession {
                            session_id,
                        })),
                    }))
                    .await;
            }
            Err(e) => {
                eprintln!(
                    "veil-vm-engine-workers: worker dial failed for session {session_id}: {e}"
                );
                fail_session(&data_out, &close_tx, &relay_finished, session_id, e);
            }
        }
    });

    Ok(Box::new(ManagedWorker {
        inner: RemoteWorker::new(config.session_id, commands, stats, finished),
        pidfd: open_pidfd(pid),
    }))
}

fn fail_session(
    out: &mpsc::Sender<Vec<u8>>,
    commands: &mpsc::Sender<Vec<u8>>,
    finished: &Arc<AtomicBool>,
    session_id: u64,
    error: io::Error,
) {
    let detail = format!("worker connection failed: {error}");
    let msg = v1::EngineMsg {
        request_id: 0,
        body: Some(v1::engine_msg::Body::SessionState(v1::SessionState {
            session_id,
            phase: v1::SessionPhase::Closed as i32,
            detail,
            security: None,
        })),
    };
    let _ = out.try_send(encode(&msg));
    let _ = commands.try_send(encode(&v1::ClientMsg {
        request_id: 0,
        body: Some(v1::client_msg::Body::CloseSession(v1::CloseSession {
            session_id,
        })),
    }));
    finished.store(true, Ordering::Relaxed);
}

// ---------------------------------------------------------------------------
// Payload entry point
// ---------------------------------------------------------------------------

fn serve_supervisor(
    config: EngineConfig,
    factory: Arc<dyn WorkerFactory>,
    log: Arc<LogRing>,
    mut reader: impl Read + Send + 'static,
    mut writer: impl Write + Send + 'static,
) -> io::Result<()> {
    let supervisor = Arc::new(
        Supervisor::start_with_factory_and_log(config, factory, log)
            .map_err(|e| io::Error::other(e.to_string()))?,
    );
    let output = supervisor.clone();
    let stop = Arc::new(AtomicBool::new(false));
    let stopped = stop.clone();
    let pump = std::thread::Builder::new()
        .name("veil-supervisor-out".into())
        .spawn(move || -> io::Result<()> {
            while !stopped.load(Ordering::Relaxed) {
                if let Some(msg) = output.recv(Duration::from_millis(200)) {
                    write_frame(&mut writer, &msg)?;
                }
            }
            Ok(())
        })?;
    let result = loop {
        match read_frame(&mut reader) {
            Ok(Some(msg)) => {
                if supervisor.send(msg).is_err() {
                    break Ok(());
                }
            }
            Ok(None) => break Ok(()),
            Err(e) => break Err(e),
        }
    };
    stop.store(true, Ordering::Relaxed);
    let _ = pump.join();
    if let Ok(supervisor) = Arc::try_unwrap(supervisor) {
        supervisor.shutdown();
    }
    result
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
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
    }

    // Fork the spawner before any listener or runtime thread exists. It is
    // single-threaded and never starts Tokio, so it can safely fork workers
    // later when the supervisor is fully multithreaded.
    let (spawner_parent, spawner_child) = match UnixStream::pair() {
        Ok(v) => v,
        Err(e) => {
            eprintln!("veil-vm-engine-workers: cannot create spawner socket: {e}");
            return 1;
        }
    };
    let spawner_pid = unsafe { libc::fork() };
    if spawner_pid < 0 {
        eprintln!(
            "veil-vm-engine-workers: cannot fork spawner: {}",
            io::Error::last_os_error()
        );
        return 2;
    }
    if spawner_pid == 0 {
        drop(spawner_parent);
        spawner_main(spawner_child);
    }
    drop(spawner_child);

    // The broker is a separate process. It owns vsock 5001 and forwards each
    // accepted stream over a private Unix socketpair with SCM_RIGHTS. The
    // supervisor keeps only the passive endpoint/lease queue and the relay.
    let broker_listener = match broker::listen(BROKER_PORT) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("veil-vm-engine-workers: cannot listen on {BROKER_PORT}: {e}");
            return 3;
        }
    };
    let (broker_parent, broker_child) = match UnixStream::pair() {
        Ok(v) => v,
        Err(e) => {
            eprintln!("veil-vm-engine-workers: cannot create broker socket: {e}");
            return 4;
        }
    };
    let broker_pid = unsafe { libc::fork() };
    if broker_pid < 0 {
        eprintln!(
            "veil-vm-engine-workers: cannot fork broker: {}",
            io::Error::last_os_error()
        );
        return 5;
    }
    if broker_pid == 0 {
        drop(broker_parent);
        let spawner_fd = spawner_parent.as_raw_fd();
        broker_main(broker_listener, broker_child, spawner_fd);
    }
    drop(broker_child);
    drop(broker_listener);

    let listener = match broker::listen(ENGINE_PORT) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("veil-vm-engine-workers: cannot listen on {ENGINE_PORT}: {e}");
            return 6;
        }
    };
    eprintln!(
        "veil-vm-engine-workers: listening on vsock {ENGINE_PORT} and {BROKER_PORT}; spawner pid {spawner_pid}, broker pid {broker_pid}"
    );
    unsafe { notify_ready() };

    let connection = unsafe { libc::accept(listener.as_raw_fd(), null_mut(), null_mut()) };
    if connection < 0 {
        eprintln!("veil-vm-engine-workers: engine accept failed");
        return 7;
    }
    let reader = unsafe { File::from_raw_fd(connection) };
    let writer = match reader.try_clone() {
        Ok(w) => w,
        Err(_) => return 8,
    };
    let log = Arc::new(LogRing::new());
    let dialer = Arc::new(broker::BrokeredDialer::new_passive(Some(log.clone())));
    let receiver_dialer = dialer.clone();
    let broker_reader = match std::thread::Builder::new()
        .name("veil-broker-recv".into())
        .spawn(move || broker_receiver(broker_parent, receiver_dialer))
    {
        Ok(thread) => thread,
        Err(e) => {
            eprintln!("veil-vm-engine-workers: cannot start broker receiver: {e}");
            return 9;
        }
    };
    let config = EngineConfig {
        version: "veil-engine-host (microdroid supervisor/workers)".into(),
        data_dir: None,
        dialer: Some(dialer),
        host_keys: Some(Arc::new(NoHostKeys)),
    };
    let factory: Arc<dyn WorkerFactory> = Arc::new(RemoteFactory::new(spawner_parent, spawner_pid));
    for warning in veil_engine_host::limits::apply(0, 256) {
        eprintln!("veil-vm-engine-workers: limit not applied: {warning}");
    }
    let result = serve_supervisor(config, factory, log, reader, writer);
    eprintln!("veil-vm-engine-workers: supervisor returned {result:?}");
    unsafe {
        libc::kill(broker_pid, libc::SIGTERM);
        libc::waitpid(broker_pid, null_mut(), 0);
    }
    let _ = broker_reader.join();
    match result {
        Ok(()) => 0,
        Err(_) => 10,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn broker_offer_round_trip() {
        let payload = encode_broker_offer("example.org", 2222, 42).unwrap();
        assert_eq!(
            decode_broker_offer(&payload),
            Some(("example.org".into(), 2222, 42))
        );
        assert!(decode_broker_offer(&payload[..payload.len() - 1]).is_none());
    }

    #[test]
    fn fd_passing_round_trip() {
        let (a, b) = UnixStream::pair().unwrap();
        let file = File::open("/dev/null").unwrap();
        send_fds(a.as_raw_fd(), &[file.as_raw_fd()]).unwrap();
        let (tag, fds) = recv_fds(b.as_raw_fd(), 2).unwrap();
        assert_eq!(tag, b'W');
        assert_eq!(fds.len(), 1);
        unsafe { libc::close(fds[0]) };
    }
}
