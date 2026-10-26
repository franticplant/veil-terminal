//! One connection to devd: TLS 1.3, our client certificate, devd's
//! certificate pinned by SHA-256 (or recorded, while pairing), then
//! length-prefixed JSON frames.

use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{CryptoProvider, ring as provider};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::{ClientConfig, ClientConnection, DigitallySignedStruct, SignatureScheme, StreamOwned};
use serde_json::{Value, json};

use crate::store::Identity;

/// The largest reply accepted (devd's frame limit).
const MAX_FRAME: usize = 4 * 1024 * 1024;

pub fn sha256_hex(bytes: &[u8]) -> String {
    ring::digest::digest(&ring::digest::SHA256, bytes)
        .as_ref()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// The six-digit code devd shows while pairing (see devd's Tls.pairingCode).
pub fn pairing_code(server_cert: &[u8], client_cert: &[u8]) -> String {
    let mut ctx = ring::digest::Context::new(&ring::digest::SHA256);
    ctx.update(server_cert);
    ctx.update(client_cert);
    let d = ctx.finish();
    let n = u32::from_be_bytes([d.as_ref()[0], d.as_ref()[1], d.as_ref()[2], d.as_ref()[3]]);
    format!("{:06}", n % 1_000_000)
}

#[derive(Debug)]
struct Pinned {
    expected: Option<String>,
    seen: Mutex<Option<Vec<u8>>>,
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for Pinned {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let got = sha256_hex(end_entity);
        if let Some(want) = &self.expected
            && *want != got
        {
            return Err(rustls::Error::General(format!(
                "devd presented certificate {got}, but {want} is pinned for this address. If devd \
                 was reinstalled, pair again with --repin; otherwise something is in the middle."
            )));
        }
        *self.seen.lock().unwrap_or_else(|e| e.into_inner()) = Some(end_entity.to_vec());
        Ok(ServerCertVerified::assertion())
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
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

pub struct Conn {
    stream: StreamOwned<ClientConnection, TcpStream>,
    next_id: u64,
    pub server_cert: Vec<u8>,
}

/// An error reply from devd, or a local failure.
#[derive(Debug)]
pub struct CallError {
    pub code: String,
    pub message: String,
}

impl std::fmt::Display for CallError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.code, self.message)
    }
}

fn local(message: impl Into<String>) -> CallError {
    CallError {
        code: "local".into(),
        message: message.into(),
    }
}

impl Conn {
    /// Connects to `addr`. With `pin` None, any devd certificate is accepted
    /// and recorded (pairing); otherwise it must match.
    pub fn open(addr: &str, id: &Identity, pin: Option<String>) -> Result<Conn, CallError> {
        let provider = Arc::new(provider::default_provider());
        let verifier = Arc::new(Pinned {
            expected: pin,
            seen: Mutex::new(None),
            provider: provider.clone(),
        });
        let config = ClientConfig::builder_with_provider(provider)
            .with_protocol_versions(&[&rustls::version::TLS13])
            .map_err(|e| local(format!("TLS setup: {e}")))?
            .dangerous()
            .with_custom_certificate_verifier(verifier.clone())
            .with_client_auth_cert(
                vec![CertificateDer::from(id.cert_der.clone())],
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(id.key_der.clone())),
            )
            .map_err(|e| local(format!("TLS client certificate: {e}")))?;
        let sockaddr = addr
            .to_socket_addrs()
            .map_err(|e| local(format!("{addr}: {e}")))?
            .next()
            .ok_or_else(|| local(format!("{addr}: no address")))?;
        let tcp = TcpStream::connect_timeout(&sockaddr, Duration::from_secs(10)).map_err(|e| {
            local(format!(
                "cannot connect to {addr}: {e} (is devd's listener on?)"
            ))
        })?;
        tcp.set_read_timeout(Some(Duration::from_secs(15 * 60)))
            .ok();
        tcp.set_nodelay(true).ok();
        let name = ServerName::try_from("veil-devd").map_err(|e| local(e.to_string()))?;
        let conn = ClientConnection::new(Arc::new(config), name)
            .map_err(|e| local(format!("TLS: {e}")))?;
        let mut stream = StreamOwned::new(conn, tcp);
        while stream.conn.is_handshaking() {
            stream
                .conn
                .complete_io(&mut stream.sock)
                .map_err(|e| local(format!("TLS handshake with {addr} failed: {e}")))?;
        }
        let server_cert = verifier
            .seen
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
            .ok_or_else(|| local("no server certificate"))?;
        Ok(Conn {
            stream,
            next_id: 1,
            server_cert,
        })
    }

    /// Calls `method` and returns its result, or devd's error.
    pub fn call(&mut self, method: &str, params: Value) -> Result<Value, CallError> {
        let id = self.next_id;
        self.next_id += 1;
        let req = json!({ "id": id, "method": method, "params": params });
        let bytes = serde_json::to_vec(&req).map_err(|e| local(e.to_string()))?;
        let len = u32::try_from(bytes.len()).map_err(|_| local("request too large"))?;
        let io = |e: std::io::Error| local(format!("connection: {e}"));
        self.stream.write_all(&len.to_be_bytes()).map_err(io)?;
        self.stream.write_all(&bytes).map_err(io)?;
        self.stream.flush().map_err(io)?;

        let mut hdr = [0u8; 4];
        self.stream.read_exact(&mut hdr).map_err(io)?;
        let n = u32::from_be_bytes(hdr) as usize;
        if n > MAX_FRAME {
            return Err(local(format!("reply of {n} bytes is too large")));
        }
        let mut buf = vec![0u8; n];
        self.stream.read_exact(&mut buf).map_err(io)?;
        let mut reply: Value =
            serde_json::from_slice(&buf).map_err(|e| local(format!("bad reply: {e}")))?;
        if let Some(err) = reply.get("error") {
            return Err(CallError {
                code: err["code"].as_str().unwrap_or("error").to_string(),
                message: err["message"].as_str().unwrap_or("").to_string(),
            });
        }
        Ok(reply["result"].take())
    }
}
