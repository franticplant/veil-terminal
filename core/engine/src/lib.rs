//! The Veil engine.
//!
//! The engine's whole interface is encoded protocol messages: [`Engine::send`]
//! takes a `ClientMsg`, [`Engine::recv`] yields `EngineMsg`s. Nothing else
//! crosses, so the same engine can sit behind JNI today and behind Binder or
//! vsock later. The engine owns no Android API; the authorities it needs are
//! handed to it: network access as a [`Dialer`], storage as a directory, and
//! later credentials as a signing capability.
//!
//! Both directions are bounded queues. A frontend that sends faster than the
//! engine handles blocks in `send`; an engine whose output is not read stops
//! producing until `recv` drains it.

mod hostkeys;
mod session;
mod ssh;

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::AtomicU64;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tokio::runtime::Runtime;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use veil_protocol::v1::{self, client_msg, engine_msg};
use veil_protocol::{
    MAX_INPUT_BYTES, MAX_SESSIONS, PROTOCOL_VERSION, check_size, decode_client, encode, error_msg,
};

use session::{Answer, Command, Prompter, Prompts};
pub use ssh::{Dialer, DirectDialer};
use veil_obs::{LogRing, SessionStats};

/// Messages queued in each direction before the sender waits.
const QUEUE_DEPTH: usize = 64;

/// Public keys one session may offer.
const MAX_KEYS: usize = 8;

/// Commands queued per session. Input beyond this is refused, not buffered.
const SESSION_QUEUE_DEPTH: usize = 256;

#[derive(Clone)]
pub struct EngineConfig {
    /// Reported in `HelloAck.engine_version`, e.g. "0.0.1 (abc1234)".
    pub version: String,
    /// Where the engine keeps state (known_hosts). None: nothing is
    /// remembered across sessions.
    pub data_dir: Option<PathBuf>,
    /// How sessions reach the network. None: [`DirectDialer`].
    pub dialer: Option<Arc<dyn Dialer>>,
}

#[derive(Debug, thiserror::Error)]
pub enum EngineError {
    #[error("engine runtime failed to start: {0}")]
    Runtime(#[from] std::io::Error),
    #[error("engine is shut down")]
    Closed,
}

/// The outcome of [`Engine::recv`].
#[derive(Debug, PartialEq, Eq)]
pub enum Recv {
    Message(Vec<u8>),
    Timeout,
    Closed,
}

pub struct Engine {
    runtime: Runtime,
    inbound: mpsc::Sender<Vec<u8>>,
    outbound: Mutex<mpsc::Receiver<Vec<u8>>>,
}

impl Engine {
    /// Starts an engine with one frontend connection.
    pub fn start(config: EngineConfig) -> Result<Engine, EngineError> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("veil-engine")
            .enable_all()
            .build()?;
        let (inbound, inbound_rx) = mpsc::channel(QUEUE_DEPTH);
        let (outbound_tx, outbound) = mpsc::channel(QUEUE_DEPTH);
        runtime.spawn(Connection::new(config, outbound_tx).run(inbound_rx));
        Ok(Engine {
            runtime,
            inbound,
            outbound: Mutex::new(outbound),
        })
    }

    /// Queues one encoded `ClientMsg`, waiting while the queue is full.
    /// Must not be called from inside the engine's runtime.
    pub fn send(&self, msg: Vec<u8>) -> Result<(), EngineError> {
        self.inbound
            .blocking_send(msg)
            .map_err(|_| EngineError::Closed)
    }

    /// Waits up to `timeout` for the next encoded `EngineMsg`.
    pub fn recv(&self, timeout: Duration) -> Recv {
        // A poisoned lock only means another reader panicked mid-call; the
        // receiver itself is still consistent.
        let mut rx = self.outbound.lock().unwrap_or_else(|e| e.into_inner());
        // The timer must be created inside the runtime, hence the async block.
        match self
            .runtime
            .block_on(async { tokio::time::timeout(timeout, rx.recv()).await })
        {
            Ok(Some(msg)) => Recv::Message(msg),
            Ok(None) => Recv::Closed,
            Err(_) => Recv::Timeout,
        }
    }

