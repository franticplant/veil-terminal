//! SSH: the transport the engine gets from a [`Dialer`], host-key
//! verification against the known-hosts file, and authentication. Everything
//! that needs a human decision goes through [`Prompter`].

use std::future::Future;
use std::io;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use russh::client;
use russh::keys::known_hosts::{check_known_hosts_path, learn_known_hosts_path};
use russh::keys::{HashAlg, PublicKey, PublicKeyOrCertificate};
use russh::{MethodKind, MethodSet};
use tokio::net::TcpStream;
use veil_protocol::v1;

use crate::session::{Answer, Prompter};

/// How the engine obtains a connected TCP stream. This is the network
/// capability boundary: today [`DirectDialer`] connects from this process;
/// later the Network Broker hands over one approved, already-connected socket
/// and the engine never creates connections itself.
pub trait Dialer: Send + Sync + 'static {
    fn dial<'a>(
        &'a self,
        host: &'a str,
        port: u16,
    ) -> Pin<Box<dyn Future<Output = io::Result<TcpStream>> + Send + 'a>>;
}

/// Connects directly, with a timeout.
pub struct DirectDialer {
    pub timeout: Duration,
}

impl Dialer for DirectDialer {
    fn dial<'a>(
        &'a self,
        host: &'a str,
        port: u16,
    ) -> Pin<Box<dyn Future<Output = io::Result<TcpStream>> + Send + 'a>> {
        Box::pin(async move {
            match tokio::time::timeout(self.timeout, TcpStream::connect((host, port))).await {
                Ok(r) => r,
                Err(_) => Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    format!("no connection within {:?}", self.timeout),
                )),
            }
        })
    }
}

/// russh's callback object: decides on the server's host key.
pub(crate) struct Client {
    pub prompter: Arc<Prompter>,
    pub host: String,
    pub port: u16,
    pub known_hosts: Option<PathBuf>,
    /// Why the key was refused, for the session's closing detail.
    pub refusal: Arc<Mutex<Option<String>>>,
}

impl Client {
    fn refuse(&self, why: String) -> Result<bool, russh::Error> {
        *self.refusal.lock().unwrap_or_else(|e| e.into_inner()) = Some(why);
        Ok(false)
    }
}

impl client::Handler for Client {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        key: &PublicKeyOrCertificate,
    ) -> Result<bool, Self::Error> {
        let key: &PublicKey = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key,
            PublicKeyOrCertificate::Certificate(_) => {
                return self.refuse(
                    "the server presented a host certificate; those are not supported yet".into(),
                );
            }
        };
        let fingerprint = key.fingerprint(HashAlg::Sha256).to_string();
        if let Some(path) = &self.known_hosts {
            match check_known_hosts_path(&self.host, self.port, key, path) {
                Ok(true) => return Ok(true),
                Ok(false) => {}
                Err(russh::keys::Error::KeyChanged { line }) => {
                    return self.refuse(format!(
                        "HOST KEY CHANGED: {}:{} now presents {} {fingerprint}, which differs from the key \
                         recorded on line {line} of known_hosts. This can mean an attack. Remove the old \
                         key only if you know why it changed.",
                        self.host,
                        self.port,
                        key.algorithm()
                    ));
                }
                Err(e) => return self.refuse(format!("cannot read known_hosts: {e}")),
            }
        }

        self.prompter
            .state(v1::SessionPhase::VerifyingHost, "")
            .await;
        let answer = self
            .prompter
            .ask(|session_id, prompt_id| {
                v1::engine_msg::Body::HostKeyPrompt(v1::HostKeyPrompt {
                    session_id,
                    prompt_id,
                    host: self.host.clone(),
                    port: self.port as u32,
                    algorithm: key.algorithm().to_string(),
                    fingerprint: fingerprint.clone(),
                    status: v1::HostKeyStatus::Unknown as i32,
                })
            })
            .await;
        match answer {
            Some(Answer::HostKey(v1::HostKeyDecision::AcceptOnce)) => Ok(true),
            Some(Answer::HostKey(v1::HostKeyDecision::AcceptAndRemember)) => {
                if let Some(path) = &self.known_hosts
                    && let Err(e) = learn_known_hosts_path(&self.host, self.port, key, path)
                {
                    return self.refuse(format!("cannot record the host key: {e}"));
                }
                Ok(true)
            }
            Some(_) => self.refuse("host key rejected".into()),
            None => self.refuse("no answer to the host key prompt".into()),
        }
    }
}

