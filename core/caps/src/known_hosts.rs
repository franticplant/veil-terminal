//! Listing and editing a known_hosts file. Checking and learning
//! keys during a handshake is russh's (see [`crate::FileHostKeys`]); this is the listing and
//! forgetting a frontend asks for.

use std::io::{self, Write};
use std::path::Path;

use russh::keys::{HashAlg, PublicKey};
use veil_protocol::v1;

/// How a host and port are written in known_hosts.
fn pattern(host: &str, port: u16) -> String {
    if port == 22 {
        host.to_string()
    } else {
        format!("[{host}]:{port}")
    }
}

/// Splits a known_hosts host pattern back into host and port.
fn parse_pattern(p: &str) -> (String, u16) {
    if p.starts_with("|1|") {
        return ("(hashed)".into(), 0);
    }
    if let Some(rest) = p.strip_prefix('[')
        && let Some((host, port)) = rest.split_once("]:")
        && let Ok(port) = port.parse()
    {
        return (host.to_string(), port);
    }
    (p.to_string(), 22)
}

/// Every key in the file. A missing file is an empty list; lines that do not
/// parse are skipped.
pub fn list(path: &Path) -> io::Result<Vec<v1::HostKeyEntry>> {
    let text = match std::fs::read_to_string(path) {
        Ok(t) => t,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(vec![]),
        Err(e) => return Err(e),
    };
    let mut out = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut fields = line.split_whitespace();
        let (Some(hosts), Some(alg), Some(b64)) = (fields.next(), fields.next(), fields.next())
        else {
            continue;
        };
        let Ok(key) = PublicKey::from_openssh(&format!("{alg} {b64}")) else {
            continue;
        };
        let fingerprint = key.fingerprint(HashAlg::Sha256).to_string();
        for p in hosts.split(',') {
            let (host, port) = parse_pattern(p);
            out.push(v1::HostKeyEntry {
                host,
                port: port as u32,
                algorithm: alg.to_string(),
                fingerprint: fingerprint.clone(),
            });
        }
    }
    Ok(out)
}

/// Removes host:port from every line; a line left with no hosts goes. The
/// file is replaced atomically. Returns how many entries were removed.
pub fn forget(path: &Path, host: &str, port: u16) -> io::Result<usize> {
    let text = match std::fs::read_to_string(path) {
        Ok(t) => t,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(0),
        Err(e) => return Err(e),
    };
    let target = pattern(host, port);
    let mut removed = 0;
    let mut kept = String::with_capacity(text.len());
    for line in text.lines() {
        let trimmed = line.trim_start();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            kept.push_str(line);
            kept.push('\n');
            continue;
        }
        let (hosts, rest) = trimmed
            .split_once(char::is_whitespace)
            .unwrap_or((trimmed, ""));
        let remaining: Vec<&str> = hosts.split(',').filter(|h| *h != target).collect();
        removed += hosts.split(',').count() - remaining.len();
        if !remaining.is_empty() {
            kept.push_str(&remaining.join(","));
            kept.push(' ');
            kept.push_str(rest);
            kept.push('\n');
        }
    }
    if removed > 0 {
        let tmp = path.with_extension("tmp");
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(kept.as_bytes())?;
        f.sync_all()?;
        std::fs::rename(&tmp, path)?;
    }
    Ok(removed)
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY: &str =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJdD7y3aLq454yWBdwLWbieU1ebz9/cu7/QEXn9OIeZJ";

    #[test]
    fn lists_and_forgets() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("known_hosts");
        std::fs::write(
            &path,
            format!("# comment\nexample.org {KEY}\n[example.org]:2222,other {KEY}\nnot a key\n"),
        )
        .unwrap();

        let all = list(&path).unwrap();
        let hosts: Vec<_> = all.iter().map(|e| (e.host.as_str(), e.port)).collect();
        assert_eq!(
            hosts,
            [("example.org", 22), ("example.org", 2222), ("other", 22)]
        );
        assert!(all[0].fingerprint.starts_with("SHA256:"));

        assert_eq!(forget(&path, "example.org", 2222).unwrap(), 1);
        let hosts: Vec<_> = list(&path).unwrap().into_iter().map(|e| e.host).collect();
        assert_eq!(hosts, ["example.org", "other"]);
        assert!(
            std::fs::read_to_string(&path)
                .unwrap()
                .starts_with("# comment\n")
        );

        assert_eq!(forget(&path, "example.org", 22).unwrap(), 1);
        assert_eq!(forget(&path, "nowhere", 22).unwrap(), 0);
    }

    #[test]
    fn missing_file_is_empty() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("known_hosts");
        assert!(list(&path).unwrap().is_empty());
        assert_eq!(forget(&path, "x", 22).unwrap(), 0);
    }
}
