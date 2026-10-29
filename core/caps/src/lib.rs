//! Capabilities: the authorities a component is handed instead of taking.
//!
//! The engine does not open network connections or keep files on its own; it
//! is given a [`Dialer`] and a [`HostKeyStore`]. Today both are local
//! implementations in the app's process ([`DirectDialer`], [`FileHostKeys`]).
//! In the Microdroid layout the engine runs in the guest and these are
//! answered across the VM boundary by Android-side brokers: the Network Broker
//! dials one approved connection, the policy core keeps known_hosts. The
//! engine's code does not change when that happens; only what it is handed.

mod known_hosts;

use std::future::Future;
use std::io;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::pin::Pin;
use std::time::Duration;

use russh::keys::PublicKey;
use russh::keys::known_hosts::{check_known_hosts_path, learn_known_hosts_path};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpStream;
use veil_protocol::v1;

/// A connected byte stream the engine may speak SSH over. It is deliberately
/// not `TcpStream`: the native broker can hand over a socket, and the VM host
/// hands over a vsock stream, without changing the engine.
pub trait Connection: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Connection for T {}

/// One approved connection: the stream and, when the broker knows it, the peer
/// address for diagnostics.
pub struct Dialed {
    pub stream: Box<dyn Connection>,
    pub peer: Option<SocketAddr>,
}

/// How a component obtains an approved connection. Today [`DirectDialer`]
/// connects from this process; later the Network Broker hands over one already
/// connected stream and the engine never creates connections itself.
pub trait Dialer: Send + Sync + 'static {
    fn dial<'a>(
        &'a self,
        host: &'a str,
        port: u16,
    ) -> Pin<Box<dyn Future<Output = io::Result<Dialed>> + Send + 'a>>;
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
    ) -> Pin<Box<dyn Future<Output = io::Result<Dialed>> + Send + 'a>> {
        Box::pin(async move {
            match tokio::time::timeout(self.timeout, TcpStream::connect((host, port))).await {
                Ok(Ok(stream)) => {
                    let peer = stream.peer_addr().ok();
                    Ok(Dialed {
                        stream: Box::new(stream),
                        peer,
                    })
                }
                Ok(Err(e)) => Err(e),
                Err(_) => Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    format!("no connection within {:?}", self.timeout),
                )),
            }
        })
    }
}

/// What a store knows about a host's key.
#[derive(Debug, PartialEq, Eq)]
pub enum HostKeyCheck {
    /// The recorded key for this host and port.
    Known,
    /// Nothing recorded for this host and port.
    Unknown,
    /// A different key is recorded: a changed key, refused without asking.
    Changed { detail: String },
}

/// Where trusted host keys live. In the Microdroid layout this is the
/// Android-side policy core; the guest engine asks it.
pub trait HostKeyStore: Send + Sync + 'static {
    fn check(&self, host: &str, port: u16, key: &PublicKey) -> Result<HostKeyCheck, String>;
    fn learn(&self, host: &str, port: u16, key: &PublicKey) -> Result<(), String>;
    fn list(&self) -> Result<Vec<v1::HostKeyEntry>, String>;
    /// Removes every key for host and port; returns how many.
    fn forget(&self, host: &str, port: u16) -> Result<usize, String>;
}

/// An OpenSSH-format known_hosts file.
pub struct FileHostKeys {
    pub path: PathBuf,
}

impl HostKeyStore for FileHostKeys {
    fn check(&self, host: &str, port: u16, key: &PublicKey) -> Result<HostKeyCheck, String> {
        match check_known_hosts_path(host, port, key, &self.path) {
            Ok(true) => Ok(HostKeyCheck::Known),
            Ok(false) => Ok(HostKeyCheck::Unknown),
            Err(russh::keys::Error::KeyChanged { line }) => Ok(HostKeyCheck::Changed {
                detail: format!("differs from the key recorded on line {line} of known_hosts"),
            }),
            Err(e) => Err(format!("cannot read known_hosts: {e}")),
        }
    }

    fn learn(&self, host: &str, port: u16, key: &PublicKey) -> Result<(), String> {
        learn_known_hosts_path(host, port, key, &self.path)
            .map_err(|e| format!("cannot record the host key: {e}"))
    }

    fn list(&self) -> Result<Vec<v1::HostKeyEntry>, String> {
        known_hosts::list(&self.path).map_err(|e| format!("cannot read known_hosts: {e}"))
    }

    fn forget(&self, host: &str, port: u16) -> Result<usize, String> {
        known_hosts::forget(&self.path, host, port)
            .map_err(|e| format!("cannot update known_hosts: {e}"))
    }
}

/// Remembers nothing: every host is unknown, accepted keys are not kept.
pub struct NoHostKeys;

impl HostKeyStore for NoHostKeys {
    fn check(&self, _: &str, _: u16, _: &PublicKey) -> Result<HostKeyCheck, String> {
        Ok(HostKeyCheck::Unknown)
    }
    fn learn(&self, _: &str, _: u16, _: &PublicKey) -> Result<(), String> {
        Ok(())
    }
    fn list(&self) -> Result<Vec<v1::HostKeyEntry>, String> {
        Ok(vec![])
    }
    fn forget(&self, _: &str, _: u16) -> Result<usize, String> {
        Ok(0)
    }
}