/// How many times credentials may be re-asked after a rejection.
const AUTH_ATTEMPTS: u32 = 3;

/// Authenticates as `user`, asking the frontend for credentials.
pub(crate) async fn authenticate(
    handle: &mut client::Handle<Client>,
    prompter: &Prompter,
    user: &str,
) -> Result<(), String> {
    let err = |e: russh::Error| format!("authentication failed: {e}");
    let first = handle.authenticate_none(user).await.map_err(err)?;
    let mut methods = match first {
        russh::client::AuthResult::Success => return Ok(()),
        russh::client::AuthResult::Failure {
            remaining_methods, ..
        } => remaining_methods,
    };

    for attempt in 0..AUTH_ATTEMPTS {
        let retry = attempt > 0;
        if methods.contains(&MethodKind::Password) {
            let Some(mut responses) = ask_auth(
                prompter,
                user,
                v1::AuthKind::Password,
                "",
                "",
                vec![v1::AuthField {
                    label: "Password".into(),
                    echo: false,
                }],
                retry,
            )
            .await?
            else {
                return Err("authentication cancelled".into());
            };
            let password = responses.pop().unwrap_or_default();
            match handle
                .authenticate_password(user, password)
                .await
                .map_err(err)?
            {
                russh::client::AuthResult::Success => return Ok(()),
                russh::client::AuthResult::Failure {
                    remaining_methods, ..
                } => methods = remaining_methods,
            }
        } else if methods.contains(&MethodKind::KeyboardInteractive) {
            let mut reply = handle
                .authenticate_keyboard_interactive_start(user, None)
                .await
                .map_err(err)?;
            let mut first_round = true;
            loop {
                match reply {
                    client::KeyboardInteractiveAuthResponse::Success => return Ok(()),
                    client::KeyboardInteractiveAuthResponse::Failure {
                        remaining_methods, ..
                    } => {
                        methods = remaining_methods;
                        break;
                    }
                    client::KeyboardInteractiveAuthResponse::InfoRequest {
                        name,
                        instructions,
                        prompts,
                    } => {
                        let responses = if prompts.is_empty() {
                            Vec::new()
                        } else {
                            let fields = prompts
                                .iter()
                                .map(|p| v1::AuthField {
                                    label: p.prompt.clone(),
                                    echo: p.echo,
                                })
                                .collect();
                            match ask_auth(
                                prompter,
                                user,
                                v1::AuthKind::KeyboardInteractive,
                                &name,
                                &instructions,
                                fields,
                                retry && first_round,
                            )
                            .await?
                            {
                                Some(r) if r.len() == prompts.len() => r,
                                Some(_) => return Err("wrong number of answers".into()),
                                None => return Err("authentication cancelled".into()),
                            }
                        };
                        first_round = false;
                        reply = handle
                            .authenticate_keyboard_interactive_respond(responses)
                            .await
                            .map_err(err)?;
                    }
                }
            }
        } else {
            return Err(format!(
                "the server offers no method this client supports (offered: {})",
                describe(&methods)
            ));
        }
    }
    Err(format!(
        "authentication failed after {AUTH_ATTEMPTS} attempts (server offers: {})",
        describe(&methods)
    ))
}

async fn ask_auth(
    prompter: &Prompter,
    user: &str,
    kind: v1::AuthKind,
    name: &str,
    instruction: &str,
    fields: Vec<v1::AuthField>,
    retry: bool,
) -> Result<Option<Vec<String>>, String> {
    let answer = prompter
        .ask(|session_id, prompt_id| {
            v1::engine_msg::Body::AuthPrompt(v1::AuthPrompt {
                session_id,
                prompt_id,
                kind: kind as i32,
                username: user.to_string(),
                name: name.to_string(),
                instruction: instruction.to_string(),
                fields: fields.clone(),
                retry,
            })
        })
        .await;
    match answer {
        Some(Answer::Auth { cancel: true, .. }) | None => Ok(None),
        Some(Answer::Auth { responses, .. }) => Ok(Some(responses)),
        Some(_) => Err("unexpected answer to an authentication prompt".into()),
    }
}

fn describe(methods: &MethodSet) -> String {
    let names: Vec<&str> = methods.iter().map(<&str>::from).collect();
    if names.is_empty() {
        "none".into()
    } else {
        names.join(", ")
    }
}
