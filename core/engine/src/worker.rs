//! A single-session worker.
//!
//! The supervisor owns the frontend protocol, session IDs, and tenant/IPC
//! boundaries. A worker owns one SSH/terminal session in its own process. It
//! receives a stream through a [`veil_caps::Dialer`] (normally
//! [`veil_caps::FixedDialer`]), emits engine messages through `out`, and accepts
//! prompt answers and commands from its supervisor.
//!
//! Keeping this unit independent of the transport lets the same worker sit
//! behind a Unix socket, a tenant boundary, or the current in-process engine.

use std::io::{Read, Write};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use veil_caps::{Dialer, HostKeyStore};
use veil_obs::{LogRing, SessionStats};
use veil_protocol::{check_size, decode_client, v1};

use crate::session::{self, Answer, Command, Env, Prompter, Prompts, Spec};

/// What the supervisor needs from a session worker, whether the worker is in
/// this process or behind a worker IPC socket.
pub trait SessionWorker: Send {
    fn session_id(&self) -> u64;
    fn stats(&self) -> v1::SessionStats;
    fn answer_host_key(&self, prompt_id: u64, decision: v1::HostKeyDecision) -> bool;
    fn answer_auth(&self, prompt_id: u64, responses: Vec<String>, cancel: bool) -> bool;
    fn answer_sign(&self, prompt_id: u64, signature: Vec<u8>, cancel: bool) -> bool;
    fn command(&self, command: WorkerCommand) -> Result<(), String>;
    fn is_finished(&self) -> bool;
    fn close(&mut self);
}

/// Creates a session worker. The in-process implementation starts a Tokio
/// task; the process implementation will hand the configuration to a pre-forked
/// worker and return a proxy.
pub trait WorkerFactory: Send + Sync {
    fn start(&self, config: WorkerConfig) -> Result<Box<dyn SessionWorker>, String>;
}

/// The current in-process factory.
pub struct LocalWorkerFactory;

impl WorkerFactory for LocalWorkerFactory {
    fn start(&self, config: WorkerConfig) -> Result<Box<dyn SessionWorker>, String> {
        Ok(Box::new(Worker::start(config)))
    }
}

/// One SSH bastion in front of the final target.
#[derive(Clone, Debug)]
pub struct Hop {
    pub host: String,
    pub port: u16,
}

/// Configuration for one worker process.
pub struct WorkerConfig {
    pub session_id: u64,
    pub host: String,
    pub port: u16,
    pub connection_id: u64,
    pub jump: Option<Hop>,
    pub user: String,
    pub allow_classical_kex: bool,
    pub keys: Vec<russh::keys::PublicKey>,
    /// The same keys in OpenSSH form, for handing to a worker process.
    pub public_keys: Vec<String>,
    pub cols: u16,
    pub rows: u16,
    pub dialer: Arc<dyn Dialer>,
    pub host_keys: Arc<dyn HostKeyStore>,
    /// Encoded `EngineMsg`s for the supervisor.
    pub out: mpsc::Sender<Vec<u8>>,
    pub log: Arc<LogRing>,
    pub next_prompt: Arc<AtomicU64>,
}

/// Runs one worker over a supervisor-owned frame stream.
///
/// The supervisor sends encoded `ClientMsg`s (answers and commands); the
/// worker sends encoded `EngineMsg`s. The session stream itself is already
/// installed in the `WorkerConfig` dialer, normally a `FixedDialer`.
pub fn serve_worker(
    config: WorkerConfig,
    reader: impl Read + Send + 'static,
    writer: impl Write + Send + 'static,
) -> std::io::Result<()> {
    let runtime = worker_runtime()?;
    serve_worker_on(runtime, config, reader, writer)
}

