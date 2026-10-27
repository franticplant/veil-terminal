//! End to end through the engine protocol: a real SSH server (russh) on
//! localhost, driven only by encoded ClientMsg / EngineMsg bytes, the way a
//! frontend drives the engine.

use std::sync::Arc;
use std::time::{Duration, Instant};

use russh::keys::ssh_encoding::Encode;
use russh::keys::{Algorithm, EcdsaCurve, PrivateKey, PublicKey};
use russh::server::{self, Auth, Msg, Session};
use russh::{Channel, ChannelId, MethodKind, MethodSet};
use tokio::net::TcpListener;
use veil_engine::{Engine, EngineConfig, Recv};
use veil_protocol::v1::{self, client_msg, engine_msg};
use veil_protocol::{decode_engine, encode};

const PASSWORD: &str = "correct horse";

/// Accepts one password, then gives a "shell" that echoes input and answers
/// "exit\r" by exiting with status 3.
#[derive(Clone)]
struct TestServer {
    line: String,
    /// The one public key accepted for alice, if any.
    key: Option<PublicKey>,
}

impl TestServer {
    fn judge(&self, user: &str, key: &PublicKey) -> Auth {
        if user == "alice" && self.key.as_ref() == Some(key) {
            Auth::Accept
        } else {
            Auth::Reject {
                proceed_with_methods: Some(MethodSet::from(&[MethodKind::Password][..])),
                partial_success: false,
            }
        }
    }
}

impl server::Handler for TestServer {
    type Error = russh::Error;

    async fn auth_publickey_offered(
        &mut self,
        user: &str,
        offer: server::PublicKeyOffer<'_>,
    ) -> Result<Auth, Self::Error> {
        Ok(self.judge(user, offer.public_key))
    }

    async fn auth_publickey(
        &mut self,
        user: &str,
        key: server::VerifiedPublicKey<'_>,
    ) -> Result<Auth, Self::Error> {
        Ok(self.judge(user, key.public_key))
    }

    async fn auth_password(&mut self, user: &str, password: &str) -> Result<Auth, Self::Error> {
        if user == "alice" && password == PASSWORD {
            Ok(Auth::Accept)
        } else {
            // Like OpenSSH: a wrong password may be retried. (russh's default
            // rejection stops offering password after one failure.)
            Ok(Auth::Reject {
                proceed_with_methods: Some(MethodSet::from(&[MethodKind::Password][..])),
                partial_success: false,
            })
        }
    }

    async fn channel_open_session(
        &mut self,
        _channel: Channel<Msg>,
        reply: server::ChannelOpenHandle,
        _session: &mut Session,
    ) -> Result<(), Self::Error> {
        reply.accept().await;
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    async fn pty_request(
        &mut self,
        channel: ChannelId,
        _term: &str,
        _cols: u32,
        _rows: u32,
        _pw: u32,
        _ph: u32,
        _modes: &[(russh::Pty, u32)],
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        session.channel_success(channel)?;
        Ok(())
    }

    async fn shell_request(
        &mut self,
        channel: ChannelId,
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        session.channel_success(channel)?;
        session.data(channel, b"welcome\r\n$ ".to_vec())?;
        Ok(())
    }

    async fn window_change_request(
        &mut self,
        channel: ChannelId,
        cols: u32,
        rows: u32,
        _pw: u32,
        _ph: u32,
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        session.data(channel, format!("[resized {cols}x{rows}]").into_bytes())?;
        Ok(())
    }

    async fn data(
        &mut self,
        channel: ChannelId,
        data: &[u8],
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        session.data(channel, data.to_vec())?;
        self.line.push_str(&String::from_utf8_lossy(data));
        if self.line.ends_with("exit\r") {
            session.exit_status_request(channel, 3)?;
            session.close(channel)?;
        }
        Ok(())
    }
}

/// Starts the server; returns its port.
async fn start_server() -> u16 {
    start_server_with(None).await
}

/// Starts a server that also accepts `key` for alice.
async fn start_server_with(key: Option<PublicKey>) -> u16 {
    start_server_full(key, false).await
}

/// `classical_only`: the server offers no post-quantum key exchange.
async fn start_server_full(key: Option<PublicKey>, classical_only: bool) -> u16 {
    let mut preferred = russh::Preferred::DEFAULT.clone();
    if classical_only {
        preferred.kex = std::borrow::Cow::Owned(
            preferred
                .kex
                .iter()
                .filter(|k| !k.as_ref().starts_with("mlkem"))
                .cloned()
                .collect(),
        );
    }
    let config = Arc::new(server::Config {
        preferred,
        keys: vec![PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap()],
        auth_rejection_time: Duration::from_millis(10),
        auth_rejection_time_initial: Some(Duration::from_millis(0)),
        ..Default::default()
    });
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        loop {
            let (stream, _) = listener.accept().await.unwrap();
            let config = config.clone();
            let key = key.clone();
            tokio::spawn(async move {
                let handler = TestServer {
                    line: String::new(),
                    key: key.clone(),
                };
                if let Ok(running) = server::run_stream(config, stream, handler).await {
                    let _ = running.await;
                }
            });
        }
    });
    port
}

