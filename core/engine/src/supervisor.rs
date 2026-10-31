//! The frontend-facing supervisor with one worker per session.
//!
//! This is the Stage 1 shape before the worker is moved behind a process
//! boundary. The supervisor owns the engine protocol, session IDs, prompt
//! answers and frame gate; each [`Worker`] owns one SSH/terminal session.
//!
//! The transport is still in-process here. A later revision replaces the
//! `Worker::start` call with a spawner/IPC assignment without changing the
//! protocol or the session boundary.

use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::AtomicU64;
use std::time::{Duration, Instant};

use tokio::runtime::Runtime;
use tokio::sync::mpsc;
use veil_caps::{Dialer, DirectDialer, FileHostKeys, HostKeyStore, NoHostKeys};
use veil_obs::LogRing;
use veil_protocol::{
    MAX_INPUT_BYTES, MAX_SESSIONS, PROTOCOL_VERSION, check_size, decode_client, encode, error_msg,
    v1,
};

use crate::EngineConfig;
use crate::worker::{
    Hop, LocalWorkerFactory, SessionWorker, WorkerCommand, WorkerConfig, WorkerFactory,
};

const QUEUE_DEPTH: usize = 64;
const MAX_KEYS: usize = 8;

pub struct Supervisor {
    runtime: Runtime,
    inbound: mpsc::Sender<Vec<u8>>,
    outbound: std::sync::Mutex<mpsc::Receiver<Vec<u8>>>,
}

impl Supervisor {
    pub fn start(config: EngineConfig) -> std::io::Result<Self> {
        Self::start_with_factory(config, Arc::new(LocalWorkerFactory))
    }

    pub fn start_with_factory(
        config: EngineConfig,
        factory: Arc<dyn WorkerFactory>,
    ) -> std::io::Result<Self> {
        Self::start_with_factory_and_log(config, factory, Arc::new(LogRing::new()))
    }

    pub fn start_with_factory_and_log(
        config: EngineConfig,
        factory: Arc<dyn WorkerFactory>,
        log: Arc<LogRing>,
    ) -> std::io::Result<Self> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("veil-supervisor")
            .enable_all()
            .build()?;
        let (inbound, inbound_rx) = mpsc::channel(QUEUE_DEPTH);
        let (outbound_tx, outbound) = mpsc::channel(QUEUE_DEPTH);
        runtime.spawn(Connection::new(config, outbound_tx, factory, log).run(inbound_rx));
        Ok(Self {
            runtime,
            inbound,
            outbound: std::sync::Mutex::new(outbound),
        })
    }

    pub fn send(&self, msg: Vec<u8>) -> Result<(), String> {
        self.inbound
            .blocking_send(msg)
            .map_err(|_| "supervisor closed".to_string())
    }

    pub fn recv(&self, timeout: Duration) -> Option<Vec<u8>> {
        let mut rx = self.outbound.lock().unwrap_or_else(|e| e.into_inner());
        match self
            .runtime
            .block_on(async { tokio::time::timeout(timeout, rx.recv()).await })
        {
            Ok(Some(msg)) => Some(msg),
            _ => None,
        }
    }

    pub fn shutdown(self) {
        self.runtime.shutdown_timeout(Duration::from_secs(2));
    }
}

struct Connection {
    config: EngineConfig,
    out: mpsc::Sender<Vec<u8>>,
    started: Instant,
    hello_done: bool,
    workers: HashMap<u64, Box<dyn SessionWorker>>,
    next_session: u64,
    next_prompt: Arc<AtomicU64>,
    log: Arc<LogRing>,
    dialer: Arc<dyn Dialer>,
    host_keys: Arc<dyn HostKeyStore>,
    factory: Arc<dyn WorkerFactory>,
    requests: u64,
    malformed_requests: u64,
}

impl Connection {
    fn new(
        config: EngineConfig,
        out: mpsc::Sender<Vec<u8>>,
        factory: Arc<dyn WorkerFactory>,
        log: Arc<LogRing>,
    ) -> Self {
        let dialer = config.dialer.clone().unwrap_or_else(|| {
            Arc::new(DirectDialer {
                timeout: Duration::from_secs(15),
            })
        });
        let host_keys = config
            .host_keys
            .clone()
            .unwrap_or_else(|| match &config.data_dir {
                Some(d) => Arc::new(FileHostKeys {
                    path: d.join("known_hosts"),
                }),
                None => Arc::new(NoHostKeys),
            });
        Self {
            config,
            out,
            started: Instant::now(),
            hello_done: false,
            workers: HashMap::new(),
            next_session: 1,
            next_prompt: Arc::new(AtomicU64::new(1)),
            log,
            dialer,
            host_keys,
            factory,
            requests: 0,
            malformed_requests: 0,
        }
    }

