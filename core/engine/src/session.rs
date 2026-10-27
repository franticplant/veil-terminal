//! One SSH session: connect, verify the host, authenticate, open a shell, then
//! pump bytes between the remote and the terminal core, sending frames to the
//! frontend as credit allows.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use russh::{ChannelMsg, Disconnect, client};
use tokio::sync::{mpsc, oneshot};
use veil_protocol::{MAX_FRAMES_IN_FLIGHT, encode, v1};
use veil_term::{TermEvent, Terminal};

use crate::ssh::{self, Client};
use veil_caps::Dialer;
use veil_obs::{LogRing, SessionStats};

/// How long a prompt waits for the frontend before giving up.
const PROMPT_TIMEOUT: Duration = Duration::from_secs(10 * 60);

/// A frontend's answer to a prompt.
#[derive(Debug)]
pub(crate) enum Answer {
    HostKey(v1::HostKeyDecision),
    Auth {
        responses: Vec<String>,
        cancel: bool,
    },
    Sign {
        signature: Vec<u8>,
        cancel: bool,
    },
}

/// Open prompts of one session, by prompt_id. The connection delivers
/// answers here; the session awaits them.
pub(crate) type Prompts = Arc<Mutex<HashMap<u64, oneshot::Sender<Answer>>>>;

/// What a session needs to talk to its frontend.
pub(crate) struct Prompter {
    pub session_id: u64,
    pub out: mpsc::Sender<Vec<u8>>,
    pub prompts: Prompts,
    pub next_prompt: Arc<AtomicU64>,
    pub stats: Arc<SessionStats>,
    pub log: Arc<LogRing>,
}

impl Prompter {
    pub async fn event(&self, body: v1::engine_msg::Body) {
        let msg = v1::EngineMsg {
            request_id: 0,
            body: Some(body),
        };
        // A closed channel means the frontend is gone; the session ends on
        // its own when the connection drops it.
        let _ = self.out.send(encode(&msg)).await;
    }

    pub fn log(&self, level: v1::LogLevel, component: &str, message: &str) {
        self.log.log(level, component, self.session_id, message);
    }

    /// Reports the session open, with what the connection negotiated.
    pub async fn open(&self, security: Option<v1::ConnectionSecurity>) {
        self.stats.set_phase(v1::SessionPhase::Open);
        self.log(v1::LogLevel::Info, "session", "OPEN");
        self.event(v1::engine_msg::Body::SessionState(v1::SessionState {
            session_id: self.session_id,
            phase: v1::SessionPhase::Open as i32,
            detail: String::new(),
            security,
        }))
        .await;
    }

    pub async fn state(&self, phase: v1::SessionPhase, detail: &str) {
        self.stats.set_phase(phase);
        let name = phase.as_str_name().trim_start_matches("SESSION_PHASE_");
        let message = if detail.is_empty() {
            name.to_string()
        } else {
            format!("{name}: {detail}")
        };
        self.log(v1::LogLevel::Info, "session", &message);
        self.event(v1::engine_msg::Body::SessionState(v1::SessionState {
            session_id: self.session_id,
            phase: phase as i32,
            detail: detail.to_string(),
            security: None,
        }))
        .await;
    }

    /// Sends the prompt `build(session_id, prompt_id)` and waits for its
    /// answer. None if the frontend never answers.
    pub async fn ask(
        &self,
        build: impl FnOnce(u64, u64) -> v1::engine_msg::Body,
    ) -> Option<Answer> {
        let prompt_id = self.next_prompt.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = oneshot::channel();
        self.prompts
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(prompt_id, tx);
        self.event(build(self.session_id, prompt_id)).await;
        let answer = tokio::time::timeout(PROMPT_TIMEOUT, rx).await;
        self.prompts
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&prompt_id);
        answer.ok()?.ok()
    }
}

/// Requests the connection forwards to a running session.
#[derive(Debug)]
pub(crate) enum Command {
    Input(Vec<u8>),
    Resize(u16, u16),
    Scroll(i32),
    ScrollToBottom,
    FrameAck(u64),
    Snapshot,
}

