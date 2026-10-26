//! What veil-ctl keeps on the laptop.
//!
//! - The client private key lives in the desktop secret service (`secret-tool`,
//!   service `veil-ctl`, account `client-key`), never in a plain file. A user
//!   without a secret service can point `VEIL_CTL_KEY_FILE` at a PEM file of
//!   their own choosing; that is their decision, not a default.
//! - The client certificate (public) and the pinned devd certificates (public)
//!   live in `~/.config/veil-ctl/` (or `$VEIL_CTL_DIR`).

use std::collections::BTreeMap;
use std::io::Write;
use std::path::PathBuf;
use std::process::{Command, Stdio};

use rcgen::{CertificateParams, DnType, KeyPair, PKCS_ECDSA_P256_SHA256};
use serde_json::{Value, json};

const SECRET_SERVICE: &str = "veil-ctl";
const SECRET_ACCOUNT: &str = "client-key";

pub struct Identity {
    pub cert_der: Vec<u8>,
    pub key_der: Vec<u8>,
}

fn dir() -> Result<PathBuf, String> {
    if let Ok(d) = std::env::var("VEIL_CTL_DIR") {
        return Ok(d.into());
    }
    let base = std::env::var("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .or_else(|_| std::env::var("HOME").map(|h| PathBuf::from(h).join(".config")))
        .map_err(|_| "neither VEIL_CTL_DIR, XDG_CONFIG_HOME nor HOME is set".to_string())?;
    Ok(base.join("veil-ctl"))
}

fn read_secret() -> Result<Option<String>, String> {
    if let Ok(path) = std::env::var("VEIL_CTL_KEY_FILE") {
        return std::fs::read_to_string(&path)
            .map(Some)
            .map_err(|e| format!("cannot read VEIL_CTL_KEY_FILE {path}: {e}"));
    }
    let out = Command::new("secret-tool")
        .args([
            "lookup",
            "service",
            SECRET_SERVICE,
            "account",
            SECRET_ACCOUNT,
        ])
        .stderr(Stdio::null())
        .output()
        .map_err(|e| {
            format!(
                "cannot run secret-tool ({e}); install libsecret's secret-tool, or set \
                 VEIL_CTL_KEY_FILE to a key file you manage"
            )
        })?;
    if out.status.success() && !out.stdout.is_empty() {
        Ok(Some(String::from_utf8_lossy(&out.stdout).into_owned()))
    } else {
        Ok(None)
    }
}

fn store_secret(pem: &str) -> Result<(), String> {
    if std::env::var("VEIL_CTL_KEY_FILE").is_ok() {
        return Err("VEIL_CTL_KEY_FILE is set but holds no key".into());
    }
    let mut child = Command::new("secret-tool")
        .args([
            "store",
            "--label",
            "veil-ctl client key",
            "service",
            SECRET_SERVICE,
            "account",
            SECRET_ACCOUNT,
        ])
        .stdin(Stdio::piped())
        .spawn()
        .map_err(|e| format!("cannot run secret-tool: {e}"))?;
    child
        .stdin
        .take()
        .ok_or("no stdin")?
        .write_all(pem.as_bytes())
        .map_err(|e| format!("secret-tool: {e}"))?;
    let status = child.wait().map_err(|e| format!("secret-tool: {e}"))?;
    if !status.success() {
        return Err("secret-tool could not store the key".into());
    }
    Ok(())
}

/// Loads this laptop's client identity, creating it on first use.
pub fn identity(name: &str) -> Result<Identity, String> {
    let cert_path = dir()?.join("client-cert.pem");
    let key = match read_secret()? {
        Some(pem) => KeyPair::from_pem(&pem).map_err(|e| format!("stored key is unusable: {e}"))?,
        None => {
            let key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256)
                .map_err(|e| format!("key generation failed: {e}"))?;
            store_secret(&key.serialize_pem())?;
            // A new key needs a new certificate.
            let _ = std::fs::remove_file(&cert_path);
            eprintln!(
                "created a client key in the secret service ({SECRET_SERVICE}/{SECRET_ACCOUNT})"
            );
            key
        }
    };
    let cert_der = match std::fs::read_to_string(&cert_path) {
        Ok(pem) => pem_to_der(&pem)?,
        Err(_) => {
            let mut params = CertificateParams::new(Vec::<String>::new())
                .map_err(|e| format!("certificate: {e}"))?;
            params.distinguished_name.push(DnType::CommonName, name);
            params.not_before = rcgen::date_time_ymd(2025, 1, 1);
            params.not_after = rcgen::date_time_ymd(2125, 1, 1);
            let cert = params
                .self_signed(&key)
                .map_err(|e| format!("certificate: {e}"))?;
            std::fs::create_dir_all(dir()?)
                .map_err(|e| format!("cannot create config dir: {e}"))?;
            std::fs::write(&cert_path, cert.pem())
                .map_err(|e| format!("cannot write {}: {e}", cert_path.display()))?;
            cert.der().to_vec()
        }
    };
    Ok(Identity {
        cert_der,
        key_der: key.serialize_der(),
    })
}

fn pem_to_der(pem: &str) -> Result<Vec<u8>, String> {
    use base64::Engine;
    let body: String = pem
        .lines()
        .filter(|l| !l.starts_with("-----"))
        .collect::<Vec<_>>()
        .concat();
    base64::engine::general_purpose::STANDARD
        .decode(body)
        .map_err(|e| format!("bad certificate file: {e}"))
}

/// Pinned devd certificates by address, and the default address.
pub struct Hosts {
    path: PathBuf,
    pub default: Option<String>,
    pub pins: BTreeMap<String, Value>,
}

impl Hosts {
    pub fn load() -> Result<Hosts, String> {
        let path = dir()?.join("hosts.json");
        let v: Value = match std::fs::read_to_string(&path) {
            Ok(s) => serde_json::from_str(&s).map_err(|e| format!("{}: {e}", path.display()))?,
            Err(_) => json!({}),
        };
        Ok(Hosts {
            path,
            default: v["default"].as_str().map(str::to_string),
            pins: v["hosts"]
                .as_object()
                .map(|o| o.iter().map(|(k, v)| (k.clone(), v.clone())).collect())
                .unwrap_or_default(),
        })
    }

    pub fn pin(&self, addr: &str) -> Option<String> {
        self.pins
            .get(addr)
            .and_then(|v| v["serverCertSha256"].as_str())
            .map(str::to_string)
    }

    pub fn save(&self) -> Result<(), String> {
        std::fs::create_dir_all(self.path.parent().unwrap_or(&self.path))
            .map_err(|e| format!("cannot create config dir: {e}"))?;
        let v = json!({ "default": self.default, "hosts": self.pins });
        let text = serde_json::to_string_pretty(&v).unwrap_or_default();
        std::fs::write(&self.path, text).map_err(|e| format!("{}: {e}", self.path.display()))
    }
}