/// A frontend, minimal: sends requests, collects events, keeps the screen.
struct Frontend {
    engine: Engine,
    next_id: u64,
    events: Vec<v1::EngineMsg>,
    screen: Vec<String>,
    /// FrameAck requests whose replies have not arrived yet.
    acks: std::collections::HashSet<u64>,
    /// Sent with OpenSession.
    allow_classical: bool,
}

impl Frontend {
    fn new(data_dir: &std::path::Path) -> Frontend {
        let engine = Engine::start(EngineConfig {
            version: "test".into(),
            data_dir: Some(data_dir.to_path_buf()),
            dialer: None,
            host_keys: None,
        })
        .unwrap();
        let mut f = Frontend {
            engine,
            next_id: 1,
            events: Vec::new(),
            screen: Vec::new(),
            acks: Default::default(),
            allow_classical: false,
        };
        let ack = f.call(client_msg::Body::Hello(v1::Hello {
            protocol_version: 1,
            client_name: "test".into(),
        }));
        assert!(matches!(ack.body, Some(engine_msg::Body::HelloAck(_))));
        f
    }

    /// Sends a request and returns its reply; events seen meanwhile are kept.
    fn call(&mut self, body: client_msg::Body) -> v1::EngineMsg {
        let id = self.next_id;
        self.next_id += 1;
        let what = format!("{body:?}");
        self.engine
            .send(encode(&v1::ClientMsg {
                request_id: id,
                body: Some(body),
            }))
            .unwrap();
        loop {
            let Some(msg) = self.recv(Duration::from_secs(10)) else {
                panic!(
                    "no reply to request {id} ({what}); events so far: {:#?}",
                    self.events
                );
            };
            if msg.request_id == id {
                return msg;
            }
            self.keep(msg);
        }
    }

    /// Sends a FrameAck without waiting: its reply is checked when it arrives (in [`keep`]). Waiting
    /// here would nest inside another request's wait and could take that request's reply.
    fn ack(&mut self, session_id: u64, seq: u64) {
        let id = self.next_id;
        self.next_id += 1;
        self.acks.insert(id);
        self.engine
            .send(encode(&v1::ClientMsg {
                request_id: id,
                body: Some(client_msg::Body::FrameAck(v1::FrameAck { session_id, seq })),
            }))
            .unwrap();
    }

    fn recv(&mut self, timeout: Duration) -> Option<v1::EngineMsg> {
        match self.engine.recv(timeout) {
            Recv::Message(buf) => Some(decode_engine(&buf).unwrap()),
            _ => None,
        }
    }

    /// Records an event; frames update the screen and are acknowledged.
    fn keep(&mut self, msg: v1::EngineMsg) {
        if let Some(engine_msg::Body::Frame(frame)) = &msg.body {
            let rows = frame.size.unwrap().rows as usize;
            self.screen.resize(rows, String::new());
            for row in &frame.rows {
                let mut s = String::new();
                for run in &row.runs {
                    while (s.chars().count() as u32) < run.col {
                        s.push(' ');
                    }
                    s.push_str(&run.text);
                }
                self.screen[row.index as usize] = s;
            }
            let (session_id, seq) = (frame.session_id, frame.seq);
            self.ack(session_id, seq);
        }
        if self.acks.remove(&msg.request_id) {
            assert!(
                matches!(msg.body, Some(engine_msg::Body::Ack(_))),
                "{msg:?}"
            );
            return;
        }
        self.events.push(msg);
    }