/// Runs one worker with an already-connected Unix stream as its SSH transport.
/// The stream is installed in a `FixedDialer` inside the worker runtime, so no
/// descriptor or reactor escapes the process boundary.
pub fn serve_worker_with_unix_stream(
    mut config: WorkerConfig,
    data: std::os::unix::net::UnixStream,
    reader: impl Read + Send + 'static,
    writer: impl Write + Send + 'static,
) -> std::io::Result<()> {
    let runtime = worker_runtime()?;
    data.set_nonblocking(true)?;
    let _guard = runtime.enter();
    let stream = tokio::net::UnixStream::from_std(data)?;
    config.dialer = Arc::new(veil_caps::FixedDialer::new(Box::new(stream), None));
    drop(_guard);
    serve_worker_on(runtime, config, reader, writer)
}

fn worker_runtime() -> std::io::Result<tokio::runtime::Runtime> {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("veil-session-worker")
        .enable_all()
        .build()
}

fn serve_worker_on(
    runtime: tokio::runtime::Runtime,
    mut config: WorkerConfig,
    mut reader: impl Read + Send + 'static,
    mut writer: impl Write + Send + 'static,
) -> std::io::Result<()> {
    let (out, mut out_rx) = mpsc::channel::<Vec<u8>>(64);
    let stats_out = out.clone();
    let log_out = out.clone();
    let log_ring = config.log.clone();
    let log_ring_pump = log_ring.clone();
    let log_cursor = Arc::new(AtomicU64::new(0));
    let log_cursor_thread = log_cursor.clone();
    config.out = out;
    eprintln!(
        "veil-worker: session {} control loop starting",
        config.session_id
    );
    let _guard = runtime.enter();
    let mut worker = Worker::start(config);
    let writer_thread = std::thread::Builder::new()
        .name("veil-worker-out".into())
        .spawn(move || {
            while let Some(frame) = out_rx.blocking_recv() {
                if veil_wire::write_frame(&mut writer, &frame).is_err() {
                    break;
                }
            }
        })?;
    // Worker logs stay in this process; this pump forwards them over the
    // control channel so the supervisor's ring is the one GetLogs reads.
    let log_stop = Arc::new(AtomicBool::new(false));
    let log_stop_thread = log_stop.clone();
    let log_thread = std::thread::Builder::new()
        .name("veil-worker-log".into())
        .spawn(move || {
            let mut after = 0u64;
            while !log_stop_thread.load(Ordering::Relaxed) {
                std::thread::sleep(std::time::Duration::from_millis(250));
                if log_stop_thread.load(Ordering::Relaxed) {
                    break;
                }
                let batch = log_ring_pump.read(&v1::GetLogs {
                    after_seq: after,
                    max: 1000,
                    min_level: v1::LogLevel::Debug as i32,
                });
                if batch.entries.is_empty() {
                    continue;
                }
                after = batch.next_seq;
                let msg = v1::EngineMsg {
                    request_id: 0,
                    body: Some(v1::engine_msg::Body::LogBatch(batch)),
                };
                if log_out.try_send(veil_protocol::encode(&msg)).is_err() {
                    break;
                }
                log_cursor_thread.store(after, Ordering::Relaxed);
            }
        })
        .ok();
    let result = loop {
        match veil_wire::read_frame(&mut reader) {
            Ok(Some(buf)) => {
                let msg = match decode_client(&buf) {
                    Ok(msg) => msg,
                    Err(e) => {
                        break Err(std::io::Error::new(
                            std::io::ErrorKind::InvalidData,
                            format!("worker request: {e}"),
                        ));
                    }
                };
                let Some(body) = msg.body else {
                    continue;
                };
                match body {
                    v1::client_msg::Body::HostKeyAnswer(a) => {
                        let decision = v1::HostKeyDecision::try_from(a.decision)
                            .unwrap_or(v1::HostKeyDecision::Reject);
                        let _ = worker.answer_host_key(a.prompt_id, decision);
                    }
                    v1::client_msg::Body::AuthAnswer(a) => {
                        let _ = worker.answer_auth(a.prompt_id, a.responses, a.cancel);
                    }
                    v1::client_msg::Body::SignAnswer(a) => {
                        let _ = worker.answer_sign(a.prompt_id, a.signature, a.cancel);
                    }
                    v1::client_msg::Body::Input(input) => {
                        let _ = worker.command(WorkerCommand::Input(input.data));
                    }
                    v1::client_msg::Body::Resize(r) => {
                        if let Ok((cols, rows)) = check_size(r.size.as_ref()) {
                            let _ = worker.command(WorkerCommand::Resize(cols, rows));
                        }
                    }
                    v1::client_msg::Body::Scroll(s) => {
                        let command = if s.to_bottom {
                            WorkerCommand::ScrollToBottom
                        } else {
                            WorkerCommand::Scroll(s.lines.clamp(-100_000, 100_000))
                        };
                        let _ = worker.command(command);
                    }
                    v1::client_msg::Body::FrameAck(a) => {
                        let _ = worker.command(WorkerCommand::FrameAck(a.seq));
                    }
                    v1::client_msg::Body::RequestSnapshot(_) => {
                        let _ = worker.command(WorkerCommand::Snapshot);
                    }
                    v1::client_msg::Body::GetStats(_) => {
                        // Control-plane stats are intercepted by the supervisor;
                        // the frontend only sees the supervisor's aggregate.
                        let msg = v1::EngineMsg {
                            request_id: msg.request_id,
                            body: Some(v1::engine_msg::Body::EngineStats(v1::EngineStats {
                                sessions: vec![worker.stats()],
                                sessions_opened: 1,
                                ..Default::default()
                            })),
                        };
                        let _ = stats_out.try_send(veil_protocol::encode(&msg));
                    }
                    v1::client_msg::Body::CloseSession(_) => {
                        eprintln!("veil-worker: CloseSession received");
                        break Ok(());
                    }
                    _ => {
                        // Hello, Ping, stats, logs and key-store operations
                        // belong to the supervisor, not an individual worker.
                    }
                }
            }
            Ok(None) => {
                eprintln!("veil-worker: control EOF");
                break Ok(());
            }
            Err(e) => {
                eprintln!("veil-worker: control read error: {e}");
                break Err(e);
            }
        }
    };
    eprintln!("veil-worker: control loop ended; closing session");
    log_stop.store(true, Ordering::Relaxed);
    if let Some(thread) = log_thread {
        let _ = thread.join();
    }
    // Flush the worker ring once more after the pump has stopped. This keeps the
    // final CONNECTING/CLOSED entries when the control loop closes a fraction
    // before the pump's next 250 ms tick.
    let after = log_cursor.load(Ordering::Relaxed);
    let final_batch = log_ring.read(&v1::GetLogs {
        after_seq: after,
        max: 1000,
        min_level: v1::LogLevel::Debug as i32,
    });
    if !final_batch.entries.is_empty() {
        let msg = v1::EngineMsg {
            request_id: 0,
            body: Some(v1::engine_msg::Body::LogBatch(final_batch)),
        };
        let _ = stats_out.try_send(veil_protocol::encode(&msg));
    }
    worker.close();
    drop(stats_out);
    drop(_guard);
    let _ = writer_thread.join();
    eprintln!("veil-worker: output thread joined");
    result
}

