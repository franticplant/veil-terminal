//! devd's network side in Rust: listeners, TLS 1.3 with client certificates
//! (rustls) and the frame protocol. Kotlin keeps the policy: it answers each
//! request (pairing, scopes, methods) and signs each handshake with devd's key,
//! which never leaves Android Keystore.
//!
//! Why native: Android's Java TLS stack (Conscrypt) leaves a stats thread
//! behind per handshake, and the JVM side costs memory devd does not need.
//! Here a connection is one thread that ends with the connection.
//!
//! JNI surface (`io.github.franticplant.veil.devd.DevdNative`):
//! - `start(callback, certDer): Long` with `callback` implementing
//!   `byte[] sign(byte[])` (SHA256withECDSA, DER) and
//!   `String request(String clientCertHex, String local, String peer, String json)`.
//! - `bind(handle, address, port): String?` (null: listening, else why not),
//!   `unbind(handle, address, port)`, `connections(handle): Int`, `stop(handle)`.

// JNI symbols must be exported unmangled (`#[unsafe(no_mangle)]`).
#![allow(unsafe_code)]

use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicUsize, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;

use jni::objects::{Global, JByteArray, JClass, JObject, JString};
use jni::strings::JNIString;
use jni::sys::{jint, jlong};
use jni::{Env, EnvUnowned, JValue, JavaVM, errors::ThrowRuntimeExAndDefault, jni_sig, jni_str};
use rustls::client::danger::HandshakeSignatureValid;
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{CertificateDer, UnixTime};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::server::{ClientHello, ResolvesServerCert};
use rustls::sign::{CertifiedKey, Signer, SigningKey};
use rustls::{
    DigitallySignedStruct, DistinguishedName, ServerConfig, ServerConnection, SignatureAlgorithm,
    SignatureScheme, StreamOwned,
};

/// The largest frame accepted (devd's request limit).
const MAX_FRAME: usize = 4 * 1024 * 1024;
const MAX_CONNECTIONS: usize = 8;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const IDLE_TIMEOUT: Duration = Duration::from_secs(10 * 60);

/// The Kotlin object that signs and answers.
struct Callback {
    vm: JavaVM,
    obj: Global<JObject<'static>>,
}

impl Callback {
    fn sign(&self, data: &[u8]) -> Option<Vec<u8>> {
        self.vm
            .attach_current_thread(|env| -> jni::errors::Result<Option<Vec<u8>>> {
                let arg = env.byte_array_from_slice(data)?;
                let out = env
                    .call_method(
                        &self.obj,
                        jni_str!("sign"),
                        jni_sig!("([B)[B"),
                        &[JValue::Object(&arg)],
                    )?
                    .l()?;
                if out.is_null() {
                    return Ok(None);
                }
                let arr = JByteArray::cast_local(env, out)?;
                Ok(Some(env.convert_byte_array(&arr)?))
            })
            .ok()
            .flatten()
    }

    fn request(&self, client: &str, local: &str, peer: &str, json: &str) -> String {
        self.vm
            .attach_current_thread(|env| -> jni::errors::Result<String> {
                let a = env.new_string(client)?;
                let b = env.new_string(local)?;
                let c = env.new_string(peer)?;
                let d = env.new_string(json)?;
                let out = env
                    .call_method(
                        &self.obj,
                        jni_str!("request"),
                        jni_sig!(
                            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                        ),
                        &[
                            JValue::Object(&a),
                            JValue::Object(&b),
                            JValue::Object(&c),
                            JValue::Object(&d),
                        ],
                    )?
                    .l()?;
                let s = JString::cast_local(env, out)?;
                s.try_to_string(env)
            })
            .unwrap_or_else(|e| {
                format!(r#"{{"error":{{"code":"internal","message":"devd failed: {e}"}}}}"#)
            })
    }
}

/// devd's Keystore key, used through the callback.
#[derive(Debug)]
struct KeystoreKey(Arc<Callback>);

impl std::fmt::Debug for Callback {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Callback")
    }
}

impl SigningKey for KeystoreKey {
    fn choose_scheme(&self, offered: &[SignatureScheme]) -> Option<Box<dyn Signer>> {
        offered
            .contains(&SignatureScheme::ECDSA_NISTP256_SHA256)
            .then(|| Box::new(KeystoreSigner(self.0.clone())) as Box<dyn Signer>)
    }

    fn algorithm(&self) -> SignatureAlgorithm {
        SignatureAlgorithm::ECDSA
    }
}

#[derive(Debug)]
struct KeystoreSigner(Arc<Callback>);