pub(crate) struct Spec {
    pub host: String,
    pub port: u16,
    pub user: String,
    /// Accept a classical key exchange when the server has no post-quantum one.
    pub allow_classical_kex: bool,
    /// Public keys the frontend can sign with, tried before passwords.
    pub keys: Vec<russh::keys::PublicKey>,
    pub cols: u16,
    pub rows: u16,
}

pub(crate) struct Env {
    pub dialer: Arc<dyn Dialer>,
    pub host_keys: Arc<dyn veil_caps::HostKeyStore>,
}

/// Runs a session to completion and reports its end.
pub(crate) async fn run(
    prompter: Arc<Prompter>,
    env: Arc<Env>,
    spec: Spec,
    cmds: mpsc::Receiver<Command>,
) {
    let detail = match run_inner(&prompter, &env, spec, cmds).await {
        Ok(detail) | Err(detail) => detail,
    };
    prompter.state(v1::SessionPhase::Closed, &detail).await;
}

async fn run_inner(
    prompter: &Arc<Prompter>,
    env: &Env,
    spec: Spec,
    mut cmds: mpsc::Receiver<Command>,
) -> Result<String, String> {
    prompter.state(v1::SessionPhase::Connecting, "").await;
    let stream = env
        .dialer
        .dial(&spec.host, spec.port)
        .await
        .map_err(|e| format!("cannot connect to {}:{}: {e}", spec.host, spec.port))?;

    let mut preferred = russh::Preferred::DEFAULT.clone();
    if !spec.allow_classical_kex {
        // Post-quantum only: the hybrid ML-KEM exchange plus the extension
        // markers (strict kex, ext-info) that are not key exchanges.
        preferred.kex = std::borrow::Cow::Owned(
            preferred
                .kex
                .iter()
                .filter(|k| {
                    let n = k.as_ref();
                    ssh::is_post_quantum(n)
                        || n.starts_with("ext-info")
                        || n.starts_with("kex-strict")
                })
                .cloned()
                .collect(),
        );
    }
    let config = Arc::new(client::Config {
        inactivity_timeout: None,
        keepalive_interval: Some(Duration::from_secs(30)),
        keepalive_max: 4,
        nodelay: true,
        preferred,
        ..Default::default()
    });
    let refusal = Arc::new(Mutex::new(None));
    let negotiated = Arc::new(Mutex::new(None));
    let handler = Client {
        negotiated: negotiated.clone(),
        prompter: prompter.clone(),
        host: spec.host.clone(),
        port: spec.port,
        host_keys: env.host_keys.clone(),
        refusal: refusal.clone(),
    };
    let mut handle = match client::connect_stream(config, stream, handler).await {
        Ok(h) => h,
        Err(e) => {
            let refused = refusal.lock().unwrap_or_else(|e| e.into_inner()).take();
            if let Some(why) = refused {
                return Err(why);
            }
            if let russh::Error::NoCommonAlgo {
                kind: russh::AlgorithmKind::Kex,
                theirs,
                ..
            } = &e
                && !spec.allow_classical_kex
            {
                return Err(format!(
                    "the server offers no post-quantum key exchange (it offers: {}); allow \
                     classical key exchange for this host to connect anyway",
                    theirs
                        .iter()
                        .filter(|k| !k.starts_with("ext-info") && !k.starts_with("kex-strict"))
                        .cloned()
                        .collect::<Vec<_>>()
                        .join(", ")
                ));
            }
            return Err(format!("SSH handshake failed: {e}"));
        }
    };

    prompter.state(v1::SessionPhase::Authenticating, "").await;
    ssh::authenticate(&mut handle, prompter, &spec).await?;

    let err = |what: &str| {
        let what = what.to_string();
        move |e: russh::Error| format!("{what}: {e}")
    };
    let mut channel = handle
        .channel_open_session()
        .await
        .map_err(err("cannot open a session channel"))?;
    channel
        .request_pty(
            true,
            "xterm-256color",
            spec.cols as u32,
            spec.rows as u32,
            0,
            0,
            &[],
        )
        .await
        .map_err(err("PTY request failed"))?;
    channel
        .request_shell(true)
        .await
        .map_err(err("shell request failed"))?;
    let security = negotiated.lock().unwrap_or_else(|e| e.into_inner()).clone();
    prompter.open(security).await;

    let mut term = Terminal::new(spec.cols, spec.rows);
    let mut sent: u64 = 0;
    let mut acked: u64 = 0;
    let mut exit_status = None;
    let ended = loop {
        tokio::select! {
            msg = channel.wait() => match msg {
                Some(ChannelMsg::Data { data }) | Some(ChannelMsg::ExtendedData { data, .. }) => {
                    SessionStats::add(&prompter.stats.bytes_from_remote, data.len() as u64);
                    for event in term.feed(&data) {
                        match event {
                            TermEvent::Reply(bytes) => {
                                SessionStats::add(&prompter.stats.bytes_to_remote, bytes.len() as u64);
                                channel.data_bytes(bytes).await.map_err(err("send failed"))?;
                            }
                            TermEvent::Clipboard { text, primary } => {
                                prompter.log(
                                    v1::LogLevel::Info,
                                    "session",
                                    &format!("remote offered {} bytes for the clipboard", text.len()),
                                );
                                prompter
                                    .event(v1::engine_msg::Body::ClipboardWrite(v1::ClipboardWrite {
                                        session_id: prompter.session_id,
                                        text,
                                        primary,
                                    }))
                                    .await;
                            }
                            TermEvent::Bell => {
                                prompter
                                    .event(v1::engine_msg::Body::Bell(v1::Bell {
                                        session_id: prompter.session_id,
                                    }))
                                    .await;
                            }
                        }
                    }
                }
                Some(ChannelMsg::ExitStatus { exit_status: s }) => exit_status = Some(s),
                Some(ChannelMsg::Eof) => {}
                Some(ChannelMsg::Close) | None => break "the remote closed the session".to_string(),
                Some(_) => {}
            },
            cmd = cmds.recv() => match cmd {
                None => break "closed".to_string(),
                Some(Command::Input(bytes)) => {
                    term.scroll_to_bottom();
                    SessionStats::add(&prompter.stats.bytes_to_remote, bytes.len() as u64);
                    channel.data_bytes(bytes).await.map_err(err("send failed"))?;
                }
                Some(Command::Resize(cols, rows)) => {
                    term.resize(cols, rows);
                    prompter.stats.set_size(cols, rows);
                    channel
                        .window_change(cols as u32, rows as u32, 0, 0)
                        .await
                        .map_err(err("resize failed"))?;
                }
                Some(Command::Scroll(lines)) => term.scroll(lines),
                Some(Command::ScrollToBottom) => term.scroll_to_bottom(),
                Some(Command::FrameAck(seq)) => {
                    acked = acked.max(seq.min(sent));
                    SessionStats::add(&prompter.stats.frames_acked, 1);
                }
                Some(Command::Snapshot) => {
                    // The frontend lost its state: earlier frames no longer
                    // count against the credit.
                    acked = sent;
                    term.request_full();
                }
            },
        }
        if sent - acked < MAX_FRAMES_IN_FLIGHT as u64
            && let Some(mut frame) = term.frame()
        {
            frame.session_id = prompter.session_id;
            sent = frame.seq;
            SessionStats::add(&prompter.stats.frames_sent, 1);
            prompter.event(v1::engine_msg::Body::Frame(frame)).await;
        }
    };

    // Whatever the screen showed last should reach the frontend.
    if let Some(mut frame) = term.frame() {
        frame.session_id = prompter.session_id;
        prompter.event(v1::engine_msg::Body::Frame(frame)).await;
    }
    let _ = handle.disconnect(Disconnect::ByApplication, "", "en").await;
    Ok(match exit_status {
        Some(s) => format!("the remote shell exited with status {s}"),
        None => ended,
    })
}