    /// Stops the engine, waiting briefly for its tasks to finish.
    pub fn shutdown(self) {
        self.runtime.shutdown_timeout(Duration::from_secs(2));
    }
}

struct SessionHandle {
    commands: mpsc::Sender<Command>,
    prompts: Prompts,
    stats: Arc<SessionStats>,
    task: JoinHandle<()>,
}

/// One frontend connection's protocol state.
struct Connection {
    config: EngineConfig,
    out: mpsc::Sender<Vec<u8>>,
    started: Instant,
    hello_done: bool,
    env: Arc<session::Env>,
    sessions: HashMap<u64, SessionHandle>,
    next_session: u64,
    next_prompt: Arc<AtomicU64>,
    log: Arc<LogRing>,
    requests: u64,
    malformed_requests: u64,
}

impl Connection {
    fn new(config: EngineConfig, out: mpsc::Sender<Vec<u8>>) -> Self {
        let log = Arc::new(LogRing::new());
        let env = Arc::new(session::Env {
            dialer: config.dialer.clone().unwrap_or_else(|| {
                Arc::new(DirectDialer {
                    timeout: Duration::from_secs(15),
                })
            }),
            known_hosts: config.data_dir.as_ref().map(|d| d.join("known_hosts")),
        });
        Connection {
            config,
            out,
            started: Instant::now(),
            hello_done: false,
            env,
            sessions: HashMap::new(),
            next_session: 1,
            next_prompt: Arc::new(AtomicU64::new(1)),
            log,
            requests: 0,
            malformed_requests: 0,
        }
    }

    async fn run(mut self, mut inbound: mpsc::Receiver<Vec<u8>>) {
        while let Some(buf) = inbound.recv().await {
            let reply = self.handle(&buf);
            if self.out.send(encode(&reply)).await.is_err() {
                break; // The frontend is gone.
            }
        }
        for (_, s) in self.sessions.drain() {
            s.task.abort();
        }
    }

