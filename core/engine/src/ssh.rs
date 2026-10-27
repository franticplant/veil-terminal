//! SSH: the transport the engine gets from a [`Dialer`], host-key
//! verification against the known-hosts file, and authentication. Everything
//! that needs a human decision goes through [`Prompter`].

use std::future::Future;
use std::sync::{Arc, Mutex};

use russh::client;
use russh::keys::{HashAlg, PublicKey, PublicKeyOrCertificate};
use russh::{MethodKind, MethodSet};
use veil_protocol::v1;

use crate::session::{Answer, Prompter, Spec};

/// russh's callback object: decides on the server's host key.
pub(crate) struct Client {
    pub prompter: Arc<Prompter>,
    pub host: String,
    pub port: u16,
    /// Trusted host keys (a capability: the Android side in the VM layout).
    pub host_keys: Arc<dyn veil_caps::HostKeyStore>,
    /// Why the key was refused, for the session's closing detail.
    pub refusal: Arc<Mutex<Option<String>>>,
    /// What the key exchange negotiated, once it is done.
    pub negotiated: Arc<Mutex<Option<v1::ConnectionSecurity>>>,
}

/// Ciphers that carry their own authentication (no separate MAC).
fn is_aead(cipher: &str) -> bool {
    cipher.contains("poly1305") || cipher.contains("gcm")
}

/// Key exchanges that are hybrid post-quantum.
pub(crate) fn is_post_quantum(kex: &str) -> bool {
    kex.starts_with("mlkem") || kex.starts_with("sntrup")
}

impl Client {
    fn refuse(&self, why: String) -> Result<bool, russh::Error> {
        self.prompter.log(v1::LogLevel::Warn, "hostkeys", &why);
        *self.refusal.lock().unwrap_or_else(|e| e.into_inner()) = Some(why);
        Ok(false)
    }
}

impl client::Handler for Client {
    type Error = russh::Error;

    async fn kex_done(
        &mut self,
        _shared_secret: Option<&[u8]>,
        names: &russh::Names,
        _session: &mut client::Session,
    ) -> Result<(), Self::Error> {
        let kex = names.kex.as_ref().to_string();
        let cipher = names.cipher.as_ref().to_string();
        let mac = names.client_mac.as_ref().to_string();
        let security = v1::ConnectionSecurity {
            post_quantum: is_post_quantum(&kex),
            kex,
            // AEAD ciphers authenticate themselves; a negotiated MAC is unused.
            mac: if mac == "none" || is_aead(&cipher) {
                String::new()
            } else {
                mac
            },
            cipher,
            host_key_algorithm: names.key.to_string(),
        };
        self.prompter.log(
            v1::LogLevel::Info,
            "ssh",
            &format!(
                "key exchange {} ({}), cipher {}, host key {}",
                security.kex,
                if security.post_quantum {
                    "post-quantum hybrid"
                } else {
                    "classical"
                },
                security.cipher,
                security.host_key_algorithm
            ),
        );
        *self.negotiated.lock().unwrap_or_else(|e| e.into_inner()) = Some(security);
        Ok(())
    }

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
        match self.host_keys.check(&self.host, self.port, key) {
            Ok(veil_caps::HostKeyCheck::Known) => {
                self.prompter.log(
                    v1::LogLevel::Debug,
                    "hostkeys",
                    &format!("known {} key {fingerprint}", key.algorithm()),
                );
                return Ok(true);
            }
            Ok(veil_caps::HostKeyCheck::Unknown) => {}
            Ok(veil_caps::HostKeyCheck::Changed { detail }) => {
                return self.refuse(format!(
                    "HOST KEY CHANGED: {}:{} now presents {} {fingerprint}, which {detail}. This \
                     can mean an attack. Remove the old key only if you know why it changed.",
                    self.host,
                    self.port,
                    key.algorithm()
                ));
            }
            Err(e) => return self.refuse(e),
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
            Some(Answer::HostKey(v1::HostKeyDecision::AcceptOnce)) => {
                self.prompter.log(
                    v1::LogLevel::Info,
                    "hostkeys",
                    &format!("accepted {fingerprint} once"),
                );
                Ok(true)
            }
            Some(Answer::HostKey(v1::HostKeyDecision::AcceptAndRemember)) => {
                if let Err(e) = self.host_keys.learn(&self.host, self.port, key) {
                    return self.refuse(e);
                }
                self.prompter.log(
                    v1::LogLevel::Info,
                    "hostkeys",
                    &format!("accepted and recorded {fingerprint}"),
                );
                Ok(true)
            }
            Some(_) => self.refuse("host key rejected".into()),
            None => self.refuse("no answer to the host key prompt".into()),
        }
    }
}