    /// Waits until `pred` matches an event (taking it) or times out.
    fn wait_for(&mut self, what: &str, pred: impl Fn(&v1::EngineMsg) -> bool) -> v1::EngineMsg {
        let deadline = Instant::now() + Duration::from_secs(15);
        loop {
            if let Some(i) = self.events.iter().position(&pred) {
                return self.events.remove(i);
            }
            let left = deadline.saturating_duration_since(Instant::now());
            assert!(
                !left.is_zero(),
                "timed out waiting for {what}; events: {:#?}",
                self.events
            );
            if let Some(msg) = self.recv(left.min(Duration::from_millis(200))) {
                self.keep(msg);
            }
        }
    }

    fn wait_screen(&mut self, needle: &str) {
        let deadline = Instant::now() + Duration::from_secs(15);
        while !self.screen.iter().any(|l| l.contains(needle)) {
            assert!(
                Instant::now() < deadline,
                "screen never showed {needle:?}: {:#?}",
                self.screen
            );
            if let Some(msg) = self.recv(Duration::from_millis(200)) {
                self.keep(msg);
            }
        }
    }

    fn state(&mut self, phase: v1::SessionPhase) -> v1::SessionState {
        let msg = self.wait_for(&format!("{phase:?}"), |m| {
            matches!(&m.body, Some(engine_msg::Body::SessionState(s)) if s.phase == phase as i32)
        });
        match msg.body {
            Some(engine_msg::Body::SessionState(s)) => s,
            _ => unreachable!(),
        }
    }

    fn open(&mut self, port: u16) -> u64 {
        self.open_with_keys(port, vec![])
    }

    fn open_with_keys(&mut self, port: u16, public_keys: Vec<String>) -> u64 {
        let reply = self.call(client_msg::Body::OpenSession(v1::OpenSession {
            host: "127.0.0.1".into(),
            port: port as u32,
            username: "alice".into(),
            size: Some(v1::TermSize { cols: 40, rows: 10 }),
            public_keys,
            allow_classical_kex: self.allow_classical,
        }));
        match reply.body {
            Some(engine_msg::Body::SessionOpened(o)) => o.session_id,
            other => panic!("open failed: {other:?}"),
        }
    }

    fn auth_prompt(&mut self) -> v1::AuthPrompt {
        match self
            .wait_for("auth prompt", |m| {
                matches!(m.body, Some(engine_msg::Body::AuthPrompt(_)))
            })
            .body
        {
            Some(engine_msg::Body::AuthPrompt(p)) => p,
            _ => unreachable!(),
        }
    }

    fn answer_password(&mut self, p: &v1::AuthPrompt, password: &str) {
        let ack = self.call(client_msg::Body::AuthAnswer(v1::AuthAnswer {
            session_id: p.session_id,
            prompt_id: p.prompt_id,
            responses: vec![password.into()],
            cancel: false,
        }));
        assert!(
            matches!(ack.body, Some(engine_msg::Body::Ack(_))),
            "{ack:?}"
        );
    }

    fn input(&mut self, session_id: u64, text: &str) {
        let ack = self.call(client_msg::Body::Input(v1::Input {
            session_id,
            data: text.as_bytes().to_vec(),
        }));
        assert!(
            matches!(ack.body, Some(engine_msg::Body::Ack(_))),
            "{ack:?}"
        );
    }
}

