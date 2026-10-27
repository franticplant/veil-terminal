//! The engine protocol (`proto/veil/engine/v1/engine.proto`): generated message
//! types, the limits every side enforces, and decoding that checks them.
//!
//! Decode only through [`decode_client`] and [`decode_engine`]. They refuse an
//! oversized buffer before prost sees it, so a peer cannot make the decoder
//! allocate more than [`MAX_MESSAGE_BYTES`].

/// Generated types for `veil.engine.v1`.
pub mod v1 {
    #![allow(clippy::all)]
    include!(concat!(env!("OUT_DIR"), "/veil.engine.v1.rs"));
}

use prost::Message;

/// The protocol version this build speaks (`Hello.protocol_version`).
pub const PROTOCOL_VERSION: u32 = 1;

/// The largest encoded message either side accepts. Terminal frames are the
/// largest messages; this leaves room for a full 500x200 screen of cells.
pub const MAX_MESSAGE_BYTES: usize = 4 * 1024 * 1024;

/// Requests a frontend may have outstanding before the engine stops reading.
pub const MAX_IN_FLIGHT: u32 = 64;

/// Unacknowledged frames per session before the engine waits for `FrameAck`.
pub const MAX_FRAMES_IN_FLIGHT: u32 = 2;

/// Sessions one connection may have open at once.
pub const MAX_SESSIONS: u32 = 16;

/// The largest `Input.data`.
pub const MAX_INPUT_BYTES: usize = 64 * 1024;

/// Terminal size bounds (`TermSize`).
pub const MIN_COLS: u32 = 2;
pub const MAX_COLS: u32 = 500;
pub const MIN_ROWS: u32 = 2;
pub const MAX_ROWS: u32 = 300;

/// The longest `Frame.title`, in characters.
pub const MAX_TITLE_CHARS: usize = 256;

/// Color encoding in `Run.fg` / `Run.bg`.
pub mod color {
    pub const DEFAULT: u32 = 0;
    pub const INDEXED: u32 = 0x0100_0000;
    pub const RGB: u32 = 0x0200_0000;

    pub fn indexed(i: u8) -> u32 {
        INDEXED | i as u32
    }

    pub fn rgb(r: u8, g: u8, b: u8) -> u32 {
        RGB | (r as u32) << 16 | (g as u32) << 8 | b as u32
    }
}

/// Checks a `TermSize` against the bounds above.
pub fn check_size(size: Option<&v1::TermSize>) -> Result<(u16, u16), String> {
    let size = size.ok_or("size is missing")?;
    if !(MIN_COLS..=MAX_COLS).contains(&size.cols) || !(MIN_ROWS..=MAX_ROWS).contains(&size.rows) {
        return Err(format!(
            "size {}x{} is outside {MIN_COLS}..={MAX_COLS} columns, {MIN_ROWS}..={MAX_ROWS} rows",
            size.cols, size.rows
        ));
    }
    Ok((size.cols as u16, size.rows as u16))
}

/// Checks a frame against the render contract (the rules every frontend
/// enforces, e.g. the Android `ScreenModel`): size within bounds, rows and
/// cursor inside it, runs inside their row and in order, each run's text
/// matching its cells, no control characters, a bounded title. `prev` is the
/// size of the frame before it, which a partial frame must keep.
pub fn check_frame(f: &v1::Frame, prev: Option<(u32, u32)>) -> Result<(), String> {
    let size = f.size.as_ref().ok_or("frame without size")?;
    let (c, r) = (size.cols, size.rows);
    if !(MIN_COLS..=MAX_COLS).contains(&c) || !(MIN_ROWS..=MAX_ROWS).contains(&r) {
        return Err(format!("size {c}x{r} out of bounds"));
    }
    if !f.full && prev != Some((c, r)) {
        return Err(format!(
            "partial frame changes the size to {c}x{r} (was {prev:?})"
        ));
    }
    if f.rows.len() > r as usize {
        return Err(format!("{} rows for a {r}-row screen", f.rows.len()));
    }
    if f.title.chars().count() > MAX_TITLE_CHARS || f.title.chars().any(char::is_control) {
        return Err("bad title".into());
    }
    if let Some(cur) = &f.cursor
        && (cur.row >= r || cur.col >= c)
    {
        return Err(format!("cursor {},{} outside {c}x{r}", cur.row, cur.col));
    }
    for row in &f.rows {
        if row.index >= r {
            return Err(format!("row index {}", row.index));
        }
        let mut next = 0;
        for run in &row.runs {
            let wide = run.flags & v1::RunFlag::Wide as u32 != 0;
            if run.col < next {
                return Err(format!(
                    "runs overlap or are out of order in row {}",
                    row.index
                ));
            }
            if run.cells == 0 || run.col + run.cells > c {
                return Err(format!("run outside row {}", row.index));
            }
            let scalars = run.text.chars().count() as u32;
            let per_cell = if wide { 2 } else { 1 };
            let one_cluster = run.cells == per_cell && scalars >= 1;
            if scalars * per_cell != run.cells && !one_cluster {
                return Err(format!(
                    "run text {:?} does not match its {} cells in row {}",
                    run.text, run.cells, row.index
                ));
            }
            if let Some(ch) = run.text.chars().find(|c| c.is_control()) {
                return Err(format!(
                    "control character U+{:04X} in row {}",
                    ch as u32, row.index
                ));
            }
            next = run.col + run.cells;
        }
    }
    Ok(())
}

