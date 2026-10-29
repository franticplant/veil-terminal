#![allow(unsafe_code)] // clock_gettime is the only unsafe call.

//! Observability: a component's log ring and per-session counters.
//!
//! Every component, whether in the app, an Android broker or a Microdroid
//! guest worker, reports into its own ring and exposes it through its
//! protocol (the engine: GetLogs/GetStats). Inside a protected VM there is no
//! logcat, so this is the only window in.
//!
//! Both are owned by one engine connection and handed to the code that
//! reports into them; there is no global logger. The ring is bounded, so a
//! chatty session costs old entries, never memory. Messages must not carry
//! secrets, typed input or screen content.

use std::collections::VecDeque;
use std::sync::Mutex;
use std::sync::atomic::{AtomicI32, AtomicU32, AtomicU64, Ordering};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use veil_protocol::v1;

/// Entries kept in the ring.
pub const LOG_CAPACITY: usize = 4000;

/// The most entries one `GetLogs` returns.
pub const MAX_LOG_BATCH: usize = 1000;

/// The longest message kept, in bytes; longer ones are cut at a character
/// boundary.
const MAX_MESSAGE_BYTES: usize = 1024;

pub struct LogRing {
    inner: Mutex<Ring>,
}

struct Ring {
    entries: VecDeque<v1::LogEntry>,
    next_seq: u64,
}

impl Default for LogRing {
    fn default() -> Self {
        Self::new()
    }
}

impl LogRing {
    pub fn new() -> Self {
        LogRing {
            inner: Mutex::new(Ring {
                entries: VecDeque::with_capacity(LOG_CAPACITY),
                next_seq: 1,
            }),
        }
    }

    pub fn log(&self, level: v1::LogLevel, component: &str, session_id: u64, message: &str) {
        let unix_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        let mut message = message.to_string();
        if message.len() > MAX_MESSAGE_BYTES {
            let mut end = MAX_MESSAGE_BYTES;
            while !message.is_char_boundary(end) {
                end -= 1;
            }
            message.truncate(end);
            message.push('…');
        }
        let mut ring = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let seq = ring.next_seq;
        ring.next_seq += 1;
        if ring.entries.len() == LOG_CAPACITY {
            ring.entries.pop_front();
        }
        ring.entries.push_back(v1::LogEntry {
            seq,
            unix_ms,
            level: level as i32,
            component: component.to_string(),
            session_id,
            message,
        });
    }

    pub fn info(&self, component: &str, session_id: u64, message: &str) {
        self.log(v1::LogLevel::Info, component, session_id, message);
    }

    pub fn warn(&self, component: &str, session_id: u64, message: &str) {
        self.log(v1::LogLevel::Warn, component, session_id, message);
    }

    /// The newest entry's seq, 0 if there is none.
    pub fn last_seq(&self) -> u64 {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .next_seq
            - 1
    }

    pub fn read(&self, req: &v1::GetLogs) -> v1::LogBatch {
        let max = match req.max as usize {
            0 => MAX_LOG_BATCH,
            n => n.min(MAX_LOG_BATCH),
        };
        let min_level = req.min_level;
        let ring = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let oldest = ring.entries.front().map_or(ring.next_seq, |e| e.seq);
        let dropped = oldest.saturating_sub(req.after_seq + 1);
        let mut next_seq = req.after_seq.max(oldest.saturating_sub(1));
        let mut entries = Vec::new();
        for e in ring.entries.iter().filter(|e| e.seq > req.after_seq) {
            if entries.len() == max {
                break;
            }
            next_seq = e.seq;
            if e.level >= min_level {
                entries.push(e.clone());
            }
        }
        v1::LogBatch {
            entries,
            next_seq,
            dropped,
        }
    }
}