#[test]
fn interactive_session_end_to_end() {
    let rt = tokio::runtime::Runtime::new().unwrap();
    let port = rt.block_on(start_server());
    let dir = tempfile::tempdir().unwrap();
    let mut f = Frontend::new(dir.path());

    let session = f.open(port);
    f.state(v1::SessionPhase::Connecting);

    // Unknown host: the engine asks. Remember the key.
    let prompt = match f
        .wait_for("host key prompt", |m| {
            matches!(m.body, Some(engine_msg::Body::HostKeyPrompt(_)))
        })
        .body
    {
        Some(engine_msg::Body::HostKeyPrompt(p)) => p,
        _ => unreachable!(),
    };
    assert_eq!(prompt.algorithm, "ssh-ed25519");
    assert!(
        prompt.fingerprint.starts_with("SHA256:"),
        "{}",
        prompt.fingerprint
    );
    f.call(client_msg::Body::HostKeyAnswer(v1::HostKeyAnswer {
        session_id: session,
        prompt_id: prompt.prompt_id,
        decision: v1::HostKeyDecision::AcceptAndRemember as i32,
    }));

    // A wrong password is retried, then the right one gets in.
    let p = f.auth_prompt();
    assert_eq!(p.kind, v1::AuthKind::Password as i32);
    assert!(!p.retry);
    assert!(!p.fields[0].echo);
    f.answer_password(&p, "wrong");
    let p = f.auth_prompt();
    assert!(p.retry);
    f.answer_password(&p, PASSWORD);
    let open = f.state(v1::SessionPhase::Open);
    let security = open.security.expect("security with OPEN");
    assert_eq!(security.kex, "mlkem768x25519-sha256");
    assert!(security.post_quantum);
    assert_eq!(security.host_key_algorithm, "ssh-ed25519");

    f.wait_screen("welcome");
    f.input(session, "echo hi");
    f.wait_screen("$ echo hi");

    let ack = f.call(client_msg::Body::Resize(v1::Resize {
        session_id: session,
        size: Some(v1::TermSize { cols: 60, rows: 12 }),
    }));
    assert!(matches!(ack.body, Some(engine_msg::Body::Ack(_))));
    f.wait_screen("[resized 60x12]");
    assert_eq!(f.screen.len(), 12);

    // Diagnostics: the live session's counters, the log, the recorded key.
    let stats = match f.call(client_msg::Body::GetStats(v1::GetStats {})).body {
        Some(engine_msg::Body::EngineStats(s)) => s,
        other => panic!("expected EngineStats, got {other:?}"),
    };
    assert_eq!(stats.sessions_opened, 1);
    let s = &stats.sessions[0];
    assert_eq!((s.session_id, s.username.as_str()), (session, "alice"));
    assert_eq!(s.phase, v1::SessionPhase::Open as i32);
    assert_eq!(s.size, Some(v1::TermSize { cols: 60, rows: 12 }));
    assert!(s.bytes_from_remote > 0 && s.bytes_to_remote > 0 && s.frames_sent > 0);
    // The wire carries more than the payload: framing, encryption, the handshake.
    assert!(s.wire_bytes_from_remote > s.bytes_from_remote, "{s:?}");
    assert!(s.wire_bytes_to_remote > s.bytes_to_remote, "{s:?}");
    assert!(s.peer_address.starts_with("127.0.0.1:"), "{s:?}");
    let process = stats.process.expect("process metrics");
    assert!(process.rss_kb > 0 && process.threads > 0, "{process:?}");
    let logs = match f
        .call(client_msg::Body::GetLogs(v1::GetLogs::default()))
        .body
    {
        Some(engine_msg::Body::LogBatch(b)) => b,
        other => panic!("expected LogBatch, got {other:?}"),
    };
    let text: Vec<_> = logs.entries.iter().map(|e| e.message.as_str()).collect();
    assert!(
        text.iter()
            .any(|m| m.starts_with("accepted and recorded SHA256:")),
        "{text:?}"
    );
    assert!(text.contains(&"OPEN"), "{text:?}");
    assert!(
        !text.iter().any(|m| m.contains(PASSWORD)),
        "a log holds the password"
    );
    let keys = match f
        .call(client_msg::Body::ListHostKeys(v1::ListHostKeys {}))
        .body
    {
        Some(engine_msg::Body::HostKeyList(l)) => l.entries,
        other => panic!("expected HostKeyList, got {other:?}"),
    };
    assert_eq!(keys.len(), 1);
    assert_eq!(
        (keys[0].host.as_str(), keys[0].port),
        ("127.0.0.1", port as u32)
    );
    assert_eq!(keys[0].fingerprint, prompt.fingerprint);

    f.input(session, "exit\r");
    let closed = f.state(v1::SessionPhase::Closed);
    assert!(closed.detail.contains("status 3"), "{}", closed.detail);

    // The key was remembered: a second session goes straight to auth.
    let second = f.open(port);
    let p = f.auth_prompt();
    assert_eq!(p.session_id, second);
    assert!(
        !f.events
            .iter()
            .any(|m| matches!(m.body, Some(engine_msg::Body::HostKeyPrompt(_)))),
        "asked about a remembered key"
    );
    f.answer_password(&p, PASSWORD);
    f.state(v1::SessionPhase::Open);

    // Closing from the frontend.
    let ack = f.call(client_msg::Body::CloseSession(v1::CloseSession {
        session_id: second,
    }));
    assert!(matches!(ack.body, Some(engine_msg::Body::Ack(_))));
    f.state(v1::SessionPhase::Closed);
    let gone = f.call(client_msg::Body::Input(v1::Input {
        session_id: second,
        data: b"x".to_vec(),
    }));
    assert!(matches!(gone.body, Some(engine_msg::Body::Error(_))));

    // Forgetting the key brings the prompt back.
    let ack = f.call(client_msg::Body::ForgetHostKey(v1::ForgetHostKey {
        host: "127.0.0.1".into(),
        port: port as u32,
    }));
    assert!(
        matches!(ack.body, Some(engine_msg::Body::Ack(_))),
        "{ack:?}"
    );
    f.open(port);
    f.wait_for("host key prompt after forgetting", |m| {
        matches!(m.body, Some(engine_msg::Body::HostKeyPrompt(_)))
    });
    drop(rt);
}