    async fn run(mut self, mut inbound: mpsc::Receiver<Vec<u8>>) {
        while let Some(buf) = inbound.recv().await {
            let reply = self.handle(&buf);
            if self.out.send(encode(&reply)).await.is_err() {
                break;
            }
        }
        for (_, mut worker) in self.workers.drain() {
            worker.close();
        }
    }

    fn handle(&mut self, buf: &[u8]) -> v1::EngineMsg {
        self.workers.retain(|_, worker| !worker.is_finished());
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
        let ack = || reply(v1::engine_msg::Body::Ack(v1::Ack {}));
        match msg.body {
            Some(v1::client_msg::Body::Hello(hello)) => {
                if self.hello_done {
                    return error_msg(id, v1::ErrorCode::Protocol, "Hello sent twice");
                }
                if hello.protocol_version == 0 {
                    return error_msg(id, v1::ErrorCode::Protocol, "protocol_version 0");
                }
                self.hello_done = true;
                reply(v1::engine_msg::Body::HelloAck(v1::HelloAck {
                    protocol_version: hello.protocol_version.min(PROTOCOL_VERSION),
                    engine_version: self.config.version.clone(),
                    limits: Some(veil_protocol::limits()),
                }))
            }
            _ if !self.hello_done => error_msg(
                id,
                v1::ErrorCode::Protocol,
                "the first message must be Hello",
            ),
            Some(v1::client_msg::Body::Ping(ping)) => reply(v1::engine_msg::Body::Pong(v1::Pong {
                nonce: ping.nonce,
                engine_uptime_ms: self.started.elapsed().as_millis() as u64,
            })),
            Some(v1::client_msg::Body::OpenSession(open)) => match self.open_session(open) {
                Ok(session_id) => reply(v1::engine_msg::Body::SessionOpened(v1::SessionOpened {
                    session_id,
                })),
                Err(e) => error_msg(id, v1::ErrorCode::Invalid, e),
            },
            Some(v1::client_msg::Body::HostKeyAnswer(a)) => {
                let decision = v1::HostKeyDecision::try_from(a.decision)
                    .unwrap_or(v1::HostKeyDecision::Reject);
                self.answer(a.session_id, |w| w.answer_host_key(a.prompt_id, decision))
                    .map_or_else(|e| error_msg(id, e.0, e.1), |_| ack())
            }
            Some(v1::client_msg::Body::SignAnswer(a)) => self
                .answer(a.session_id, |w| {
                    w.answer_sign(a.prompt_id, a.signature, a.cancel)
                })
                .map_or_else(|e| error_msg(id, e.0, e.1), |_| ack()),
            Some(v1::client_msg::Body::AuthAnswer(a)) => self
                .answer(a.session_id, |w| {
                    w.answer_auth(a.prompt_id, a.responses, a.cancel)
                })
                .map_or_else(|e| error_msg(id, e.0, e.1), |_| ack()),
            Some(v1::client_msg::Body::Input(input)) => {
                if input.data.len() > MAX_INPUT_BYTES {
                    return error_msg(
                        id,
                        v1::ErrorCode::Invalid,
                        format!("input over {MAX_INPUT_BYTES} bytes"),
                    );
                }
                self.command(input.session_id, WorkerCommand::Input(input.data))
                    .map_or_else(|e| error_msg(id, e.0, e.1), |_| ack())
            }
            Some(v1::client_msg::Body::Resize(r)) => match check_size(r.size.as_ref()) {
                Ok((cols, rows)) => self
                    .command(r.session_id, WorkerCommand::Resize(cols, rows))
                    .map_or_else(|e| error_msg(id, e.0, e.1), |_| ack()),
                Err(e) => error_msg(id, v1::ErrorCode::Invalid, e),
            },
            Some(v1::client_msg::Body::Scroll(s)) => {
                let command = if s.to_bottom {
                    WorkerCommand::ScrollToBottom
                } else {
                    WorkerCommand::Scroll(s.lines.clamp(-100_000, 100_000))
                };
                self.command(s.session_id, command)
                    .map_or_else(|e| error_msg(id, e.0, e.1), |_| ack())
            }
            Some(v1::client_msg::Body::FrameAck(a)) => self
                .command(a.session_id, WorkerCommand::FrameAck(a.seq))
                .map_or_else(|e| error_msg(id, e.0, e.1), |_| ack()),
            Some(v1::client_msg::Body::RequestSnapshot(r)) => self
                .command(r.session_id, WorkerCommand::Snapshot)
                .map_or_else(|e| error_msg(id, e.0, e.1), |_| ack()),
            Some(v1::client_msg::Body::CloseSession(c)) => {
                match self.workers.remove(&c.session_id) {
                    Some(mut worker) => {
                        worker.close();
                        self.log
                            .info("supervisor", c.session_id, "session closed by the frontend");
                        ack()
                    }
                    None => error_msg(
                        id,
                        v1::ErrorCode::NoSuchSession,
                        format!("no session {}", c.session_id),
                    ),
                }
            }
            Some(v1::client_msg::Body::GetStats(_)) => {
                reply(v1::engine_msg::Body::EngineStats(self.stats()))
            }
            Some(v1::client_msg::Body::GetLogs(req)) => {
                reply(v1::engine_msg::Body::LogBatch(self.log.read(&req)))
            }
            Some(v1::client_msg::Body::ListHostKeys(_)) => match self.host_keys.list() {
                Ok(entries) => reply(v1::engine_msg::Body::HostKeyList(v1::HostKeyList {
                    entries,
                })),
                Err(e) => error_msg(id, v1::ErrorCode::Internal, e),
            },
            Some(v1::client_msg::Body::ForgetHostKey(f)) => {
                let port = match f.port {
                    0 => 22,
                    p @ 1..=65535 => p as u16,
                    _ => {
                        return error_msg(id, v1::ErrorCode::Invalid, "invalid port");
                    }
                };
                if f.host.is_empty()
                    || f.host.len() > 255
                    || f.host.chars().any(char::is_whitespace)
                {
                    return error_msg(id, v1::ErrorCode::Invalid, "invalid host");
                }
                match self.host_keys.forget(&f.host, port) {
                    Ok(n) => {
                        self.log.info(
                            "hostkeys",
                            0,
                            &format!("forgot {n} key(s) for {}:{port}", f.host),
                        );
                        ack()
                    }
                    Err(e) => error_msg(id, v1::ErrorCode::Internal, e),
                }
            }
            None => error_msg(id, v1::ErrorCode::Unsupported, "unknown or empty request"),
        }
    }