/// Counters one session updates and `GetStats` reads, without locks.
pub struct SessionStats {
    pub host: String,
    pub port: u16,
    pub user: String,
    started: Instant,
    phase: AtomicI32,
    cols: AtomicU32,
    rows: AtomicU32,
    pub bytes_from_remote: AtomicU64,
    pub bytes_to_remote: AtomicU64,
    pub frames_sent: AtomicU64,
    pub frames_acked: AtomicU64,
    /// The TCP connection: bytes on the wire, and the address reached.
    pub wire_from_remote: std::sync::Arc<AtomicU64>,
    pub wire_to_remote: std::sync::Arc<AtomicU64>,
    peer: Mutex<String>,
}

impl SessionStats {
    pub fn new(host: &str, port: u16, user: &str, cols: u16, rows: u16) -> Self {
        SessionStats {
            host: host.to_string(),
            port,
            user: user.to_string(),
            started: Instant::now(),
            phase: AtomicI32::new(v1::SessionPhase::Connecting as i32),
            cols: AtomicU32::new(cols as u32),
            rows: AtomicU32::new(rows as u32),
            bytes_from_remote: AtomicU64::new(0),
            bytes_to_remote: AtomicU64::new(0),
            frames_sent: AtomicU64::new(0),
            frames_acked: AtomicU64::new(0),
            wire_from_remote: Default::default(),
            wire_to_remote: Default::default(),
            peer: Mutex::new(String::new()),
        }
    }

    pub fn set_phase(&self, phase: v1::SessionPhase) {
        self.phase.store(phase as i32, Ordering::Relaxed);
    }

    pub fn set_peer(&self, peer: String) {
        *self.peer.lock().unwrap_or_else(|e| e.into_inner()) = peer;
    }

    pub fn set_size(&self, cols: u16, rows: u16) {
        self.cols.store(cols as u32, Ordering::Relaxed);
        self.rows.store(rows as u32, Ordering::Relaxed);
    }

    pub fn add(counter: &AtomicU64, n: u64) {
        counter.fetch_add(n, Ordering::Relaxed);
    }

    pub fn snapshot(&self, session_id: u64) -> v1::SessionStats {
        let get = |c: &AtomicU64| c.load(Ordering::Relaxed);
        v1::SessionStats {
            session_id,
            host: self.host.clone(),
            port: self.port as u32,
            username: self.user.clone(),
            phase: self.phase.load(Ordering::Relaxed),
            size: Some(v1::TermSize {
                cols: self.cols.load(Ordering::Relaxed),
                rows: self.rows.load(Ordering::Relaxed),
            }),
            age_ms: self.started.elapsed().as_millis() as u64,
            bytes_from_remote: get(&self.bytes_from_remote),
            bytes_to_remote: get(&self.bytes_to_remote),
            frames_sent: get(&self.frames_sent),
            frames_acked: get(&self.frames_acked),
            peer_address: self.peer.lock().unwrap_or_else(|e| e.into_inner()).clone(),
            wire_bytes_from_remote: self.wire_from_remote.load(Ordering::Relaxed),
            wire_bytes_to_remote: self.wire_to_remote.load(Ordering::Relaxed),
        }
    }
}