impl Signer for KeystoreSigner {
    fn sign(&self, message: &[u8]) -> Result<Vec<u8>, rustls::Error> {
        self.0
            .sign(message)
            .ok_or_else(|| rustls::Error::General("Keystore refused to sign".into()))
    }

    fn scheme(&self) -> SignatureScheme {
        SignatureScheme::ECDSA_NISTP256_SHA256
    }
}

#[derive(Debug)]
struct ServerKey(Arc<CertifiedKey>);

impl ResolvesServerCert for ServerKey {
    fn resolve(&self, _hello: ClientHello<'_>) -> Option<Arc<CertifiedKey>> {
        Some(self.0.clone())
    }
}

/// Requires a client certificate and checks that the client holds its key
/// (the TLS 1.3 CertificateVerify). Whether the certificate is paired is
/// decided by Kotlin, by its SHA-256.
#[derive(Debug)]
struct AnyClientCert(Arc<CryptoProvider>);

impl ClientCertVerifier for AnyClientCert {
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> Result<ClientCertVerified, rustls::Error> {
        Ok(ClientCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Err(rustls::Error::General("TLS 1.2 is not used".into()))
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.0.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.0.signature_verification_algorithms.supported_schemes()
    }
}

struct Server {
    config: Arc<ServerConfig>,
    callback: Arc<Callback>,
    listeners: Mutex<HashMap<String, Arc<AtomicBool>>>,
    connections: Arc<AtomicUsize>,
}

static SERVERS: LazyLock<Mutex<HashMap<jlong, Arc<Server>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT: AtomicI64 = AtomicI64::new(1);

fn servers() -> std::sync::MutexGuard<'static, HashMap<jlong, Arc<Server>>> {
    SERVERS.lock().unwrap_or_else(|e| e.into_inner())
}

impl Server {
    fn bind(self: &Arc<Self>, addr: SocketAddr) -> Result<(), String> {
        let key = addr.to_string();
        if self
            .listeners
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .contains_key(&key)
        {
            return Ok(());
        }
        let listener = TcpListener::bind(addr).map_err(|e| e.to_string())?;
        // Non-blocking, polled, so unbind can stop the loop.
        listener.set_nonblocking(true).map_err(|e| e.to_string())?;
        let stop = Arc::new(AtomicBool::new(false));
        self.listeners
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(key.clone(), stop.clone());
        let server = self.clone();
        std::thread::Builder::new()
            .name(format!("devd-accept-{key}"))
            .spawn(move || server.accept_loop(listener, stop))
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    fn accept_loop(self: Arc<Self>, listener: TcpListener, stop: Arc<AtomicBool>) {
        while !stop.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((sock, _)) => {
                    if self.connections.load(Ordering::Relaxed) >= MAX_CONNECTIONS {
                        continue; // Dropped: closed.
                    }
                    self.connections.fetch_add(1, Ordering::Relaxed);
                    let server = self.clone();
                    let spawned =
                        std::thread::Builder::new()
                            .name("devd-conn".into())
                            .spawn(move || {
                                server.serve(sock);
                                server.connections.fetch_sub(1, Ordering::Relaxed);
                            });
                    if spawned.is_err() {
                        self.connections.fetch_sub(1, Ordering::Relaxed);
                    }
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(150));
                }
                Err(_) => std::thread::sleep(Duration::from_millis(500)),
            }
        }
    }

    fn serve(&self, sock: TcpStream) {
        let local = sock.local_addr().map(|a| a.to_string()).unwrap_or_default();
        let peer = sock.peer_addr().map(|a| a.to_string()).unwrap_or_default();
        let _ = sock.set_nonblocking(false);
        let _ = sock.set_read_timeout(Some(HANDSHAKE_TIMEOUT));
        let _ = sock.set_nodelay(true);
        let Ok(conn) = ServerConnection::new(self.config.clone()) else {
            return;
        };
        let mut tls = StreamOwned::new(conn, sock);
        while tls.conn.is_handshaking() {
            if tls.conn.complete_io(&mut tls.sock).is_err() {
                return;
            }
        }
        let Some(client) = tls
            .conn
            .peer_certificates()
            .and_then(|c| c.first())
            .map(|c| c.iter().map(|x| format!("{x:02x}")).collect::<String>())
        else {
            return;
        };
        let _ = tls.sock.set_read_timeout(Some(IDLE_TIMEOUT));
        loop {
            let mut hdr = [0u8; 4];
            if tls.read_exact(&mut hdr).is_err() {
                return;
            }
            let n = u32::from_be_bytes(hdr) as usize;
            let reply = if n == 0 || n > MAX_FRAME {
                let r = format!(
                    r#"{{"id":null,"error":{{"code":"invalid_params","message":"frame of {n} bytes (limit {MAX_FRAME})"}}}}"#
                );
                let _ = write_frame(&mut tls, r.as_bytes());
                return;
            } else {
                let mut buf = vec![0u8; n];
                if tls.read_exact(&mut buf).is_err() {
                    return;
                }
                match String::from_utf8(buf) {
                    Ok(json) => self.callback.request(&client, &local, &peer, &json),
                    Err(_) => r#"{"id":null,"error":{"code":"invalid_params","message":"request is not UTF-8"}}"#.into(),
                }
            };
            if write_frame(&mut tls, reply.as_bytes()).is_err() {
                return;
            }
        }
    }
}