    fn open_session(&mut self, open: v1::OpenSession) -> Result<u64, String> {
        if self.workers.len() >= MAX_SESSIONS as usize {
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
        let jump = match open.jump {
            Some(j) => {
                if j.host.is_empty()
                    || j.host.len() > 255
                    || j.host.chars().any(|c| c.is_control() || c.is_whitespace())
                {
                    return Err("invalid jump host".into());
                }
                let port = match j.port {
                    0 => 22,
                    p @ 1..=65535 => p as u16,
                    _ => return Err("invalid jump port".into()),
                };
                Some(Hop { host: j.host, port })
            }
            None => None,
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
        let worker = self.factory.start(WorkerConfig {
            session_id,
            host: open.host,
            port,
            connection_id: open.connection_id,
            jump,
            user: open.username,
            allow_classical_kex: open.allow_classical_kex,
            keys,
            public_keys: open.public_keys,
            cols,
            rows,
            dialer: self.dialer.clone(),
            host_keys: self.host_keys.clone(),
            out: self.out.clone(),
            log: self.log.clone(),
            next_prompt: self.next_prompt.clone(),
        });
        let worker = match worker {
            Ok(worker) => worker,
            Err(e) => {
                self.log.warn(
                    "supervisor",
                    session_id,
                    &format!("could not start a session worker: {e}"),
                );
                return Err(e);
            }
        };
        self.log
            .info("supervisor", session_id, "session worker started");
        self.workers.insert(session_id, worker);
        Ok(session_id)
    }

    fn answer(
        &self,
        session_id: u64,
        f: impl FnOnce(&dyn SessionWorker) -> bool,
    ) -> Result<(), (v1::ErrorCode, String)> {
        let worker = self.workers.get(&session_id).ok_or((
            v1::ErrorCode::NoSuchSession,
            format!("no session {session_id}"),
        ))?;
        if f(worker.as_ref()) {
            Ok(())
        } else {
            Err((
                v1::ErrorCode::Invalid,
                "no matching open prompt".to_string(),
            ))
        }
    }

    fn command(
        &self,
        session_id: u64,
        command: WorkerCommand,
    ) -> Result<(), (v1::ErrorCode, String)> {
        let worker = self.workers.get(&session_id).ok_or((
            v1::ErrorCode::NoSuchSession,
            format!("no session {session_id}"),
        ))?;
        worker
            .command(command)
            .map_err(|e| (v1::ErrorCode::Invalid, e))
    }

    fn stats(&self) -> v1::EngineStats {
        let mut sessions: Vec<_> = self.workers.values().map(|worker| worker.stats()).collect();
        sessions.sort_by_key(|s| s.session_id);
        v1::EngineStats {
            uptime_ms: self.started.elapsed().as_millis() as u64,
            sessions,
            sessions_opened: self.next_session - 1,
            requests: self.requests,
            malformed_requests: self.malformed_requests,
            last_log_seq: self.log.last_seq(),
            process: Some(veil_obs::process_metrics()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use veil_protocol::decode_engine;

    fn supervisor() -> Supervisor {
        Supervisor::start(EngineConfig {
            version: "test".into(),
            data_dir: None,
            dialer: None,
            host_keys: None,
        })
        .unwrap()
    }

    fn call(s: &Supervisor, id: u64, body: v1::client_msg::Body) -> v1::EngineMsg {
        s.send(encode(&v1::ClientMsg {
            request_id: id,
            body: Some(body),
        }))
        .unwrap();
        decode_engine(&s.recv(Duration::from_secs(5)).unwrap()).unwrap()
    }

    #[test]
    fn hello_then_ping() {
        let s = supervisor();
        let ack = call(
            &s,
            1,
            v1::client_msg::Body::Hello(v1::Hello {
                protocol_version: PROTOCOL_VERSION,
                client_name: "test".into(),
            }),
        );
        assert!(matches!(ack.body, Some(v1::engine_msg::Body::HelloAck(_))));
        let pong = call(&s, 2, v1::client_msg::Body::Ping(v1::Ping { nonce: 99 }));
        assert!(matches!(
            pong.body,
            Some(v1::engine_msg::Body::Pong(v1::Pong { nonce: 99, .. }))
        ));
        s.shutdown();
    }

    #[test]
    fn rejects_pre_hello_request() {
        let s = supervisor();
        let reply = call(&s, 1, v1::client_msg::Body::Ping(v1::Ping { nonce: 1 }));
        assert!(matches!(
            reply.body,
            Some(v1::engine_msg::Body::Error(e)) if e.code == v1::ErrorCode::Protocol as i32
        ));
        s.shutdown();
    }
}

#[cfg(test)]
mod factory_tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, Ordering};
    use veil_protocol::decode_engine;

    struct DummyWorker(u64);

    impl crate::worker::SessionWorker for DummyWorker {
        fn session_id(&self) -> u64 {
            self.0
        }
        fn stats(&self) -> v1::SessionStats {
            v1::SessionStats {
                session_id: self.0,
                ..Default::default()
            }
        }
        fn answer_host_key(&self, _: u64, _: v1::HostKeyDecision) -> bool {
            false
        }
        fn answer_auth(&self, _: u64, _: Vec<String>, _: bool) -> bool {
            false
        }
        fn answer_sign(&self, _: u64, _: Vec<u8>, _: bool) -> bool {
            false
        }
        fn command(&self, _: crate::worker::WorkerCommand) -> Result<(), String> {
            Ok(())
        }
        fn is_finished(&self) -> bool {
            false
        }
        fn close(&mut self) {}
    }

    struct Factory(AtomicBool);

    impl crate::worker::WorkerFactory for Factory {
        fn start(
            &self,
            config: crate::worker::WorkerConfig,
        ) -> Result<Box<dyn crate::worker::SessionWorker>, String> {
            self.0.store(true, Ordering::SeqCst);
            Ok(Box::new(DummyWorker(config.session_id)))
        }
    }

    #[test]
    fn supervisor_uses_injected_worker_factory() {
        let factory = Arc::new(Factory(AtomicBool::new(false)));
        let s = Supervisor::start_with_factory(
            EngineConfig {
                version: "test".into(),
                data_dir: None,
                dialer: None,
                host_keys: None,
            },
            factory.clone(),
        )
        .unwrap();
        let hello = v1::ClientMsg {
            request_id: 1,
            body: Some(v1::client_msg::Body::Hello(v1::Hello {
                protocol_version: PROTOCOL_VERSION,
                client_name: "test".into(),
            })),
        };
        s.send(encode(&hello)).unwrap();
        let _ = s.recv(Duration::from_secs(1)).unwrap();
        let open = v1::ClientMsg {
            request_id: 2,
            body: Some(v1::client_msg::Body::OpenSession(v1::OpenSession {
                host: "127.0.0.1".into(),
                port: 22,
                username: "alice".into(),
                size: Some(v1::TermSize { cols: 80, rows: 24 }),
                public_keys: vec![],
                allow_classical_kex: false,
                connection_id: 0,
                jump: None,
            })),
        };
        s.send(encode(&open)).unwrap();
        let reply = decode_engine(&s.recv(Duration::from_secs(1)).unwrap()).unwrap();
        assert!(matches!(
            reply.body,
            Some(v1::engine_msg::Body::SessionOpened(v1::SessionOpened {
                session_id: 1
            }))
        ));
        assert!(factory.0.load(Ordering::SeqCst));
        s.shutdown();
    }
}