/// This process's own metrics, from /proc/self. Fields it cannot read are 0.
pub fn process_metrics() -> v1::ProcessMetrics {
    let mut m = v1::ProcessMetrics {
        pid: std::process::id(),
        ..Default::default()
    };
    if let Ok(status) = std::fs::read_to_string("/proc/self/status") {
        for line in status.lines() {
            let mut it = line.split_whitespace();
            match it.next() {
                Some("VmRSS:") => m.rss_kb = it.next().and_then(|v| v.parse().ok()).unwrap_or(0),
                Some("Threads:") => m.threads = it.next().and_then(|v| v.parse().ok()).unwrap_or(0),
                _ => {}
            }
        }
    }
    if let Ok(rollup) = std::fs::read_to_string("/proc/self/smaps_rollup") {
        m.pss_kb = rollup
            .lines()
            .find(|l| l.starts_with("Pss:"))
            .and_then(|l| l.split_whitespace().nth(1))
            .and_then(|v| v.parse().ok())
            .unwrap_or(0);
    }
    m.fds = std::fs::read_dir("/proc/self/fd")
        .map(|d| d.count() as u32)
        .unwrap_or(0);
    // /proc/self/stat: fields after the ")" ending the command name; utime and
    // stime are fields 14 and 15, starttime 22 (1-based, in clock ticks).
    if let Ok(stat) = std::fs::read_to_string("/proc/self/stat")
        && let Some(rest) = stat.rsplit_once(')').map(|(_, r)| r)
    {
        let f: Vec<&str> = rest.split_whitespace().collect();
        // rest starts at field 3 (state).
        let tick_ms = 10u64; // USER_HZ is 100 on Linux and Android.
        let field = |n: usize| {
            f.get(n - 3)
                .and_then(|v| v.parse::<u64>().ok())
                .unwrap_or(0)
        };
        m.cpu_user_ms = field(14) * tick_ms;
        m.cpu_system_ms = field(15) * tick_ms;
        let start_ms = field(22) * tick_ms;
        // /proc/uptime is blocked by Microdroid's SELinux policy. CLOCK_BOOTTIME
        // gives the same process age without a procfs read (and therefore
        // without an audit entry every sampler tick).
        let mut ts = libc::timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        if unsafe { libc::clock_gettime(libc::CLOCK_BOOTTIME, &mut ts) } == 0 {
            let boot_ms = (ts.tv_sec as u64) * 1000 + (ts.tv_nsec as u64) / 1_000_000;
            m.uptime_ms = boot_ms.saturating_sub(start_ms);
        }
    }
    m
}

#[cfg(test)]
mod tests {
    use super::*;

    fn get(after_seq: u64, max: u32, min_level: v1::LogLevel) -> v1::GetLogs {
        v1::GetLogs {
            after_seq,
            max,
            min_level: min_level as i32,
        }
    }

    #[test]
    fn measures_this_process() {
        let m = process_metrics();
        assert_eq!(m.pid, std::process::id());
        if cfg!(target_os = "linux") {
            assert!(m.rss_kb > 0 && m.threads > 0 && m.fds > 0, "{m:?}");
        }
    }

    #[test]
    fn reads_after_a_cursor() {
        let ring = LogRing::new();
        for i in 0..5 {
            ring.info("test", 0, &format!("m{i}"));
        }
        let batch = ring.read(&get(2, 2, v1::LogLevel::Unspecified));
        let msgs: Vec<_> = batch.entries.iter().map(|e| e.message.as_str()).collect();
        assert_eq!(msgs, ["m2", "m3"]);
        assert_eq!(batch.next_seq, 4);
        assert_eq!(batch.dropped, 0);
        assert_eq!(
            ring.read(&get(5, 0, v1::LogLevel::Unspecified))
                .entries
                .len(),
            0
        );
        assert_eq!(ring.last_seq(), 5);
    }

    #[test]
    fn level_filter_still_advances_the_cursor() {
        let ring = LogRing::new();
        ring.info("test", 0, "quiet");
        ring.warn("test", 0, "loud");
        ring.info("test", 0, "quiet again");
        let batch = ring.read(&get(0, 0, v1::LogLevel::Warn));
        assert_eq!(batch.entries.len(), 1);
        assert_eq!(batch.entries[0].message, "loud");
        assert_eq!(batch.next_seq, 3);
    }

    #[test]
    fn reports_what_the_ring_dropped() {
        let ring = LogRing::new();
        for _ in 0..LOG_CAPACITY + 10 {
            ring.info("test", 0, "x");
        }
        let batch = ring.read(&get(0, 1, v1::LogLevel::Unspecified));
        assert_eq!(batch.dropped, 10);
        assert_eq!(batch.entries[0].seq, 11);
    }

    #[test]
    fn long_messages_are_cut_on_a_character_boundary() {
        let ring = LogRing::new();
        ring.info("test", 0, &"é".repeat(MAX_MESSAGE_BYTES));
        let batch = ring.read(&get(0, 0, v1::LogLevel::Unspecified));
        assert!(batch.entries[0].message.len() <= MAX_MESSAGE_BYTES + '…'.len_utf8());
    }
}