    fn handle(&mut self, buf: &[u8]) -> v1::EngineMsg {
        // Sessions that ended on their own (and reported it) are forgotten.
        self.sessions.retain(|_, s| !s.task.is_finished());

        self.requests += 1;
        let msg = match decode_client(buf) {
            Ok(msg) => msg,
            Err(e) => {
                self.malformed_requests += 1;
                self.log
                    .warn("connection", 0, &format!("malformed request: {e}"));
                return error_msg(0, v1::ErrorCode::Malformed, e.to_string());
            }
        };
        let id = msg.request_id;
        let reply = |body| v1::EngineMsg {
            request_id: id,
            body: Some(body),
        };
        let ack = || reply(engine_msg::Body::Ack(v1::Ack {}));
        let result = match msg.body {
            Some(client_msg::Body::Hello(hello)) => {
                if self.hello_done {
                    return error_msg(id, v1::ErrorCode::Protocol, "Hello sent twice");
                }
                if hello.protocol_version == 0 {
                    return error_msg(id, v1::ErrorCode::Protocol, "protocol_version 0");
                }
                self.hello_done = true;
                self.log.info(
                    "connection",
                    0,
                    &format!(
                        "hello from {:?}, protocol {}",
                        hello.client_name, hello.protocol_version
                    ),
                );
                return reply(engine_msg::Body::HelloAck(v1::HelloAck {
                    protocol_version: hello.protocol_version.min(PROTOCOL_VERSION),
                    engine_version: self.config.version.clone(),
                    limits: Some(veil_protocol::limits()),
                }));
            }
            _ if !self.hello_done => {
                return error_msg(
                    id,
                    v1::ErrorCode::Protocol,
                    "the first message must be Hello",
                );
            }
            Some(client_msg::Body::Ping(ping)) => {
                return reply(engine_msg::Body::Pong(v1::Pong {
                    nonce: ping.nonce,
                    engine_uptime_ms: self.started.elapsed().as_millis() as u64,
                }));
            }
            Some(client_msg::Body::OpenSession(open)) => {
                return match self.open_session(open) {
                    Ok(session_id) => reply(engine_msg::Body::SessionOpened(v1::SessionOpened {
                        session_id,
                    })),
                    Err(e) => error_msg(id, v1::ErrorCode::Invalid, e),
                };
            }
            Some(client_msg::Body::HostKeyAnswer(a)) => {
                let decision = v1::HostKeyDecision::try_from(a.decision)
                    .unwrap_or(v1::HostKeyDecision::Reject);
                self.answer(a.session_id, a.prompt_id, Answer::HostKey(decision))
            }
            Some(client_msg::Body::SignAnswer(a)) => self.answer(
                a.session_id,
                a.prompt_id,
                Answer::Sign {
                    signature: a.signature,
                    cancel: a.cancel,
                },
            ),
            Some(client_msg::Body::AuthAnswer(a)) => self.answer(
                a.session_id,
                a.prompt_id,
                Answer::Auth {
                    responses: a.responses,
                    cancel: a.cancel,
                },
            ),
            Some(client_msg::Body::Input(input)) => {
                if input.data.len() > MAX_INPUT_BYTES {
                    Err((
                        v1::ErrorCode::Invalid,
                        format!("input over {MAX_INPUT_BYTES} bytes"),
                    ))
                } else {
                    self.command(input.session_id, Command::Input(input.data))
                }
            }
            Some(client_msg::Body::Resize(r)) => match check_size(r.size.as_ref()) {
                Ok((cols, rows)) => self.command(r.session_id, Command::Resize(cols, rows)),
                Err(e) => Err((v1::ErrorCode::Invalid, e)),
            },
            Some(client_msg::Body::Scroll(s)) => {
                let cmd = if s.to_bottom {
                    Command::ScrollToBottom
                } else {
                    Command::Scroll(s.lines.clamp(-100_000, 100_000))
                };
                self.command(s.session_id, cmd)
            }
            Some(client_msg::Body::FrameAck(a)) => {
                // A frame can be acknowledged after its session ended (the last
                // frame races the close); that is not an error.
                match self.command(a.session_id, Command::FrameAck(a.seq)) {
                    Err((v1::ErrorCode::NoSuchSession, _)) => Ok(()),
                    other => other,
                }
            }
            Some(client_msg::Body::RequestSnapshot(r)) => {
                self.command(r.session_id, Command::Snapshot)
            }
            Some(client_msg::Body::CloseSession(c)) => match self.sessions.remove(&c.session_id) {
                Some(s) => {
                    s.task.abort();
                    s.stats.set_phase(v1::SessionPhase::Closed);
                    self.log
                        .info("session", c.session_id, "closed by the frontend");
                    let out = self.out.clone();
                    let session_id = c.session_id;
                    // Reported from a task: this loop must not wait on the
                    // queue it is the only reader of.
                    tokio::spawn(async move {
                        let msg = v1::EngineMsg {
                            request_id: 0,
                            body: Some(engine_msg::Body::SessionState(v1::SessionState {
                                session_id,
                                phase: v1::SessionPhase::Closed as i32,
                                detail: "closed".into(),
                                security: None,
                            })),
                        };
                        let _ = out.send(encode(&msg)).await;
                    });
                    Ok(())
                }
                None => Err((
                    v1::ErrorCode::NoSuchSession,
                    format!("no session {}", c.session_id),
                )),
            },
            Some(client_msg::Body::GetStats(_)) => {
                return reply(engine_msg::Body::EngineStats(self.stats()));
            }
            Some(client_msg::Body::GetLogs(req)) => {
                return reply(engine_msg::Body::LogBatch(self.log.read(&req)));
            }
            Some(client_msg::Body::ListHostKeys(_)) => {
                let Some(path) = &self.env.known_hosts else {
                    return reply(engine_msg::Body::HostKeyList(v1::HostKeyList::default()));
                };
                return match hostkeys::list(path) {
                    Ok(entries) => {
                        reply(engine_msg::Body::HostKeyList(v1::HostKeyList { entries }))
                    }
                    Err(e) => error_msg(
                        id,
                        v1::ErrorCode::Internal,
                        format!("cannot read known_hosts: {e}"),
                    ),
                };
            }
            Some(client_msg::Body::ForgetHostKey(f)) => self.forget_host_key(f),
            // A variant newer than this engine: prost leaves the body empty.
            None => return error_msg(id, v1::ErrorCode::Unsupported, "unknown or empty request"),
        };
        match result {
            Ok(()) => ack(),
            Err((code, detail)) => error_msg(id, code, detail),
        }
    }