#[test]
fn rejected_host_key_closes_the_session() {
    let rt = tokio::runtime::Runtime::new().unwrap();
    let port = rt.block_on(start_server());
    let dir = tempfile::tempdir().unwrap();
    let mut f = Frontend::new(dir.path());
    let session = f.open(port);
    let prompt = match f
        .wait_for("host key prompt", |m| {
            matches!(m.body, Some(engine_msg::Body::HostKeyPrompt(_)))
        })
        .body
    {
        Some(engine_msg::Body::HostKeyPrompt(p)) => p,
        _ => unreachable!(),
    };
    f.call(client_msg::Body::HostKeyAnswer(v1::HostKeyAnswer {
        session_id: session,
        prompt_id: prompt.prompt_id,
        decision: v1::HostKeyDecision::Reject as i32,
    }));
    let closed = f.state(v1::SessionPhase::Closed);
    assert_eq!(closed.detail, "host key rejected");
    assert!(!dir.path().join("known_hosts").exists());
    drop(rt);
}

#[test]
fn changed_host_key_is_refused_without_asking() {
    let rt = tokio::runtime::Runtime::new().unwrap();
    let port = rt.block_on(start_server());
    let dir = tempfile::tempdir().unwrap();
    // A different key recorded for this host and port.
    let other = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap();
    std::fs::write(
        dir.path().join("known_hosts"),
        format!(
            "[127.0.0.1]:{port} {}\n",
            other.public_key().to_openssh().unwrap()
        ),
    )
    .unwrap();
    let mut f = Frontend::new(dir.path());
    f.open(port);
    let closed = f.state(v1::SessionPhase::Closed);
    assert!(
        closed.detail.starts_with("HOST KEY CHANGED"),
        "{}",
        closed.detail
    );
    assert!(
        !f.events
            .iter()
            .any(|m| matches!(m.body, Some(engine_msg::Body::HostKeyPrompt(_))))
    );
    drop(rt);
}

#[test]
fn bad_requests_are_refused() {
    let dir = tempfile::tempdir().unwrap();
    let mut f = Frontend::new(dir.path());
    for open in [
        v1::OpenSession {
            host: "".into(),
            port: 22,
            username: "a".into(),
            size: Some(v1::TermSize { cols: 80, rows: 24 }),
            public_keys: vec![],
            allow_classical_kex: false,
        },
        v1::OpenSession {
            host: "h".into(),
            port: 70000,
            username: "a".into(),
            size: Some(v1::TermSize { cols: 80, rows: 24 }),
            public_keys: vec![],
            allow_classical_kex: false,
        },
        v1::OpenSession {
            host: "h".into(),
            port: 22,
            username: "a".into(),
            size: Some(v1::TermSize {
                cols: 10_000,
                rows: 24,
            }),
            public_keys: vec![],
            allow_classical_kex: false,
        },
    ] {
        let reply = f.call(client_msg::Body::OpenSession(open));
        assert!(
            matches!(reply.body, Some(engine_msg::Body::Error(_))),
            "{reply:?}"
        );
    }
    let reply = f.call(client_msg::Body::Input(v1::Input {
        session_id: 99,
        data: vec![1],
    }));
    match reply.body {
        Some(engine_msg::Body::Error(e)) => assert_eq!(e.code, v1::ErrorCode::NoSuchSession as i32),
        other => panic!("{other:?}"),
    }
}

fn sign_request(f: &mut Frontend) -> v1::SignRequest {
    match f
        .wait_for("sign request", |m| {
            matches!(m.body, Some(engine_msg::Body::SignRequest(_)))
        })
        .body
    {
        Some(engine_msg::Body::SignRequest(r)) => r,
        _ => unreachable!(),
    }
}