/// Commands a supervisor can send to a session worker.
pub enum WorkerCommand {
    Input(Vec<u8>),
    Resize(u16, u16),
    Scroll(i32),
    ScrollToBottom,
    FrameAck(u64),
    Snapshot,
}

/// A supervisor-side proxy for a worker in another process.
///
/// The IPC owner feeds `commands` to the worker and updates `stats`/`finished`
/// as worker messages arrive. The proxy deliberately knows nothing about the
/// transport, so the same type works over a Unix socket, a tenant channel, or
/// a pre-forked worker pool.
pub struct RemoteWorker {
    session_id: u64,
    commands: mpsc::Sender<Vec<u8>>,
    stats: Arc<std::sync::Mutex<v1::SessionStats>>,
    finished: Arc<std::sync::atomic::AtomicBool>,
}

impl RemoteWorker {
    pub fn new(
        session_id: u64,
        commands: mpsc::Sender<Vec<u8>>,
        stats: Arc<std::sync::Mutex<v1::SessionStats>>,
        finished: Arc<std::sync::atomic::AtomicBool>,
    ) -> Self {
        Self {
            session_id,
            commands,
            stats,
            finished,
        }
    }

    fn send(&self, body: v1::client_msg::Body) -> bool {
        let msg = v1::ClientMsg {
            request_id: 0,
            body: Some(body),
        };
        self.commands.try_send(veil_protocol::encode(&msg)).is_ok()
    }
}