    fn open_session(&mut self, open: v1::OpenSession) -> Result<u64, String> {
        if self.sessions.len() >= MAX_SESSIONS as usize {
            return Err(format!("at most {MAX_SESSIONS} sessions"));
        }
        let (cols, rows) = check_size(open.size.as_ref())?;
        if open.host.is_empty()
            || open.host.len() > 255
            || open
                .host
                .chars()
                .any(|c| c.is_control() || c.is_whitespace())
        {
            return Err("invalid host".into());
        }
        if open.username.is_empty()
            || open.username.len() > 255
            || open.username.chars().any(char::is_control)
        {
            return Err("invalid username".into());
        }
        let port = match open.port {
            0 => 22,
            p @ 1..=65535 => p as u16,
            _ => return Err("invalid port".into()),
        };

        if open.public_keys.len() > MAX_KEYS {
            return Err(format!("at most {MAX_KEYS} public keys"));
        }
        let keys = open
            .public_keys
            .iter()
            .map(|k| {
                russh::keys::PublicKey::from_openssh(k)
                    .map_err(|e| format!("invalid public key: {e}"))
            })
            .collect::<Result<Vec<_>, _>>()?;
        let session_id = self.next_session;
        self.next_session += 1;
        let stats = Arc::new(SessionStats::new(
            &open.host,
            port,
            &open.username,
            cols,
            rows,
        ));
        self.log.info(
            "session",
            session_id,
            &format!(
                "open {}@{}:{port} ({cols}x{rows})",
                open.username, open.host
            ),
        );
        let prompts: Prompts = Arc::new(Mutex::new(HashMap::new()));
        let prompter = Arc::new(Prompter {
            session_id,
            out: self.out.clone(),
            prompts: prompts.clone(),
            next_prompt: self.next_prompt.clone(),
            stats: stats.clone(),
            log: self.log.clone(),
        });
        let (commands, commands_rx) = mpsc::channel(SESSION_QUEUE_DEPTH);
        let spec = session::Spec {
            host: open.host,
            port,
            user: open.username,
            allow_classical_kex: open.allow_classical_kex,
            keys,
            cols,
            rows,
        };
        let task = tokio::spawn(session::run(prompter, self.env.clone(), spec, commands_rx));
        self.sessions.insert(
            session_id,
            SessionHandle {
                commands,
                prompts,
                stats,
                task,
            },
        );
        Ok(session_id)
    }

    fn stats(&self) -> v1::EngineStats {
        let mut sessions: Vec<_> = self
            .sessions
            .iter()
            .map(|(id, s)| s.stats.snapshot(*id))
            .collect();
        sessions.sort_by_key(|s| s.session_id);
        v1::EngineStats {
            uptime_ms: self.started.elapsed().as_millis() as u64,
            sessions,
            sessions_opened: self.next_session - 1,
            requests: self.requests,
            malformed_requests: self.malformed_requests,
            last_log_seq: self.log.last_seq(),
        }
    }

    fn forget_host_key(&mut self, f: v1::ForgetHostKey) -> Result<(), (v1::ErrorCode, String)> {
        let port = match f.port {
            0 => 22,
            p @ 1..=65535 => p as u16,
            _ => return Err((v1::ErrorCode::Invalid, "invalid port".into())),
        };
        if f.host.is_empty() || f.host.len() > 255 || f.host.chars().any(char::is_whitespace) {
            return Err((v1::ErrorCode::Invalid, "invalid host".into()));
        }
        let Some(path) = &self.env.known_hosts else {
            return Ok(());
        };
        match hostkeys::forget(path, &f.host, port) {
            Ok(n) => {
                self.log.info(
                    "hostkeys",
                    0,
                    &format!("forgot {n} key(s) for {}:{port}", f.host),
                );
                Ok(())
            }
            Err(e) => Err((
                v1::ErrorCode::Internal,
                format!("cannot update known_hosts: {e}"),
            )),
        }
    }