/// How many times credentials may be re-asked after a rejection.
const AUTH_ATTEMPTS: u32 = 3;

/// Asks the frontend's signer to sign a userauth request with one of the
/// session's public keys. The private key never reaches the engine.
struct PromptSigner<'a> {
    prompter: &'a Prompter,
    spec: &'a Spec,
}

#[derive(Debug)]
pub(crate) enum SignError {
    Send,
    Refused,
}

impl From<russh::SendError> for SignError {
    fn from(_: russh::SendError) -> Self {
        SignError::Send
    }
}

impl russh::Signer for PromptSigner<'_> {
    type Error = SignError;

    fn auth_sign(
        &mut self,
        key: &russh::keys::agent::AgentIdentity,
        _hash_alg: Option<HashAlg>,
        mut to_sign: Vec<u8>,
    ) -> impl Future<Output = Result<Vec<u8>, SignError>> + Send {
        let public = match key {
            russh::keys::agent::AgentIdentity::PublicKey { key, .. } => key.to_openssh().ok(),
            _ => None,
        };
        async move {
            let public = public.ok_or(SignError::Refused)?;
            let answer = self
                .prompter
                .ask(|session_id, prompt_id| {
                    v1::engine_msg::Body::SignRequest(v1::SignRequest {
                        session_id,
                        prompt_id,
                        public_key: public.clone(),
                        username: self.spec.user.clone(),
                        host: self.spec.host.clone(),
                        port: self.spec.port as u32,
                        data: to_sign.clone(),
                    })
                })
                .await;
            match answer {
                Some(Answer::Sign {
                    signature,
                    cancel: false,
                }) if !signature.is_empty() && signature.len() <= MAX_SIGNATURE_BYTES => {
                    // russh sends the request data followed by the signature
                    // blob as an SSH string.
                    to_sign.extend_from_slice(&(signature.len() as u32).to_be_bytes());
                    to_sign.extend_from_slice(&signature);
                    Ok(to_sign)
                }
                _ => Err(SignError::Refused),
            }
        }
    }
}

/// The largest signature blob accepted from the signer.
const MAX_SIGNATURE_BYTES: usize = 2048;

/// Authenticates as `spec.user`: the session's public keys first (each
/// signature asked of the frontend), then password or keyboard-interactive
/// prompts.
pub(crate) async fn authenticate(
    handle: &mut client::Handle<Client>,
    prompter: &Prompter,
    spec: &Spec,
) -> Result<(), String> {
    let user = spec.user.as_str();
    let err = |e: russh::Error| format!("authentication failed: {e}");
    let first = handle.authenticate_none(user).await.map_err(err)?;
    let mut methods = match first {
        russh::client::AuthResult::Success => return Ok(()),
        russh::client::AuthResult::Failure {
            remaining_methods, ..
        } => remaining_methods,
    };

    if methods.contains(&MethodKind::PublicKey) {
        for key in &spec.keys {
            let mut signer = PromptSigner { prompter, spec };
            match handle
                .authenticate_publickey_with(user, key.clone(), None, &mut signer)
                .await
            {
                Ok(russh::client::AuthResult::Success) => {
                    prompter.log(
                        v1::LogLevel::Info,
                        "ssh",
                        &format!("authenticated with key {}", key.algorithm()),
                    );
                    return Ok(());
                }
                Ok(russh::client::AuthResult::Failure {
                    remaining_methods, ..
                }) => {
                    prompter.log(v1::LogLevel::Info, "ssh", "the server did not accept a key");
                    methods = remaining_methods;
                }
                Err(SignError::Refused) => {
                    prompter.log(v1::LogLevel::Warn, "ssh", "the signer refused to sign");
                }
                Err(SignError::Send) => {
                    return Err("connection lost during key authentication".into());
                }
            }
        }
    }

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