#[derive(Debug, thiserror::Error)]
pub enum DecodeError {
    #[error("message is {len} bytes, over the {max}-byte limit")]
    TooLarge { len: usize, max: usize },
    #[error("malformed message: {0}")]
    Malformed(#[from] prost::DecodeError),
}

fn check_len(buf: &[u8]) -> Result<(), DecodeError> {
    if buf.len() > MAX_MESSAGE_BYTES {
        return Err(DecodeError::TooLarge {
            len: buf.len(),
            max: MAX_MESSAGE_BYTES,
        });
    }
    Ok(())
}

/// Decodes a frontend -> engine message.
pub fn decode_client(buf: &[u8]) -> Result<v1::ClientMsg, DecodeError> {
    check_len(buf)?;
    Ok(v1::ClientMsg::decode(buf)?)
}

/// Decodes an engine -> frontend message.
pub fn decode_engine(buf: &[u8]) -> Result<v1::EngineMsg, DecodeError> {
    check_len(buf)?;
    Ok(v1::EngineMsg::decode(buf)?)
}

/// Encodes any protocol message.
pub fn encode<M: Message>(msg: &M) -> Vec<u8> {
    msg.encode_to_vec()
}

/// The limits an engine advertises in `HelloAck`.
pub fn limits() -> v1::Limits {
    v1::Limits {
        max_message_bytes: MAX_MESSAGE_BYTES as u32,
        max_in_flight: MAX_IN_FLIGHT,
        max_frames_in_flight: MAX_FRAMES_IN_FLIGHT,
        max_sessions: MAX_SESSIONS,
        max_input_bytes: MAX_INPUT_BYTES as u32,
    }
}

/// Shorthand for an `EngineMsg` carrying an `Error`.
pub fn error_msg(request_id: u64, code: v1::ErrorCode, detail: impl Into<String>) -> v1::EngineMsg {
    v1::EngineMsg {
        request_id,
        body: Some(v1::engine_msg::Body::Error(v1::Error {
            code: code as i32,
            detail: detail.into(),
        })),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let msg = v1::ClientMsg {
            request_id: 7,
            body: Some(v1::client_msg::Body::Ping(v1::Ping { nonce: 42 })),
        };
        assert_eq!(decode_client(&encode(&msg)).unwrap(), msg);
    }

    #[test]
    fn refuses_oversized_before_decoding() {
        let buf = vec![0u8; MAX_MESSAGE_BYTES + 1];
        assert!(matches!(
            decode_client(&buf),
            Err(DecodeError::TooLarge { .. })
        ));
    }

    /// Mutated messages: decoding never panics, and what decodes re-encodes
    /// and decodes to the same message.
    #[test]
    fn mutated_messages_decode_or_fail_cleanly() {
        let seeds: Vec<Vec<u8>> = vec![
            encode(&v1::ClientMsg {
                request_id: 7,
                body: Some(v1::client_msg::Body::OpenSession(v1::OpenSession {
                    host: "example.org".into(),
                    port: 22,
                    username: "alice".into(),
                    size: Some(v1::TermSize { cols: 80, rows: 24 }),
                    public_keys: vec!["ecdsa-sha2-nistp256 AAAA".into()],
                })),
            }),
            encode(&v1::EngineMsg {
                request_id: 0,
                body: Some(v1::engine_msg::Body::Frame(v1::Frame {
                    session_id: 1,
                    seq: 3,
                    size: Some(v1::TermSize { cols: 4, rows: 2 }),
                    full: true,
                    rows: vec![v1::Row {
                        index: 0,
                        runs: vec![v1::Run {
                            col: 0,
                            cells: 2,
                            text: "hi".into(),
                            ..Default::default()
                        }],
                    }],
                    ..Default::default()
                })),
            }),
        ];
        let mut rng = 0x9e37_79b9_7f4a_7c15u64;
        let mut next = || {
            rng ^= rng << 13;
            rng ^= rng >> 7;
            rng ^= rng << 17;
            rng
        };
        for _ in 0..20_000 {
            let mut buf = seeds[(next() % 2) as usize].clone();
            for _ in 0..(1 + next() % 4) {
                let i = (next() as usize) % buf.len();
                match next() % 3 {
                    0 => buf[i] = next() as u8,
                    1 => buf.insert(i, next() as u8),
                    _ => {
                        buf.remove(i);
                    }
                }
                if buf.is_empty() {
                    buf.push(0);
                }
            }
            if let Ok(m) = decode_client(&buf) {
                assert_eq!(decode_client(&encode(&m)).unwrap(), m);
            }
            if let Ok(m) = decode_engine(&buf) {
                assert_eq!(decode_engine(&encode(&m)).unwrap(), m);
            }
        }
    }

    #[test]
    fn refuses_garbage() {
        // A field header promising a length-delimited field longer than the buffer.
        assert!(matches!(
            decode_client(&[0x52, 0xff, 0xff, 0x03]),
            Err(DecodeError::Malformed(_))
        ));
    }
}