    fn answer(
        &mut self,
        session_id: u64,
        prompt_id: u64,
        answer: Answer,
    ) -> Result<(), (v1::ErrorCode, String)> {
        let session = self.sessions.get(&session_id).ok_or((
            v1::ErrorCode::NoSuchSession,
            format!("no session {session_id}"),
        ))?;
        let waiting = session
            .prompts
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&prompt_id)
            .ok_or((
                v1::ErrorCode::Invalid,
                format!("no open prompt {prompt_id}"),
            ))?;
        let _ = waiting.send(answer);
        Ok(())
    }

    fn command(&mut self, session_id: u64, cmd: Command) -> Result<(), (v1::ErrorCode, String)> {
        let session = self.sessions.get(&session_id).ok_or((
            v1::ErrorCode::NoSuchSession,
            format!("no session {session_id}"),
        ))?;
        session.commands.try_send(cmd).map_err(|e| match e {
            mpsc::error::TrySendError::Full(_) => (
                v1::ErrorCode::Invalid,
                "session is busy; input refused".into(),
            ),
            mpsc::error::TrySendError::Closed(_) => (
                v1::ErrorCode::NoSuchSession,
                format!("session {session_id} has ended"),
            ),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use veil_protocol::decode_engine;

    fn engine() -> Engine {
        Engine::start(EngineConfig {
            version: "test".into(),
            data_dir: None,
            dialer: None,
        })
        .unwrap()
    }

    fn call(e: &Engine, id: u64, body: client_msg::Body) -> v1::EngineMsg {
        let msg = v1::ClientMsg {
            request_id: id,
            body: Some(body),
        };
        e.send(encode(&msg)).unwrap();
        match e.recv(Duration::from_secs(5)) {
            Recv::Message(buf) => decode_engine(&buf).unwrap(),
            other => panic!("no reply: {other:?}"),
        }
    }

    fn hello() -> client_msg::Body {
        client_msg::Body::Hello(v1::Hello {
            protocol_version: PROTOCOL_VERSION,
            client_name: "test".into(),
        })
    }

    fn error_code(msg: &v1::EngineMsg) -> Option<v1::ErrorCode> {
        match &msg.body {
            Some(engine_msg::Body::Error(e)) => v1::ErrorCode::try_from(e.code).ok(),
            _ => None,
        }
    }

    #[test]
    fn hello_then_ping() {
        let e = engine();
        let ack = call(&e, 1, hello());
        assert_eq!(ack.request_id, 1);
        let Some(engine_msg::Body::HelloAck(ack)) = ack.body else {
            panic!("expected HelloAck, got {ack:?}");
        };
        assert_eq!(ack.protocol_version, PROTOCOL_VERSION);
        assert_eq!(ack.limits, Some(veil_protocol::limits()));

        let pong = call(&e, 2, client_msg::Body::Ping(v1::Ping { nonce: 99 }));
        assert_eq!(pong.request_id, 2);
        assert!(matches!(
            pong.body,
            Some(engine_msg::Body::Pong(v1::Pong { nonce: 99, .. }))
        ));
        e.shutdown();
    }

    #[test]
    fn refuses_requests_before_hello() {
        let e = engine();
        let reply = call(&e, 1, client_msg::Body::Ping(v1::Ping { nonce: 1 }));
        assert_eq!(error_code(&reply), Some(v1::ErrorCode::Protocol));
    }

    #[test]
    fn refuses_second_hello() {
        let e = engine();
        call(&e, 1, hello());
        assert_eq!(
            error_code(&call(&e, 2, hello())),
            Some(v1::ErrorCode::Protocol)
        );
    }

    #[test]
    fn malformed_input_gets_an_error_not_a_crash() {
        let e = engine();
        e.send(vec![0x52, 0xff, 0xff, 0x03]).unwrap();
        let Recv::Message(buf) = e.recv(Duration::from_secs(5)) else {
            panic!("no reply");
        };
        let reply = decode_engine(&buf).unwrap();
        assert_eq!(error_code(&reply), Some(v1::ErrorCode::Malformed));
        // And the connection still works.
        assert!(error_code(&call(&e, 1, hello())).is_none());
    }

    #[test]
    fn recv_times_out_when_idle() {
        let e = engine();
        assert_eq!(e.recv(Duration::from_millis(20)), Recv::Timeout);
    }
}