fn write_frame(w: &mut impl Write, b: &[u8]) -> std::io::Result<()> {
    w.write_all(&(b.len() as u32).to_be_bytes())?;
    w.write_all(b)?;
    w.flush()
}

fn throw(env: &mut Env, msg: String) -> jni::errors::Result<()> {
    env.throw_new(
        jni_str!("java/lang/IllegalStateException"),
        JNIString::from(msg),
    )
}

fn lookup(handle: jlong) -> Option<Arc<Server>> {
    servers().get(&handle).cloned()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_franticplant_veil_devd_DevdNative_start<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    callback: JObject<'caller>,
    cert_der: JByteArray<'caller>,
) -> jlong {
    unowned
        .with_env(|env| -> jni::errors::Result<jlong> {
            let cert = env.convert_byte_array(&cert_der)?;
            let callback = Arc::new(Callback {
                vm: env.get_java_vm()?,
                obj: env.new_global_ref(callback)?,
            });
            let provider = Arc::new(rustls::crypto::ring::default_provider());
            let key = Arc::new(CertifiedKey::new(
                vec![CertificateDer::from(cert)],
                Arc::new(KeystoreKey(callback.clone())),
            ));
            let config = ServerConfig::builder_with_provider(provider.clone())
                .with_protocol_versions(&[&rustls::version::TLS13])
                .map_err(|e| jni::errors::Error::ParseFailed(e.to_string()))?
                .with_client_cert_verifier(Arc::new(AnyClientCert(provider)))
                .with_cert_resolver(Arc::new(ServerKey(key)));
            let handle = NEXT.fetch_add(1, Ordering::Relaxed);
            servers().insert(
                handle,
                Arc::new(Server {
                    config: Arc::new(config),
                    callback,
                    listeners: Mutex::new(HashMap::new()),
                    connections: Arc::new(AtomicUsize::new(0)),
                }),
            );
            Ok(handle)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_franticplant_veil_devd_DevdNative_bind<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    address: JString<'caller>,
    port: jint,
) -> JString<'caller> {
    unowned
        .with_env(|env| -> jni::errors::Result<JString<'caller>> {
            let Some(server) = lookup(handle) else {
                throw(env, format!("no devd server {handle}"))?;
                return Ok(JString::default());
            };
            let address = address.try_to_string(env)?;
            let result = address
                .parse::<std::net::IpAddr>()
                .map_err(|e| e.to_string())
                .and_then(|ip| server.bind(SocketAddr::new(ip, port as u16)));
            match result {
                Ok(()) => Ok(JString::default()),
                Err(e) => env.new_string(e),
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_franticplant_veil_devd_DevdNative_unbind<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    address: JString<'caller>,
    port: jint,
) {
    unowned
        .with_env(|env| -> jni::errors::Result<()> {
            let address = address.try_to_string(env)?;
            if let Some(server) = lookup(handle)
                && let Ok(ip) = address.parse::<std::net::IpAddr>()
                && let Some(stop) = server
                    .listeners
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .remove(&SocketAddr::new(ip, port as u16).to_string())
            {
                stop.store(true, Ordering::Relaxed);
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_franticplant_veil_devd_DevdNative_connections<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
) -> jint {
    unowned
        .with_env(|_env| -> jni::errors::Result<jint> {
            Ok(lookup(handle).map_or(0, |s| s.connections.load(Ordering::Relaxed) as jint))
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_franticplant_veil_devd_DevdNative_stop<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
) {
    unowned
        .with_env(|_env| -> jni::errors::Result<()> {
            if let Some(server) = servers().remove(&handle) {
                for (_, stop) in server
                    .listeners
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .drain()
                {
                    stop.store(true, Ordering::Relaxed);
                }
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}