impl SessionWorker for RemoteWorker {
    fn session_id(&self) -> u64 {
        self.session_id
    }

    fn stats(&self) -> v1::SessionStats {
        self.stats.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    fn answer_host_key(&self, prompt_id: u64, decision: v1::HostKeyDecision) -> bool {
        self.send(v1::client_msg::Body::HostKeyAnswer(v1::HostKeyAnswer {
            session_id: self.session_id,
            prompt_id,
            decision: decision as i32,
        }))
    }

    fn answer_auth(&self, prompt_id: u64, responses: Vec<String>, cancel: bool) -> bool {
        self.send(v1::client_msg::Body::AuthAnswer(v1::AuthAnswer {
            session_id: self.session_id,
            prompt_id,
            responses,
            cancel,
        }))
    }

    fn answer_sign(&self, prompt_id: u64, signature: Vec<u8>, cancel: bool) -> bool {
        self.send(v1::client_msg::Body::SignAnswer(v1::SignAnswer {
            session_id: self.session_id,
            prompt_id,
            signature,
            cancel,
        }))
    }

    fn command(&self, command: WorkerCommand) -> Result<(), String> {
        let body = match command {
            WorkerCommand::Input(data) => v1::client_msg::Body::Input(v1::Input {
                session_id: self.session_id,
                data,
            }),
            WorkerCommand::Resize(cols, rows) => v1::client_msg::Body::Resize(v1::Resize {
                session_id: self.session_id,
                size: Some(v1::TermSize {
                    cols: cols as u32,
                    rows: rows as u32,
                }),
            }),
            WorkerCommand::Scroll(lines) => v1::client_msg::Body::Scroll(v1::Scroll {
                session_id: self.session_id,
                lines,
                to_bottom: false,
            }),
            WorkerCommand::ScrollToBottom => v1::client_msg::Body::Scroll(v1::Scroll {
                session_id: self.session_id,
                lines: 0,
                to_bottom: true,
            }),
            WorkerCommand::FrameAck(seq) => v1::client_msg::Body::FrameAck(v1::FrameAck {
                session_id: self.session_id,
                seq,
            }),
            WorkerCommand::Snapshot => v1::client_msg::Body::RequestSnapshot(v1::RequestSnapshot {
                session_id: self.session_id,
            }),
        };
        if self.send(body) {
            Ok(())
        } else {
            Err("session worker IPC is closed".to_string())
        }
    }

    fn is_finished(&self) -> bool {
        self.finished.load(std::sync::atomic::Ordering::Relaxed)
    }

    fn close(&mut self) {
        let _ = self.send(v1::client_msg::Body::CloseSession(v1::CloseSession {
            session_id: self.session_id,
        }));
        self.finished
            .store(true, std::sync::atomic::Ordering::Relaxed);
    }
}

/// A running single-session worker.
pub struct Worker {
    session_id: u64,
    prompts: Prompts,
    commands: mpsc::Sender<Command>,
    stats: Arc<SessionStats>,
    task: JoinHandle<()>,
}

impl Worker {
    /// Starts a session task. The caller must keep draining `config.out`.
    pub fn start(config: WorkerConfig) -> Self {
        let prompts: Prompts = Arc::new(std::sync::Mutex::new(std::collections::HashMap::new()));
        let stats = Arc::new(SessionStats::new(
            &config.host,
            config.port,
            &config.user,
            config.cols,
            config.rows,
        ));
        let prompter = Arc::new(Prompter {
            session_id: config.session_id,
            out: config.out,
            prompts: prompts.clone(),
            next_prompt: config.next_prompt,
            stats: stats.clone(),
            log: config.log,
        });
        let (commands, commands_rx) = mpsc::channel(crate::SESSION_QUEUE_DEPTH);
        let spec = Spec {
            host: config.host,
            port: config.port,
            connection_id: config.connection_id,
            jump: config.jump,
            user: config.user,
            allow_classical_kex: config.allow_classical_kex,
            keys: config.keys,
            cols: config.cols,
            rows: config.rows,
        };
        let env = Arc::new(Env {
            dialer: config.dialer,
            host_keys: config.host_keys,
        });
        let task = tokio::spawn(session::run(prompter, env, spec, commands_rx));
        Self {
            session_id: config.session_id,
            prompts,
            commands,
            stats,
            task,
        }
    }