#[test]
fn key_authentication_signs_through_the_frontend() {
    let rt = tokio::runtime::Runtime::new().unwrap();
    let key = PrivateKey::random(
        &mut rand::rng(),
        Algorithm::Ecdsa {
            curve: EcdsaCurve::NistP256,
        },
    )
    .unwrap();
    let public = key.public_key().clone();
    let port = rt.block_on(start_server_with(Some(public.clone())));
    let dir = tempfile::tempdir().unwrap();
    let mut f = Frontend::new(dir.path());

    let openssh = public.to_openssh().unwrap();
    let session = f.open_with_keys(port, vec![openssh.clone()]);
    let p = match f
        .wait_for("host key prompt", |m| {
            matches!(m.body, Some(engine_msg::Body::HostKeyPrompt(_)))
        })
        .body
    {
        Some(engine_msg::Body::HostKeyPrompt(p)) => p,
        _ => unreachable!(),
    };
    f.call(client_msg::Body::HostKeyAnswer(v1::HostKeyAnswer {
        session_id: session,
        prompt_id: p.prompt_id,
        decision: v1::HostKeyDecision::AcceptOnce as i32,
    }));

    // The engine asks to sign a publickey userauth request for alice.
    let req = sign_request(&mut f);
    assert_eq!(req.public_key, openssh);
    assert_eq!(req.username, "alice");
    let marker = |s: &[u8]| req.data.windows(s.len()).any(|w| w == s);
    assert!(marker(b"publickey") && marker(b"alice") && marker(b"ssh-connection"));

    // The "frontend" signs with the private key the engine never saw.
    let sig: russh::keys::ssh_key::Signature =
        russh::keys::signature::Signer::try_sign(&key, &req.data).unwrap();
    f.call(client_msg::Body::SignAnswer(v1::SignAnswer {
        session_id: session,
        prompt_id: req.prompt_id,
        signature: sig.encode_vec().unwrap(),
        cancel: false,
    }));
    f.state(v1::SessionPhase::Open);
    assert!(
        !f.events
            .iter()
            .any(|m| matches!(m.body, Some(engine_msg::Body::AuthPrompt(_)))),
        "asked for a password although the key was accepted"
    );

    // A refused signature falls back to the password.
    let second = f.open_with_keys(port, vec![openssh]);
    let p = match f
        .wait_for("host key prompt", |m| {
            matches!(m.body, Some(engine_msg::Body::HostKeyPrompt(_)))
        })
        .body
    {
        Some(engine_msg::Body::HostKeyPrompt(p)) => p,
        _ => unreachable!(),
    };
    f.call(client_msg::Body::HostKeyAnswer(v1::HostKeyAnswer {
        session_id: second,
        prompt_id: p.prompt_id,
        decision: v1::HostKeyDecision::AcceptOnce as i32,
    }));
    let req = sign_request(&mut f);
    f.call(client_msg::Body::SignAnswer(v1::SignAnswer {
        session_id: second,
        prompt_id: req.prompt_id,
        signature: vec![],
        cancel: true,
    }));
    let p = f.auth_prompt();
    assert_eq!(p.session_id, second);
    f.answer_password(&p, PASSWORD);
    f.state(v1::SessionPhase::Open);
    drop(rt);
}

#[test]
fn classical_key_exchange_is_refused_unless_allowed() {
    let rt = tokio::runtime::Runtime::new().unwrap();
    let port = rt.block_on(start_server_full(None, true));
    let dir = tempfile::tempdir().unwrap();
    let mut f = Frontend::new(dir.path());

    f.open(port);
    let closed = f.state(v1::SessionPhase::Closed);
    assert!(
        closed.detail.contains("no post-quantum key exchange"),
        "{}",
        closed.detail
    );

    f.allow_classical = true;
    let session = f.open(port);
    let p = match f
        .wait_for("host key prompt", |m| {
            matches!(m.body, Some(engine_msg::Body::HostKeyPrompt(_)))
        })
        .body
    {
        Some(engine_msg::Body::HostKeyPrompt(p)) => p,
        _ => unreachable!(),
    };
    f.call(client_msg::Body::HostKeyAnswer(v1::HostKeyAnswer {
        session_id: session,
        prompt_id: p.prompt_id,
        decision: v1::HostKeyDecision::AcceptOnce as i32,
    }));
    let p = f.auth_prompt();
    f.answer_password(&p, PASSWORD);
    let open = f.state(v1::SessionPhase::Open);
    let security = open.security.unwrap();
    assert!(!security.post_quantum, "{security:?}");
    drop(rt);
}
