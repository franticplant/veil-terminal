//! One SSH session: connect, verify the host, authenticate, open a shell, then
//! pump bytes between the remote and the terminal core, sending frames to the
//! frontend as credit allows.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use russh::{ChannelMsg, Disconnect, client};
use tokio::sync::{mpsc, oneshot};
use veil_protocol::{MAX_FRAMES_IN_FLIGHT, encode, v1};
use veil_term::{TermEvent, Terminal};

use crate::ssh::{self, Client, Dialer};

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

    pub async fn state(&self, phase: v1::SessionPhase, detail: &str) {
        self.event(v1::engine_msg::Body::SessionState(v1::SessionState {
            session_id: self.session_id,
            phase: phase as i32,
            detail: detail.to_string(),
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
    pub cols: u16,
    pub rows: u16,
}

pub(crate) struct Env {
    pub dialer: Arc<dyn Dialer>,
    pub known_hosts: Option<PathBuf>,
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

    let config = Arc::new(client::Config {
        inactivity_timeout: None,
        keepalive_interval: Some(Duration::from_secs(30)),
        keepalive_max: 4,
        nodelay: true,
        ..Default::default()
    });
    let refusal = Arc::new(Mutex::new(None));
    let handler = Client {
        prompter: prompter.clone(),
        host: spec.host.clone(),
        port: spec.port,
        known_hosts: env.known_hosts.clone(),
        refusal: refusal.clone(),
    };
    let mut handle = match client::connect_stream(config, stream, handler).await {
        Ok(h) => h,
        Err(e) => {
            let refused = refusal.lock().unwrap_or_else(|e| e.into_inner()).take();
            return Err(refused.unwrap_or_else(|| format!("SSH handshake failed: {e}")));
        }
    };

    prompter.state(v1::SessionPhase::Authenticating, "").await;
    ssh::authenticate(&mut handle, prompter, &spec.user).await?;

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
    prompter.state(v1::SessionPhase::Open, "").await;

    let mut term = Terminal::new(spec.cols, spec.rows);
    let mut sent: u64 = 0;
    let mut acked: u64 = 0;
    let mut exit_status = None;
    let ended = loop {
        tokio::select! {
            msg = channel.wait() => match msg {
                Some(ChannelMsg::Data { data }) | Some(ChannelMsg::ExtendedData { data, .. }) => {
                    for event in term.feed(&data) {
                        match event {
                            TermEvent::Reply(bytes) => {
                                channel.data_bytes(bytes).await.map_err(err("send failed"))?;
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
                    channel.data_bytes(bytes).await.map_err(err("send failed"))?;
                }
                Some(Command::Resize(cols, rows)) => {
                    term.resize(cols, rows);
                    channel
                        .window_change(cols as u32, rows as u32, 0, 0)
                        .await
                        .map_err(err("resize failed"))?;
                }
                Some(Command::Scroll(lines)) => term.scroll(lines),
                Some(Command::ScrollToBottom) => term.scroll_to_bottom(),
                Some(Command::FrameAck(seq)) => acked = acked.max(seq.min(sent)),
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