    pub fn session_id(&self) -> u64 {
        self.session_id
    }

    pub fn stats(&self) -> v1::SessionStats {
        self.stats.snapshot(self.session_id)
    }

    pub fn is_finished(&self) -> bool {
        self.task.is_finished()
    }

    pub fn answer_host_key(&self, prompt_id: u64, decision: v1::HostKeyDecision) -> bool {
        self.answer(prompt_id, Answer::HostKey(decision))
    }

    pub fn answer_auth(&self, prompt_id: u64, responses: Vec<String>, cancel: bool) -> bool {
        self.answer(prompt_id, Answer::Auth { responses, cancel })
    }

    pub fn answer_sign(&self, prompt_id: u64, signature: Vec<u8>, cancel: bool) -> bool {
        self.answer(prompt_id, Answer::Sign { signature, cancel })
    }

    fn answer(&self, prompt_id: u64, answer: Answer) -> bool {
        let waiting = self
            .prompts
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&prompt_id);
        waiting.is_some_and(|tx| tx.send(answer).is_ok())
    }

    pub fn command(&self, command: WorkerCommand) -> Result<(), String> {
        let command = match command {
            WorkerCommand::Input(data) => Command::Input(data),
            WorkerCommand::Resize(cols, rows) => Command::Resize(cols, rows),
            WorkerCommand::Scroll(lines) => Command::Scroll(lines),
            WorkerCommand::ScrollToBottom => Command::ScrollToBottom,
            WorkerCommand::FrameAck(seq) => Command::FrameAck(seq),
            WorkerCommand::Snapshot => Command::Snapshot,
        };
        self.commands.try_send(command).map_err(|e| match e {
            mpsc::error::TrySendError::Full(_) => "session worker is busy".to_string(),
            mpsc::error::TrySendError::Closed(_) => "session worker has ended".to_string(),
        })
    }

    pub fn close(&mut self) {
        self.task.abort();
    }
}

impl SessionWorker for Worker {
    fn session_id(&self) -> u64 {
        Worker::session_id(self)
    }

    fn stats(&self) -> v1::SessionStats {
        Worker::stats(self)
    }

    fn answer_host_key(&self, prompt_id: u64, decision: v1::HostKeyDecision) -> bool {
        Worker::answer_host_key(self, prompt_id, decision)
    }

    fn answer_auth(&self, prompt_id: u64, responses: Vec<String>, cancel: bool) -> bool {
        Worker::answer_auth(self, prompt_id, responses, cancel)
    }

    fn answer_sign(&self, prompt_id: u64, signature: Vec<u8>, cancel: bool) -> bool {
        Worker::answer_sign(self, prompt_id, signature, cancel)
    }

    fn command(&self, command: WorkerCommand) -> Result<(), String> {
        Worker::command(self, command)
    }

    fn is_finished(&self) -> bool {
        Worker::is_finished(self)
    }

    fn close(&mut self) {
        Worker::close(self)
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        self.task.abort();
    }
}
